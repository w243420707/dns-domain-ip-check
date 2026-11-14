package io.github.w243420707.dnspool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 从候选池里挑替补 IP：只取通过、未被占用、未拉黑、公网段，按延迟升序 */
object CandidatePicker {
    fun pick(pool: List<IpCandidate>, usedIps: Set<String>, now: Long, limit: Int): List<IpCandidate> =
        pool.asSequence()
            .filter { it.ok && it.bannedUntil <= now && it.ip !in usedIps && IpUtil.isPublicIpv4(it.ip) }
            .sortedWith(compareBy({ it.avgRttMs }, { it.maxRttMs }, { it.ip }))
            .distinctBy { it.ip }
            .take(limit)
            .toList()
}

/**
 * 轮询引擎：一轮 = 测已发布的那 N 条 → 判定连续失败 → 从池里取替补 → 单条 PATCH 修好。
 * 稳态下不产生任何 Cloudflare 写操作，也不做全量 DNS 解析。
 */
class PollEngine(private val repo: StateRepository) {

    private val log = repo.log

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cf: CloudflareClient by lazy {
        CloudflareClient(
            tokenProvider = { repo.settings().cfToken },
            limiter = RateLimiter(repo.settings().writeMinIntervalMs),
            http = http
        )
    }

    /* ======================= 对外动作 ======================= */

    /** 完整重建：重新解析 → 全量探测 → 取前 N → 只写有差异的记录 */
    suspend fun rebuild(onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val s = repo.settings()
        val problems = preflight(s)
        if (problems.isNotEmpty()) return@withContext "配置不完整：" + problems.joinToString("；")
        runCatching {
            val zoneId = resolveZoneId(s)
            val msg = rebuildInternal(s, zoneId, onProgress)
            repo.saveRunState(repo.runState().copy(lastResolveAt = System.currentTimeMillis()))
            msg
        }.getOrElse { humanError(it) }
    }

    /** 预览：只算差异，不写任何东西 */
    suspend fun preview(): String = withContext(Dispatchers.IO) {
        val s = repo.settings()
        val problems = preflight(s)
        if (problems.isNotEmpty()) return@withContext "配置不完整：" + problems.joinToString("；")
        runCatching {
            val zoneId = resolveZoneId(s)
            val desired = buildDesired(s, repo.slots())
            val live = cf.listAllARecords(zoneId)
            val plan = DiffPlanner.plan(desired, live, System.currentTimeMillis() / 1000, s.commentRefreshSec)
            "计划：${plan.summary()}" + quotaNote(s, zoneId, live, plan) +
                if (plan.blocked.isNotEmpty()) "；${plan.blocked.size} 条记录名与期望不符，需先清理" else ""
        }.getOrElse { humanError(it) }
    }

    /** 删除所有带本程序备注的记录（切模式/换前缀时用） */
    suspend fun cleanupManaged(): String = withContext(Dispatchers.IO) {
        val s = repo.settings()
        if (s.cfToken.isBlank()) return@withContext "Token 未填"
        runCatching {
            val zoneId = resolveZoneId(s)
            val live = cf.listAllARecords(zoneId)
            val mine = live.filter { SlotComment.parse(it.comment) != null }
            if (mine.isEmpty()) return@withContext "没有找到带 dslk1 备注的记录"
            var ok = 0
            val errors = mutableListOf<String>()
            for (rec in mine) {
                runCatching { cf.deleteRecord(zoneId, rec.id); ok++ }
                    .onFailure { errors += "${rec.name}: ${humanError(it)}" }
            }
            repo.saveSlots(emptyList())
            if (errors.isEmpty()) "已删除 $ok 条托管记录" else "已删除 $ok 条，失败 ${errors.size} 条：${errors.first()}"
        }.getOrElse { humanError(it) }
    }

    /* ======================= 一轮轮询 ======================= */

    suspend fun runCycle(trigger: String = "timer"): CycleStats = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val s = repo.settings()

        val problems = preflight(s)
        if (problems.isNotEmpty()) return@withContext finish(startedAt, "SKIPPED", problems.joinToString("；"), trigger)

        val zoneId = try {
            resolveZoneId(s)
        } catch (e: Exception) {
            return@withContext finish(startedAt, "ERROR", humanError(e), trigger)
        }

        try {
            var slots = repo.slots()
            if (slots.isEmpty() || slots.size != s.slotCount) {
                slots = claimSlots(s, cf.listAllARecords(zoneId), slots)
                repo.saveSlots(slots)
            }

            val published = slots.count { it.recordId != null && it.ip != null }
            if (published == 0) {
                val msg = rebuildInternal(s, zoneId) {}
                return@withContext finish(startedAt, "REBUILT", msg, trigger)
            }

            // 1) 只测已发布的 N 条：IP 字面量，不触发任何 DNS 解析
            val targets = slots.filter { it.recordId != null && !it.ip.isNullOrBlank() }
            val results = ProbeScheduler(s.probeConcurrency).probeAll(
                ips = targets.mapNotNull { it.ip },
                ports = s.ports,
                timeoutMs = s.connectTimeoutMs,
                attempts = s.attemptsPerPort,
                budgetMs = s.maxProbeBudgetMs
            )
            val now = System.currentTimeMillis()

            // 弱网熔断：整体失败率过高说明是本地网络问题，本轮不累加失败计数
            if (ProbeTuning.looksOffline(results)) {
                log.add("W", "本轮 ${results.size} 条仅 ${results.count { it.ok }} 条连通，判定本地网络异常，跳过计数")
                return@withContext finish(startedAt, "OFFLINE", "本地网络异常，已跳过本轮（不累加失败次数）", trigger)
            }

            val byIp = results.associateBy { it.ip }
            var updated = slots.map { slot ->
                val ip = slot.ip ?: return@map slot
                val r = byIp[ip] ?: return@map slot.copy(state = SlotState.SUSPECT)
                if (r.ok) {
                    slot.copy(
                        state = SlotState.OK, consecutiveFailures = 0,
                        lastProbeAt = now, lastOkAt = now,
                        lastAvgRttMs = r.avgRttMs, lastMaxRttMs = r.maxRttMs
                    )
                } else {
                    val fails = slot.consecutiveFailures + 1
                    slot.copy(
                        state = if (fails >= s.failThreshold) SlotState.DOWN else SlotState.SUSPECT,
                        consecutiveFailures = fails, lastProbeAt = now
                    )
                }
            }

            // 2) 只修到阈值的，且每轮限量，避免一次打爆 CF
            val broken = updated.filter { it.consecutiveFailures >= s.failThreshold && it.recordId != null }
                .take(s.maxRepairsPerCycle)
            var repairs = 0
            if (broken.isNotEmpty()) {
                val usedIps = updated.mapNotNull { it.ip }.toMutableSet()
                var pool = ensurePool(s, zoneId, force = false)
                for (slot in broken) {
                    val patched = repairOne(s, zoneId, slot, pool, usedIps)
                    if (patched != null) {
                        updated = updated.map { if (it.index == slot.index) patched else it }
                        usedIps += patched.ip!!
                        repairs++
                        repo.saveSlots(updated)
                    } else {
                        // 池里挑不出可用替补：拉黑该槽位旧 IP 并标记，下轮再试
                        pool = ensurePool(s, zoneId, force = true)
                        val retry = repairOne(s, zoneId, slot, pool, usedIps)
                        if (retry != null) {
                            updated = updated.map { if (it.index == slot.index) retry else it }
                            usedIps += retry.ip!!
                            repairs++
                            repo.saveSlots(updated)
                        }
                    }
                }
            }

            repo.saveSlots(updated)
            val okCount = updated.count { it.recordId != null && it.state == SlotState.OK }
            val downCount = updated.count { it.state == SlotState.DOWN }
            val result = when {
                repairs > 0 -> "REPAIRED"
                downCount > 0 -> "DEGRADED"
                else -> "OK"
            }
            val note = if (repairs > 0) "修复 $repairs 条" else if (downCount > 0) "$downCount 条待修复" else "全部正常"
            finish(
                startedAt, result, note, trigger,
                slotsOk = okCount, slotsDown = downCount, repairs = repairs,
                probed = results.size, passed = results.count { it.ok },
                durationMs = System.currentTimeMillis() - startedAt
            )
        } catch (e: Exception) {
            finish(startedAt, "ERROR", humanError(e), trigger)
        }
    }

    /* ======================= 单条修复 ======================= */

    private suspend fun repairOne(
        s: AppSettings,
        zoneId: String,
        slot: Slot,
        pool: List<IpCandidate>,
        usedIps: Set<String>
    ): Slot? {
        val recordId = slot.recordId ?: return null
        val now = System.currentTimeMillis()
        val candidates = CandidatePicker.pick(pool, usedIps, now, s.verifyCandidates)

        for (cand in candidates) {
            // 池里的成绩可能已经过期，落库前再握一次手，避免把一个死 IP 写上去
            val verify = TcpProber.probe(cand.ip, s.ports, s.connectTimeoutMs, 1)
            if (!verify.ok) {
                banCandidate(cand.ip)
                continue
            }
            val comment = SlotComment.encode(
                slot = slot.index,
                avgRttMs = verify.avgRttMs,
                maxRttMs = verify.maxRttMs,
                nowSec = now / 1000,
                sourceDomain = cand.sourceDomain,
                pad = s.slotPad
            )
            return try {
                cf.patchRecord(
                    zoneId, recordId,
                    CfRecordPatch(content = cand.ip, comment = comment, ttl = s.ttlSec)
                )
                log.add("I", "槽位 ${slot.index} ${slot.name}：${slot.ip ?: "-"} -> ${cand.ip}（${verify.avgRttMs}ms）")
                slot.copy(
                    ip = cand.ip, comment = comment, state = SlotState.OK,
                    consecutiveFailures = 0, lastProbeAt = now, lastOkAt = now,
                    lastAvgRttMs = verify.avgRttMs, lastMaxRttMs = verify.maxRttMs,
                    lastWrittenAt = now, pendingRepair = false
                )
            } catch (e: Exception) {
                log.add("E", "槽位 ${slot.index} 修复失败：${humanError(e)}")
                if (e is CfException && !e.retryable) return null
                null
            }
        }
        return null
    }

    private fun banCandidate(ip: String) {
        val until = System.currentTimeMillis() + repo.settings().banTtlSec * 1000
        repo.savePool(repo.pool().map { if (it.ip == ip) it.copy(bannedUntil = until) else it })
    }

    /* ======================= 候选池 ======================= */

    private suspend fun ensurePool(s: AppSettings, zoneId: String, force: Boolean): List<IpCandidate> {
        val now = System.currentTimeMillis()
        val pool = repo.pool().filter { it.ok && it.bannedUntil <= now }
        val rs = repo.runState()
        val rescanAllowed = now - rs.lastResolveAt > s.fullRescanMinIntervalSec * 1000
        val needRescan = force || pool.isEmpty() || (rescanAllowed && pool.size < maxOf(3, s.slotCount / 4))

        if (!needRescan) return repo.pool()

        val discovered = discover(s)
        if (discovered.isEmpty()) {
            log.add("W", "全量解析没有拿到可用 IP（域名列表 ${s.domainsRaw.lineSequence().count { it.isNotBlank() }} 条）")
            return repo.pool()
        }
        repo.savePool(discovered)
        repo.saveRunState(rs.copy(lastResolveAt = now))
        log.add("I", "候选池刷新：${discovered.count { it.ok }}/${discovered.size} 条可用")
        return discovered
    }

    private suspend fun discover(s: AppSettings): List<IpCandidate> {
        val domains = parseDomains(s.domainsRaw)
        if (domains.isEmpty()) return emptyList()

        val resolver = DnsResolverFactory.create(s, http)
        val ipToDomain = LinkedHashMap<String, String>()
        for (d in domains) {
            if (ipToDomain.size >= s.maxPoolSize) break
            if (IpUtil.isIpv4(d)) {
                ipToDomain.putIfAbsent(d, "list")
                continue
            }
            val resolved = resolver.resolveA(d)
            resolved.getOrNull()?.forEach { ip ->
                if (IpUtil.isPublicIpv4(ip) && ipToDomain.size < s.maxPoolSize) {
                    ipToDomain.putIfAbsent(ip, d)
                }
            }
            if (resolved.isFailure) log.add("W", "解析失败 $d：${resolved.exceptionOrNull()?.message ?: "-"}")
        }
        if (ipToDomain.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val results = ProbeScheduler(s.probeConcurrency).probeAll(
            ips = ipToDomain.keys.toList(),
            ports = s.ports,
            timeoutMs = s.connectTimeoutMs,
            attempts = s.attemptsPerPort,
            budgetMs = s.maxProbeBudgetMs * 3
        )
        return results.map { r ->
            IpCandidate(
                ip = r.ip, ok = r.ok, timings = r.timings,
                avgRttMs = r.avgRttMs, maxRttMs = r.maxRttMs,
                sourceDomain = ipToDomain[r.ip] ?: "", probedAt = now
            )
        }
    }

    private fun parseDomains(raw: String): List<String> = raw.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { IpUtil.cleanHost(it) }
        .filter { it.contains('.') }
        .distinct()
        .take(500)
        .toList()

    /* ======================= 发布与差异 ======================= */

    private suspend fun rebuildInternal(s: AppSettings, zoneId: String, onProgress: (String) -> Unit): String {
        onProgress("解析域名…")
        val discovered = discover(s)
        if (discovered.isEmpty()) return "没有解析到可用 IP，检查域名列表与 DNS 设置"
        repo.savePool(discovered)
        repo.saveRunState(repo.runState().copy(lastResolveAt = System.currentTimeMillis()))

        val usable = discovered.filter { it.ok }.size
        onProgress("可用 $usable 条，取前 ${s.slotCount} 条…")
        val picked = CandidatePicker.pick(discovered, emptySet(), System.currentTimeMillis(), s.slotCount)
        if (picked.isEmpty()) return "探测完成但没有端口全通的 IP（端口 ${PortParser.format(s.ports)}）"

        val nowSec = System.currentTimeMillis() / 1000
        val desired = picked.mapIndexed { i, cand ->
            val index = i + 1
            DiffPlanner.DesiredSlot(
                slot = index,
                name = s.recordName(index),
                content = cand.ip,
                ttl = s.ttlSec.coerceAtLeast(60),
                comment = SlotComment.encode(
                    index, cand.avgRttMs, cand.maxRttMs, nowSec, cand.sourceDomain, s.slotPad
                )
            )
        }

        val live = cf.listAllARecords(zoneId)
        val plan = DiffPlanner.plan(desired, live, nowSec, s.commentRefreshSec)
        quotaBlock(s, zoneId, live, plan)?.let { return it }

        if (plan.isEmpty) return "已有 ${desired.size} 条全部一致，无需写入（不变 ${plan.unchanged}）"

        onProgress("写入 ${plan.writes} 条…")
        val (writes, errors) = applyPlan(zoneId, plan)
        return buildString {
            append("发布完成：创建 ${plan.creates.size}、修改 ${plan.patches.size}、删除 ${plan.deletes.size}，实际写入 $writes")
            if (errors.isNotEmpty()) append("；失败 ${errors.size} 条：${errors.first()}")
        }
    }

    private fun buildDesired(s: AppSettings, slots: List<Slot>): List<DiffPlanner.DesiredSlot> =
        slots.filter { it.recordId != null && !it.ip.isNullOrBlank() }
            .sortedBy { it.index }
            .map { slot ->
                DiffPlanner.DesiredSlot(
                    slot = slot.index,
                    name = slot.name.ifBlank { s.recordName(slot.index) },
                    content = slot.ip!!,
                    ttl = s.ttlSec.coerceAtLeast(60),
                    comment = slot.comment ?: SlotComment.encode(
                        slot.index, slot.lastAvgRttMs, slot.lastMaxRttMs,
                        System.currentTimeMillis() / 1000, "", s.slotPad
                    )
                )
            }

    private suspend fun applyPlan(zoneId: String, plan: DiffPlanner.CfPlan): Pair<Int, List<String>> {
        var writes = 0
        val errors = mutableListOf<String>()
        var slots = repo.slots()
        val now = System.currentTimeMillis()

        for (d in plan.creates) {
            try {
                val rec = cf.createRecord(zoneId, CfRecordDraft(d.name, d.content, d.ttl, d.comment))
                slots = upsertSlot(
                    slots,
                    Slot(
                        index = d.slot, name = rec.name, recordId = rec.id, ip = rec.content,
                        comment = rec.comment ?: d.comment, state = SlotState.OK,
                        lastProbeAt = now, lastOkAt = now, lastWrittenAt = now
                    )
                )
                writes++
                // 每 20 条落一次盘：进程被杀后靠备注仍可认领，但少了重复写文件
                if (writes % 20 == 0) repo.saveSlots(slots)
            } catch (e: Exception) {
                errors += "槽位 ${d.slot} 创建失败：${humanError(e)}"
                if (e is CfException && !e.retryable) break
            }
        }
        repo.saveSlots(slots)

        for (p in plan.patches) {
            try {
                val rec = cf.patchRecord(zoneId, p.recordId, CfRecordPatch(p.content, p.comment, p.ttl))
                slots = slots.map {
                    if (it.recordId == p.recordId) {
                        it.copy(
                            ip = rec.content, comment = rec.comment ?: it.comment,
                            lastWrittenAt = now
                        )
                    } else it
                }
                writes++
            } catch (e: Exception) {
                errors += "修补 ${p.slot} 失败：${humanError(e)}"
                if (e is CfException && !e.retryable) break
            }
        }
        repo.saveSlots(slots)

        for (id in plan.deletes) {
            // DiffPlanner 只会把"备注能解析出槽位"的记录放进 deletes，这里不再二次猜测
            try {
                cf.deleteRecord(zoneId, id)
                slots = slots.filterNot { it.recordId == id }
                writes++
            } catch (e: Exception) {
                errors += "删除失败：${humanError(e)}"
            }
        }
        repo.saveSlots(slots)
        return writes to errors
    }

    private fun upsertSlot(list: List<Slot>, slot: Slot): List<Slot> {
        val idx = list.indexOfFirst { it.index == slot.index }
        return if (idx >= 0) list.toMutableList().also { it[idx] = slot } else list + slot
    }

    /* ======================= 认领与额度 ======================= */

    /**
     * 把云端记录映射回本地槽位。本地状态丢失、换机、重装都能靠备注前缀恢复，
     * 因此"重建"按钮天然幂等，不会产生重复记录。
     */
    fun claimSlots(s: AppSettings, live: List<CfRecord>, existing: List<Slot>): List<Slot> {
        val bySlot = mutableMapOf<Int, CfRecord>()
        for (rec in live) {
            val parsed = SlotComment.parse(rec.comment) ?: continue
            val old = bySlot[parsed.slot]
            if (old == null) {
                bySlot[parsed.slot] = rec
            } else {
                val oldTs = SlotComment.parse(old.comment)?.updatedAt ?: 0
                if (parsed.updatedAt > oldTs) bySlot[parsed.slot] = rec
            }
        }
        return (1..s.slotCount).map { index ->
            val base = existing.firstOrNull { it.index == index } ?: Slot(index = index, name = s.recordName(index))
            val rec = bySlot[index]
            if (rec == null) {
                base.copy(name = s.recordName(index), recordId = null, ip = null, comment = null, state = SlotState.EMPTY)
            } else {
                base.copy(
                    index = index,
                    name = rec.name,
                    recordId = rec.id,
                    ip = rec.content,
                    comment = rec.comment,
                    state = if (rec.name != s.recordName(index)) SlotState.SUSPECT else base.state.takeIf { it == SlotState.OK } ?: SlotState.OK
                )
            }
        }
    }

    private suspend fun quotaBlock(s: AppSettings, zoneId: String, live: List<CfRecord>, plan: DiffPlanner.CfPlan): String? {
        if (plan.creates.isEmpty()) return null
        val total = runCatching { cf.countRecords(zoneId) }.getOrDefault(-1)
        if (total < 0) return null
        val managed = live.count { SlotComment.parse(it.comment) != null }
        val nonManaged = (total - managed).coerceAtLeast(0)
        val room = s.recordLimit - nonManaged
        if (plan.creates.size > room) {
            return "额度不足：Zone 现有 $total 条记录（其中非托管 $nonManaged 条），套餐上限 ${s.recordLimit} 条，" +
                "最多还能创建 ${room} 条。请把 N 降到 $room 以内，或先清理记录。"
        }
        return null
    }

    private suspend fun quotaNote(s: AppSettings, zoneId: String, live: List<CfRecord>, plan: DiffPlanner.CfPlan): String {
        val total = runCatching { cf.countRecords(zoneId) }.getOrDefault(-1)
        if (total < 0) return ""
        val managed = live.count { SlotComment.parse(it.comment) != null }
        val nonManaged = (total - managed).coerceAtLeast(0)
        return "；Zone 已用 $total/${s.recordLimit} 条（非托管 $nonManaged），本次最多可新增 ${s.recordLimit - nonManaged} 条"
    }

    /* ======================= 辅助 ======================= */

    suspend fun resolveZoneId(s: AppSettings): String {
        if (s.zoneId.isNotBlank()) return s.zoneId
        val root = s.rootDomain.trim().trimEnd('.').lowercase()
        if (root.isBlank()) throw CfException("根域名未填写", false)
        val zones = cf.listZones(root)
        val zone = zones.firstOrNull { it.name.equals(root, true) } ?: zones.firstOrNull()
            ?: throw CfException("找不到 $root 对应的 Zone，确认 Token 有 Zone:Read 权限且该域名在本账号下", false)
        repo.saveSettings(s.copy(zoneId = zone.id, zoneName = zone.name))
        return zone.id
    }

    suspend fun checkCredentials(): String = withContext(Dispatchers.IO) {
        val s = repo.settings()
        if (s.cfToken.isBlank()) return@withContext "Token 未填"
        runCatching {
            val info = cf.verifyToken()
            if (info.status != "active") return@withContext "Token 状态是 ${info.status}，不是 active"
            val zoneId = resolveZoneId(s)
            val total = cf.countRecords(zoneId)
            "Token 有效；Zone ${s.zoneName.ifBlank { s.rootDomain }} 已用 ${if (total < 0) "?" else total} / ${s.recordLimit} 条"
        }.getOrElse { humanError(it) }
    }

    private fun preflight(s: AppSettings): List<String> {
        val problems = s.validate().toMutableList()
        if (s.cfToken.isBlank()) problems += "Cloudflare Token 未填"
        if (s.domainsRaw.isBlank()) problems += "域名列表为空"
        return problems
    }

    fun humanError(e: Throwable): String = when (e) {
        is CfException -> e.reason
        is java.net.UnknownHostException -> "DNS 解析失败：${e.message ?: "未知主机"}"
        is java.net.SocketTimeoutException -> "连接超时（${e.message?.take(60) ?: "无响应"}）"
        is java.net.ConnectException -> "连接被拒绝：${e.message ?: "-"}"
        else -> (e.message ?: e.javaClass.simpleName).take(160)
    }

    private fun finish(
        startedAt: Long,
        result: String,
        note: String,
        trigger: String,
        slotsOk: Int = 0,
        slotsDown: Int = 0,
        repairs: Int = 0,
        probed: Int = 0,
        passed: Int = 0,
        durationMs: Long = System.currentTimeMillis() - startedAt
    ): CycleStats {
        val now = System.currentTimeMillis()
        val rs = repo.runState()
        val pool = repo.pool()
        val stats = CycleStats(
            cycleId = now,
            startedAt = startedAt,
            durationMs = durationMs,
            domainsResolved = if (result == "REBUILT") repo.settings().domainsRaw.lineSequence().count { it.isNotBlank() } else 0,
            ipsDiscovered = pool.size,
            ipsProbed = probed,
            ipsPassed = passed,
            slotsOk = slotsOk,
            slotsDown = slotsDown,
            repairs = repairs,
            cfWrites = 0,
            result = result,
            note = note
        )

        repo.appendStats(stats)
        repo.saveRunState(
            rs.copy(
                lastCycleAt = now,
                lastCycleMs = durationMs,
                lastResult = result,
                lastError = if (result == "ERROR" || result == "SKIPPED") note else "",
                heartbeatAt = now,
                poolAvailable = pool.count { it.ok },
                slotsOk = slotsOk,
                slotsTotal = repo.slots().count { it.recordId != null },
                repairsToday = rs.repairsToday + repairs,
                phase = "空闲"
            )
        )
        log.add(if (result == "ERROR") "E" else "I", "[$trigger] $result $note ${durationMs}ms")
        return stats
    }
}

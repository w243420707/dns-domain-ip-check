package io.github.w243420707.dnspool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 9 步自检：把"配置填错"和"网络不通"在第一次发布前就暴露出来。
 * 只做只读请求，不写任何 Cloudflare 记录。
 */
class SelfTest(private val repo: StateRepository, private val engine: PollEngine) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val lines = mutableListOf<String>()
    private var pass = 0
    private var total = 0

    suspend fun runAll(): String = withContext(Dispatchers.IO) {
        lines.clear()
        pass = 0
        total = 0
        val s = repo.settings()

        checkLocal(s)
        checkDns(s)
        checkCloudflare(s)

        val summary = "$pass/$total 通过"
        repo.saveRunState(repo.runState().copy(selfTestSummary = summary))
        repo.log.add("I", "自检 $summary")
        (lines + "" + "—— $summary ——").joinToString("\n")
    }

    /* ---------------- 本地配置 ---------------- */

    private fun checkLocal(s: AppSettings) {
        mark("端口配置", s.ports.isNotEmpty(), PortParser.format(s.ports).ifEmpty { "空" } + "（全通才算可用）")

        val domains = s.domainsRaw.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.count()
        mark("域名列表", domains > 0, "$domains 条")

        mark(
            "根域名与命名", s.rootDomain.isNotBlank(),
            if (s.mode == RecordMode.SLOT_SUBDOMAIN) "槽位示例 ${s.recordName(1)} / ${s.recordName(s.slotCount)}"
            else "全部挂在 ${s.recordName(1)}，靠备注区分槽位"
        )

        mark(
            "N 与套餐额度", s.slotCount <= s.recordLimit,
            "N=${s.slotCount}，单 Zone 上限 ${s.recordLimit}"
        )

        val sample = SlotComment.encode(7, 123, 456, System.currentTimeMillis() / 1000, "jp1.example.net", s.slotPad)
        val parsed = SlotComment.parse(sample)
        mark(
            "备注编解码", parsed?.slot == 7 && sample.length <= SlotComment.MAX_LEN,
            "$sample（${sample.length} 字符）"
        )

        val st = repo.runState()
        mark("后台服务", st.running, if (st.running) "运行中" else "未运行（点「启动守护」）")
    }

    /* ---------------- DNS ---------------- */

    private suspend fun checkDns(s: AppSettings) {
        val first = s.domainsRaw.lineSequence()
            .map { IpUtil.cleanHost(it.substringBefore('#').trim()) }
            .firstOrNull { it.isNotBlank() && it.contains('.') }
        if (first == null) {
            mark("DNS 解析", false, "域名列表为空，无法验证")
            return
        }
        val resolver = DnsResolverFactory.create(s, http)
        val result = resolver.resolveA(first)
        val ips = result.getOrNull().orEmpty()
        mark(
            "DNS 解析（${resolver.id}）", ips.isNotEmpty(),
            if (ips.isEmpty()) "解析 $first 失败：${result.exceptionOrNull()?.message?.take(80) ?: "无 A 记录"}"
            else "$first → ${ips.take(3).joinToString(", ")}${if (ips.size > 3) " …共 ${ips.size} 个" else ""}"
        )

        val target = ips.firstOrNull() ?: return
        val probe = TcpProber.probe(target, s.ports, s.connectTimeoutMs, 1)
        val detail = if (probe.ok) {
            probe.timings.joinToString(" ") { "${it.port}:${it.rttMs}ms" }
        } else {
            probe.timings.joinToString(" ") { "${it.port}:${it.error.take(40).ifBlank { "失败" }}" }
        }
        mark("端口连通（$target）", probe.ok, detail)
    }

    /* ---------------- Cloudflare ---------------- */

    private suspend fun checkCloudflare(s: AppSettings) {
        if (s.cfToken.isBlank()) {
            mark("Token", false, "未填写")
            mark("Zone 与额度", false, "跳过")
            mark("差异预览", false, "跳过")
            return
        }
        val client = CloudflareClient({ s.cfToken }, RateLimiter(250), http)
        try {
            val info = client.verifyToken()
            mark("Token", info.status == "active", "状态 ${info.status}，ID ${info.id.take(12)}")
        } catch (e: Exception) {
            mark("Token", false, engine.humanError(e))
            mark("Zone 与额度", false, "跳过")
            mark("差异预览", false, "跳过")
            return
        }

        val zoneId = try {
            engine.resolveZoneId(s)
        } catch (e: Exception) {
            mark("Zone 与额度", false, engine.humanError(e))
            mark("差异预览", false, "跳过")
            return
        }

        try {
            val total = client.countRecords(zoneId)
            val live = client.listAllARecords(zoneId)
            val managed = live.count { SlotComment.parse(it.comment) != null }
            val nonManaged = (total - managed).coerceAtLeast(0)
            mark(
                "Zone 与额度", total in 0..s.recordLimit || total < 0,
                "已用 ${if (total < 0) "?" else total}/${s.recordLimit}，托管 $managed 条，非托管 $nonManaged 条，" +
                    "最多可新增 ${(s.recordLimit - nonManaged).coerceAtLeast(0)} 条"
            )

            val desired = (1..minOf(s.slotCount, 2000)).map { i ->
                DiffPlanner.DesiredSlot(
                    slot = i, name = s.recordName(i), content = "0.0.0.0",
                    ttl = s.ttlSec, comment = SlotComment.encode(i, null, null, 0, "")
                )
            }
            val plan = DiffPlanner.plan(desired, live, System.currentTimeMillis() / 1000, s.commentRefreshSec)
            mark(
                "差异预览（只算不写）", true,
                plan.summary() + if (plan.blocked.isNotEmpty()) "，${plan.blocked.size} 条记录名不符需先清理" else ""
            )
        } catch (e: Exception) {
            mark("Zone 与额度", false, engine.humanError(e))
            mark("差异预览", false, "跳过")
        }
    }

    private fun mark(name: String, ok: Boolean, detail: String) {
        total++
        if (ok) pass++
        lines += "${if (ok) "✓" else "✗"} $name：$detail"
    }
}

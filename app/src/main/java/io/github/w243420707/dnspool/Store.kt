package io.github.w243420707.dnspool

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/* ============================ Token 加密 ============================ */

object SecretBox {
    private const val TAG = "SecretBox"
    private const val ALIAS = "dnspool_cf_token"
    private const val PREFIX = "enc:v1:"

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "seal fallback: ${e.javaClass.simpleName}")
            "plain:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
        }
    }

    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return try {
            when {
                sealed.startsWith(PREFIX) -> {
                    val raw = Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP)
                    val iv = raw.copyOfRange(0, 12)
                    val body = raw.copyOfRange(12, raw.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                    String(cipher.doFinal(body), Charsets.UTF_8)
                }
                sealed.startsWith("plain:") -> String(
                    Base64.decode(sealed.removePrefix("plain:"), Base64.NO_WRAP), Charsets.UTF_8
                )
                else -> sealed
            }
        } catch (e: Exception) {
            Log.w(TAG, "open failed: ${e.javaClass.simpleName}")
            ""
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}

/* ============================ 原子 JSON 文件 ============================ */

class JsonStore(private val dir: File) {

    fun read(name: String): String? = try {
        val f = File(dir, name)
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        Log.w("JsonStore", "read $name failed: ${e.javaClass.simpleName}")
        null
    }

    fun write(name: String, body: String) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            val target = File(dir, name)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            Log.w("JsonStore", "write $name failed: ${e.javaClass.simpleName}")
        }
    }
}

/* ============================ 运行日志 ============================ */

class LogRing(private val dir: File, private val memoryLimit: Int = 300) {
    private val memory = ArrayDeque<LogEntry>()
    private val maxBytes = 512 * 1024L

    init {
        // 进程重启后把磁盘上的尾部日志捞回内存，否则「日志」页在新进程里永远是空的
        runCatching {
            val f = File(dir, "cycle.log")
            if (f.exists()) {
                f.readLines().takeLast(memoryLimit).forEach { line ->
                    val parts = line.split(' ', limit = 3)
                    val at = parts.getOrNull(0)?.toLongOrNull()
                    if (parts.size == 3 && at != null) {
                        memory.addLast(LogEntry(at, parts[1], parts[2]))
                    }
                }
            }
        }
    }

    @Synchronized
    fun add(level: String, text: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, text.take(500))
        memory.addLast(entry)
        while (memory.size > memoryLimit) memory.removeFirst()
        try {
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "cycle.log")
            if (f.exists() && f.length() > maxBytes) {
                val old = File(dir, "cycle.log.1")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            f.appendText("${entry.at} ${entry.level} ${entry.text}\n", Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun tail(n: Int): List<LogEntry> = memory.takeLast(n)

    @Synchronized
    fun dump(): String = memory.joinToString("\n") { "${fmtTime(it.at)} [${it.level}] ${it.text}" }
}

fun fmtTime(ms: Long): String {
    if (ms <= 0) return "-"
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
    return sdf.format(java.util.Date(ms))
}

/** 只到分钟与秒，给 18dp 高的日志行用 */
fun fmtClock(ms: Long): String {
    if (ms <= 0) return "--:--:--"
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
    return sdf.format(java.util.Date(ms))
}

/** 相对时间，运维扫一眼就知道新旧 */
fun fmtAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "—"
    val sec = (now - ms) / 1000
    return when {
        sec < 0 -> "刚刚"
        sec < 60 -> "${sec} 秒前"
        sec < 3600 -> "${sec / 60} 分钟前"
        sec < 86400 -> "${sec / 3600} 小时前"
        else -> "${sec / 86400} 天前"
    }
}

/* ============================ 状态仓库 ============================ */

class StateRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val store = JsonStore(File(app.filesDir, "state"))
    val log = LogRing(File(app.filesDir, "logs"))

    @Volatile
    private var cached: AppSettings = loadSettings()

    companion object {
        @Volatile
        private var instance: StateRepository? = null

        fun get(context: Context): StateRepository =
            instance ?: synchronized(this) {
                instance ?: StateRepository(context).also { instance = it }
            }
    }

    /* ---------- 设置 ---------- */

    fun settings(): AppSettings = cached

    @Synchronized
    fun saveSettings(s: AppSettings) {
        cached = s
        store.write("settings.json", settingsToJson(s).toString())
    }

    private fun loadSettings(): AppSettings {
        val raw = store.read("settings.json") ?: return AppSettings()
        return try {
            settingsFromJson(JSONObject(raw))
        } catch (e: Exception) {
            Log.w("StateRepository", "settings parse failed: ${e.javaClass.simpleName}")
            AppSettings()
        }
    }

    /* ---------- 槽位 ---------- */

    @Synchronized
    fun slots(): List<Slot> {
        val raw = store.read("slots.json") ?: return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("slots") ?: return emptyList()
            (0 until arr.length()).mapNotNull { slotFromJson(arr.optJSONObject(it) ?: return@mapNotNull null) }
        } catch (e: Exception) {
            Log.w("StateRepository", "slots parse failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    @Synchronized
    fun saveSlots(list: List<Slot>) {
        val arr = JSONArray()
        list.forEach { arr.put(slotToJson(it)) }
        store.write("slots.json", JSONObject().put("slots", arr).toString())
    }

    /* ---------- 候选池 ---------- */

    @Synchronized
    fun pool(): List<IpCandidate> {
        val raw = store.read("pool.json") ?: return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("pool") ?: return emptyList()
            (0 until arr.length()).mapNotNull { candidateFromJson(arr.optJSONObject(it) ?: return@mapNotNull null) }
        } catch (e: Exception) {
            Log.w("StateRepository", "pool parse failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    @Synchronized
    fun savePool(list: List<IpCandidate>) {
        val arr = JSONArray()
        list.forEach { arr.put(candidateToJson(it)) }
        store.write("pool.json", JSONObject().put("pool", arr).toString())
    }

    /* ---------- 轮次统计 ---------- */

    @Synchronized
    fun stats(): List<CycleStats> {
        val raw = store.read("stats.json") ?: return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("stats") ?: return emptyList()
            (0 until arr.length()).mapNotNull { o ->
                val j = arr.optJSONObject(o) ?: return@mapNotNull null
                CycleStats(
                    cycleId = j.optLong("cycleId"),
                    startedAt = j.optLong("startedAt"),
                    durationMs = j.optLong("durationMs"),
                    domainsResolved = j.optInt("domainsResolved"),
                    ipsDiscovered = j.optInt("ipsDiscovered"),
                    ipsProbed = j.optInt("ipsProbed"),
                    ipsPassed = j.optInt("ipsPassed"),
                    slotsOk = j.optInt("slotsOk"),
                    slotsDown = j.optInt("slotsDown"),
                    repairs = j.optInt("repairs"),
                    cfWrites = j.optInt("cfWrites"),
                    result = j.optString("result"),
                    note = j.optString("note")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun appendStats(s: CycleStats) {
        val list = stats().toMutableList()
        list.add(s)
        while (list.size > 100) list.removeAt(0)
        val arr = JSONArray()
        list.forEach { j ->
            arr.put(
                JSONObject()
                    .put("cycleId", j.cycleId)
                    .put("startedAt", j.startedAt)
                    .put("durationMs", j.durationMs)
                    .put("domainsResolved", j.domainsResolved)
                    .put("ipsDiscovered", j.ipsDiscovered)
                    .put("ipsProbed", j.ipsProbed)
                    .put("ipsPassed", j.ipsPassed)
                    .put("slotsOk", j.slotsOk)
                    .put("slotsDown", j.slotsDown)
                    .put("repairs", j.repairs)
                    .put("cfWrites", j.cfWrites)
                    .put("result", j.result)
                    .put("note", j.note)
            )
        }
        store.write("stats.json", JSONObject().put("stats", arr).toString())
    }

    /* ---------- 运行态 ---------- */

    @Synchronized
    fun runState(): RunState {
        val raw = store.read("run.json") ?: return RunState()
        return try {
            val j = JSONObject(raw)
            RunState(
                running = j.optBoolean("running"),
                phase = j.optString("phase"),
                lastCycleAt = j.optLong("lastCycleAt"),
                lastCycleMs = j.optLong("lastCycleMs"),
                lastResult = j.optString("lastResult"),
                lastError = j.optString("lastError"),
                heartbeatAt = j.optLong("heartbeatAt"),
                nextRunAt = j.optLong("nextRunAt"),
                poolAvailable = j.optInt("poolAvailable"),
                slotsOk = j.optInt("slotsOk"),
                slotsTotal = j.optInt("slotsTotal"),
                repairsToday = j.optInt("repairsToday"),
                lastResolveAt = j.optLong("lastResolveAt"),
                selfTestSummary = j.optString("selfTestSummary")
            )
        } catch (e: Exception) {
            RunState()
        }
    }

    @Synchronized
    fun saveRunState(s: RunState) {
        store.write(
            "run.json",
            JSONObject()
                .put("running", s.running)
                .put("phase", s.phase)
                .put("lastCycleAt", s.lastCycleAt)
                .put("lastCycleMs", s.lastCycleMs)
                .put("lastResult", s.lastResult)
                .put("lastError", s.lastError)
                .put("heartbeatAt", s.heartbeatAt)
                .put("nextRunAt", s.nextRunAt)
                .put("poolAvailable", s.poolAvailable)
                .put("slotsOk", s.slotsOk)
                .put("slotsTotal", s.slotsTotal)
                .put("repairsToday", s.repairsToday)
                .put("lastResolveAt", s.lastResolveAt)
                .put("selfTestSummary", s.selfTestSummary)
                .toString()
        )
    }

    /* ---------- 未完成写计划 ---------- */

    @Synchronized
    fun pendingWrite(): String? = store.read("pending.json")

    @Synchronized
    fun savePending(json: String?) {
        if (json == null) {
            File(File(app.filesDir, "state"), "pending.json").delete()
        } else {
            store.write("pending.json", json)
        }
    }

    /* ---------- JSON 映射 ---------- */

    private fun settingsToJson(s: AppSettings): JSONObject = JSONObject()
        .put("rootDomain", s.rootDomain)
        .put("mode", s.mode.name)
        .put("sharedHost", s.sharedHost)
        .put("slotPrefix", s.slotPrefix)
        .put("slotPad", s.slotPad)
        .put("slotCount", s.slotCount)
        .put("ttlSec", s.ttlSec)
        .put("proxied", s.proxied)
        .put("domainsRaw", s.domainsRaw)
        .put("portsRaw", s.portsRaw)
        .put("attemptsPerPort", s.attemptsPerPort)
        .put("connectTimeoutMs", s.connectTimeoutMs)
        .put("probeConcurrency", s.probeConcurrency)
        .put("maxProbeBudgetMs", s.maxProbeBudgetMs)
        .put("maxPoolSize", s.maxPoolSize)
        .put("failThreshold", s.failThreshold)
        .put("maxRepairsPerCycle", s.maxRepairsPerCycle)
        .put("minLoopIntervalMs", s.minLoopIntervalMs)
        .put("poolFreshSec", s.poolFreshSec)
        .put("fullRescanMinIntervalSec", s.fullRescanMinIntervalSec)
        .put("banTtlSec", s.banTtlSec)
        .put("verifyCandidates", s.verifyCandidates)
        .put("dnsMode", s.dnsMode.name)
        .put("udpServersRaw", s.udpServersRaw)
        .put("dohUrl", s.dohUrl)
        .put("dnsTimeoutMs", s.dnsTimeoutMs)
        .put("dnsRetries", s.dnsRetries)
        .put("cfToken", SecretBox.seal(s.cfToken))
        .put("zoneId", s.zoneId)
        .put("zoneName", s.zoneName)
        .put("recordLimit", s.recordLimit)
        .put("commentRefreshSec", s.commentRefreshSec)
        .put("writeMinIntervalMs", s.writeMinIntervalMs)
        .put("autoRun", s.autoRun)

    private fun settingsFromJson(j: JSONObject): AppSettings {
        val d = AppSettings()
        return AppSettings(
            rootDomain = j.optString("rootDomain", d.rootDomain),
            mode = runCatching { RecordMode.valueOf(j.optString("mode", d.mode.name)) }.getOrDefault(d.mode),
            sharedHost = j.optString("sharedHost", d.sharedHost),
            slotPrefix = j.optString("slotPrefix", d.slotPrefix),
            slotPad = j.optInt("slotPad", d.slotPad),
            slotCount = j.optInt("slotCount", d.slotCount),
            ttlSec = j.optInt("ttlSec", d.ttlSec),
            proxied = j.optBoolean("proxied", d.proxied),
            domainsRaw = j.optString("domainsRaw", d.domainsRaw),
            portsRaw = j.optString("portsRaw", d.portsRaw),
            attemptsPerPort = j.optInt("attemptsPerPort", d.attemptsPerPort),
            connectTimeoutMs = j.optInt("connectTimeoutMs", d.connectTimeoutMs),
            probeConcurrency = j.optInt("probeConcurrency", d.probeConcurrency),
            maxProbeBudgetMs = j.optLong("maxProbeBudgetMs", d.maxProbeBudgetMs),
            maxPoolSize = j.optInt("maxPoolSize", d.maxPoolSize),
            failThreshold = j.optInt("failThreshold", d.failThreshold),
            maxRepairsPerCycle = j.optInt("maxRepairsPerCycle", d.maxRepairsPerCycle),
            minLoopIntervalMs = j.optLong("minLoopIntervalMs", d.minLoopIntervalMs),
            poolFreshSec = j.optLong("poolFreshSec", d.poolFreshSec),
            fullRescanMinIntervalSec = j.optLong("fullRescanMinIntervalSec", d.fullRescanMinIntervalSec),
            banTtlSec = j.optLong("banTtlSec", d.banTtlSec),
            verifyCandidates = j.optInt("verifyCandidates", d.verifyCandidates),
            dnsMode = runCatching { DnsMode.valueOf(j.optString("dnsMode", d.dnsMode.name)) }.getOrDefault(d.dnsMode),
            udpServersRaw = j.optString("udpServersRaw", d.udpServersRaw),
            dohUrl = j.optString("dohUrl", d.dohUrl),
            dnsTimeoutMs = j.optInt("dnsTimeoutMs", d.dnsTimeoutMs),
            dnsRetries = j.optInt("dnsRetries", d.dnsRetries),
            cfToken = SecretBox.open(j.optString("cfToken", "")),
            zoneId = j.optString("zoneId", d.zoneId),
            zoneName = j.optString("zoneName", d.zoneName),
            recordLimit = j.optInt("recordLimit", d.recordLimit),
            commentRefreshSec = j.optLong("commentRefreshSec", d.commentRefreshSec),
            writeMinIntervalMs = j.optLong("writeMinIntervalMs", d.writeMinIntervalMs),
            autoRun = j.optBoolean("autoRun", d.autoRun)
        )
    }

    private fun timingsToJson(t: List<PortTiming>): JSONArray {
        val arr = JSONArray()
        t.forEach { p ->
            arr.put(
                JSONObject().put("port", p.port).put("ok", p.ok)
                    .put("rttMs", p.rttMs).put("error", p.error)
            )
        }
        return arr
    }

    private fun timingsFromJson(a: JSONArray?): List<PortTiming> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            PortTiming(o.optInt("port"), o.optBoolean("ok"), o.optInt("rttMs"), o.optString("error"))
        }
    }

    private fun slotToJson(s: Slot): JSONObject = JSONObject()
        .put("index", s.index)
        .put("name", s.name)
        .put("recordId", s.recordId ?: JSONObject.NULL)
        .put("ip", s.ip ?: JSONObject.NULL)
        .put("comment", s.comment ?: JSONObject.NULL)
        .put("state", s.state.name)
        .put("consecutiveFailures", s.consecutiveFailures)
        .put("lastProbeAt", s.lastProbeAt)
        .put("lastOkAt", s.lastOkAt)
        .put("lastAvgRttMs", s.lastAvgRttMs)
        .put("lastMaxRttMs", s.lastMaxRttMs)
        .put("lastWrittenAt", s.lastWrittenAt)
        .put("pendingRepair", s.pendingRepair)

    private fun slotFromJson(j: JSONObject): Slot = Slot(
        index = j.optInt("index"),
        name = j.optString("name"),
        recordId = if (j.isNull("recordId")) null else j.optString("recordId").ifEmpty { null },
        ip = if (j.isNull("ip")) null else j.optString("ip").ifEmpty { null },
        comment = if (j.isNull("comment")) null else j.optString("comment").ifEmpty { null },
        state = runCatching { SlotState.valueOf(j.optString("state", SlotState.EMPTY.name)) }
            .getOrDefault(SlotState.EMPTY),
        consecutiveFailures = j.optInt("consecutiveFailures"),
        lastProbeAt = j.optLong("lastProbeAt"),
        lastOkAt = j.optLong("lastOkAt"),
        lastAvgRttMs = j.optInt("lastAvgRttMs"),
        lastMaxRttMs = j.optInt("lastMaxRttMs"),
        lastWrittenAt = j.optLong("lastWrittenAt"),
        pendingRepair = j.optBoolean("pendingRepair")
    )

    private fun candidateToJson(c: IpCandidate): JSONObject = JSONObject()
        .put("ip", c.ip)
        .put("ok", c.ok)
        .put("timings", timingsToJson(c.timings))
        .put("avgRttMs", c.avgRttMs)
        .put("maxRttMs", c.maxRttMs)
        .put("sourceDomain", c.sourceDomain)
        .put("probedAt", c.probedAt)
        .put("bannedUntil", c.bannedUntil)

    private fun candidateFromJson(j: JSONObject): IpCandidate = IpCandidate(
        ip = j.optString("ip"),
        ok = j.optBoolean("ok"),
        timings = timingsFromJson(j.optJSONArray("timings")),
        avgRttMs = j.optInt("avgRttMs"),
        maxRttMs = j.optInt("maxRttMs"),
        sourceDomain = j.optString("sourceDomain"),
        probedAt = j.optLong("probedAt"),
        bannedUntil = j.optLong("bannedUntil")
    )
}

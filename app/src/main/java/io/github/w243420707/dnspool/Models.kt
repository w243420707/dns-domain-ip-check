package io.github.w243420707.dnspool

import kotlin.math.roundToInt

/* ============================ 枚举 ============================ */

enum class RecordMode(val label: String) {
    SLOT_SUBDOMAIN("槽位子域名"),
    SHARED_HOST("同主机多 A")
}

enum class DnsMode(val label: String) {
    SYSTEM("系统 DNS"),
    UDP("指定 UDP"),
    DOH("DoH")
}

enum class SlotState(val label: String) {
    EMPTY("待建"),
    OK("正常"),
    SUSPECT("可疑"),
    DOWN("故障"),
    PENDING_WRITE("待写")
}

/* ============================ 设置 ============================ */

data class AppSettings(
    // 目标
    val rootDomain: String = "",
    val mode: RecordMode = RecordMode.SLOT_SUBDOMAIN,
    val sharedHost: String = "cf",
    val slotPrefix: String = "n",
    val slotPad: Int = 3,
    val slotCount: Int = 200,
    val ttlSec: Int = 60,
    val proxied: Boolean = false,
    val domainsRaw: String = "",
    // 探测
    val portsRaw: String = "443,80",
    val attemptsPerPort: Int = 1,
    val connectTimeoutMs: Int = 2000,
    val probeConcurrency: Int = 32,
    val maxProbeBudgetMs: Long = 60_000,
    val maxPoolSize: Int = 2000,
    // 判定与修复
    val failThreshold: Int = 2,
    val maxRepairsPerCycle: Int = 5,
    val minLoopIntervalMs: Long = 0,
    val poolFreshSec: Long = 900,
    val fullRescanMinIntervalSec: Long = 300,
    val banTtlSec: Long = 1800,
    val verifyCandidates: Int = 3,
    // DNS
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val udpServersRaw: String = "223.5.5.5,119.29.29.29",
    val dohUrl: String = "https://dns.alidns.com/dns-query",
    val dnsTimeoutMs: Int = 1500,
    val dnsRetries: Int = 2,
    // Cloudflare
    val cfToken: String = "",
    val zoneId: String = "",
    val zoneName: String = "",
    val recordLimit: Int = 200,
    val commentRefreshSec: Long = 21600,
    val writeMinIntervalMs: Long = 250,
    // 运行
    val autoRun: Boolean = true
) {
    val ports: List<Int> get() = PortParser.parse(portsRaw)
    val udpServers: List<String> get() = udpServersRaw.split(',', ' ', ';', '\n')
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun recordName(index: Int): String {
        val root = rootDomain.trim().trimEnd('.').lowercase()
        if (mode == RecordMode.SHARED_HOST) {
            val host = sharedHost.trim().trimEnd('.')
            return if (host.isEmpty() || host == "@") root else "$host.$root"
        }
        val prefix = slotPrefix.trim().ifEmpty { "n" }
        return "$prefix${index.toString().padStart(slotPad.coerceIn(1, 4), '0')}.$root"
    }

    /** 返回配置问题清单，空表示可用 */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (rootDomain.isBlank()) problems += "根域名未填写"
        if (ports.isEmpty()) problems += "端口没填对"
        if (slotCount !in 1..2000) problems += "N 必须在 1..2000"
        if (probeConcurrency !in 1..128) problems += "并发数必须在 1..128"
        if (failThreshold !in 1..10) problems += "失败阈值必须在 1..10"
        if (mode == RecordMode.SHARED_HOST && slotCount > 1) {
            // 同一主机名下 N 条 A 记录，靠备注区分槽位，允许但需提示配额
        }
        return problems
    }
}

/* ============================ 数据模型 ============================ */

data class PortTiming(
    val port: Int,
    val ok: Boolean,
    val rttMs: Int = 0,
    val error: String = ""
)

data class ProbeResult(
    val ip: String,
    val ok: Boolean,
    val timings: List<PortTiming> = emptyList()
) {
    val avgRttMs: Int
        get() = if (!ok || timings.isEmpty()) 0
        else timings.map { it.rttMs }.average().roundToInt()

    val maxRttMs: Int
        get() = if (!ok || timings.isEmpty()) 0 else timings.maxOf { it.rttMs }
}

data class IpCandidate(
    val ip: String,
    val ok: Boolean,
    val timings: List<PortTiming> = emptyList(),
    val avgRttMs: Int = 0,
    val maxRttMs: Int = 0,
    val sourceDomain: String = "",
    val probedAt: Long = 0L,
    val bannedUntil: Long = 0L
)

data class Slot(
    val index: Int,
    val name: String = "",
    val recordId: String? = null,
    val ip: String? = null,
    val comment: String? = null,
    val state: SlotState = SlotState.EMPTY,
    val consecutiveFailures: Int = 0,
    val lastProbeAt: Long = 0L,
    val lastOkAt: Long = 0L,
    val lastAvgRttMs: Int = 0,
    val lastMaxRttMs: Int = 0,
    val lastWrittenAt: Long = 0L,
    val pendingRepair: Boolean = false
)

data class CycleStats(
    val cycleId: Long = 0L,
    val startedAt: Long = 0L,
    val durationMs: Long = 0L,
    val domainsResolved: Int = 0,
    val ipsDiscovered: Int = 0,
    val ipsProbed: Int = 0,
    val ipsPassed: Int = 0,
    val slotsOk: Int = 0,
    val slotsDown: Int = 0,
    val repairs: Int = 0,
    val cfWrites: Int = 0,
    val result: String = "",
    val note: String = ""
)

data class RunState(
    val running: Boolean = false,
    val phase: String = "",
    val lastCycleAt: Long = 0L,
    val lastCycleMs: Long = 0L,
    val lastResult: String = "",
    val lastError: String = "",
    val heartbeatAt: Long = 0L,
    val nextRunAt: Long = 0L,
    val poolAvailable: Int = 0,
    val slotsOk: Int = 0,
    val slotsTotal: Int = 0,
    val repairsToday: Int = 0,
    val lastResolveAt: Long = 0L,
    val selfTestSummary: String = ""
)

data class LogEntry(
    val at: Long = 0L,
    val level: String = "I",
    val text: String = ""
)

/* ============================ Cloudflare DTO ============================ */

data class CfRecord(
    val id: String,
    val name: String,
    val type: String = "A",
    val content: String,
    val ttl: Int = 60,
    val proxied: Boolean = false,
    val comment: String? = null
)

data class CfRecordDraft(
    val name: String,
    val content: String,
    val ttl: Int,
    val comment: String
)

data class CfRecordPatch(
    val content: String? = null,
    val comment: String? = null,
    val ttl: Int? = null
)

data class CfZone(val id: String, val name: String)

data class CfTokenInfo(val status: String, val id: String)

/* ============================ 纯工具 ============================ */

object PortParser {
    fun parse(raw: String): List<Int> = raw
        .split(',', ' ', ';', '\n', '\t', '、', '，')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..65535 }
        .distinct()

    fun format(ports: List<Int>): String = ports.joinToString(",")
}

object IpUtil {
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isIpv4(raw: String): Boolean = ipv4.matches(raw.trim())

    /** 剔除私网、环回、保留段与 CGNAT，避免把内网地址发布出去 */
    fun isPublicIpv4(raw: String): Boolean {
        val ip = raw.trim()
        if (!isIpv4(ip)) return false
        val p = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        val (a, b) = p[0] to p[1]
        if (a == 10) return false
        if (a == 127) return false
        if (a == 0) return false
        if (a == 169 && b == 254) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 100 && b in 64..127) return false
        if (a == 192 && b == 0) return false
        if (a == 198 && b in 18..19) return false
        if (a >= 224) return false
        return true
    }

    fun cleanHost(raw: String): String = raw
        .trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore(':')
        .trim()
        .trimEnd('.')
        .lowercase()
}

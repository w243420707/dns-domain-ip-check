package io.github.w243420707.dnspool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单 IP 的 TCP 连接探测：只做“能不能连上 + 连得有多快”，不发送任何应用层数据。
 */
object TcpProber {

    private const val MAX_ERROR_CHARS = 80
    private const val MAX_ATTEMPTS = 10

    suspend fun probe(
        ip: String,
        ports: List<Int>,
        timeoutMs: Int,
        attempts: Int,
        failFast: Boolean = false
    ): ProbeResult = withContext(Dispatchers.IO) {
        val host = ip.trim()
        if (ports.isEmpty() || !IpUtil.isIpv4(host)) {
            return@withContext ProbeResult(ip, false, emptyList())
        }

        val timeout = timeoutMs.coerceAtLeast(1)
        val tries = attempts.coerceIn(1, MAX_ATTEMPTS)
        val timings = ArrayList<PortTiming>(ports.size)

        // 同 IP 的端口必须顺序测：并发会互相争抢排队与带宽，RTT 失去可比性
        for (port in ports) {
            val timing = probePort(host, port, timeout, tries)
            timings += timing
            if (!timing.ok && failFast) {
                return@withContext ProbeResult(ip, false, timings)
            }
        }

        ProbeResult(ip, timings.all { it.ok }, timings)
    }

    private fun probePort(ip: String, port: Int, timeoutMs: Int, attempts: Int): PortTiming {
        var bestRtt = Int.MAX_VALUE
        var lastError = ""

        repeat(attempts) {
            val started = System.nanoTime()
            try {
                // use{} 保证任何分支下 FD 都会被关闭，超时的 socket 也不会泄漏
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(ip, port), timeoutMs)
                }
                val cost = ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
                if (cost < bestRtt) bestRtt = cost
            } catch (error: Exception) {
                lastError = describe(error)
            }
        }

        return if (bestRtt != Int.MAX_VALUE) {
            PortTiming(port, true, bestRtt, "")
        } else {
            PortTiming(port, false, 0, lastError)
        }
    }

    private fun describe(error: Exception): String {
        val name = error.javaClass.simpleName
        val message = error.message.orEmpty().trim()
        val text = if (message.isEmpty()) name else "$name:$message"
        return text.take(MAX_ERROR_CHARS)
    }
}

/**
 * 批量探测调度：限制并发数，并用总预算兜住整轮耗时。
 */
class ProbeScheduler(private val concurrency: Int) {

    private val permits = Semaphore(concurrency.coerceIn(1, 128))

    suspend fun probeAll(
        ips: List<String>,
        ports: List<Int>,
        timeoutMs: Int,
        attempts: Int,
        budgetMs: Long,
        failFast: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ProbeResult> {
        if (ips.isEmpty()) return emptyList()

        val total = ips.size
        val done = AtomicInteger(0)
        // 预算超时后仍要把已完成部分交回上层，所以先落袋再等 awaitAll
        val collected = Collections.synchronizedList(ArrayList<ProbeResult>(total))

        withTimeoutOrNull(budgetMs.coerceAtLeast(0L)) {
            coroutineScope {
                ips.map { ip ->
                    async {
                        permits.withPermit {
                            val result = runProbe(ip, ports, timeoutMs, attempts, failFast)
                            collected += result
                            runCatching { onProgress(done.incrementAndGet(), total) }
                            result
                        }
                    }
                }.awaitAll()
            }
        }

        synchronized(collected) { return collected.toList() }
    }

    private suspend fun runProbe(
        ip: String,
        ports: List<Int>,
        timeoutMs: Int,
        attempts: Int,
        failFast: Boolean
    ): ProbeResult = try {
        TcpProber.probe(ip, ports, timeoutMs, attempts, failFast)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ProbeResult(ip, false, emptyList())
    }
}

object ProbeTuning {

    /** 弱网熔断：样本数 >= minSample 且失败率 > ratio 时判定本地网络异常 */
    fun looksOffline(results: List<ProbeResult>, minSample: Int = 20, ratio: Double = 0.8): Boolean {
        if (results.isEmpty() || results.size < minSample) return false
        return results.count { !it.ok }.toDouble() / results.size > ratio
    }
}

package io.github.w243420707.dnspool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val DNS_MEDIA_TYPE = "application/dns-message"
private val DNS_MEDIA = DNS_MEDIA_TYPE.toMediaType()

/** 统一的 A 记录解析入口；实现需线程安全，每次调用自行创建 socket。 */
interface DnsResolver {
    val id: String
    suspend fun resolveA(host: String): Result<List<String>>
}

/** 响应 TC=1，UDP 实现捕获后应改用 TCP 重试。 */
class DnsTruncatedException(message: String = "响应被截断，需要 TCP 重试") : Exception(message)

object DnsMessage {
    private const val HEADER_SIZE = 12
    private const val TYPE_A = 1
    private const val CLASS_IN = 1

    fun buildQuery(host: String, id: Int): ByteArray {
        val ascii = IDN.toASCII(host.trim().trimEnd('.')).lowercase()
        require(ascii.isNotEmpty()) { "域名为空" }
        val labels = ascii.split('.')
        var nameLen = 1
        for (label in labels) {
            require(label.isNotEmpty()) { "域名含空标签" }
            require(label.length <= 63) { "标签超长: $label" }
            nameLen += label.length + 1
        }
        require(nameLen <= 255) { "域名过长: $nameLen 字节" }

        // DNS 头部 12 字节 + QNAME + QTYPE/QCLASS + EDNS0 OPT(11 字节)
        val buf = ByteArray(HEADER_SIZE + nameLen + 4 + 11)
        buf[0] = (id ushr 8).toByte()
        buf[1] = id.toByte()
        buf[2] = 0x01                         // flags 高字节: RD=1
        buf[3] = 0x00
        buf[5] = 1                            // QDCOUNT=1
        buf[11] = 1                           // ARCOUNT=1

        var p = HEADER_SIZE
        for (label in labels) {
            val raw = label.toByteArray(Charsets.US_ASCII)
            buf[p++] = raw.size.toByte()
            raw.copyInto(buf, p)
            p += raw.size
        }
        buf[p++] = 0                          // QNAME 结束
        buf[p++] = 0; buf[p++] = TYPE_A.toByte()
        buf[p++] = 0; buf[p++] = CLASS_IN.toByte()

        // EDNS0 OPT 伪记录: NAME=0, TYPE=41, CLASS=1232, TTL=0, RDLEN=0
        buf[p++] = 0
        buf[p++] = 0; buf[p++] = 41
        buf[p++] = ((1232 ushr 8) and 0xFF).toByte()
        buf[p++] = (1232 and 0xFF).toByte()
        p += 4
        buf[p++] = 0; buf[p++] = 0
        check(p == buf.size) { "查询包长度计算错误" }
        return buf
    }

    fun parseResponse(bytes: ByteArray, expectId: Int): List<String> {
        if (bytes.size < HEADER_SIZE) throw IllegalArgumentException("回包过短: ${bytes.size} 字节")
        val id = u16(bytes, 0)
        val wantId = expectId and 0xFFFF
        if (id != wantId) throw IllegalStateException("ID 不匹配: 期望 $wantId 实际 $id")

        val flags = u16(bytes, 2)
        if (flags and 0x8000 == 0) throw IllegalStateException("不是 DNS 响应(QR=0)")
        // TC 必须在解析答段前判断，让 UDP 实现转而走 TCP
        if (flags and 0x0200 != 0) throw DnsTruncatedException()
        when (val rcode = flags and 0x000F) {
            0 -> Unit
            2 -> throw IllegalStateException("上游 SERVFAIL")
            3 -> throw IllegalStateException("域名不存在(NXDOMAIN)")
            5 -> throw IllegalStateException("上游拒绝(REFUSED)")
            else -> throw IllegalStateException("RCODE=$rcode")
        }

        val qdCount = u16(bytes, 4)
        val anCount = u16(bytes, 6)
        var off = HEADER_SIZE
        repeat(qdCount) {
            off = skipName(bytes, off)
            require(off + 4 <= bytes.size) { "Question 区越界" }
            off += 4
        }

        val ips = LinkedHashSet<String>(anCount.coerceAtMost(16))
        repeat(anCount) {
            off = skipName(bytes, off)
            require(off + 10 <= bytes.size) { "资源记录头越界" }
            val type = u16(bytes, off)
            val klass = u16(bytes, off + 2)
            val rdLen = u16(bytes, off + 8)
            off += 10
            require(off + rdLen <= bytes.size) { "RDLENGTH 越界" }
            if (type == TYPE_A && klass == CLASS_IN && rdLen == 4) {
                val ip = "${bytes[off].toInt() and 0xFF}.${bytes[off + 1].toInt() and 0xFF}." +
                    "${bytes[off + 2].toInt() and 0xFF}.${bytes[off + 3].toInt() and 0xFF}"
                ips.add(ip)
            }
            off += rdLen
        }
        return ips.toList()
    }

    /**
     * 跳过 wire 格式中的域名。指向别处的 0xC0 压缩指针即表示名字在此结束，
     * 这里不做解压，只需吃掉 2 字节；保留跳转计数上限用于防御畸形包。
     */
    private fun skipName(bytes: ByteArray, start: Int): Int {
        var p = start
        var hops = 0
        while (true) {
            require(p < bytes.size) { "域名越界" }
            val len = bytes[p].toInt() and 0xFF
            if (len == 0) return p + 1
            if (len and 0xC0 == 0xC0) {
                require(p + 1 < bytes.size) { "压缩指针越界" }
                hops++
                require(hops <= 16) { "压缩指针跳转过多" }
                return p + 2
            }
            require(len <= 63) { "非法标签长度: $len" }
            p += len + 1
        }
    }

    private fun u16(bytes: ByteArray, off: Int): Int {
        require(off >= 0 && off + 1 < bytes.size) { "回包越界" }
        return ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)
    }
}

class SystemDnsResolver : DnsResolver {
    override val id: String = "system"

    override suspend fun resolveA(host: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val attempt = runCatching {
            val clean = IpUtil.cleanHost(host)
            require(clean.isNotEmpty()) { "域名为空" }
            InetAddress.getAllByName(clean)
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .distinct()
        }
        attempt.fold(
            onSuccess = { ips ->
                if (ips.isEmpty()) Result.failure<List<String>>(Exception("没有 A 记录"))
                else Result.success(ips)
            },
            onFailure = { e -> Result.failure<List<String>>(Exception(shortError(e))) }
        )
    }
}

class UdpDnsResolver(
    private val servers: List<String>,
    private val timeoutMs: Int,
    private val retries: Int
) : DnsResolver {
    override val id: String = "udp:${servers.firstOrNull().orEmpty()}"

    override suspend fun resolveA(host: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) {
            return@withContext Result.failure<List<String>>(IllegalStateException("没有配置 UDP DNS"))
        }
        val attempts = retries.coerceAtLeast(1)
        val timeout = timeoutMs.coerceAtLeast(1)
        var lastError: Exception = IllegalStateException("DNS 查询失败")

        for (n in 0 until attempts) {
            val server = servers[n % servers.size]
            val queryId = Random.nextInt(0xFFFF)
            try {
                val query = DnsMessage.buildQuery(host, queryId)
                return@withContext Result.success(udpQuery(server, query, queryId, timeout))
            } catch (e: DnsTruncatedException) {
                // UDP 被截断：同一服务器改走 TCP，长度前缀 + 完整查询
                try {
                    val query = DnsMessage.buildQuery(host, queryId)
                    return@withContext Result.success(tcpQuery(server, query, queryId, timeout))
                } catch (t: Exception) {
                    lastError = t
                }
            } catch (e: Exception) {
                lastError = e // 超时等单次失败不致命，继续下一次尝试
            }
        }
        Result.failure<List<String>>(lastError)
    }

    private fun udpQuery(server: String, query: ByteArray, queryId: Int, timeout: Int): List<String> {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeout
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
            val buffer = ByteArray(4096)   // EDNS 允许较大的响应
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            if (packet.length < 12) throw IllegalStateException("回包过短: ${packet.length} 字节")
            val replyId = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            if (replyId != queryId) throw IllegalStateException("ID 不匹配")
            return DnsMessage.parseResponse(buffer.copyOf(packet.length), queryId)
        }
    }

    private fun tcpQuery(server: String, query: ByteArray, queryId: Int, timeout: Int): List<String> {
        Socket().use { socket ->
            socket.soTimeout = timeout
            socket.connect(InetSocketAddress(server, 53), timeout)
            val out = socket.getOutputStream()
            out.write((query.size ushr 8) and 0xFF)
            out.write(query.size and 0xFF)
            out.write(query)
            out.flush()

            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            require(length >= 12) { "TCP 回包长度异常: $length" }
            val reply = ByteArray(length)
            input.readFully(reply)
            return DnsMessage.parseResponse(reply, queryId)
        }
    }
}

class DohDnsResolver(
    private val endpoint: String,
    client: OkHttpClient,
    timeoutMs: Int
) : DnsResolver {
    override val id: String = "doh:$endpoint"
    private val http = client.newBuilder()
        .callTimeout(timeoutMs.toLong().coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        .build()

    override suspend fun resolveA(host: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val query = DnsMessage.buildQuery(host, 0)
            val request = Request.Builder()
                .url(endpoint)
                .post(query.toRequestBody(DNS_MEDIA))
                .header("Accept", DNS_MEDIA_TYPE)
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code != 200) throw IllegalStateException("DoH HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("DoH 响应为空")
                DnsMessage.parseResponse(body.bytes(), 0)
            }
        }
    }
}

object DnsResolverFactory {
    fun create(settings: AppSettings, client: OkHttpClient): DnsResolver = when (settings.dnsMode) {
        DnsMode.SYSTEM -> SystemDnsResolver()
        DnsMode.UDP -> UdpDnsResolver(settings.udpServers, settings.dnsTimeoutMs, settings.dnsRetries)
        DnsMode.DOH -> DohDnsResolver(settings.dohUrl, client, settings.dnsTimeoutMs)
    }

    fun createById(id: String, settings: AppSettings, client: OkHttpClient): DnsResolver {
        val raw = id.trim()
        return when {
            raw.equals("system", ignoreCase = true) || raw.isEmpty() -> SystemDnsResolver()
            raw.startsWith("udp:", ignoreCase = true) -> {
                val servers = raw.substring(4)
                    .split(',', ' ', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                UdpDnsResolver(
                    servers.ifEmpty { settings.udpServers },
                    settings.dnsTimeoutMs,
                    settings.dnsRetries
                )
            }
            raw.startsWith("doh:", ignoreCase = true) -> {
                val url = raw.substring(4).trim()
                if (url.isEmpty()) SystemDnsResolver()
                else DohDnsResolver(url, client, settings.dnsTimeoutMs)
            }
            else -> SystemDnsResolver()
        }
    }
}

private fun shortError(e: Throwable): String {
    val text = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    return text.take(120)
}

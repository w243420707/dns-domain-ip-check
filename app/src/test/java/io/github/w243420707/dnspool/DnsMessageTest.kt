package io.github.w243420707.dnspool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {

    @Test
    fun `构造的查询包头部与问题区正确`() {
        val q = DnsMessage.buildQuery("www.example.com", 0x1234)
        assertEquals(0x12, q[0].toInt() and 0xFF)
        assertEquals(0x34, q[1].toInt() and 0xFF)
        assertEquals(0x01, q[2].toInt() and 0xFF)
        assertEquals(0x00, q[3].toInt() and 0xFF)
        assertEquals(1, q[5].toInt() and 0xFF)
        assertEquals(1, q[11].toInt() and 0xFF)
        assertEquals("www.example.com", readQName(q, 12))
        val qType = ((q[12 + 17] .toInt() and 0xFF) shl 8) or (q[12 + 18].toInt() and 0xFF)
        assertEquals(1, qType)
        // 末尾 11 字节是 EDNS0 OPT：根名(1) + type 0x0029(2) + class 1232(2) + ttl(4) + rdlen(2)
        assertEquals(0x00, q[q.size - 11].toInt() and 0xFF)
        assertEquals(0x00, q[q.size - 10].toInt() and 0xFF)
        assertEquals(0x29, q[q.size - 9].toInt() and 0xFF)
    }

    @Test
    fun `解析单条 A 记录（含压缩指针）`() {
        val answer = answerPacket(0x1234, flags = 0x8180, ip = byteArrayOf(1, 2, 3, 4))
        val ips = DnsMessage.parseResponse(answer, 0x1234)
        assertEquals(listOf("1.2.3.4"), ips)
    }

    @Test
    fun `跳过非 A 记录`() {
        val answer = answerPacket(0x1234, flags = 0x8180, ip = byteArrayOf(8, 8, 4, 4), recordType = 5)
        assertTrue(DnsMessage.parseResponse(answer, 0x1234).isEmpty())
    }

    @Test(expected = DnsTruncatedException::class)
    fun `TC 置位抛出可重试异常`() {
        val answer = answerPacket(0x1234, flags = 0x8380, ip = byteArrayOf(1, 2, 3, 4))
        DnsMessage.parseResponse(answer, 0x1234)
    }

    @Test(expected = Exception::class)
    fun `ID 不匹配必须失败`() {
        val answer = answerPacket(0x1234, flags = 0x8180, ip = byteArrayOf(1, 2, 3, 4))
        DnsMessage.parseResponse(answer, 0x9999)
    }

    @Test(expected = Exception::class)
    fun `过短回包必须失败`() {
        DnsMessage.parseResponse(byteArrayOf(1, 2, 3), 1)
    }

    @Test(expected = Exception::class)
    fun `越界答段不得静默通过`() {
        val answer = answerPacket(0x1234, flags = 0x8180, ip = byteArrayOf(1, 2, 3, 4))
        DnsMessage.parseResponse(answer.copyOf(answer.size - 2), 0x1234)
    }

    private fun readQName(packet: ByteArray, start: Int): String {
        val sb = StringBuilder()
        var i = start
        while (true) {
            val len = packet[i].toInt() and 0xFF
            if (len == 0) break
            sb.append(String(packet, i + 1, len, Charsets.US_ASCII))
            sb.append('.')
            i += len + 1
        }
        return sb.toString().trimEnd('.')
    }

    private fun answerPacket(id: Int, flags: Int, ip: ByteArray, recordType: Int = 1): ByteArray {
        val out = ArrayList<Byte>()
        out += byteOf(id ushr 8)
        out += byteOf(id)
        out += byteOf(flags ushr 8)
        out += byteOf(flags)
        out += byteOf(0); out += byteOf(1)
        out += byteOf(0); out += byteOf(1)
        out += byteOf(0); out += byteOf(0)
        out += byteOf(0); out += byteOf(0)
        for (label in listOf("www", "example", "com")) {
            out += byteOf(label.length)
            label.forEach { out += byteOf(it.code) }
        }
        out += byteOf(0)
        out += byteOf(0); out += byteOf(1)
        out += byteOf(0); out += byteOf(1)
        out += byteOf(0xC0); out += byteOf(0x0C)
        out += byteOf(0); out += byteOf(recordType)
        out += byteOf(0); out += byteOf(1)
        out += byteOf(0); out += byteOf(0); out += byteOf(0); out += byteOf(60)
        out += byteOf(0); out += byteOf(4)
        ip.forEach { out += it }
        return out.toByteArray()
    }

    private fun byteOf(v: Int): Byte = (v and 0xFF).toByte()
}

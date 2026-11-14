package io.github.w243420707.dnspool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolSelectionTest {

    private fun cand(ip: String, avg: Int, ok: Boolean = true, ban: Long = 0) =
        IpCandidate(ip = ip, ok = ok, avgRttMs = avg, maxRttMs = avg + 5, bannedUntil = ban)

    @Test
    fun `按延迟升序取前 N`() {
        val pool = listOf(cand("3.3.3.3", 30), cand("1.1.1.1", 10), cand("2.2.2.2", 20))
        val picked = CandidatePicker.pick(pool, emptySet(), 0, 2)
        assertEquals(listOf("1.1.1.1", "2.2.2.2"), picked.map { it.ip })
    }

    @Test
    fun `跳过已占用 未通过 拉黑与内网地址`() {
        val pool = listOf(
            cand("1.1.1.1", 10),
            cand("2.2.2.2", 20),
            cand("3.3.3.3", 30, ok = false),
            cand("4.4.4.4", 40, ban = 9_999),
            cand("192.168.1.1", 5)
        )
        val picked = CandidatePicker.pick(pool, setOf("1.1.1.1"), 1000, 10)
        assertFalse(picked.any { it.ip == "1.1.1.1" })
        assertTrue(picked.none { it.ip == "192.168.1.1" })
        assertTrue(picked.none { it.ip == "3.3.3.3" })
        assertEquals(1, picked.size)
        assertEquals("2.2.2.2", picked[0].ip)
    }

    @Test
    fun `拉黑到期后可重新入选`() {
        val pool = listOf(cand("1.1.1.1", 10, ban = 500))
        assertTrue(CandidatePicker.pick(pool, emptySet(), 400, 5).isEmpty())
        assertEquals(1, CandidatePicker.pick(pool, emptySet(), 600, 5).size)
    }

    @Test
    fun `同一 IP 只出现一次`() {
        val pool = listOf(cand("1.1.1.1", 10), cand("1.1.1.1", 99))
        assertEquals(1, CandidatePicker.pick(pool, emptySet(), 0, 5).size)
    }

    @Test
    fun `探测结果聚合口径`() {
        val ok = ProbeResult("1.1.1.1", true, listOf(PortTiming(443, true, 100), PortTiming(80, true, 200)))
        assertEquals(150, ok.avgRttMs)
        assertEquals(200, ok.maxRttMs)

        val bad = ProbeResult("2.2.2.2", false, listOf(PortTiming(443, true, 50), PortTiming(80, false, 0)))
        assertEquals(0, bad.avgRttMs)
    }

    @Test
    fun `弱网熔断阈值`() {
        val allDown = (1..30).map { ProbeResult("1.1.1.$it", false) }
        assertTrue(ProbeTuning.looksOffline(allDown, minSample = 20, ratio = 0.8))

        val mostlyUp = (1..30).map { ProbeResult("1.1.1.$it", it > 3) }
        assertFalse(ProbeTuning.looksOffline(mostlyUp, minSample = 20, ratio = 0.8))

        val fewDown = (1..5).map { ProbeResult("1.1.1.$it", false) }
        assertFalse("样本太少不该熔断", ProbeTuning.looksOffline(fewDown, minSample = 20, ratio = 0.8))
    }

    @Test
    fun `公网地址判定`() {
        assertTrue(IpUtil.isPublicIpv4("1.2.3.4"))
        assertTrue(IpUtil.isPublicIpv4("104.16.1.1"))
        assertFalse(IpUtil.isPublicIpv4("10.0.0.1"))
        assertFalse(IpUtil.isPublicIpv4("172.20.0.1"))
        assertFalse(IpUtil.isPublicIpv4("192.168.0.1"))
        assertFalse(IpUtil.isPublicIpv4("127.0.0.1"))
        assertFalse(IpUtil.isPublicIpv4("100.64.0.1"))
        assertFalse(IpUtil.isPublicIpv4("224.0.0.1"))
        assertFalse(IpUtil.isPublicIpv4("not-an-ip"))
    }

    @Test
    fun `端口串解析容忍各种分隔符`() {
        assertEquals(listOf(443, 80), PortParser.parse("443,80"))
        assertEquals(listOf(443, 80, 8443), PortParser.parse("443 80;8443"))
        assertEquals(listOf(443, 80), PortParser.parse("443,443,80,"))
        assertTrue(PortParser.parse("0,70000,abc").isEmpty())
    }
}

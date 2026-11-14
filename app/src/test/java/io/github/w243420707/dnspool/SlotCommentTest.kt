package io.github.w243420707.dnspool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCommentTest {

    @Test
    fun `往返编解码保真`() {
        val raw = SlotComment.encode(7, 123, 456, 1763100000L, "jp1.example.net")
        val parsed = SlotComment.parse(raw)
        assertNotNull(parsed)
        assertEquals(7, parsed!!.slot)
        assertEquals(123, parsed.avgRttMs)
        assertEquals(456, parsed.maxRttMs)
        assertEquals(1763100000L, parsed.updatedAt)
        assertEquals("jp1.example.net", parsed.sourceDomain)
    }

    @Test
    fun `长度永不超过 100`() {
        val longDomain = "a".repeat(300) + ".example.com"
        val raw = SlotComment.encode(9999, 9999, 9999, 9999999999L, longDomain)
        assertTrue("实际长度 ${raw.length}", raw.length <= SlotComment.MAX_LEN)
        assertNotNull(SlotComment.parse(raw))
    }

    @Test
    fun `超长域名按 ~ 截断且仍可解析`() {
        val longDomain = "x".repeat(200)
        val raw = SlotComment.encode(1, 10, 20, 1000L, longDomain)
        assertTrue(raw.contains("~"))
        val parsed = SlotComment.parse(raw)!!
        assertEquals(1, parsed.slot)
    }

    @Test
    fun `别人的备注一律不认`() {
        assertNull(SlotComment.parse(null))
        assertNull(SlotComment.parse(""))
        assertNull(SlotComment.parse("我的服务器"))
        assertNull(SlotComment.parse("dslk1"))
        assertNull(SlotComment.parse("DSLK1|s=001"))
        assertNull(SlotComment.parse("dslk1|s=abc"))
        assertNull(SlotComment.parse("dslk1|s=0"))
    }

    @Test
    fun `槽位缺失或越界返回 null`() {
        assertNull(SlotComment.parse("dslk1|r=1/2|u=1|d=a.com"))
        assertNull(SlotComment.parse("dslk1|s=10000|u=1"))
    }

    @Test
    fun `未知字段被忽略且向后兼容`() {
        val parsed = SlotComment.parse("dslk1|s=012|w=future|r=-|u=99|d=a.com|p=x")
        assertNotNull(parsed)
        assertEquals(12, parsed!!.slot)
        assertNull(parsed.avgRttMs)
        assertEquals("a.com", parsed.sourceDomain)
    }

    @Test
    fun `域名里的分隔符被清洗`() {
        val raw = SlotComment.encode(1, 1, 1, 1L, "a|b c.com")
        assertEquals(5, raw.split("|").size)
        assertTrue(!raw.substringAfter("d=").contains("|"))
    }

    @Test
    fun `sanitize 处理大小写与非法字符`() {
        assertEquals("abc.example.com", SlotComment.sanitize("  ABC.Example.COM  "))
        assertEquals("a_b", SlotComment.sanitize("a|b"))
        assertEquals("", SlotComment.sanitize("   "))
    }

    @Test
    fun `畸形备注不抛异常`() {
        assertNull(SlotComment.parse("dslk1|s=|r=/|u=x|d="))
        assertNull(SlotComment.parse("|".repeat(120)))
    }
}

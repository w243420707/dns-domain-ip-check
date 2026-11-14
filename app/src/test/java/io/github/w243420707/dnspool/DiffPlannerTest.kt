package io.github.w243420707.dnspool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffPlannerTest {

    private fun desired(slot: Int, name: String, ip: String, comment: String, ttl: Int = 60) =
        DiffPlanner.DesiredSlot(slot, name, ip, ttl, comment)

    private fun live(id: String, name: String, ip: String, comment: String?, ttl: Int = 60) =
        CfRecord(id = id, name = name, content = ip, ttl = ttl, comment = comment)

    @Test
    fun `完全一致时零写入`() {
        val now = 1763100000L
        val c = SlotComment.encode(1, 100, 120, now, "a.com")
        val plan = DiffPlanner.plan(
            listOf(desired(1, "n001.a.com", "1.2.3.4", c)),
            listOf(live("r1", "n001.a.com", "1.2.3.4", c)),
            now, 3600
        )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun `IP 变化只带 content 和 comment`() {
        val now = 1763100000L
        val old = SlotComment.encode(1, 100, 120, now, "a.com")
        val new = SlotComment.encode(1, 90, 100, now, "a.com")
        val plan = DiffPlanner.plan(
            listOf(desired(1, "n001.a.com", "5.6.7.8", new)),
            listOf(live("r1", "n001.a.com", "1.2.3.4", old)),
            now, 3600
        )
        assertEquals(1, plan.patches.size)
        assertEquals("5.6.7.8", plan.patches[0].content)
        assertNotNull(plan.patches[0].comment)
        assertNull(plan.patches[0].ttl)
    }

    @Test
    fun `只有备注过期时只改备注`() {
        val now = 1763100000L
        val stale = SlotComment.encode(1, 100, 120, now - 9999, "a.com")
        val plan = DiffPlanner.plan(
            listOf(desired(1, "n001.a.com", "1.2.3.4", SlotComment.encode(1, 100, 120, now, "a.com"))),
            listOf(live("r1", "n001.a.com", "1.2.3.4", stale)),
            now, 3600
        )
        assertEquals(1, plan.patches.size)
        assertNull(plan.patches[0].content)
        assertNotNull(plan.patches[0].comment)
    }

    @Test
    fun `缺少的记录才创建`() {
        val now = 1763100000L
        val plan = DiffPlanner.plan(
            listOf(
                desired(1, "n001.a.com", "1.1.1.1", SlotComment.encode(1, 10, 10, now, "a.com")),
                desired(2, "n002.a.com", "2.2.2.2", SlotComment.encode(2, 10, 10, now, "a.com"))
            ),
            listOf(live("r1", "n001.a.com", "1.1.1.1", SlotComment.encode(1, 10, 10, now, "a.com"))),
            now, 3600
        )
        assertEquals(1, plan.creates.size)
        assertEquals(2, plan.creates[0].slot)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun `记录名不符只拦不写`() {
        val now = 1763100000L
        val plan = DiffPlanner.plan(
            listOf(desired(1, "n001.a.com", "1.1.1.1", SlotComment.encode(1, 10, 10, now, "a.com"))),
            listOf(live("r1", "old-prefix.a.com", "1.1.1.1", SlotComment.encode(1, 10, 10, now, "a.com"))),
            now, 3600
        )
        assertEquals(0, plan.writes)
        assertEquals(1, plan.blocked.size)
    }

    @Test
    fun `别人的记录永不被删`() {
        val now = 1763100000L
        val plan = DiffPlanner.plan(
            listOf(desired(1, "n001.a.com", "1.1.1.1", SlotComment.encode(1, 10, 10, now, "a.com"))),
            listOf(
                live("r0", "www.a.com", "9.9.9.9", "我的官网"),
                live("r1", "n001.a.com", "1.1.1.1", SlotComment.encode(1, 10, 10, now, "a.com"))
            ),
            now, 3600, allowShrink = true
        )
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `同槽位重复只保留最新并删旧的`() {
        val now = 1763100000L
        val older = SlotComment.encode(1, 10, 10, now - 500, "a.com")
        val newer = SlotComment.encode(1, 10, 10, now, "a.com")
        val plan = DiffPlanner.plan(
            listOf(desired(1, "n001.a.com", "1.1.1.1", newer)),
            listOf(
                live("r-old", "n001.a.com", "1.1.1.1", older),
                live("r-new", "n001.a.com", "1.1.1.1", newer)
            ),
            now, 3600
        )
        assertEquals(listOf("r-old"), plan.deletes)
        assertEquals(0, plan.patches.size)
    }

    @Test
    fun `缩容时只删超出 N 的托管记录`() {
        val now = 1763100000L
        val plan = DiffPlanner.plan(
            listOf(desired(1, "n001.a.com", "1.1.1.1", SlotComment.encode(1, 10, 10, now, "a.com"))),
            listOf(
                live("r1", "n001.a.com", "1.1.1.1", SlotComment.encode(1, 10, 10, now, "a.com")),
                live("r2", "n002.a.com", "2.2.2.2", SlotComment.encode(2, 10, 10, now, "a.com"))
            ),
            now, 3600, allowShrink = true
        )
        assertEquals(listOf("r2"), plan.deletes)
    }

    @Test
    fun `同输入顺序稳定`() {
        val now = 1763100000L
        val desiredList = (1..5).map { desired(it, "n00$it.a.com", "1.1.1.$it", SlotComment.encode(it, 1, 1, now, "a.com")) }
        val a = DiffPlanner.plan(desiredList, emptyList(), now, 3600)
        val b = DiffPlanner.plan(desiredList, emptyList(), now, 3600)
        assertEquals(a.creates.map { it.slot }, b.creates.map { it.slot })
    }
}

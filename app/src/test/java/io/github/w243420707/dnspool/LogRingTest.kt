package io.github.w243420707.dnspool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 日志的两个上限：内存 300 条、磁盘约 1MB（cycle.log + cycle.log.1），别让长期挂机把存储吃满 */
class LogRingTest {

    private fun tempDir(): File = Files.createTempDirectory("dnspool-log").toFile()

    @Test
    fun `内存只保留最近的 300 条`() {
        val dir = tempDir()
        val ring = LogRing(dir)
        repeat(1200) { ring.add("I", "第 $it 行") }

        val tail = ring.tail(5000)
        assertEquals(300, tail.size)
        assertEquals("第 1199 行", tail.last().text)
        assertEquals("第 900 行", tail.first().text)
    }

    @Test
    fun `单条日志长度被截断到 500 字`() {
        val dir = tempDir()
        val ring = LogRing(dir)
        ring.add("E", "x".repeat(5000))
        assertEquals(500, ring.tail(1).single().text.length)
    }

    @Test
    fun `文件超过 512KB 会轮转成 cycle log 1`() {
        val dir = tempDir()
        val log = File(dir, "cycle.log")
        // 每行都要能解析回时间戳，否则就测不到真实体积
        val line = "1700000000000 I " + "y".repeat(90) + "\n"
        log.writeText(line.repeat(6000))          // 约 588KB
        assertTrue(log.length() > 512 * 1024)

        val ring = LogRing(dir)
        ring.add("I", "触发一次写入")

        val rotated = File(dir, "cycle.log.1")
        assertTrue("应该生成 cycle.log.1", rotated.exists())
        assertTrue("旧文件应该保留原体积", rotated.length() > 500 * 1024)
        assertTrue("新的 cycle.log 应该重新开始", log.length() < 4096)
    }

    @Test
    fun `第二轮轮转不会累积多个备份`() {
        val dir = tempDir()
        val log = File(dir, "cycle.log")
        val line = "1700000000000 I " + "z".repeat(90) + "\n"

        // 第一次撑爆
        log.writeText(line.repeat(6000))
        var ring = LogRing(dir)
        ring.add("I", "第一次")

        // 第二次撑爆，cycle.log.1 应该被替换而不是变成 .2
        log.appendText(line.repeat(6000))
        ring = LogRing(dir)
        ring.add("I", "第二次")

        val files = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("cycle.log", "cycle.log.1"), files)
        assertTrue("总占用应该稳定在 1MB 上下", dir.listFiles()!!.sumOf { it.length() } < 1_100_000)
    }

    @Test
    fun `进程重启后能从磁盘恢复尾部日志`() {
        val dir = tempDir()
        val first = LogRing(dir)
        repeat(20) { first.add("I", "重启前第 $it 行") }

        // 模拟进程被杀后重新构造
        val second = LogRing(dir)
        val restored = second.tail(10)
        assertEquals(10, restored.size)
        assertEquals("重启前第 19 行", restored.last().text)
    }
}

package io.github.w243420707.dnspool

/**
 * Cloudflare 记录备注（comment）的编解码。
 *
 * 备注是判定"这条记录归本程序管"的唯一依据：[parse] 返回 null 的记录永不允许被删除或改写，
 * 因此这里对所有畸形输入都只返回 null，绝不抛异常。
 */
object SlotComment {
    const val PREFIX = "dslk1"
    const val MAX_LEN = 100

    /** 固定字段（前缀 + s/r/u）的基准长度，用于给 d= 分配剩余空间 */
    private val BASE_LEN = "dslk1|s=007|r=123/456|u=1763100000".length

    data class Parsed(
        val slot: Int,
        val avgRttMs: Int?,
        val maxRttMs: Int?,
        val updatedAt: Long,
        val sourceDomain: String,
        val raw: String
    )

    fun encode(
        slot: Int,
        avgRttMs: Int?,
        maxRttMs: Int?,
        nowSec: Long,
        sourceDomain: String,
        pad: Int = 3
    ): String {
        // 槽位夹到 parse 可接受的范围，保证编码结果一定能被重新识别
        val width = pad.coerceIn(1, 4)
        val slotText = slot.coerceIn(1, 9999).toString().padStart(width, '0')
        val rttText = if (avgRttMs == null && maxRttMs == null) {
            "-"
        } else {
            "${avgRttMs ?: "-"}/${maxRttMs ?: "-"}"
        }
        val time = if (nowSec < 0L) 0L else nowSec
        val prefix = "$PREFIX|s=$slotText|r=$rttText|u=$time"

        // 名额按格式基准算；实际前缀可能更长（大 RTT / 大时间戳），取较小值以保证硬上限 100
        val room = minOf(MAX_LEN - BASE_LEN - 3, MAX_LEN - prefix.length - 3)
        if (room < 4) return prefix

        val domain = sanitize(sourceDomain)
        val clipped = if (domain.length <= room) domain else domain.take(room - 1) + "~"
        val result = "$prefix|d=$clipped"
        check(result.length <= MAX_LEN) { "comment 超长: ${result.length}" }
        return result
    }

    /** null 表示"不是本程序写的"，调用方据此永不删除该记录；任何异常输入都返回 null */
    fun parse(comment: String?): Parsed? {
        return try {
            if (comment.isNullOrBlank() || comment.length > MAX_LEN) return null
            if (!comment.startsWith("$PREFIX|")) return null

            var slot = 0
            var avg: Int? = null
            var max: Int? = null
            var updatedAt = 0L
            var domain = ""

            // 用切分而非正则，未知键直接忽略（向前兼容）
            for (part in comment.split('|').drop(1)) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq)
                val value = part.substring(eq + 1)
                when (key) {
                    "s" -> slot = value.toIntOrNull() ?: return null
                    "r" -> {
                        val slash = value.indexOf('/')
                        if (slash >= 0) {
                            val a = value.substring(0, slash).toIntOrNull()
                            val b = value.substring(slash + 1).toIntOrNull()
                            if (a != null && b != null) {
                                avg = a
                                max = b
                            }
                        }
                    }
                    "u" -> updatedAt = value.toLongOrNull() ?: 0L
                    "d" -> domain = value
                }
            }
            if (slot !in 1..9999) return null
            Parsed(slot, avg, max, updatedAt, domain, comment)
        } catch (e: Exception) {
            null
        }
    }

    fun sanitize(raw: String): String {
        val sb = StringBuilder(raw.length)
        var lastUnderscore = false
        for (ch in raw.lowercase()) {
            val c = when {
                ch in 'a'..'z' || ch in '0'..'9' -> ch
                ch == '.' || ch == '-' || ch == '_' || ch == '~' -> ch
                else -> '_'
            }
            if (c == '_') {
                if (lastUnderscore) continue
                lastUnderscore = true
            } else {
                lastUnderscore = false
            }
            sb.append(c)
        }
        var start = 0
        var end = sb.length
        while (start < end && (sb[start] == '.' || sb[start] == '_')) start++
        while (end > start && (sb[end - 1] == '.' || sb[end - 1] == '_')) end--
        return sb.substring(start, end)
    }
}

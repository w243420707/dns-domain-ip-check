package io.github.w243420707.dnspool

/**
 * 根据"期望槽位"与"线上记录"计算最小写操作集合。
 *
 * 纯函数：无 IO、无 Android 依赖，可被 JVM 单测直接调用。判据只有三条——
 * content 变化、ttl 变化、备注过期；其余情况一律零写入。
 */
object DiffPlanner {

    data class DesiredSlot(
        val slot: Int,
        val name: String,
        val content: String,
        val ttl: Int,
        val comment: String
    )

    data class PlanPatch(
        val recordId: String,
        val slot: Int,
        val content: String? = null,
        val comment: String? = null,
        val ttl: Int? = null
    )

    data class CfPlan(
        val creates: List<DesiredSlot> = emptyList(),
        val patches: List<PlanPatch> = emptyList(),
        val deletes: List<String> = emptyList(),
        val unchanged: Int = 0,
        /** 因记录名与期望不符而拒绝自动处理（改名等于删+建，必须用户显式重建） */
        val blocked: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = creates.isEmpty() && patches.isEmpty() && deletes.isEmpty()
        val writes: Int get() = creates.size + patches.size + deletes.size

        fun summary(): String =
            "创建 ${creates.size} / 修改 ${patches.size} / 删除 ${deletes.size} / 不变 $unchanged"
    }

    fun plan(
        desired: List<DesiredSlot>,
        live: List<CfRecord>,
        nowSec: Long,
        commentRefreshSec: Long,
        allowShrink: Boolean = false
    ): CfPlan {
        val creates = mutableListOf<DesiredSlot>()
        val patches = mutableListOf<PlanPatch>()
        val deletes = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        var unchanged = 0

        // 1. 只有能解析出槽位的记录才归本程序管；解析失败的永不进入 deletes
        val managed = live.mapNotNull { rec ->
            val parsed = SlotComment.parse(rec.comment) ?: return@mapNotNull null
            rec to parsed
        }

        // 2. 同槽位多条：保留 updatedAt 最大的（同值时取 id 较小者，保证顺序稳定），其余删除
        val keepers = LinkedHashMap<Int, Pair<CfRecord, SlotComment.Parsed>>()
        for ((rec, parsed) in managed) {
            val current = keepers[parsed.slot]
            if (current == null) {
                keepers[parsed.slot] = rec to parsed
                continue
            }
            val oldUpdated = current.second.updatedAt
            val preferNew = parsed.updatedAt > oldUpdated ||
                (parsed.updatedAt == oldUpdated && rec.id < current.first.id)
            if (preferNew) {
                deletes += current.first.id
                keepers[parsed.slot] = rec to parsed
            } else {
                deletes += rec.id
            }
        }

        // 3. 逐槽位比对；名字不符只上报 blocked，绝不改名/删除
        for (d in desired) {
            val keepPair = keepers[d.slot]
            if (keepPair == null) {
                creates += d
                continue
            }
            val (keep, parsed) = keepPair
            if (keep.name != d.name) {
                blocked += keep.name
                continue
            }
            val contentChanged = keep.content != d.content
            val ttlChanged = keep.ttl != d.ttl
            val commentStale = nowSec - parsed.updatedAt > commentRefreshSec
            if (contentChanged || ttlChanged || commentStale) {
                patches += PlanPatch(
                    recordId = keep.id,
                    slot = d.slot,
                    content = if (contentChanged) d.content else null,
                    comment = if (contentChanged || commentStale) d.comment else null,
                    ttl = if (ttlChanged) d.ttl else null
                )
            } else {
                unchanged++
            }
        }

        // 4. 缩容：仅显式允许时删除编号超过期望数量的托管记录
        if (allowShrink) {
            for ((slot, pair) in keepers) {
                if (slot > desired.size) deletes += pair.first.id
            }
        }

        return CfPlan(
            creates = creates.sortedBy { it.slot },
            patches = patches.sortedWith(compareBy({ it.slot }, { it.recordId })),
            deletes = deletes.distinct().sorted(),
            unchanged = unchanged,
            blocked = blocked.distinct().sorted()
        )
    }
}

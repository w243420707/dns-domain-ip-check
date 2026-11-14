package io.github.w243420707.dnspool.ui

import android.app.Dialog
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import io.github.w243420707.dnspool.Dimens
import io.github.w243420707.dnspool.MEDIUM
import io.github.w243420707.dnspool.MainActivity
import io.github.w243420707.dnspool.R
import io.github.w243420707.dnspool.Slot
import io.github.w243420707.dnspool.color
import io.github.w243420707.dnspool.dp
import io.github.w243420707.dnspool.field
import io.github.w243420707.dnspool.fmtAgo
import io.github.w243420707.dnspool.fmtTime
import io.github.w243420707.dnspool.kvRow
import io.github.w243420707.dnspool.monoPara
import io.github.w243420707.dnspool.multiline
import io.github.w243420707.dnspool.para

/** 设置页用到的四个弹层：单行编辑、单选、槽位详情、域名批量编辑、日志。 */
object Dialogs {

    fun editText(
        activity: MainActivity,
        title: String,
        initial: String,
        hint: String = "",
        numeric: Boolean = false,
        /** 返回非 null 表示错误提示，弹窗不关闭 */
        validate: (String) -> String? = { null },
        onOk: (String) -> Unit
    ) {
        if (activity.isFinishing) return
        val ctx = activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(18), ctx.dp(4), ctx.dp(18), 0)
        }
        val edit = ctx.field(
            "", initial, if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        )
        box.addView(edit, LinearLayout.LayoutParams(-1, -2))
        if (hint.isNotBlank()) {
            box.addView(ctx.para(hint, Dimens.LABEL, R.color.sub).apply {
                setPadding(ctx.dp(2), ctx.dp(6), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        // 默认 positive 点击必然 dismiss，非法值会被吞，必须换成自校验
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = edit.text.toString().trim()
                val err = validate(raw)
                if (err != null) {
                    edit.error = err
                } else {
                    dialog.dismiss()
                    onOk(raw)
                }
            }
        }
        dialog.show()
    }

    fun pick(
        activity: MainActivity,
        title: String,
        options: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, i -> onPick(i) }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showSlot(activity: MainActivity, slot: Slot, slotCount: Int, pad: Int) {
        if (activity.isFinishing) return
        val ctx = activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(6), ctx.dp(20), ctx.dp(2))
        }
        val index = slot.index.toString().padStart(pad.coerceIn(1, 4), '0')
        val comment = slot.comment?.takeIf { it.isNotBlank() } ?: "—"
        val pairs = listOf(
            "槽位" to "$index / $slotCount",
            "记录名" to slot.name.ifBlank { "—" },
            "IP" to (slot.ip ?: "—"),
            "状态" to slot.state.label,
            "失败次数" to "${slot.consecutiveFailures} 次",
            "延迟" to "${slot.lastAvgRttMs} / ${slot.lastMaxRttMs} ms",
            "最后探测" to "${fmtTime(slot.lastProbeAt)} · ${fmtAgo(slot.lastProbeAt)}",
            "最后成功" to fmtAgo(slot.lastOkAt),
            "最后写入" to fmtTime(slot.lastWrittenAt),
            "记录 ID" to (slot.recordId ?: "—")
        )
        val copy = StringBuilder()
        pairs.forEach { (label, value) ->
            box.addView(ctx.kvRow(label, value))
            copy.append(label).append(": ").append(value).append('\n')
        }
        box.addView(ctx.para("备注原文", Dimens.LABEL, R.color.sub).apply {
            setPadding(0, ctx.dp(8), 0, ctx.dp(2))
        })
        box.addView(ctx.monoPara(comment, 10f, R.color.sub).apply { setTextIsSelectable(true) })
        copy.append("备注: ").append(comment)

        val scroll = ScrollView(ctx).apply { addView(box, ViewGroup.LayoutParams(-1, -2)) }
        AlertDialog.Builder(ctx)
            .setTitle("槽位 ${slot.index}")
            .setView(scroll)
            .setPositiveButton("复制信息") { _, _ ->
                activity.copyToClipboard("槽位 ${slot.index}", copy.toString())
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    fun editDomains(activity: MainActivity, initial: String, onOk: (String) -> Unit) {
        if (activity.isFinishing) return
        val ctx = activity
        val dialog = Dialog(ctx, R.style.Theme_DnsPool_FullScreen)
        val edit = ctx.multiline("一行一个域名", initial, 20)
        val save = {
            val now = edit.text.toString()
            if (now != initial) onOk(now)
            dialog.dismiss()
        }
        val root = fullScreen(ctx, "域名列表", listOf("取消" to { dialog.dismiss() }, "保存" to save))
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(12))
            addView(edit, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.show()
    }

    fun showLogs(activity: MainActivity, text: String) {
        if (activity.isFinishing) return
        val ctx = activity
        val dialog = Dialog(ctx, R.style.Theme_DnsPool_FullScreen)
        val count = text.lines().count { it.isNotBlank() }
        val root = fullScreen(
            ctx, "日志 · $count 条",
            listOf("复制" to { activity.copyToClipboard("日志", text) }, "×" to { dialog.dismiss() })
        )
        val body = ctx.monoPara(text.ifBlank { "（暂无日志）" }, 11f, R.color.sub).apply {
            setTextIsSelectable(true)
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(12))
        }
        val scroll = ScrollView(ctx).apply { addView(body, LinearLayout.LayoutParams(-1, -2)) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.show()
    }

    /* ------------------------------ 内部 ------------------------------ */

    /** 全屏弹层的外壳：bg 底 + 48dp 标题栏 */
    private fun fullScreen(
        ctx: MainActivity,
        title: String,
        actions: List<Pair<String, () -> Unit>>
    ): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.color(R.color.bg))
        }
        root.addView(header(ctx, title, actions), LinearLayout.LayoutParams(-1, ctx.dp(48)))
        return root
    }

    private fun header(
        ctx: MainActivity,
        title: String,
        actions: List<Pair<String, () -> Unit>>
    ): LinearLayout {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), 0, ctx.dp(4), 0)
        }
        bar.addView(ctx.para(title, Dimens.BODY, R.color.text).apply {
            typeface = MEDIUM
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        })
        actions.forEach { (label, act) ->
            bar.addView(ctx.para(label, Dimens.SMALL, if (label == "×") R.color.sub else R.color.accent).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
                setOnClickListener { act() }
            })
        }
        return bar
    }
}

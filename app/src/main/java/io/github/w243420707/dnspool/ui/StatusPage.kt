package io.github.w243420707.dnspool.ui

import android.graphics.Typeface
import android.os.PowerManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import io.github.w243420707.dnspool.Dimens
import io.github.w243420707.dnspool.Host
import io.github.w243420707.dnspool.MainActivity
import io.github.w243420707.dnspool.Page
import io.github.w243420707.dnspool.R
import io.github.w243420707.dnspool.Slot
import io.github.w243420707.dnspool.SlotState
import io.github.w243420707.dnspool.UiSnapshot
import io.github.w243420707.dnspool.actionButton
import io.github.w243420707.dnspool.color
import io.github.w243420707.dnspool.dp
import io.github.w243420707.dnspool.fmtAgo
import io.github.w243420707.dnspool.fmtClock
import io.github.w243420707.dnspool.monoPara
import io.github.w243420707.dnspool.para
import io.github.w243420707.dnspool.rounded
import io.github.w243420707.dnspool.setTextColorIfChanged
import io.github.w243420707.dnspool.setTextIfChanged
import io.github.w243420707.dnspool.soft
import io.github.w243420707.dnspool.vSpace
import java.util.Locale

/**
 * 状态页：一屏说清「活着吗 / 有没有坏 / 池还够吗 / 上轮干了啥」。
 * 所有文本走脏值比较，稳态刷新不触发重绘。
 */
class StatusPage(private val activity: MainActivity) : Page {

    private val host: Host = activity
    private val ctx = activity

    private lateinit var tipsBar: LinearLayout
    private lateinit var tipText: TextView
    private lateinit var tipAction: TextView

    private val bandValue = arrayOfNulls<TextView>(4)
    private lateinit var btnRebuild: android.widget.Button
    private lateinit var errorLine: TextView

    private lateinit var anomalyTitle: TextView
    private lateinit var viewAll: TextView
    private val rows = mutableListOf<LinearLayout>()
    private val rowIndex = mutableListOf<TextView>()
    private val rowIp = mutableListOf<TextView>()
    private val rowFail = mutableListOf<TextView>()
    private val rowBtn = mutableListOf<TextView>()
    private lateinit var allOkLine: TextView

    private lateinit var summaryLine: TextView
    private val logLines = mutableListOf<TextView>()

    private var currentAnomalies: List<Slot> = emptyList()
    private val dismissed = mutableSetOf<String>()
    private var lastRenderKey: String? = null

    override val view: View = build()

    /* ------------------------------ 构建 ------------------------------ */

    private fun build(): View {
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(Dimens.PAGE_PAD), ctx.dp(12), ctx.dp(Dimens.PAGE_PAD), ctx.dp(24))
        }
        scroll.addView(box)

        tipsBar = buildTips()
        box.addView(tipsBar, h(-2).apply { bottomMargin = ctx.dp(10) })
        box.addView(buildBand(), h(ctx.dp(Dimens.BAND_H)))
        box.addView(ctx.vSpace(12))
        box.addView(buildActions(), h(ctx.dp(Dimens.ACT_H)))
        box.addView(buildErrorLine(), h(-2))
        box.addView(ctx.vSpace(14))
        box.addView(buildAnomalyHeader(), h(ctx.dp(28)))
        repeat(5) { i -> rows += buildAnomalyRow(i).also { box.addView(it, h(ctx.dp(32))) } }
        allOkLine = ctx.para("", Dimens.SMALL, R.color.sub).apply {
            setPadding(0, ctx.dp(6), 0, ctx.dp(6))
        }
        box.addView(allOkLine, h(-2))
        box.addView(ctx.vSpace(12))

        summaryLine = ctx.monoPara("", Dimens.LABEL, R.color.sub).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        box.addView(summaryLine, h(ctx.dp(24)))

        box.addView(buildLogHeader(), h(ctx.dp(22)).apply { topMargin = ctx.dp(6) })
        repeat(4) {
            val line = ctx.monoPara("", Dimens.LABEL, R.color.sub).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            logLines += line
            box.addView(line, h(ctx.dp(18)))
        }
        return scroll
    }

    private fun h(height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun buildTips(): LinearLayout {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ctx.rounded(ctx.soft(R.color.info, 26), 8, ctx.soft(R.color.info, 80))
            setPadding(ctx.dp(10), 0, ctx.dp(6), 0)
            visibility = View.GONE
        }
        tipText = ctx.para("", Dimens.LABEL, R.color.text).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tipAction = ctx.para("", Dimens.LABEL, R.color.info).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(-2, -1)
            isClickable = true
        }
        val close = ctx.para("×", Dimens.BODY, R.color.sub).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(6), 0, ctx.dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(-2, -1)
            isClickable = true
            setOnClickListener {
                dismissed += "notify"
                dismissed += "battery"
                tipsBar.visibility = View.GONE
            }
        }
        bar.addView(tipText)
        bar.addView(tipAction)
        bar.addView(close)
        return bar
    }

    private fun buildBand(): LinearLayout {
        val band = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("正常", "故障", "候选", "额度").forEachIndexed { i, label ->
            if (i > 0) {
                band.addView(View(ctx).apply {
                    setBackgroundColor(ctx.color(R.color.stroke))
                    layoutParams = LinearLayout.LayoutParams(Math.max(1, ctx.dp(1) / 2), ctx.dp(24)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
                })
            }
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val value = ctx.monoPara("—", Dimens.NUM, R.color.text).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(2), 0, ctx.dp(2), 0)
                maxLines = 1
            }
            // 额度格可能是 2000/1000 这种长串，按格宽自动缩字号，避免顶到分隔线
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                value, 11, 18, 1, TypedValue.COMPLEX_UNIT_SP
            )
            val caption = ctx.para(label, Dimens.LABEL, R.color.sub).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(1), 0, 0)
            }
            cell.addView(value)
            cell.addView(caption)
            band.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
            bandValue[i] = value
        }
        return band
    }

    private fun buildActions(): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val run = ctx.actionButton("跑一轮") { host.runNow() }
        btnRebuild = ctx.actionButton("重建", primary = true) { host.rebuild() }
        btnRebuild.setOnLongClickListener {
            host.preview()
            true
        }
        val selfTest = ctx.actionButton("自检") { host.selfTest() }
        val logs = ctx.actionButton("日志") { host.openLogs() }
        listOf(run, btnRebuild, selfTest, logs).forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(0, ctx.dp(Dimens.ACT_H), 1f).apply {
                if (i > 0) marginStart = ctx.dp(6)
            })
        }
        return row
    }

    private fun buildErrorLine(): View {
        errorLine = ctx.para("", Dimens.LABEL, R.color.danger).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, ctx.dp(6), 0, 0)
            visibility = View.GONE
            setOnClickListener { host.openLogs() }
        }
        return errorLine
    }

    private fun buildAnomalyHeader(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        anomalyTitle = ctx.para("异常 0 条", Dimens.SMALL, R.color.text).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }
        viewAll = ctx.para("查看全部 ›", Dimens.LABEL, R.color.info).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { host.openSlots(1) }
        }
        row.addView(anomalyTitle)
        row.addView(viewAll)
        return row
    }

    private fun buildAnomalyRow(i: Int): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val idx = ctx.monoPara("", Dimens.SMALL, R.color.text).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(32), -2)
        }
        val ip = ctx.monoPara("", Dimens.SMALL, R.color.text).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val fail = ctx.para("", Dimens.LABEL, R.color.danger).apply {
            setPadding(ctx.dp(6), 0, ctx.dp(6), 0)
        }
        val btn = ctx.para("重测", Dimens.LABEL, R.color.info).apply {
            background = ctx.rounded(ctx.soft(R.color.info, 26), 6)
            setPadding(ctx.dp(9), ctx.dp(4), ctx.dp(9), ctx.dp(4))
            isClickable = true
        }
        row.addView(idx)
        row.addView(ip)
        row.addView(fail)
        row.addView(btn)
        rowIndex += idx
        rowIp += ip
        rowFail += fail
        rowBtn += btn
        return row
    }

    private fun buildLogHeader(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ctx.para("日志", Dimens.LABEL, R.color.sub).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            gravity = Gravity.CENTER_VERTICAL
        })
        row.addView(ctx.para("全部", Dimens.LABEL, R.color.info).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { host.openLogs() }
        })
        return row
    }

    /* ------------------------------ 渲染 ------------------------------ */

    override fun render(snap: UiSnapshot) {
        renderTips()

        val s = snap.settings
        val rs = snap.run
        val bad = snap.badSlots

        val key = buildString {
            append(snap.okCount).append('/').append(snap.total)
            append('|').append(rs.poolAvailable)
            append('|').append(bad.size)
            append('|').append(bad.take(5).joinToString(",") { "${it.index}:${it.ip}:${it.consecutiveFailures}:${it.state}" })
            append('|').append(rs.lastCycleAt).append('|').append(s.slotCount).append('/').append(s.recordLimit)
            append('|').append(snap.lastWriteAt)
            append('|').append(rs.lastError)
            append('|').append(snap.logs.joinToString(";") { it.at.toString() + it.text })
            append('|').append(snap.stats?.durationMs).append(snap.stats?.ipsProbed).append(snap.stats?.repairs)
        }
        if (key == lastRenderKey) return
        lastRenderKey = key

        bandValue[0]?.setTextIfChanged(snap.okCount.toString())
        bandValue[0]?.setTextColorIfChanged(ctx.color(R.color.accent))
        bandValue[1]?.setTextIfChanged((snap.total - snap.okCount).coerceAtLeast(0).toString())
        bandValue[1]?.setTextColorIfChanged(ctx.color(if (bad.isNotEmpty()) R.color.danger else R.color.text))
        bandValue[2]?.setTextIfChanged(rs.poolAvailable.toString())
        bandValue[3]?.setTextIfChanged("${s.slotCount}/${s.recordLimit}")
        val nearLimit = s.slotCount >= (s.recordLimit * 0.95).toInt()
        bandValue[3]?.setTextColorIfChanged(ctx.color(if (nearLimit) R.color.warn else R.color.text))

        btnRebuild.setTextIfChanged("重建 ${s.slotCount}")

        if (rs.lastError.isBlank()) {
            errorLine.visibility = View.GONE
        } else {
            errorLine.visibility = View.VISIBLE
            errorLine.setTextIfChanged("! ${rs.lastError}")
        }

        anomalyTitle.setTextIfChanged("异常 ${bad.size} 条")
        viewAll.visibility = if (bad.size > 5) View.VISIBLE else View.GONE
        currentAnomalies = bad
        rows.forEachIndexed { i, row ->
            val slot = bad.getOrNull(i)
            if (slot == null) {
                row.visibility = View.GONE
            } else {
                row.visibility = View.VISIBLE
                rowIndex[i].setTextIfChanged(slot.index.toString().padStart(s.slotPad.coerceIn(1, 4), '0'))
                rowIndex[i].setTextColorIfChanged(
                    ctx.color(if (slot.state == SlotState.DOWN) R.color.danger else R.color.warn)
                )
                rowIp[i].setTextIfChanged(slot.ip ?: "—")
                rowFail[i].setTextIfChanged("失败 ${slot.consecutiveFailures} 次")
            }
        }
        rowBtn.forEachIndexed { i, btn ->
            btn.setOnClickListener { currentAnomalies.getOrNull(i)?.let { host.retest(it) } }
        }

        when {
            snap.total == 0 -> {
                allOkLine.visibility = View.VISIBLE
                allOkLine.setTextIfChanged("还没有槽位，填好设置后点「重建」")
            }
            bad.isEmpty() -> {
                allOkLine.visibility = View.VISIBLE
                val ago = if (snap.lastWriteAt > 0) " · 最近写入 ${fmtAgo(snap.lastWriteAt, snap.now)}" else ""
                allOkLine.setTextIfChanged("全部正常$ago")
            }
            else -> allOkLine.visibility = View.GONE
        }

        val st = snap.stats
        val dur = if (rs.lastCycleMs > 0) rs.lastCycleMs else (st?.durationMs ?: 0L)
        val durText = if (dur > 0) String.format(Locale.CHINA, "%.1fs", dur / 1000.0) else "—"
        summaryLine.setTextIfChanged(
            if (rs.lastCycleAt <= 0 && st == null) {
                "还没有跑过一轮"
            } else {
                val clock = fmtClock(rs.lastCycleAt)
                "$clock · $durText · 探测 ${st?.ipsProbed ?: 0}/${st?.ipsPassed ?: 0} · 修复 ${st?.repairs ?: 0}"
            }
        )

        val logs = snap.logs.reversed()
        logLines.forEachIndexed { i, line ->
            val entry = logs.getOrNull(i)
            if (entry == null) {
                // 空态落在第一行，不留大片空白
                line.visibility = if (i == 0) View.VISIBLE else View.GONE
                if (i == 0) {
                    line.setTextIfChanged("还没有日志")
                    line.setTextColorIfChanged(ctx.color(R.color.sub))
                }
            } else {
                line.visibility = View.VISIBLE
                line.setTextIfChanged("${fmtClock(entry.at)} ${entry.text}")
                line.setTextColorIfChanged(
                    ctx.color(
                        when (entry.level) {
                            "E" -> R.color.danger
                            "W" -> R.color.warn
                            else -> R.color.sub
                        }
                    )
                )
            }
        }
    }

    private fun renderTips() {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val batteryOk = pm == null || pm.isIgnoringBatteryOptimizations(ctx.packageName)
        val notifyOk = activity.hasNotificationPermission()

        if (!notifyOk && "notify" !in dismissed) {
            showTip("notify", "通知权限没开，锁屏后看不到守护状态。", "去开启") {
                activity.requestNotificationPermission()
            }
        } else if (!batteryOk && "battery" !in dismissed) {
            showTip("battery", "电池优化在限制后台，云手机可能把守护冻结。", "去设置") {
                host.askBattery()
            }
        } else {
            tipsBar.visibility = View.GONE
        }
    }

    private fun showTip(key: String, text: String, actionText: String, action: () -> Unit) {
        tipsBar.visibility = View.VISIBLE
        tipText.setTextIfChanged(text)
        tipAction.setTextIfChanged(actionText)
        tipAction.setOnClickListener {
            dismissed += key
            action()
            tipsBar.visibility = View.GONE
        }
    }
}

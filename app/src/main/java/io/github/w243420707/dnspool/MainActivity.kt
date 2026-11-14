package io.github.w243420707.dnspool

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var repo: StateRepository
    private var filter = 0
    private lateinit var headerBadge: TextView
    private lateinit var headerToggle: Button
    private lateinit var pages: List<ScrollView>
    private lateinit var tabItems: List<TextView>
    private lateinit var tabLines: List<View>

    // 概览
    private lateinit var tipsBox: LinearLayout
    private lateinit var bigStatus: TextView
    private lateinit var heartbeatView: TextView
    private lateinit var summaryView: TextView
    private val cycleRows = mutableMapOf<String, TextView>()
    private var selfTestValue: TextView? = null
    private lateinit var logsBox: LinearLayout
    private lateinit var selfTestBody: TextView
    private var rebuildBtn: Button? = null

    // 槽位
    private lateinit var slotsBox: LinearLayout
    private lateinit var slotsCount: TextView
    private lateinit var filterSeg: Segment

    // 设置表单
    private lateinit var etToken: EditText; private lateinit var etRoot: EditText; private lateinit var etShared: EditText
    private lateinit var etPrefix: EditText; private lateinit var etCount: EditText; private lateinit var etTtl: EditText
    private lateinit var etRecordLimit: EditText; private lateinit var etPorts: EditText; private lateinit var etConc: EditText
    private lateinit var etConnect: EditText; private lateinit var etAttempts: EditText; private lateinit var etFail: EditText
    private lateinit var etRepairs: EditText; private lateinit var etInterval: EditText; private lateinit var etBudget: EditText
    private lateinit var etUdp: EditText; private lateinit var etDoh: EditText; private lateinit var etDnsTimeout: EditText
    private lateinit var etDomains: EditText; private lateinit var sharedBox: LinearLayout; private lateinit var udpBox: LinearLayout
    private lateinit var dohBox: LinearLayout
    private lateinit var swProxied: Switch; private lateinit var swAutoRun: Switch
    private lateinit var modeSeg: Segment; private lateinit var dnsSeg: Segment

    private val dismissedTips = mutableSetOf<String>()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshOverview(); refreshSlots()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StateRepository.get(this)
        val s = repo.settings()
        setContentView(buildRoot())
        fillForm(s)
        selectPage(0)
        refreshOverview(); refreshSlots()
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(KeepAliveService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (s.autoRun && s.cfToken.isNotBlank()) {
            KeepAliveService.command(this, KeepAliveService.ACTION_START)
        }
        if (s.cfToken.isBlank()) guide()
    }

    override fun onResume() {
        super.onResume()
        buildTips(); refreshOverview(); refreshSlots()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
    }

    /* ============================ 骨架 ============================ */

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, -2))
        root.addView(buildTabs(), LinearLayout.LayoutParams(-1, -2))
        pages = listOf(page { buildOverview(it) }, page { buildSlots(it) }, page { buildSettings(it) })
        val frame = FrameLayout(this)
        pages.forEach { frame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        bar.addView(para("DNS 池守护", 18f, R.color.text).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        headerBadge = badge("已停止", color(R.color.sub))
        bar.addView(headerBadge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        headerToggle = actionButton("启动") { toggleRun() }
        bar.addView(headerToggle, LinearLayout.LayoutParams(-2, dp(36)))
        return bar
    }

    private fun buildTabs(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = mutableListOf<TextView>(); val lines = mutableListOf<View>()
        listOf("概览", "槽位", "设置").forEachIndexed { i, title ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { selectPage(i) }
            }
            val label = para(title, 14f, R.color.sub).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(8))
            }
            val line = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            cell.addView(label, LinearLayout.LayoutParams(-1, -2)); cell.addView(line, LinearLayout.LayoutParams(-1, dp(2)))
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            items += label; lines += line
        }
        tabItems = items; tabLines = lines
        return row
    }

    private fun selectPage(index: Int) {
        pages.forEachIndexed { i, view -> view.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabItems.forEachIndexed { i, label ->
            val on = i == index
            label.setTextColor(color(if (on) R.color.text else R.color.sub))
            label.typeface = if (on) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        }
        tabLines.forEachIndexed { i, line -> line.setBackgroundColor(if (i == index) color(R.color.accent) else Color.TRANSPARENT) }
    }

    private fun page(build: (LinearLayout) -> Unit): ScrollView {
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        build(box); scroll.addView(box)
        return scroll
    }

    /* ============================ 概览页 ============================ */

    private fun buildOverview(box: LinearLayout) {
        tipsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tipsBox)
        val status = card()
        bigStatus = para("已停止", 24f, R.color.sub).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        heartbeatView = para("", 12f, R.color.sub).apply { setPadding(0, dp(4), 0, dp(2)) }
        summaryView = para("", 13f, R.color.text)
        status.addView(bigStatus); status.addView(heartbeatView); status.addView(summaryView)
        box.addView(status, lpM(bottom = 12))

        val actions = card()
        actions.addView(sectionTitle("下一步"))
        actions.addView(buttonRow(
            actionButton("跑一轮") { fire(KeepAliveService.ACTION_RUN_NOW, "已开始跑一轮") },
            actionButton("预览差异") { fire(KeepAliveService.ACTION_PREVIEW, "正在对比，结果见日志") }
        ), lpM(bottom = 8))
        val rebuild = actionButton("重建 N 条", primary = true) { confirmRebuild() }; rebuildBtn = rebuild
        actions.addView(buttonRow(rebuild, actionButton("运行自检", primary = true) {
            fire(KeepAliveService.ACTION_SELFTEST, "已开始自检，结果见日志")
        }))
        box.addView(actions, lpM(bottom = 12))

        val cycle = section(box, "本轮数据")
        statRow(cycle, "dur", "上轮耗时"); statRow(cycle, "probe", "探测 / 通过")
        statRow(cycle, "rate", "可用率"); statRow(cycle, "result", "上轮结果")
        selfTestValue = para("还没跑过自检", 12f, R.color.sub).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        cycle.addView(selfTestValue, lpM(top = 2))

        val logs = section(box, "运行日志（最近 12 条）")
        logsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; logs.addView(logsBox)

        val st = card()
        val stHead = para("自检结果（点击展开）", 13f, R.color.info).apply { setPadding(0, dp(4), 0, dp(4)) }
        selfTestBody = para("还没有自检结果", 12f, R.color.sub).apply { visibility = View.GONE }
        stHead.setOnClickListener {
            val show = selfTestBody.visibility != View.VISIBLE
            selfTestBody.visibility = if (show) View.VISIBLE else View.GONE
            stHead.text = if (show) "自检结果（点击收起）" else "自检结果（点击展开）"
        }
        st.addView(stHead); st.addView(selfTestBody, lpM(top = 4))
        box.addView(st)
    }

    private fun refreshOverview() {
        if (!::bigStatus.isInitialized) return
        val rs = repo.runState()
        val s = repo.settings()
        val total = if (rs.slotsTotal > 0) rs.slotsTotal else s.slotCount
        val (title, tint) = when {
            s.cfToken.isBlank() || s.rootDomain.isBlank() -> "配置待完善" to R.color.warn
            rs.lastResult == "ERROR" -> "异常" to R.color.danger
            rs.running && rs.lastResult == "SKIPPED" -> "配置待完善" to R.color.warn
            rs.running -> "运行中" to R.color.accent
            else -> "已停止" to R.color.sub
        }
        bigStatus.text = title; bigStatus.setTextColor(color(tint))
        headerBadge.text = title; headerBadge.setTextColor(color(tint))
        headerBadge.background = rounded(soft(tint, 32), 9, soft(tint, 110))

        val hb = rs.heartbeatAt
        val age = if (hb > 0) (System.currentTimeMillis() - hb) / 1000 else -1
        heartbeatView.text = if (hb <= 0) "还没有心跳，服务没跑起来" else
            "最后心跳 ${fmtTime(hb)}（${age} 秒前${if (age > 120) "，已超 2 分钟没更新" else ""}）"
        heartbeatView.setTextColor(if (age > 120) color(R.color.danger) else color(R.color.sub))
        summaryView.text = "正常 ${rs.slotsOk}/$total · 候选池 ${rs.poolAvailable} · 累计修复 ${rs.repairsToday}" +
            if (rs.lastError.isNotBlank()) "\n最近错误：${rs.lastError}" else ""

        headerToggle.text = if (rs.running) "暂停" else "启动"
        headerToggle.background = rounded(if (rs.running) color(R.color.surface2) else color(R.color.accent), 10)
        headerToggle.setTextColor(if (rs.running) color(R.color.text) else color(R.color.bg))
        rebuildBtn?.text = "重建 ${s.slotCount} 条"

        val stats = repo.stats().lastOrNull()
        val dur = if (rs.lastCycleMs > 0) rs.lastCycleMs else (stats?.durationMs ?: 0L)
        val probed = stats?.ipsProbed ?: 0
        val passed = stats?.ipsPassed ?: 0
        cycleRows["dur"]?.text = if (dur > 0) String.format("%.1f 秒", dur / 1000.0) else "—"
        cycleRows["probe"]?.text = "$probed / $passed"
        cycleRows["rate"]?.text = if (probed > 0) "${passed * 100 / probed}%" else "—"
        cycleRows["result"]?.text = resultLabel(stats?.result ?: rs.lastResult)

        val summary = rs.selfTestSummary
        selfTestValue?.text = if (summary.isBlank()) "还没跑过自检" else summary
        selfTestBody.text = if (summary.isBlank()) "还没有自检结果，点上面的「运行自检」" else summary
        renderLogs()
    }

    private fun renderLogs() {
        if (!::logsBox.isInitialized) return
        logsBox.removeAllViews()
        val entries = repo.log.tail(12).asReversed()
        if (entries.isEmpty()) { logsBox.addView(para("还没有日志", 11f, R.color.sub)); return }
        entries.forEach { entry ->
            logsBox.addView(para("${fmtTime(entry.at)}  ${entry.text}", 11f, when (entry.level) {
                "E" -> R.color.danger
                "W" -> R.color.warn
                else -> R.color.sub
            }).apply {
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(2), 0, dp(2))
            })
        }
    }

    /* ============================ 槽位页 ============================ */

    private fun buildSlots(box: LinearLayout) {
        filterSeg = Segment(this, listOf("全部", "仅异常", "仅空位"), filter) { filter = it; refreshSlots() }
        box.addView(filterSeg.root, lpM(bottom = 10))
        slotsCount = para("共 0 条", 12f, R.color.sub); box.addView(slotsCount, lpM(bottom = 10))
        slotsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(slotsBox)
    }

    private fun refreshSlots() {
        if (!::slotsBox.isInitialized) return
        val all = repo.slots()
        val ok = all.count { it.state == SlotState.OK }
        val bad = all.count { it.state == SlotState.DOWN || it.state == SlotState.SUSPECT }
        val empty = all.count { it.state == SlotState.EMPTY }
        slotsCount.text = "共 ${all.size} 条 · 正常 $ok · 异常 $bad · 待建 $empty"
        val filtered = when (filter) {
            1 -> all.filter { it.state == SlotState.DOWN || it.state == SlotState.SUSPECT }
            2 -> all.filter { it.state == SlotState.EMPTY }
            else -> all
        }
        slotsBox.removeAllViews()
        if (filtered.isEmpty()) {
            val msg = if (all.isEmpty()) "还没有槽位，先在设置里填好域名与 Token，然后点「重建 N 条」"
            else "这个筛选下没有槽位"
            slotsBox.addView(para(msg, 12f, R.color.sub)); return
        }
        val limit = 60
        filtered.take(limit).forEach { slotsBox.addView(slotRow(it), lpM(bottom = 8)) }
        if (filtered.size > limit) {
            slotsBox.addView(para("仅显示前 $limit 条（共 ${filtered.size} 条）", 11f, R.color.sub), lpM(top = 6))
        }
    }

    private fun slotRow(slot: Slot): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(R.color.surface), 10, color(R.color.stroke))
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(para(slot.index.toString().padStart(3, '0'), 13f, R.color.text).apply {
            typeface = Typeface.MONOSPACE
        })
        left.addView(para(slot.name.ifBlank { "未命名" }, 12f, R.color.sub).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        mid.addView(para(slot.ip ?: "—", 13f, R.color.text).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.END
        })
        val rtt = if (slot.lastAvgRttMs > 0) {
            "平均 ${slot.lastAvgRttMs}ms · 最大 ${slot.lastMaxRttMs}ms"
        } else {
            "还没测过"
        }
        mid.addView(para(rtt, 11f, R.color.sub).apply { gravity = Gravity.END })
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(badge(slot.state.label, stateColor(slot.state)),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        return row
    }

    private fun stateColor(state: SlotState): Int = color(when (state) {
        SlotState.OK -> R.color.accent; SlotState.SUSPECT -> R.color.warn
        SlotState.DOWN -> R.color.danger; SlotState.PENDING_WRITE -> R.color.info
        else -> R.color.sub
    })

    /* ============================ 设置页 ============================ */

    private fun section(box: LinearLayout, title: String, bottom: Int = 12): LinearLayout {
        val c = card()
        c.addView(sectionTitle(title))
        box.addView(c, lpM(bottom))
        return c
    }

    private fun buildSettings(box: LinearLayout) {
        val cf = section(box, "Cloudflare")
        cf.addView(fieldLabel("API Token")); etToken = field("粘贴 Cloudflare API Token", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val tokenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        tokenRow.addView(etToken, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        tokenRow.addView(actionButton("校验") { fire(KeepAliveService.ACTION_CHECK_CREDS, "正在校验 Token，结果见日志") },
            LinearLayout.LayoutParams(dp(72), dp(44)))
        cf.addView(tokenRow, lpM(bottom = 4))
        etRoot = labeledField(cf, "根域名", "example.com", hint = "只填域名，比如 example.com")
        cf.addView(fieldLabel("记录模式")); modeSeg = Segment(this, listOf("槽位子域名", "同主机多 A"), 0) { applyModeVisibility() }
        cf.addView(modeSeg.root, lpM(bottom = 4))
        sharedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        etShared = labeledField(sharedBox, "共享主机名", "cf", hint = "留空或填 @ 表示根域名")
        sharedBox.addView(para("所有槽位写在同一个名字下，靠备注区分。", 11f, R.color.sub), lpM(bottom = 4))
        cf.addView(sharedBox)
        etPrefix = labeledField(cf, "槽位名前缀", "n", hint = "例如 n → n001.example.com")
        etCount = labeledField(cf, "槽位数量", "200", true, "1 到 2000")
        etTtl = labeledField(cf, "TTL 秒", "60", true, "最低 60")
        swProxied = switchRow(cf, "开启 Cloudflare 代理（橙云）", "代理只转发 HTTP(S)，IP 池必须保持灰云，一般别开")
        etRecordLimit = labeledField(cf, "记录条数上限", "200", true, "免费版新 Zone 上限 200")

        val probe = section(box, "探测")
        etPorts = labeledField(probe, "端口列表", "443,80", hint = "逗号分隔，例如 443,80")
        etConc = labeledField(probe, "同时探测数", "32", true, "1 到 128")
        etConnect = labeledField(probe, "单次连接超时（毫秒）", "2000", true, "2000 约等于 2 秒")
        etAttempts = labeledField(probe, "每个端口尝试次数", "1", true)
        etFail = labeledField(probe, "连续失败几次才换", "2", true, "默认 2 次")
        probe.addView(para("达到阈值才判定这条 IP 坏了，避免网络抖动误伤。", 11f, R.color.sub), lpM(bottom = 4))
        etRepairs = labeledField(probe, "每轮最多修几条", "5", true)
        etInterval = labeledField(probe, "两轮之间等待（毫秒）", "0", true, "0 表示一轮接一轮不间断")
        etBudget = labeledField(probe, "单轮探测时间上限（毫秒）", "60000", true)

        val dns = section(box, "DNS 解析")
        dns.addView(fieldLabel("解析方式")); dnsSeg = Segment(this, listOf("系统 DNS", "指定 UDP", "DoH"), 0) { applyDnsVisibility() }
        dns.addView(dnsSeg.root, lpM(bottom = 4))
        udpBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        etUdp = labeledField(udpBox, "UDP 服务器", "223.5.5.5,119.29.29.29", hint = "多个用逗号分隔")
        dns.addView(udpBox)
        dohBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        etDoh = labeledField(dohBox, "DoH 地址", "https://dns.alidns.com/dns-query", hint = "完整的 https 地址", mono = true)
        dns.addView(dohBox)
        etDnsTimeout = labeledField(dns, "DNS 超时（毫秒）", "1500", true)

        val domains = section(box, "域名列表")
        etDomains = multiline("一行一个域名，# 开头是注释", "")
        domains.addView(etDomains, lpM(top = 4))

        val run = section(box, "运行")
        swAutoRun = switchRow(run, "自动运行", "保存后立刻开始 7×24 轮询，并交给看门狗保活")
        run.addView(actionButton("保存设置", primary = true) { saveForm() }, lpM(top = 8, bottom = 8))
        run.addView(actionButton("暂停自动运行") { pauseRun() })

        val danger = card().apply { background = rounded(color(R.color.surface), 12, color(R.color.danger)) }
        danger.addView(para("危险操作", 15f, R.color.danger).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        danger.addView(para("只删备注以 dslk1 开头的记录，别人的记录不动。", 12f, R.color.sub), lpM(bottom = 10))
        danger.addView(actionButton("删除我管理的记录") { confirmCleanup() })
        box.addView(danger)
    }

    private fun fillForm(s: AppSettings) {
        listOf(
            etToken to s.cfToken, etRoot to s.rootDomain, etShared to s.sharedHost,
            etPrefix to s.slotPrefix, etCount to s.slotCount.toString(), etTtl to s.ttlSec.toString(),
            etRecordLimit to s.recordLimit.toString(), etPorts to s.portsRaw,
            etConc to s.probeConcurrency.toString(), etConnect to s.connectTimeoutMs.toString(),
            etAttempts to s.attemptsPerPort.toString(), etFail to s.failThreshold.toString(),
            etRepairs to s.maxRepairsPerCycle.toString(), etInterval to s.minLoopIntervalMs.toString(),
            etBudget to s.maxProbeBudgetMs.toString(), etUdp to s.udpServersRaw,
            etDoh to s.dohUrl, etDnsTimeout to s.dnsTimeoutMs.toString(), etDomains to s.domainsRaw
        ).forEach { (edit, value) -> edit.setText(value) }
        swProxied.isChecked = s.proxied; swAutoRun.isChecked = s.autoRun
        modeSeg.select(if (s.mode == RecordMode.SHARED_HOST) 1 else 0, false)
        dnsSeg.select(when (s.dnsMode) {
            DnsMode.UDP -> 1
            DnsMode.DOH -> 2
            else -> 0
        }, false)
        applyModeVisibility(); applyDnsVisibility()
    }

    private fun applyModeVisibility() {
        if (!::sharedBox.isInitialized) return
        sharedBox.visibility = if (modeSeg.index == 1) View.VISIBLE else View.GONE
    }

    private fun applyDnsVisibility() {
        if (!::udpBox.isInitialized) return
        udpBox.visibility = if (dnsSeg.index == 1) View.VISIBLE else View.GONE
        dohBox.visibility = if (dnsSeg.index == 2) View.VISIBLE else View.GONE
    }

    private fun readForm(): AppSettings {
        val base = repo.settings()
        return base.copy(
            cfToken = etToken.text.toString().trim(), rootDomain = IpUtil.cleanHost(etRoot.text.toString()),
            mode = if (modeSeg.index == 1) RecordMode.SHARED_HOST else RecordMode.SLOT_SUBDOMAIN,
            sharedHost = etShared.text.toString().trim(),
            slotPrefix = etPrefix.text.toString().trim().ifBlank { "n" },
            slotCount = num(etCount, base.slotCount).coerceIn(1, 2000),
            ttlSec = num(etTtl, base.ttlSec).coerceAtLeast(60),
            proxied = swProxied.isChecked, recordLimit = num(etRecordLimit, base.recordLimit),
            portsRaw = etPorts.text.toString().trim(),
            probeConcurrency = num(etConc, base.probeConcurrency).coerceIn(1, 128),
            connectTimeoutMs = num(etConnect, base.connectTimeoutMs),
            attemptsPerPort = num(etAttempts, base.attemptsPerPort),
            failThreshold = num(etFail, base.failThreshold).coerceIn(1, 10),
            maxRepairsPerCycle = num(etRepairs, base.maxRepairsPerCycle),
            minLoopIntervalMs = numL(etInterval, base.minLoopIntervalMs),
            maxProbeBudgetMs = numL(etBudget, base.maxProbeBudgetMs),
            dnsMode = when (dnsSeg.index) { 1 -> DnsMode.UDP; 2 -> DnsMode.DOH; else -> DnsMode.SYSTEM },
            udpServersRaw = etUdp.text.toString().trim(), dohUrl = etDoh.text.toString().trim(),
            dnsTimeoutMs = num(etDnsTimeout, base.dnsTimeoutMs),
            domainsRaw = etDomains.text.toString(), autoRun = swAutoRun.isChecked
        )
    }

    private fun saveForm(forceStart: Boolean = false) {
        var next = readForm()
        if (forceStart) next = next.copy(autoRun = true)
        repo.saveSettings(next)
        swAutoRun.isChecked = next.autoRun
        applyModeVisibility(); applyDnsVisibility()
        toast("设置已保存")
        val problems = next.validate()
        if (problems.isNotEmpty()) toast("还需要处理：" + problems.joinToString("，"))
        if (next.autoRun) {
            KeepAliveService.command(this, KeepAliveService.ACTION_START)
            WatchdogReceiver.schedule(this)
            if (next.cfToken.isBlank()) toast("Token 还没填，服务会先空转")
        }
        refreshOverview()
    }

    private fun pauseRun() {
        swAutoRun.isChecked = false
        KeepAliveService.command(this, KeepAliveService.ACTION_STOP)
        WatchdogReceiver.cancel(this)
        toast("已暂停自动运行"); refreshOverview()
    }

    private fun toggleRun() {
        if (repo.runState().running) pauseRun() else saveForm(forceStart = true)
    }

    /* ============================ 对话框与提示 ============================ */

    private fun confirmRebuild() {
        val s = readForm()
        val modeText = if (s.mode == RecordMode.SHARED_HOST) "同主机多 A（全部写在一个名字下）"
        else "槽位子域名（${s.slotPrefix}001 这种）"
        confirm("重建 ${s.slotCount} 条记录",
            "会按当前设置写入 ${s.slotCount} 条 A 记录。\n域名：${s.rootDomain.ifBlank { "还没填" }}\n" +
                "模式：$modeText\n\n已有槽位会复用，只写真正有差异的记录，不会整批重写。确定开始？",
            "开始重建") { fire(KeepAliveService.ACTION_REBUILD, "已开始重建，进度见日志") }
    }

    private fun confirmCleanup() {
        val managed = repo.slots().count { it.comment?.startsWith("dslk1") == true }
        confirm("删除我管理的记录",
            "只删备注以 dslk1 开头的记录，别人的记录不动，当前会删 $managed 条。\n删除后槽位变成待建，需要重新点「重建」。确定继续？",
            "确认删除") { fire(KeepAliveService.ACTION_CLEANUP, "已开始清理，结果见日志") }
    }

    private fun guide() {
        confirm("三步就能跑起来",
            "1. 填 Cloudflare API Token（Zone 读写权限就够）\n2. 填根域名和自己的域名列表\n" +
                "3. 保存后先「运行自检」，全绿再点「启动」",
            "去填写", "先看看") { selectPage(2) }
    }

    private fun confirm(
        title: String, message: String, positive: String, negative: String = "取消", action: () -> Unit
    ) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton(negative, null)
            .setPositiveButton(positive) { _, _ -> action() }
            .show()
    }

    private fun buildTips() {
        if (!::tipsBox.isInitialized) return
        tipsBox.removeAllViews()
        val pm = getSystemService(PowerManager::class.java)
        val pending = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED && "notify" !in dismissedTips ->
                Triple("notify", "通知权限没开，锁屏后看不到守护状态。", "去开启")
            pm != null && !pm.isIgnoringBatteryOptimizations(packageName) && "battery" !in dismissedTips ->
                Triple("battery", "电池优化在限制后台，云手机可能把守护进程冻结。", "去设置")
            else -> return
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(soft(R.color.info, 26), 10, soft(R.color.info, 80))
            setPadding(dp(12), dp(10), dp(10), dp(10))
        }
        bar.addView(para(pending.second, 12f, R.color.text), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(para(pending.third, 12f, R.color.info).apply {
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener {
                if (pending.first == "notify") {
                    ActivityCompat.requestPermissions(this@MainActivity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), 91)
                } else {
                    askBattery()
                }
                dismissedTips.add(pending.first); buildTips()
            }
        })
        bar.addView(para("×", 15f, R.color.sub).apply {
            setPadding(dp(6), dp(2), dp(2), dp(2))
            setOnClickListener { dismissedTips.add(pending.first); buildTips() }
        })
        tipsBox.addView(bar, lpM(bottom = 8))
    }

    private fun askBattery() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun fire(action: String, message: String) {
        // 动作前先落盘：用户在设置里改了 N/端口但没点保存，也应该按新值执行
        if (::etToken.isInitialized) runCatching { repo.saveSettings(readForm()) }
        KeepAliveService.command(this, action)
        toast(message); refreshOverview()
    }

    /* ============================ 小工具 ============================ */

    private fun statRow(parent: LinearLayout, key: String, label: String) {
        val row = textRow(label, "—")
        parent.addView(row, lpM(bottom = 6))
        row.rowValue()?.let { cycleRows[key] = it }
    }

    private fun lpM(bottom: Int = 0, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun num(edit: EditText, def: Int): Int =
        edit.text.toString().trim().toIntOrNull() ?: def

    private fun numL(edit: EditText, def: Long): Long =
        edit.text.toString().trim().toLongOrNull() ?: def

    private fun resultLabel(raw: String): String = when (raw) {
        "OK" -> "正常"; "REPAIRED" -> "已修复"; "REBUILT" -> "已重建"
        "SKIPPED" -> "配置不全，跳过"; "OFFLINE" -> "本机断网，跳过"; "ERROR" -> "出错"
        "" -> "—"
        else -> raw
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

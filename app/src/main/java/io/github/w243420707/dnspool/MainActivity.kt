package io.github.w243420707.dnspool

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.github.w243420707.dnspool.ui.LockOverlay
import io.github.w243420707.dnspool.ui.PatternStore
import io.github.w243420707.dnspool.ui.SettingsPage
import io.github.w243420707.dnspool.ui.SlotsPage
import io.github.w243420707.dnspool.ui.StatusPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), Host {

    companion object {
        /** 离开超过这个时长再回来就要重新画图案，免得每次切个通知都要解锁 */
        private const val AUTO_LOCK_AFTER_MS = 60_000L
    }

    private lateinit var repo: StateRepository
    private lateinit var statusPage: StatusPage
    private var slotsPage: SlotsPage? = null
    private var settingsPage: SettingsPage? = null

    private lateinit var pages: MutableList<Page>
    private lateinit var tabCells: List<LinearLayout>
    private lateinit var tabLabels: List<TextView>
    private lateinit var tabLines: List<View>
    private lateinit var content: FrameLayout

    private lateinit var dotView: View
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var poolText: TextView
    private lateinit var toggleBtn: TextView

    private var currentTab = 0
    private var receiverRegistered = false
    private var refreshing = false
    private var dirty = false
    private var statsCacheKey = -1L
    private var statsCache: CycleStats? = null
    private lateinit var lockOverlay: LockOverlay
    private var lastStopAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTick = Runnable { doRefresh() }
    private val ageTick: Runnable = Runnable {
        requestRefresh()
        handler.postDelayed(ageTick, 15_000)
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = requestRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StateRepository.get(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val frame = FrameLayout(this)
        frame.addView(buildRoot(), FrameLayout.LayoutParams(-1, -1))
        lockOverlay = LockOverlay(this)
        frame.addView(lockOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        applyInsets()

        statusPage = StatusPage(this)
        pages[0] = statusPage
        content.removeViewAt(0)
        content.addView(statusPage.view, 0, FrameLayout.LayoutParams(-1, -1))
        selectTab(0)
        val s = repo.settings()
        // 上次是自动运行状态的话，重新打开 App 要接着跑（v2.0.0 漏了这一步）
        if (s.autoRun && s.cfToken.isNotBlank() && s.rootDomain.isNotBlank()) {
            KeepAliveService.command(this, KeepAliveService.ACTION_START)
        }
        if (PatternStore.isEnabled(this)) lockOverlay.showVerify { }
        requestRefresh()
    }

    override fun onStart() {
        super.onStart()
        if (lastStopAt > 0 && PatternStore.isEnabled(this) &&
            System.currentTimeMillis() - lastStopAt > AUTO_LOCK_AFTER_MS
        ) {
            lockOverlay.showVerify { }
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, stateReceiver, IntentFilter(KeepAliveService.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        handler.postDelayed(ageTick, 15_000)
    }

    override fun onStop() {
        super.onStop()
        lastStopAt = System.currentTimeMillis()
        handler.removeCallbacks(ageTick)
        handler.removeCallbacks(refreshTick)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
    }

    /* ============================ 骨架 ============================ */

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(Dimens.HEAD_H)))
        root.addView(hairline())
        root.addView(buildTabs(), LinearLayout.LayoutParams(-1, dp(Dimens.TAB_H)))
        root.addView(hairline())

        content = FrameLayout(this)
        pages = mutableListOf(placeholder(), placeholder(), placeholder())
        pages.forEachIndexed { i, p -> content.addView(p.view, i, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun placeholder(): Page = object : Page {
        override val view: View = View(this@MainActivity)
        override fun render(snap: UiSnapshot) = Unit
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        dotView = View(this).apply {
            background = oval(R.color.sub, 8)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
        }
        bar.addView(dotView)
        statusText = para("已停止", Dimens.BODY, R.color.sub).apply { typeface = MEDIUM }
        bar.addView(statusText)
        bar.addView(para("·", Dimens.SMALL, R.color.stroke).apply { setPadding(dp(6), 0, dp(6), 0) })
        countText = monoPara("0/0", Dimens.SMALL, R.color.text)
        bar.addView(countText)
        poolText = para("池 0", Dimens.LABEL, R.color.sub).apply { setPadding(dp(8), 0, 0, 0) }
        bar.addView(poolText)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        toggleBtn = TextView(this).apply {
            textSize = Dimens.LABEL
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { setAutoRun(!repo.runState().running) }
        }
        bar.addView(toggleBtn, LinearLayout.LayoutParams(-2, dp(28)))
        return bar
    }

    private fun buildTabs(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val titles = listOf("状态", "槽位", "设置")
        val cells = mutableListOf<LinearLayout>()
        val labels = mutableListOf<TextView>()
        val lines = mutableListOf<View>()
        titles.forEachIndexed { i, title ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { selectTab(i) }
            }
            val label = para(title, Dimens.BODY, R.color.sub).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }
            val line = View(this).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) }
            cell.addView(label, LinearLayout.LayoutParams(-1, 0, 1f))
            cell.addView(line, LinearLayout.LayoutParams(dp(22), dp(2)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(4)
            })
            row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
            cells += cell; labels += label; lines += line
        }
        tabCells = cells; tabLabels = labels; tabLines = lines
        return row
    }

    private fun applyInsets() {
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun selectTab(index: Int) {
        currentTab = index
        if (index == 1) ensureSlotsPage()
        if (index == 2) ensureSettingsPage()
        pages.forEachIndexed { i, p -> p.view.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabLabels.forEachIndexed { i, label ->
            val on = i == index
            label.setTextColorIfChanged(color(if (on) R.color.text else R.color.sub))
            label.typeface = if (on) MEDIUM else android.graphics.Typeface.DEFAULT
        }
        tabLines.forEachIndexed { i, line ->
            line.setBackgroundColor(if (i == index) color(R.color.accent) else android.graphics.Color.TRANSPARENT)
        }
        if (index == 1) {
            slotsPage?.let { page ->
                page.view.post {
                    page.view.requestLayout()
                    page.onShown()
                }
            }
        }
        requestRefresh()
    }

    private fun ensureSlotsPage() {
        if (slotsPage != null) return
        val page = SlotsPage(this)
        slotsPage = page
        pages[1] = page
        content.removeViewAt(1)
        content.addView(page.view, 1, FrameLayout.LayoutParams(-1, -1))
    }

    private fun ensureSettingsPage() {
        if (settingsPage != null) return
        val page = SettingsPage(this)
        settingsPage = page
        pages[2] = page
        content.removeViewAt(2)
        content.addView(page.view, 2, FrameLayout.LayoutParams(-1, -1))
    }

    /* ============================ 刷新管线 ============================ */

    private fun requestRefresh() {
        handler.removeCallbacks(refreshTick)
        handler.postDelayed(refreshTick, 250)
    }

    private fun doRefresh() {
        if (refreshing) {
            dirty = true
            return
        }
        refreshing = true
        lifecycleScope.launch {
            do {
                dirty = false
                val snap = withContext(Dispatchers.IO) { readSnapshot() }
                if (isFinishing || isDestroyed) break
                apply(snap)
            } while (dirty)
            refreshing = false
        }
    }

    private fun readSnapshot(): UiSnapshot {
        val run = repo.runState()
        if (run.lastCycleAt != statsCacheKey) {
            statsCache = repo.stats().lastOrNull()
            statsCacheKey = run.lastCycleAt
        }
        return UiSnapshot(
            settings = repo.settings(),
            run = run,
            slots = repo.slots(),
            stats = statsCache,
            logs = repo.log.tail(4),
            now = System.currentTimeMillis()
        )
    }

    private fun apply(snap: UiSnapshot) {
        renderHeader(snap)
        statusPage.render(snap)
        if (currentTab == 1) slotsPage?.render(snap)
        if (currentTab == 2) settingsPage?.render(snap)
    }

    private var lastHeaderKey: String? = null

    private fun renderHeader(snap: UiSnapshot) {
        val s = snap.settings
        val rs = snap.run
        val (title, tintRes) = when {
            s.cfToken.isBlank() || s.rootDomain.isBlank() -> "配置待完善" to R.color.warn
            rs.lastResult == "ERROR" -> "异常" to R.color.danger
            rs.running && rs.lastResult == "SKIPPED" -> "配置待完善" to R.color.warn
            rs.running -> "运行中" to R.color.accent
            else -> "已停止" to R.color.sub
        }
        val key = "$title|${snap.okCount}/${snap.total}|${rs.poolAvailable}|${rs.running}"
        if (key == lastHeaderKey) return
        lastHeaderKey = key

        dotView.background = oval(tintRes, 8)
        statusText.setTextIfChanged(title)
        statusText.setTextColorIfChanged(color(tintRes))
        countText.setTextIfChanged("${snap.okCount}/${snap.total}")
        poolText.setTextIfChanged("池 ${rs.poolAvailable}")

        toggleBtn.setTextIfChanged(if (rs.running) "暂停" else "启动")
        toggleBtn.setTextColorIfChanged(color(if (rs.running) R.color.text else R.color.bg))
        toggleBtn.background = rounded(
            if (rs.running) color(R.color.surface2) else color(R.color.accent), 7
        )
    }

    /* ============================ Host ============================ */

    override fun runNow() = fire(KeepAliveService.ACTION_RUN_NOW, "已开始跑一轮")

    override fun rebuild() {
        val s = repo.settings()
        confirm(
            "重建 ${s.slotCount} 条记录",
            "域名：${s.rootDomain.ifBlank { "还没填" }}\n模式：" +
                if (s.mode == RecordMode.SHARED_HOST) "同主机多 A" else "槽位子域名（${s.slotPrefix}001）" +
                "\n\n已有槽位会复用，只写真正有差异的记录，不会整批重写。",
            "开始重建"
        ) { fire(KeepAliveService.ACTION_REBUILD, "已开始重建，进度见日志") }
    }

    override fun preview() = fire(KeepAliveService.ACTION_PREVIEW, "正在对比，结果见日志")

    override fun selfTest() = fire(KeepAliveService.ACTION_SELFTEST, "已开始自检，结果见日志")

    override fun openLogs() = io.github.w243420707.dnspool.ui.Dialogs.showLogs(this, repo.log.dump())

    override fun retest(slot: Slot) {
        confirm(
            "重测槽位 ${slot.index}",
            "会跑一整轮（约 4 秒，全部 ${repo.settings().slotCount} 条都会重测），连续失败达阈值的会被自动替换。",
            "开始重测"
        ) { fire(KeepAliveService.ACTION_RUN_NOW, "已开始重测") }
    }

    override fun cleanup() {
        val managed = repo.slots().count { it.comment?.startsWith(SlotComment.PREFIX) == true }
        confirm(
            "删除我管理的记录",
            "只删备注以 ${SlotComment.PREFIX} 开头的记录，别人的记录不动，当前会删 $managed 条。\n删除后槽位变成待建，需要重新「重建」。",
            "确认删除"
        ) { fire(KeepAliveService.ACTION_CLEANUP, "已开始清理，结果见日志") }
    }

    override fun checkToken() = fire(KeepAliveService.ACTION_CHECK_CREDS, "正在校验 Token，结果见日志")

    override fun restartService() {
        KeepAliveService.command(this, KeepAliveService.ACTION_STOP)
        KeepAliveService.command(this, KeepAliveService.ACTION_START)
        toast("已重启守护")
        requestRefresh()
    }

    override fun commit(change: (AppSettings) -> AppSettings) {
        repo.saveSettings(change(repo.settings()))
        requestRefresh()
    }

    override fun setAutoRun(on: Boolean) {
        // 先落盘再发命令：ACTION_STOP 内部也会写 autoRun=false，顺序反了会导致暂停失效
        repo.saveSettings(repo.settings().copy(autoRun = on))
        if (on) {
            KeepAliveService.command(this, KeepAliveService.ACTION_START)
            WatchdogReceiver.schedule(this)
        } else {
            KeepAliveService.command(this, KeepAliveService.ACTION_STOP)
            WatchdogReceiver.cancel(this)
        }
        requestRefresh()
    }

    override fun setAppLock(on: Boolean) {
        if (!on) {
            lockOverlay.showVerify {
                PatternStore.clear(this)
                toast("应用锁已关闭")
                requestRefresh()
            }
            return
        }
        if (PatternStore.isEnabled(this)) {
            toast("应用锁已经开着")
            return
        }
        confirm(
            "开启应用锁",
            "开启后每次打开应用、或离开超过 1 分钟回来，都要画一遍图案。\n\n" +
                "图案只存加盐哈希，不存原文。忘记图案只能卸载重装应用——" +
                "槽位记录会靠 Cloudflare 备注重新认领，Token 需要重新填一次。",
            "去设置图案"
        ) {
            lockOverlay.showSetup {
                toast("应用锁已开启")
                requestRefresh()
            }
        }
    }

    override fun openSlots(filter: Int) {        ensureSlotsPage()
        selectTab(1)
        slotsPage?.setFilter(filter)
    }

    override fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun confirm(title: String, message: String, positive: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(positive) { _, _ -> action() }
            .show()
    }

    override fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("已复制")
    }

    override fun askBattery() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 91)
        }
    }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun fire(action: String, message: String) {
        KeepAliveService.command(this, action)
        toast(message)
        requestRefresh()
    }
}

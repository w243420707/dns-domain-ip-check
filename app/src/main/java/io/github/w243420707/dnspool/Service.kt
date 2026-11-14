package io.github.w243420707.dnspool

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 常驻前台服务：specialUse 类型（不受 Android 14+ dataSync 的 6 小时/日配额限制）。
 * 循环节奏由 PollEngine 决定，这里只负责「别被系统杀掉」和「把状态送回界面」。
 */
class KeepAliveService : LifecycleService() {

    companion object {
        const val ACTION_STATE = "io.github.w243420707.dnspool.STATE_CHANGED"
        const val ACTION_START = "dnspool.start"
        const val ACTION_STOP = "dnspool.stop"
        const val ACTION_RUN_NOW = "dnspool.run_now"
        const val ACTION_REBUILD = "dnspool.rebuild"
        const val ACTION_PREVIEW = "dnspool.preview"
        const val ACTION_CLEANUP = "dnspool.cleanup"
        const val ACTION_SELFTEST = "dnspool.selftest"
        const val ACTION_CHECK_CREDS = "dnspool.check_creds"

        private const val CHANNEL_ID = "dnspool_keepalive"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) = command(context, ACTION_START)
        fun stop(context: Context) = command(context, ACTION_STOP)

        fun command(context: Context, action: String) {
            val intent = Intent(context, KeepAliveService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                context.getSharedPreferences("dnspool_crash", Context.MODE_PRIVATE)
                    .edit().putString("last_start_error", e.message ?: e.javaClass.simpleName).apply()
            }
        }

        fun notifyUi(context: Context) {
            context.sendBroadcast(Intent(ACTION_STATE).setPackage(context.packageName))
        }
    }

    private lateinit var repo: StateRepository
    private lateinit var engine: PollEngine
    private val loopMutex = Mutex()
    private val loopLock = Any()
    private var loopJob: Job? = null
    @Volatile
    private var loopRunning = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var busy = false

    override fun onCreate() {
        super.onCreate()
        repo = StateRepository.get(this)
        engine = PollEngine(repo)
        createChannel()
        startAsForeground("正在启动…")
        registerNetworkCallback()
        repo.log.add("I", "服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                val s = repo.settings()
                repo.saveSettings(s.copy(autoRun = false))
                stopLoop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN_NOW -> runOnce("手动")
            ACTION_REBUILD -> runBlockingAction("重建") { engine.rebuild { p -> updateNotification(p, p) } }
            ACTION_PREVIEW -> runBlockingAction("预览") { engine.preview() }
            ACTION_CLEANUP -> runBlockingAction("清理") { engine.cleanupManaged() }
            ACTION_SELFTEST -> runBlockingAction("自检") { SelfTest(repo, engine).runAll() }
            ACTION_CHECK_CREDS -> runBlockingAction("校验") { engine.checkCredentials() }
            else -> {
                val s = repo.settings()
                if (s.autoRun) startLoop() else startAsForeground("已暂停（自动运行已关闭）")
            }
        }
        return START_STICKY
    }

    /* ---------------- 循环 ---------------- */

    private fun startLoop() {
        // 网络回调在 binder 线程、onCreate 在主线程，两边同时判断 loopJob 为空会各起一个循环，
        // 表现为同一时刻连着跑两轮。用锁 + 标志位双保险。
        synchronized(loopLock) {
            if (loopRunning || loopJob?.isActive == true) return
            loopRunning = true
            loopJob = lifecycleScope.launch {
                try {
                    repo.saveRunState(repo.runState().copy(running = true))
                    while (isActive) {
                        val s = repo.settings()
                        if (!s.autoRun) break
                        val stats = lockedCycle("定时")
                        updateNotificationFromState(stats.note, stats.result)
                        // 没有保底间隔时，SKIPPED/ERROR 这类毫秒级返回会满速空转（实测 1.5 秒转 100 轮）
                        val floor = when {
                            stats.result == "SKIPPED" || stats.result == "ERROR" -> 5_000L
                            stats.durationMs < 500 -> 1_000L
                            else -> 0L
                        }
                        val wait = maxOf(s.minLoopIntervalMs.coerceAtLeast(0L), floor)
                        repo.saveRunState(repo.runState().copy(nextRunAt = System.currentTimeMillis() + wait))
                        notifyUi(this@KeepAliveService)
                        if (wait > 0) delay(wait)
                    }
                } finally {
                    loopRunning = false
                    repo.saveRunState(repo.runState().copy(running = false, phase = "已停止"))
                    notifyUi(this@KeepAliveService)
                }
            }
        }
    }

    private fun stopLoop() {
        synchronized(loopLock) {
            loopJob?.cancel()
            loopJob = null
            loopRunning = false
        }
        repo.saveRunState(repo.runState().copy(running = false, phase = "已停止"))
        notifyUi(this)
    }

    private suspend fun lockedCycle(trigger: String): CycleStats = withContext(Dispatchers.IO) {
        loopMutex.lock()
        busy = true
        acquireWakeLock()
        try {
            repo.saveRunState(repo.runState().copy(phase = "检测中", heartbeatAt = System.currentTimeMillis()))
            notifyUi(this@KeepAliveService)
            engine.runCycle(trigger)
        } finally {
            busy = false
            releaseWakeLock()
            loopMutex.unlock()
        }
    }

    private fun runOnce(trigger: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            lockedCycle(trigger)
            notifyUi(this@KeepAliveService)
        }
    }

    private fun runBlockingAction(label: String, block: suspend () -> String) {
        lifecycleScope.launch {
            if (loopMutex.isLocked) {
                repo.log.add("W", "$label 正在忙，稍后再试")
                return@launch
            }
            loopMutex.lock()
            busy = true
            try {
                repo.saveRunState(repo.runState().copy(phase = "${label}中", heartbeatAt = System.currentTimeMillis()))
                notifyUi(this@KeepAliveService)
                val msg = withContext(Dispatchers.IO) { block() }
                repo.log.add("I", "$label：$msg")
                updateNotification("$label 完成", msg)
            } catch (e: Exception) {
                repo.log.add("E", "$label 失败：${engine.humanError(e)}")
                updateNotification("$label 失败", engine.humanError(e))
            } finally {
                busy = false
                loopMutex.unlock()
                repo.saveRunState(repo.runState().copy(phase = "空闲"))
                notifyUi(this@KeepAliveService)
            }
        }
    }

    /* ---------------- 保活辅助 ---------------- */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "守护运行状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "常驻通知，用于保持后台轮询不被系统回收"
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat, "暂停", toggle)
            .build()
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification("DNS 池守护", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification(title, text))
        }
    }

    private fun updateNotificationFromState(note: String, result: String) {
        val rs = repo.runState()
        val title = when (result) {
            "ERROR" -> "运行异常"
            "SKIPPED" -> "配置待完善"
            "OFFLINE" -> "本地网络异常"
            "REPAIRED" -> "已自动修复"
            else -> "运行中"
        }
        updateNotification(title, "正常 ${rs.slotsOk}/${rs.slotsTotal} · 池 ${rs.poolAvailable} · $note")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dnspool:cycle").apply {
                setReferenceCounted(false)
                acquire(6 * 60_000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    repo.log.add("I", "网络恢复，检查循环")
                    val s = repo.settings()
                    if (s.autoRun && loopJob?.isActive != true) startLoop()
                }
            }
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        }
    }

    override fun onDestroy() {
        stopLoop()
        runCatching { networkCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        releaseWakeLock()
        repo.saveRunState(repo.runState().copy(running = false, phase = "服务已销毁"))
        repo.log.add("W", "服务销毁")
        super.onDestroy()
    }
}

/** 开机与应用更新后自动拉起 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val repo = StateRepository.get(context)
        val s = repo.settings()
        if (!s.autoRun) return
        if (s.cfToken.isBlank() || s.rootDomain.isBlank()) return
        KeepAliveService.start(context)
    }
}

/**
 * 看门狗：云手机厂商经常"静默冻结"后台进程，AlarmManager 每 15 分钟唤醒一次把服务拉回来。
 * 已经活着时重复 start 是幂等的。
 */
class WatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val REQUEST_CODE = 7712
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java).setAction("dnspool.watchdog"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setInexactRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + INTERVAL_MS,
                        INTERVAL_MS, pi
                    )
                } else {
                    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, INTERVAL_MS, pi)
                }
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java).setAction("dnspool.watchdog"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val repo = StateRepository.get(context)
        val s = repo.settings()
        if (!s.autoRun || s.cfToken.isBlank() || s.rootDomain.isBlank()) return
        KeepAliveService.start(context)
    }
}

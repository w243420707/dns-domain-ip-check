package io.github.w243420707.dnspool

import android.view.View

/** 一帧界面所需的全部数据，在 IO 线程一次性读出，避免多处零散读盘 */
data class UiSnapshot(
    val settings: AppSettings,
    val run: RunState,
    val slots: List<Slot>,
    val stats: CycleStats?,
    val logs: List<LogEntry>,
    val now: Long
) {
    val total: Int get() = if (run.slotsTotal > 0) run.slotsTotal else slots.count { it.recordId != null }
    val okCount: Int get() = slots.count { it.recordId != null && it.state == SlotState.OK }
    val emptyCount: Int get() = slots.count { it.state == SlotState.EMPTY }
    val badSlots: List<Slot>
        get() = slots.filter { it.state == SlotState.DOWN || it.state == SlotState.SUSPECT }
            .sortedWith(compareBy({ if (it.state == SlotState.DOWN) 0 else 1 }, { it.index }))
    val lastWriteAt: Long get() = slots.maxOfOrNull { it.lastWrittenAt } ?: 0L
}

interface Page {
    val view: View
    fun render(snap: UiSnapshot)
}

/** 页面回给宿主的能力，避免页面直接持有 Activity 的字段 */
interface Host {
    fun runNow()
    fun rebuild()
    fun preview()
    fun selfTest()
    fun openLogs()
    fun retest(slot: Slot)
    fun cleanup()
    fun checkToken()
    fun restartService()
    fun commit(change: (AppSettings) -> AppSettings)
    fun setAutoRun(on: Boolean)
    fun setAppLock(on: Boolean)
    fun openSlots(filter: Int)
    fun toast(message: String)
    fun confirm(title: String, message: String, positive: String, action: () -> Unit)
    fun copyToClipboard(label: String, text: String)
    fun askBattery()
}

package io.github.w243420707.dnspool.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.w243420707.dnspool.AppSettings
import io.github.w243420707.dnspool.Dimens
import io.github.w243420707.dnspool.FilterTabs
import io.github.w243420707.dnspool.HairlineDecoration
import io.github.w243420707.dnspool.Host
import io.github.w243420707.dnspool.MainActivity
import io.github.w243420707.dnspool.Page
import io.github.w243420707.dnspool.R
import io.github.w243420707.dnspool.Slot
import io.github.w243420707.dnspool.SlotState
import io.github.w243420707.dnspool.UiSnapshot
import io.github.w243420707.dnspool.dp
import io.github.w243420707.dnspool.hairline
import io.github.w243420707.dnspool.para
import io.github.w243420707.dnspool.setTextIfChanged

/**
 * 槽位页：筛选 + 排序 + 列表。
 * 只在 data class 行内容变化时 submitList，重复 render 不重建视图。
 */
class SlotsPage(private val activity: MainActivity) : Page {

    private val ctx = activity
    private val host: Host = activity

    private lateinit var filterTabs: FilterTabs
    private lateinit var sortLabel: TextView
    private lateinit var rv: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var listAdapter: SlotAdapter

    private var settings: AppSettings = AppSettings()
    private var slotByIndex: Map<Int, Slot> = emptyMap()
    private var allRows: List<SlotRow> = emptyList()
    private var lastRows: List<SlotRow> = emptyList()
    private var totalCount = 0
    private var sortByState = true

    override val view: View = build()

    private fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val tabsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterTabs = FilterTabs(ctx, listOf("全部 0", "异常 0", "空位 0"), 0) { refreshList() }
        tabsRow.addView(
            filterTabs.root,
            LinearLayout.LayoutParams(0, ctx.dp(Dimens.TAB_H), 1f)
        )
        sortLabel = ctx.para(sortText(), Dimens.LABEL, R.color.info).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(6), 0, ctx.dp(12), 0)
            isClickable = true
            setOnClickListener {
                sortByState = !sortByState
                setTextIfChanged(sortText())
                refreshList()
            }
        }
        tabsRow.addView(
            sortLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(Dimens.TAB_H))
        )
        root.addView(
            tabsRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(Dimens.TAB_H))
        )
        root.addView(ctx.hairline())

        listAdapter = SlotAdapter(
            onClick = { row ->
                val slot = slotByIndex[row.index]
                if (slot != null) {
                    Dialogs.showSlot(activity, slot, settings.slotCount, settings.slotPad)
                }
            },
            onLongClick = { row ->
                val ip = row.ip ?: "—"
                host.copyToClipboard(
                    "槽位 ${row.index}",
                    "${settings.recordName(row.index)} → $ip (${row.rtt}ms)"
                )
            }
        )

        rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = listAdapter
            setPadding(0, 0, 0, ctx.dp(12))
            clipToPadding = false
            itemAnimator = null
            setHasFixedSize(true)
            addItemDecoration(HairlineDecoration(insetStartDp = 12))
        }
        emptyText = ctx.para("", Dimens.SMALL, R.color.sub).apply {
            setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        val body = FrameLayout(ctx)
        body.addView(
            rv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        body.addView(
            emptyText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun render(snap: UiSnapshot) {
        settings = snap.settings
        totalCount = snap.slots.size
        val bad = snap.slots.count {
            it.state == SlotState.DOWN || it.state == SlotState.SUSPECT
        }
        val holes = snap.slots.count { it.state == SlotState.EMPTY }
        filterTabs.setLabels(listOf("全部 $totalCount", "异常 $bad", "空位 $holes"))
        slotByIndex = snap.slots.associateBy { it.index }
        allRows = snap.slots.map {
            SlotRow(
                index = it.index,
                ip = it.ip,
                state = it.state,
                failures = it.consecutiveFailures,
                rtt = it.lastAvgRttMs,
                pad = snap.settings.slotPad
            )
        }
        refreshList()
    }

    /** 0 全部 / 1 仅异常 / 2 仅空位；外部（状态页「查看全部」）调用 */
    fun setFilter(index: Int) {
        filterTabs.select(index, notify = false)
        refreshList()
    }

    /** 选中该页时调用，修复首帧空白 */
    fun onShown() {
        rv.post { rv.requestLayout() }
    }

    private fun refreshList() {
        val filtered = when (filterTabs.index) {
            1 -> allRows.filter { it.state == SlotState.DOWN || it.state == SlotState.SUSPECT }
            2 -> allRows.filter { it.state == SlotState.EMPTY }
            else -> allRows
        }
        val rows = if (sortByState) {
            filtered.sortedWith(compareBy({ rank(it.state) }, { it.index }))
        } else {
            filtered.sortedBy { it.index }
        }

        if (rows.isEmpty()) {
            rv.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.setTextIfChanged(
                if (totalCount == 0) "还没有槽位，填好设置后点「重建」"
                else "这个筛选下没有槽位"
            )
        } else {
            emptyText.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }

        if (rows == lastRows) return
        lastRows = rows
        listAdapter.submitList(rows)
    }

    private fun sortText(): String = if (sortByState) "异常优先 ⇄" else "按编号 ⇄"

    private fun rank(state: SlotState): Int = when (state) {
        SlotState.DOWN -> 0
        SlotState.SUSPECT -> 1
        SlotState.PENDING_WRITE -> 2
        SlotState.OK -> 3
        SlotState.EMPTY -> 4
    }
}

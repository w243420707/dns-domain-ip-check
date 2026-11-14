package io.github.w243420707.dnspool.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.w243420707.dnspool.*

/** rtt 只用于显示，不参与排序，避免每轮刷新行跳动 */
data class SlotRow(val index: Int, val ip: String?, val state: SlotState, val failures: Int, val rtt: Int, val pad: Int)

private fun buildRow(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
    background = ctx.selectableBg()
    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(Dimens.ROW_H))
}

class SlotAdapter(
    private val onClick: (SlotRow) -> Unit,
    private val onLongClick: (SlotRow) -> Unit
) : ListAdapter<SlotRow, SlotAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(parent.context, onClick, onLongClick)

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val ctx: Context,
        click: (SlotRow) -> Unit,
        longClick: (SlotRow) -> Unit
    ) : RecyclerView.ViewHolder(buildRow(ctx)) {

        private var bound: SlotRow? = null
        private var dotColor = 0
        val dot: View
        val idx: TextView
        val ip: TextView
        val rtt: TextView
        val fail: TextView

        init {
            val row = itemView as LinearLayout
            dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { marginEnd = ctx.dp(9) }
                background = ctx.oval(R.color.sub, 8)
            }
            idx = ctx.monoPara("", Dimens.SMALL, R.color.text).apply {
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ctx.dp(34), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            ip = ctx.monoPara("", Dimens.BODY, R.color.text).apply {
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            rtt = ctx.monoPara("", Dimens.LABEL, R.color.sub).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            fail = ctx.para("", Dimens.LABEL, R.color.danger).apply {
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                // 和左边的「—」留出间隔，否则会读成「—×2」一个词
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { marginStart = ctx.dp(8) }
            }
            row.addView(dot)
            row.addView(idx)
            row.addView(ip)
            row.addView(rtt)
            row.addView(fail)
            row.setOnClickListener { bound?.let(click) }
            row.setOnLongClickListener {
                val r = bound ?: return@setOnLongClickListener false
                longClick(r)
                true
            }
        }

        fun bind(row: SlotRow) {
            bound = row
            val c = when (row.state) {
                SlotState.OK -> R.color.accent
                SlotState.SUSPECT -> R.color.warn
                SlotState.DOWN -> R.color.danger
                SlotState.PENDING_WRITE -> R.color.info
                SlotState.EMPTY -> R.color.sub
            }
            if (dotColor != c) {
                dotColor = c
                dot.background = ctx.oval(c, 8)
            }
            idx.setTextIfChanged(row.index.toString().padStart(row.pad.coerceIn(1, 4), '0'))
            idx.setTextColorIfChanged(ctx.color(c))
            ip.setTextIfChanged(row.ip ?: "—")
            rtt.setTextIfChanged(if (row.rtt > 0) "${row.rtt}ms" else "—")
            fail.visibility = if (row.failures > 0) View.VISIBLE else View.GONE
            if (row.failures > 0) fail.setTextIfChanged("×${row.failures}")
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SlotRow>() {
            override fun areItemsTheSame(oldItem: SlotRow, newItem: SlotRow): Boolean = oldItem.index == newItem.index
            override fun areContentsTheSame(oldItem: SlotRow, newItem: SlotRow): Boolean = oldItem == newItem
        }
    }
}

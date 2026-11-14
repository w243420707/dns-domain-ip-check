package io.github.w243420707.dnspool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/* 纯代码建界面的工具集：只用 AppCompat + 原生控件，密度优先。改密度只动 Dimens。 */

object Dimens {
    const val HEAD_H = 44
    const val TAB_H = 36
    const val BAND_H = 56
    const val ACT_H = 40
    const val ROW_H = 36
    const val SET_ROW = 40
    const val GROUP_H = 30
    const val PAGE_PAD = 16

    const val LABEL = 11f
    const val SMALL = 12f
    const val BODY = 13f
    const val NUM = 18f
}

internal const val TAG_ROW_VALUE = "ui_row_value"
internal const val TAG_GROUP_SUMMARY = "ui_group_summary"
internal const val TAG_GROUP_CHEVRON = "ui_group_chevron"
internal const val TAG_SWITCH = "ui_switch"

val MONO: Typeface = Typeface.MONOSPACE
val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

fun Context.color(res: Int): Int = ContextCompat.getColor(this, res)
fun Context.dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
fun Int.color(context: Context): Int = ContextCompat.getColor(context, this)

/** 同色系的低透明度版本，用于徽标底、提示条 */
fun Context.soft(res: Int, alpha: Int = 36): Int {
    val c = color(res)
    return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
}

fun Context.rounded(fill: Int, radius: Int = 10, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(Math.max(1, dp(1) / 2), stroke)
    }

fun Context.oval(res: Int, sizeDp: Int = 8): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setSize(dp(sizeDp), dp(sizeDp))
    setColor(color(res))
}

fun Context.card(padding: Int = 14, strokeRes: Int = R.color.stroke): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color(R.color.surface), 12, color(strokeRes))
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

/* ------------------------------ 文本 ------------------------------ */

fun Context.para(text: CharSequence, size: Float = Dimens.BODY, colorRes: Int = R.color.text): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color(colorRes))
        includeFontPadding = false
    }

fun Context.monoPara(text: CharSequence, size: Float = Dimens.SMALL, colorRes: Int = R.color.text): TextView =
    para(text, size, colorRes).apply { typeface = MONO }

fun TextView.setTextIfChanged(value: CharSequence) {
    if (text?.toString() != value.toString()) text = value
}

fun TextView.setTextColorIfChanged(argb: Int) {
    if (currentTextColor != argb) setTextColor(argb)
}

fun Context.hairline(): View = View(this).apply {
    setBackgroundColor(color(R.color.stroke))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2))
}

fun Context.hairlineV(heightDp: Int): View = View(this).apply {
    setBackgroundColor(color(R.color.stroke))
    layoutParams = LinearLayout.LayoutParams(Math.max(1, dp(1) / 2), dp(heightDp)).apply {
        gravity = Gravity.CENTER_VERTICAL
    }
}

fun Context.spacer(h: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(1, dp(h))
}

fun Context.vSpace(h: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
}

/* ------------------------------ 交互 ------------------------------ */

fun Context.actionButton(
    text: String,
    primary: Boolean = false,
    height: Int = Dimens.ACT_H,
    size: Float = Dimens.SMALL,
    onClick: () -> Unit
): Button = Button(this).apply {
    this.text = text
    textSize = size
    isAllCaps = false
    stateListAnimator = null
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    setPadding(0, 0, 0, 0)
    gravity = Gravity.CENTER
    includeFontPadding = false
    background = rounded(if (primary) color(R.color.accent) else color(R.color.surface2), 8)
    setTextColor(if (primary) color(R.color.bg) else color(R.color.text))
    layoutParams = LinearLayout.LayoutParams(0, dp(height), 1f)
    setOnClickListener { onClick() }
}

fun Context.actionRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    buttons.forEachIndexed { i, b ->
        val lp = LinearLayout.LayoutParams(0, dp(Dimens.ACT_H), 1f)
        if (i > 0) lp.marginStart = dp(6)
        addView(b, lp)
    }
}

/** 设置页的一行：左标签 + 右值 + 箭头，整行可点 */
fun Context.settingRow(
    label: String,
    value: String,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null
): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Dimens.PAGE_PAD), 0, dp(Dimens.PAGE_PAD), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Dimens.SET_ROW))
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            background = selectableBg()
            setOnClickListener { onClick() }
        }
    }
    row.addView(para(label, Dimens.BODY, if (danger) R.color.danger else R.color.sub).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    })
    row.addView(TextView(this).apply {
        text = value
        textSize = Dimens.SMALL
        typeface = MONO
        setTextColor(color(if (danger) R.color.danger else R.color.text))
        gravity = Gravity.END
        maxLines = 1
        maxWidth = dp(196)
        ellipsize = TextUtils.TruncateAt.MIDDLE
        tag = TAG_ROW_VALUE
    })
    row.addView(para("›", Dimens.LABEL, R.color.sub).apply {
        setPadding(dp(6), 0, 0, 0)
    })
    return row
}

fun LinearLayout.rowValue(): TextView? = findViewWithTag(TAG_ROW_VALUE) as? TextView

/** 分组头：标题 + 右侧摘要 + 展开箭头，整行可点 */
fun Context.groupHeader(
    title: String,
    summary: String = "",
    expanded: Boolean = true,
    onClick: () -> Unit
): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Dimens.PAGE_PAD), 0, dp(Dimens.PAGE_PAD), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Dimens.GROUP_H))
        isClickable = true
        isFocusable = true
        background = selectableBg()
        setOnClickListener { onClick() }
    }
    row.addView(para(title, Dimens.LABEL, R.color.sub).apply {
        letterSpacing = 0.08f
        typeface = MEDIUM
    })
    row.addView(para(summary, Dimens.LABEL, R.color.sub).apply {
        gravity = Gravity.END
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(10), 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tag = TAG_GROUP_SUMMARY
        alpha = 0.75f
    })
    row.addView(para(if (expanded) "▾" else "▸", Dimens.LABEL, R.color.sub).apply {
        setPadding(dp(6), 0, 0, 0)
        tag = TAG_GROUP_CHEVRON
    })
    return row
}

fun LinearLayout.groupSummary(): TextView? = findViewWithTag(TAG_GROUP_SUMMARY) as? TextView
fun LinearLayout.groupChevron(): TextView? = findViewWithTag(TAG_GROUP_CHEVRON) as? TextView

/** 一行开关：标签 + SwitchCompat（原生 Switch 在 40dp 行里会被裁） */
fun Context.switchRow(
    label: String,
    sub: String = "",
    checked: Boolean = false,
    onChange: (Boolean) -> Unit = {}
): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Dimens.PAGE_PAD), 0, dp(Dimens.PAGE_PAD), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Dimens.SET_ROW))
    }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(para(label, Dimens.BODY, R.color.sub))
    if (sub.isNotBlank()) col.addView(para(sub, Dimens.LABEL, R.color.sub).apply { alpha = 0.8f })
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val toggle = SwitchCompat(this).apply {
        isChecked = checked
        minHeight = 0
        minimumHeight = 0
        switchMinWidth = dp(36)
        setOnCheckedChangeListener { _, v -> onChange(v) }
        tag = TAG_SWITCH
    }
    row.addView(toggle)
    return row
}

fun LinearLayout.rowSwitch(): SwitchCompat? = findViewWithTag(TAG_SWITCH) as? SwitchCompat

fun Context.selectableBg(): Drawable {
    val out = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
    return ContextCompat.getDrawable(this, out.resourceId) ?: GradientDrawable()
}

/** 筛选/排序用的轻量分段：文字 + 计数，选中加粗变白 */
class FilterTabs(
    context: Context,
    labels: List<String>,
    initial: Int = 0,
    private val onSelect: (Int) -> Unit = {}
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12), 0, context.dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(Dimens.TAB_H))
    }
    private val ctx = context
    private val items = mutableListOf<TextView>()
    var index = initial
        private set

    init {
        labels.forEachIndexed { i, label ->
            val cell = ctx.para(label, Dimens.SMALL, R.color.sub).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
                setOnClickListener { select(i) }
            }
            root.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = ctx.dp(2)
            })
            items += cell
        }
        paint()
    }

    fun select(i: Int, notify: Boolean = true) {
        if (i == index) return
        index = i
        paint()
        if (notify) onSelect(i)
    }

    fun setLabels(labels: List<String>) {
        labels.forEachIndexed { i, label -> items.getOrNull(i)?.setTextIfChanged(label) }
        paint()
    }

    private fun paint() {
        items.forEachIndexed { i, cell ->
            val on = i == index
            cell.setTextColorIfChanged(ctx.color(if (on) R.color.text else R.color.sub))
            cell.typeface = if (on) MEDIUM else Typeface.DEFAULT
        }
    }
}

/** RecyclerView 行分隔线：1px，左侧留出内容缩进 */
class HairlineDecoration(private val insetStartDp: Int = 0) : RecyclerView.ItemDecoration() {
    private val paint = Paint().apply { isAntiAlias = false }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        paint.color = parent.context.color(R.color.stroke)
        val inset = parent.context.dp(insetStartDp).toFloat()
        val left = parent.paddingLeft + inset
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val params = child.layoutParams as? RecyclerView.LayoutParams ?: continue
            if (params.viewAdapterPosition == RecyclerView.NO_POSITION) continue
            val y = (child.bottom + params.bottomMargin).toFloat()
            c.drawRect(left, y - 1f, (parent.width - parent.paddingRight).toFloat(), y, paint)
        }
    }
}

/* ------------------------------ 弹层用 ------------------------------ */

fun Context.field(
    hint: String,
    initial: String,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    mono: Boolean = false
): EditText = EditText(this).apply {
    this.hint = hint
    setText(initial)
    setSelection(text.length)
    this.inputType = inputType
    isSingleLine = true
    textSize = Dimens.BODY
    minHeight = dp(42)
    setTextColor(color(R.color.text))
    setHintTextColor(color(R.color.sub))
    includeFontPadding = false
    background = rounded(color(R.color.surface2), 8)
    setPadding(dp(10), dp(9), dp(10), dp(9))
    if (mono) typeface = MONO
}

fun Context.multiline(hint: String, initial: String, minLines: Int = 8): EditText =
    field(hint, initial, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, mono = true).apply {
        isSingleLine = false
        gravity = Gravity.TOP or Gravity.START
        this.minLines = minLines
        setHorizontallyScrolling(false)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

fun Context.kvRow(label: String, value: String, mono: Boolean = true): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(0, dp(4), 0, dp(4))
        addView(para(label, Dimens.LABEL, R.color.sub).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(para(value, Dimens.SMALL, R.color.text).apply {
            if (mono) typeface = MONO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextIsSelectable(true)
        })
    }

fun Context.badge(text: String, color: Int): TextView = TextView(this).apply {
    this.text = text
    textSize = Dimens.LABEL
    gravity = Gravity.CENTER
    includeFontPadding = false
    setTextColor(color)
    background = rounded(
        Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)),
        7,
        Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
    )
    setPadding(dp(7), dp(2), dp(7), dp(2))
}

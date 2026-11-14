package io.github.w243420707.dnspool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

/* 纯代码建界面用的小工具，不依赖 Material，只用 AppCompat + 原生控件。 */

private const val ROW_VALUE_TAG = "ui_row_value"

fun Context.color(res: Int): Int = ContextCompat.getColor(this, res)

fun Context.dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

/** 同色系的低透明度版本，用于徽标底、浅描边 */
fun Context.soft(res: Int, alpha: Int = 36): Int {
    val c = color(res)
    return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
}

fun Context.rounded(fill: Int, radius: Int = 12, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(Math.max(1, dp(1) / 2), stroke)
    }

/** 圆角卡片：surface 底 + 1px 描边 + 12dp 圆角 */
fun Context.card(padding: Int = 14): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(color(R.color.surface), 12, color(R.color.stroke))
    clipToOutline = true
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

/** 左灰标签 + 右白值；值可用 row.rowValue()?.text 更新 */
fun Context.textRow(label: String, value: String): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(3), 0, dp(3))
    }
    row.addView(TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(color(R.color.sub))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    row.addView(TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(color(R.color.text))
        gravity = Gravity.END
        tag = ROW_VALUE_TAG
    })
    return row
}

fun LinearLayout.rowValue(): TextView? = findViewWithTag(ROW_VALUE_TAG) as? TextView

fun Context.sectionTitle(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 15f
    setTextColor(color(R.color.text))
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setPadding(0, dp(2), 0, dp(8))
}

fun Context.fieldLabel(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    setTextColor(color(R.color.sub))
    setPadding(0, dp(6), 0, dp(4))
}

fun Context.para(text: String, size: Float = 13f, colorRes: Int = R.color.text): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color(colorRes))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

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
    textSize = 13f
    minHeight = dp(42)
    setTextColor(color(R.color.text))
    setHintTextColor(color(R.color.sub))
    includeFontPadding = false
    background = rounded(color(R.color.surface2), 10)
    setPadding(dp(10), dp(9), dp(10), dp(9))
    if (mono) typeface = Typeface.MONOSPACE
}

fun Context.multiline(hint: String, initial: String, minLines: Int = 6): EditText =
    field(hint, initial, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, mono = true).apply {
        isSingleLine = false
        gravity = Gravity.TOP or Gravity.START
        this.minLines = minLines
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

fun Context.actionButton(text: String, primary: Boolean = false, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        stateListAnimator = null
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        background = rounded(if (primary) color(R.color.accent) else color(R.color.surface2), 10)
        setTextColor(if (primary) color(R.color.bg) else color(R.color.text))
        setOnClickListener { onClick() }
    }

/** 小圆角标签，颜色自带浅底与描边 */
fun Context.badge(text: String, color: Int): TextView = TextView(this).apply {
    this.text = text
    textSize = 11f
    gravity = Gravity.CENTER
    includeFontPadding = false
    setTextColor(color)
    background = rounded(
        Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)),
        9,
        Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
    )
    setPadding(dp(8), dp(3), dp(8), dp(3))
}

fun Context.hairline(): View = View(this).apply {
    setBackgroundColor(color(R.color.stroke))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        Math.max(1, dp(1) / 2)
    )
}

/** 带标签的输入框，标签在上、输入框在下，自动加进 parent */
fun Context.labeledField(
    parent: LinearLayout,
    label: String,
    initial: String,
    numeric: Boolean = false,
    hint: String = "",
    mono: Boolean = false
): EditText {
    parent.addView(fieldLabel(label))
    val edit = field(
        hint.ifBlank { label }, initial,
        if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT, mono
    )
    parent.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    return edit
}

/** 标题 + 说明 + 开关的一行 */
fun Context.switchRow(parent: LinearLayout, title: String, sub: String): Switch {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
    }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(para(title, 13f, R.color.text))
    col.addView(para(sub, 11f, R.color.sub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    val toggle = Switch(this).apply { isChecked = false }
    row.addView(toggle)
    parent.addView(row)
    return toggle
}

/** 两个等宽按钮一行 */
fun Context.buttonRow(left: Button, right: Button): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    addView(left, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
    addView(right, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
}

/** 分段切换：多个 TextView 拼成一条，选中项描边高亮 */
class Segment(
    context: Context,
    labels: List<String>,
    initial: Int,
    private val onChange: (Int) -> Unit = {}
) {
    val root = LinearLayout(context)
    private val items = mutableListOf<TextView>()
    var index = initial
        private set

    init {
        root.orientation = LinearLayout.HORIZONTAL
        root.background = context.rounded(context.color(R.color.surface2), 10)
        root.setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        labels.forEachIndexed { i, label ->
            val cell = context.para(label, 13f, R.color.sub).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                setOnClickListener { select(i) }
            }
            root.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            items += cell
        }
        paint(context)
    }

    fun select(i: Int, notify: Boolean = true) {
        if (i == index) return
        index = i
        paint(root.context)
        if (notify) onChange(i)
    }

    private fun paint(context: Context) {
        items.forEachIndexed { i, cell ->
            val on = i == index
            cell.setTextColor(context.color(if (on) R.color.text else R.color.sub))
            cell.background =
                if (on) context.rounded(context.color(R.color.surface), 8, context.color(R.color.stroke)) else null
            cell.typeface =
                if (on) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        }
    }
}

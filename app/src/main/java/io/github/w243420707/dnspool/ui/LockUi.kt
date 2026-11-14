package io.github.w243420707.dnspool.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.w243420707.dnspool.Dimens
import io.github.w243420707.dnspool.MEDIUM
import io.github.w243420707.dnspool.R
import io.github.w243420707.dnspool.color
import io.github.w243420707.dnspool.dp
import io.github.w243420707.dnspool.rounded
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 应用锁：只存盐 + 哈希，不存图案本身。
 * 忘记图案只能卸载重装（记录靠备注能重新认领），开启前会明确提示这一点。
 */
object PatternStore {
    private const val PREF = "dnspool_lock"
    private const val KEY_HASH = "hash"
    private const val KEY_SALT = "salt"
    const val MIN_LEN = 4

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getString(KEY_HASH, null) != null

    fun set(ctx: Context, pattern: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltText = Base64.getEncoder().encodeToString(salt)
        prefs(ctx).edit()
            .putString(KEY_SALT, saltText)
            .putString(KEY_HASH, hash(salt, pattern))
            .apply()
    }

    fun verify(ctx: Context, pattern: String): Boolean {
        val p = prefs(ctx)
        val saltText = p.getString(KEY_SALT, null) ?: return false
        val hash = p.getString(KEY_HASH, null) ?: return false
        return hash == hash(Base64.getDecoder().decode(saltText), pattern)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun hash(salt: ByteArray, pattern: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(pattern.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/**
 * 3×3 九宫格图案控件。跨点时如果中间那个点还没连，会自动补上（和系统图案锁一致）。
 */
class PatternView(context: Context, private val onComplete: (String) -> Unit) : View(context) {

    private val dots = Array(9) { PointF() }
    private val selected = mutableListOf<Int>()
    private var finger: PointF? = null
    private var error = false
    private var enabled = true
    private var cell = 0f
    private var dotR = 0f
    private var originX = 0f
    private var originY = 0f
    private var sizePx = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cap = context.dp(292)
        val size = minOf(w, h, cap)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sizePx = minOf(w, h).toFloat()
        originX = (w - sizePx) / 2f
        originY = (h - sizePx) / 2f
        val pad = sizePx * 0.13f
        cell = (sizePx - pad * 2f) / 2f
        dotR = sizePx * 0.030f
        ringPaint.strokeWidth = sizePx * 0.008f
        linePaint.strokeWidth = sizePx * 0.026f
        for (i in 0..8) {
            dots[i].set(originX + pad + (i % 3) * cell, originY + pad + (i / 3) * cell)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val accent = context.color(if (error) R.color.danger else R.color.accent)
        val sub = context.color(R.color.sub)

        linePaint.color = accent
        var prev: PointF? = null
        for (idx in selected) {
            val p = dots[idx]
            prev?.let { canvas.drawLine(it.x, it.y, p.x, p.y, linePaint) }
            prev = p
        }
        finger?.let { f ->
            prev?.let { canvas.drawLine(it.x, it.y, f.x, f.y, linePaint) }
        }

        for (i in 0..8) {
            val p = dots[i]
            val on = selected.contains(i)
            ringPaint.color = if (on) accent else context.color(R.color.stroke)
            canvas.drawCircle(p.x, p.y, dotR * 2.0f, ringPaint)
            dotPaint.color = if (on) accent else sub
            canvas.drawCircle(p.x, p.y, dotR, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                finger = PointF(event.x, event.y)
                pick(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                finger = PointF(event.x, event.y)
                pick(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finger = null
                if (selected.size >= PatternStore.MIN_LEN) {
                    val pattern = selected.joinToString("")
                    onComplete(pattern)
                } else {
                    flashError()
                }
                invalidate()
            }
        }
        return true
    }

    private fun pick(x: Float, y: Float) {
        val hit = dotR * 3.2f
        for (i in 0..8) {
            val p = dots[i]
            if (selected.contains(i)) continue
            val dx = x - p.x
            val dy = y - p.y
            if (dx * dx + dy * dy <= hit * hit) {
                // 跨过未连接的中间点时补上，避免同一个手势产生两种含义
                val last = selected.lastOrNull()
                if (last != null) {
                    val mid = midpoint(last, i)
                    if (mid >= 0 && !selected.contains(mid)) selected.add(mid)
                }
                selected.add(i)
                performClick()
                invalidate()
                return
            }
        }
    }

    private fun midpoint(a: Int, b: Int): Int {
        val ax = a % 3; val ay = a / 3
        val bx = b % 3; val by = b / 3
        if ((ax + bx) % 2 != 0 || (ay + by) % 2 != 0) return -1
        val mx = (ax + bx) / 2; val my = (ay + by) / 2
        return my * 3 + mx
    }

    fun reset() {
        selected.clear()
        error = false
        invalidate()
    }

    fun setInteractive(on: Boolean) {
        enabled = on
        if (!on) reset()
    }

    fun flashError() {
        error = true
        invalidate()
        postDelayed({
            error = false
            selected.clear()
            invalidate()
        }, 420)
    }
}

/**
 * 锁屏浮层：VERIFY 单次绘制；SETUP 需要画两次确认。
 * 连续 5 次错误冷却 30 秒，避免被随手试开。
 */
class LockOverlay(context: Context) : FrameLayout(context) {

    private enum class Mode { VERIFY, SETUP }

    private val titleView: TextView
    private val subtitleView: TextView
    private val errorView: TextView
    private val hintView: TextView
    private val pad: PatternView

    private var mode = Mode.VERIFY
    private var onVerified: (() -> Unit)? = null
    private var setupFirst: String? = null
    private var fails = 0
    private var cooldownUntil = 0L

    init {
        setBackgroundColor(context.color(R.color.bg))
        isClickable = true
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(32), context.dp(48), context.dp(32), context.dp(32))
        }
        titleView = TextView(context).apply {
            textSize = 17f
            typeface = MEDIUM
            setTextColor(context.color(R.color.text))
            gravity = Gravity.CENTER
        }
        subtitleView = TextView(context).apply {
            textSize = Dimens.SMALL
            setTextColor(context.color(R.color.sub))
            gravity = Gravity.CENTER
            setPadding(0, context.dp(6), 0, 0)
        }
        pad = PatternView(context) { pattern -> handle(pattern) }
        errorView = TextView(context).apply {
            textSize = Dimens.SMALL
            setTextColor(context.color(R.color.danger))
            gravity = Gravity.CENTER
            setPadding(0, context.dp(4), 0, 0)
            visibility = GONE
        }
        hintView = TextView(context).apply {
            textSize = Dimens.LABEL
            setTextColor(context.color(R.color.sub))
            gravity = Gravity.CENTER
            alpha = 0.8f
        }

        column.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        column.addView(subtitleView, LinearLayout.LayoutParams(-1, -2))
        // 正方形控件放进 weight 容器里再居中，否则会被顶到上边、下面留一大片空白
        val padHost = FrameLayout(context)
        padHost.addView(pad, LayoutParams(-2, -2, Gravity.CENTER))
        column.addView(padHost, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(12) })
        column.addView(errorView, LinearLayout.LayoutParams(-1, -2))
        column.addView(hintView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
        addView(column, LayoutParams(-1, -1))
        visibility = GONE
    }

    fun showVerify(onSuccess: () -> Unit) {
        mode = Mode.VERIFY
        onVerified = onSuccess
        setupFirst = null
        fails = 0
        cooldownUntil = 0
        titleView.text = "图案解锁"
        subtitleView.text = "画出你的图案"
        hintView.text = "忘记图案只能卸载重装，记录会靠备注重新认领"
        errorView.visibility = GONE
        pad.setInteractive(true)
        pad.reset()
        visibility = VISIBLE
        bringToFront()
    }

    fun showSetup(onSaved: () -> Unit) {
        mode = Mode.SETUP
        onVerified = onSaved
        setupFirst = null
        fails = 0
        cooldownUntil = 0
        titleView.text = "设置应用锁"
        subtitleView.text = "画出至少 ${PatternStore.MIN_LEN} 个点"
        hintView.text = "忘记图案只能卸载重装应用"
        errorView.visibility = GONE
        pad.setInteractive(true)
        pad.reset()
        visibility = VISIBLE
        bringToFront()
    }

    fun hide() {
        visibility = GONE
        pad.setInteractive(true)
        pad.reset()
    }

    private fun handle(pattern: String) {
        if (System.currentTimeMillis() < cooldownUntil) return
        when (mode) {
            Mode.VERIFY -> {
                if (PatternStore.verify(context, pattern)) {
                    hide()
                    onVerified?.invoke()
                } else {
                    fail("图案不对")
                }
            }
            Mode.SETUP -> {
                val first = setupFirst
                if (first == null) {
                    if (pattern.length < PatternStore.MIN_LEN) {
                        fail("至少连 4 个点")
                        return
                    }
                    setupFirst = pattern
                    subtitleView.text = "再画一次确认"
                    errorView.visibility = GONE
                    pad.reset()
                } else if (pattern == first) {
                    PatternStore.set(context, pattern)
                    setupFirst = null
                    hide()
                    onVerified?.invoke()
                } else {
                    setupFirst = null
                    subtitleView.text = "两次不一致，重新画一次"
                    fail("两次绘制不一致")
                }
            }
        }
    }

    private fun fail(message: String) {
        fails++
        if (fails >= 5) {
            val wait = 30_000L
            cooldownUntil = System.currentTimeMillis() + wait
            errorView.setTextColor(context.color(R.color.danger))
            errorView.text = "错误次数过多，30 秒后再试"
            errorView.visibility = VISIBLE
            pad.setInteractive(false)
            postDelayed({
                fails = 0
                cooldownUntil = 0
                pad.setInteractive(true)
                errorView.visibility = GONE
                subtitleView.text = "画出你的图案"
            }, wait)
        } else {
            errorView.setTextColor(context.color(R.color.danger))
            errorView.text = "$message（还可试 ${5 - fails} 次）"
            errorView.visibility = VISIBLE
            pad.flashError()
        }
    }

    fun verifyPattern(pattern: String, onOk: () -> Unit) {
        if (PatternStore.verify(context, pattern)) onOk() else fail("图案不对")
    }
}

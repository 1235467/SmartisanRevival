package com.smartisanrevival.bang.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.smartisanrevival.bang.service.BangAccessibilityService

/**
 * 设置页里那块「按住记下你的拇指」区域。
 * 接触面积走 PressWatcher 的 evdev TOUCH_MAJOR，和炸词用的是同一个数。
 */
class ThumbPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onSaved: ((major: Int) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8E6E4.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A87F2.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A87F2.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(16f)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
    }

    private val box = RectF()
    private var pressing = false
    private var liveMajor = 0
    private var peak = 0
    private var downAt = 0L
    private var caption = "用平时按屏幕的那个拇指，按住这里"
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!pressing) return
            val m = BangAccessibilityService.instance?.peekMajor() ?: 0
            if (m > peak) peak = m
            liveMajor = peak
            invalidate()
            main.postDelayed(this, 40)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(168f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val r = dp(18f)
        box.set(dp(8f), dp(8f), width - dp(8f), height - dp(8f))
        canvas.drawRoundRect(box, r, r, fill)
        val cx = width / 2f
        val cy = height / 2f - dp(8f)
        val maxR = dp(48f)
        val frac = (liveMajor / 255f).coerceIn(0.08f, 1f)
        blob.alpha = if (pressing) 180 else 70
        canvas.drawCircle(cx, cy, maxR * frac, blob)
        canvas.drawCircle(cx, cy, maxR, ring)
        canvas.drawText(caption, cx, height - dp(22f), if (pressing) label else hint)
        if (liveMajor > 0) {
            canvas.drawText("面积 $liveMajor / 255", cx, dp(28f), label)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val svc = BangAccessibilityService.instance
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressing = true
                peak = 0
                liveMajor = 0
                downAt = SystemClock.uptimeMillis()
                caption = "按住，别抬起来"
                svc?.setCalibrating(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                main.removeCallbacks(tick)
                main.post(tick)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val m = svc?.peekMajor() ?: 0
                if (m > peak) peak = m
                liveMajor = peak
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressing = false
                main.removeCallbacks(tick)
                svc?.setCalibrating(false)
                val held = SystemClock.uptimeMillis() - downAt
                val m = maxOf(peak, svc?.peekMajor() ?: 0)
                liveMajor = m
                if (event.actionMasked == MotionEvent.ACTION_UP && held >= 400 && m >= 20) {
                    caption = "已记下你的拇指（$m）"
                    onSaved?.invoke(m)
                } else {
                    caption = "按住久一点，用拇指肚"
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun showSaved(major: Int) {
        liveMajor = major
        caption = if (major > 0) "已记下你的拇指（$major）" else "用平时按屏幕的那个拇指，按住这里"
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

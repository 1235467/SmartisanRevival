package com.smartisanrevival.onestep.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.smartisanrevival.onestep.OneStepPrefs

/**
 * 上角热区：不可见、可触摸的小窗（宽 20dp × 高 200dp，状态栏以下），
 * 注册系统手势排除区，使角落内划不触发返回手势；向内滑动 ≥24dp 即呼出侧栏。
 *
 * 代价（已与用户确认）：热区矩形内的触摸被热区吃掉；热区外返回手势不受影响。
 */
class HotZoneController(
    private val service: AccessibilityService,
    private val onTrigger: () -> Unit
) {

    companion object {
        private const val TAG = "OneStep"
        private const val WIDTH_DP = 28
        private const val HEIGHT_DP = 200
        private const val TRIGGER_DP = 24
        private const val COOLDOWN_MS = 800L
    }

    private val wm by lazy { service.getSystemService(WindowManager::class.java) }
    private var view: View? = null
    private var lastTrigger = 0L

    fun destroy() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    fun flashLocation() {
        val v = view ?: return
        v.background = android.graphics.drawable.ColorDrawable(0x33FF4444)
        v.postDelayed({ v.background = null }, 1200)
    }

    init {
        val railRight = OneStepPrefs.sideRight(service)
        val dm = service.resources.displayMetrics
        val density = dm.density
        val w = (WIDTH_DP * density).toInt()
        val h = (HEIGHT_DP * density).toInt()
        val statusBar = statusBarHeight()

        val root = object : View(service) {
            private var downX = 0f
            private var downY = 0f
            private var fired = false

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; fired = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (fired) return true
                        val inward = if (railRight) downX - e.rawX else e.rawX - downX
                        if (inward >= TRIGGER_DP * density) {
                            fired = true
                            tryTrigger()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return super.onTouchEvent(e)
            }
        }

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE 是生死线：缺了它这个常驻窗会抢走系统输入焦点，键盘打不进字
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = (if (railRight) Gravity.END else Gravity.START) or Gravity.TOP
            y = statusBar + (8 * density).toInt()
        }

        try {
            wm.addView(root, lp)
            view = root
            // 排除返回手势：排除区 = 热区自身 bounds（Android 11 上每边预算 200dp，恰好用满）
            root.post {
                try {
                    val r = Rect(0, 0, root.width, root.height)
                    root.systemGestureExclusionRects = listOf(r)
                } catch (e: Exception) {
                    Log.w(TAG, "gesture exclusion failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "hotzone add failed", e)
        }
    }

    private fun tryTrigger() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTrigger < COOLDOWN_MS) return
        lastTrigger = now
        Log.i(TAG, "hotzone triggered")
        onTrigger()
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else (24 * service.resources.displayMetrics.density).toInt()
    }
}

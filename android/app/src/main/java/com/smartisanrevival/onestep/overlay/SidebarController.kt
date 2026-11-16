package com.smartisanrevival.onestep.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.smartisanrevival.onestep.OneStepPrefs
import com.smartisanrevival.onestep.anim.OneStepAnim
import com.smartisanrevival.onestep.service.OneStepAccessibilityService

/**
 * 一步窗口总控（对应官方 SidebarController，Apache-2.0 语义重写）。
 *
 * 两个窗口（官方三个窗的原因是 framework 窗权，我们把内容面板合并进标题条窗）：
 *  - 侧栏窗：宽 railW，全高，栏侧；NOT_TOUCH_MODAL → 栏外触摸穿透给应用（等价原版「应用区可交互」）
 *  - 标题条窗：宽 = 屏宽 - railW，高 48+72dp，应用区顶；NOT_TOUCH_MODAL 同理
 *
 * 进场：侧栏横移滑入 150ms + fade（CUBIC_OUT），条底 fade 200ms 延迟 100ms；
 *       标题条自顶落下 200ms（CUBIC_OUT）+ fade。出场反向 200ms。
 */
class SidebarController(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "OneStep"
    }

    private val wm by lazy { service.getSystemService(WindowManager::class.java) }
    private val main = Handler(Looper.getMainLooper())

    private var railView: SideRailView? = null
    private var titleView: TitleBarView? = null
    private var contentView: ContentView? = null
    private var animating = false

    val isVisible: Boolean
        get() = railView != null

    val railWidthDp: Int get() = 66

    fun show() {
        if (railView != null || animating) return
        if (!OneStepPrefs.enabled(service)) return
        showWithCard(null)
    }

    /**
     * 收缩卡片参数留位：原版 OneStep 1.0 的「画面收缩到左下角」是 framework 活窗口，
     * 无 root 路线已实测四条全堵（见 docs/notes/onestep-spec.md）。
     * 截图静态卡方案被否，此参数保留，等 root/LSPosed 路线再接活窗口。
     */
    private fun showWithCard(@Suppress("UNUSED_PARAMETER") shrink: android.graphics.Bitmap?) {
        if (railView != null || animating) return
        if (!OneStepPrefs.enabled(service)) return
        val svc = service as OneStepAccessibilityService
        val railRight = OneStepPrefs.sideRight(service)
        val metrics = service.resources.displayMetrics
        val railW = dp(railWidthDp)
        val barW = metrics.widthPixels - railW
        val statusBar = statusBarHeight()

        val rail = SideRailView(service, railRight, { svc.foregroundPackage }, { dismiss() })
        val title = TitleBarView(service, railRight,
            onExit = { dismiss() }, onSettings = { openSettings() },
            onOpenPanel = { showPanel(it) })

        val railLp = WindowManager.LayoutParams(
            railW, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE：所有悬浮窗一律不可聚焦，否则抢系统焦点毁掉键盘
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (railRight) Gravity.END or Gravity.TOP else Gravity.START or Gravity.TOP
            y = statusBar
        }

        val titleLp = WindowManager.LayoutParams(
            barW, dp(120),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (railRight) Gravity.START or Gravity.TOP else Gravity.END or Gravity.TOP
            y = statusBar
        }

        try {
            // 进场前先摆到屏幕外
            rail.translationX = (if (railRight) railW else -railW).toFloat()
            rail.alpha = 0f
            title.translationY = -dp(120).toFloat()
            title.alpha = 0f
            wm.addView(rail, railLp)
            wm.addView(title, titleLp)
            railView = rail
            titleView = title
        } catch (e: Exception) {
            Log.e(TAG, "sidebar add failed", e)
            removeViewsNow()
            return
        }

        animating = true
        // 侧栏滑入：150ms 横移 CUBIC_OUT + fade 150ms；底色 fade 200ms 延迟 100ms
        val railAnim = ObjectAnimator.ofFloat(rail, View.TRANSLATION_X, 0f).apply {
            duration = OneStepAnim.SIDEBAR_IN
            interpolator = OneStepAnim.CubicOut()
        }
        val railFade = ObjectAnimator.ofFloat(rail, View.ALPHA, 1f).apply {
            duration = OneStepAnim.SIDEBAR_IN
            interpolator = LinearInterpolator()
        }
        rail.postDelayed({
            if (railView === rail) rail.setBackgroundColor(TitleBarView.SIDEBAR_BG)
        }, 100)
        // 标题条落下：200ms CUBIC_OUT + fade
        val titleAnim = ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, 0f).apply {
            duration = OneStepAnim.TOPBAR_IN
            interpolator = OneStepAnim.CubicOut()
        }
        val titleFade = ObjectAnimator.ofFloat(title, View.ALPHA, 1f).apply {
            duration = OneStepAnim.TOPBAR_IN
        }
        AnimatorSet().apply {
            playTogether(railAnim, railFade, titleAnim, titleFade)
            start()
        }
        main.postDelayed({
            animating = false
            rail.translationX = 0f
            title.translationY = 0f
        }, OneStepAnim.TOPBAR_IN)
    }

    fun dismiss() {
        val rail = railView ?: return
        val title = titleView ?: return
        if (animating) return
        val railRight = OneStepPrefs.sideRight(service)
        val railW = dp(railWidthDp)
        animating = true
        val railOut = ObjectAnimator.ofFloat(
            rail, View.TRANSLATION_X, (if (railRight) railW else -railW).toFloat()
        ).apply { duration = OneStepAnim.SIDEBAR_OUT; interpolator = OneStepAnim.CubicOut() }
        val railFade = ObjectAnimator.ofFloat(rail, View.ALPHA, 0f).apply { duration = OneStepAnim.SIDEBAR_OUT }
        val titleOut = ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, -dp(120).toFloat()).apply {
            duration = OneStepAnim.SIDEBAR_OUT; interpolator = OneStepAnim.CubicOut()
        }
        val titleFade = ObjectAnimator.ofFloat(title, View.ALPHA, 0f).apply { duration = OneStepAnim.SIDEBAR_OUT }
        AnimatorSet().apply {
            playTogether(railOut, railFade, titleOut, titleFade)
            start()
        }
        main.postDelayed({
            removeViewsNow()
            animating = false
        }, OneStepAnim.SIDEBAR_OUT)
    }

    /** 设置页变化（换边/开关）后重建 */
    fun refresh() {
        if (railView == null) return
        dismiss()
        main.postDelayed({ show() }, OneStepAnim.SIDEBAR_OUT + 50)
    }

    /** 内容面板：全屏 dim 窗，官方 alpha 300ms 进出。全屏窗必须保证用完即删，不能残留。 */
    fun showPanel(which: Int) {
        val wasSameOpen = panelOpen && lastPanel == which
        // 清场：旧面板直接从 WindowManager 摘掉（摘窗永远不走 View.parent，那会失败）
        contentView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        contentView = null
        panelOpen = false
        if (wasSameOpen) return  // 开着同一个面板再点 = 关闭
        val cv = ContentView(service)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm.addView(cv, lp)
            cv.onClosed = {
                try { wm.removeView(cv) } catch (_: Exception) {}
                if (contentView === cv) {
                    contentView = null
                    panelOpen = false
                }
            }
            cv.showPanel(which)
            contentView = cv
            panelOpen = true
            lastPanel = which
        } catch (e: Exception) {
            Log.e(TAG, "content panel add failed", e)
        }
    }

    private var lastPanel = -1
    private var panelOpen = false

    private fun closePanel() {
        val cv = contentView ?: return
        cv.dismiss()  // 动画后由 onClosed 真正摘窗
        // 兜底：onClosed 若未触发（动画被清等），500ms 后强制摘
        main.postDelayed({
            if (contentView === cv) {
                try { wm.removeView(cv) } catch (_: Exception) {}
                contentView = null
                panelOpen = false
            }
        }, 500)
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        removeViewsNow()
        // 直接摘窗，不走动画——任何路径都不允许全屏窗残留
        contentView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        contentView = null
        panelOpen = false
        animating = false
    }

    private fun removeViewsNow() {
        railView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        titleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        railView = null
        titleView = null
        // 侧栏整体收起时内容面板跟着收
        closePanel()
    }

    private fun openSettings() {
        try {
            val i = android.content.Intent().apply {
                setClassName(service, "com.smartisanrevival.bang.MainActivity")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "open settings failed", e)
        }
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density + 0.5f).toInt()

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id)
        else (24 * service.resources.displayMetrics.density).toInt()
    }
}

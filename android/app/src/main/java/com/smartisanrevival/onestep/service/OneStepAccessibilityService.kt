package com.smartisanrevival.onestep.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import com.smartisanrevival.onestep.OneStepPrefs
import com.smartisanrevival.onestep.overlay.HotZoneController
import com.smartisanrevival.onestep.overlay.SidebarController
import com.smartisanrevival.onestep.press.ThumbPullWatcher

/**
 * 一步引擎：承载侧栏悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）与触发层。
 *
 * 与大爆炸引擎是两个独立的无障碍服务，互不干扰；本服务不解析文字内容，
 * 只关心「当前前台应用是什么」（给 "+" 槽位钉应用用）。
 */
class OneStepAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OneStep"
        private const val RETRY_DELAY_MS = 3000L
        private const val RETRY_MAX = 40

        @Volatile
        var instance: OneStepAccessibilityService? = null
            private set
    }

    val overlay by lazy { SidebarController(this) }
    private var hotzone: HotZoneController? = null
    private var thumbWatcher: ThumbPullWatcher? = null
    private var retryCount = 0

    /** 当前前台应用包名，"+" 槽位钉应用时用 */
    @Volatile
    var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected")
        if (OneStepPrefs.enabled(this)) startTriggers()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            // 输入法/系统弹层的窗口状态会混进来，只记正常应用包名
            if (!pkg.isNullOrBlank() && pkg != "com.android.systemui" &&
                !pkg.startsWith("com.smartisanrevival")
            ) {
                foregroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopTriggers()
        instance = null
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    fun startTriggers() {
        startHotzone()
        if (OneStepPrefs.thumbPullEnabled(this)) startThumbPull()
    }

    fun stopTriggers() {
        hotzone?.destroy()
        hotzone = null
        thumbWatcher?.stop()
        thumbWatcher = null
        overlay.shutdown()
    }

    private fun startHotzone() {
        if (!OneStepPrefs.hotzoneEnabled(this)) return
        if (hotzone != null) return
        try {
            hotzone = HotZoneController(this) { toggleSidebar() }
        } catch (e: Exception) {
            Log.e(TAG, "hotzone init failed", e)
        }
    }

    private fun startThumbPull() {
        if (thumbWatcher != null) return
        val watcher = ThumbPullWatcher(this, OneStepPrefs.thumbAreaLevel(this)) { toggleSidebar() }
        thumbWatcher = watcher
        retryCount = 0
        tryBindThumb(watcher)
    }

    private fun tryBindThumb(watcher: ThumbPullWatcher) {
        try {
            watcher.start()
            Log.i(TAG, "thumb pull watcher bound")
        } catch (e: Exception) {
            // Shizuku 常常比无障碍晚就绪（尤其重启后），照 bang 的节奏退避重试
            if (retryCount++ < RETRY_MAX) {
                mainHandler.postDelayed({ if (thumbWatcher === watcher) tryBindThumb(watcher) }, RETRY_DELAY_MS)
            } else {
                Log.e(TAG, "thumb pull gave up after $retryCount retries", e)
            }
        }
    }

    private val mainHandler get() = android.os.Handler(android.os.Looper.getMainLooper())

    fun toggleSidebar() {
        if (overlay.isVisible) overlay.dismiss() else overlay.show()
    }

    fun showSidebar() = overlay.show()
    fun dismissSidebar() = overlay.dismiss()

    /** 调试：闪红标出热区位置 */
    fun flashHotzone() {
        hotzone?.flashLocation() ?: android.widget.Toast.makeText(
            this, "热区未启用", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** 实验：让当前应用进入系统分屏（活窗口）。返回是否提交成功。 */
    fun trySplitScreen(): Boolean = try {
        performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
    } catch (e: Exception) {
        android.util.Log.w("OneStep", "split screen action failed", e)
        false
    }

    /**
     * 截屏（「画面收缩到左下角」卡片用）。失败返回 null（黑屏限制 App 等），调用方静默降级。
     */
    fun takeScreenShotCompat(onResult: (android.graphics.Bitmap?) -> Unit) {
        val exec = java.util.concurrent.Executor { r ->
            android.os.Handler(android.os.Looper.getMainLooper()).post(r)
        }
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY, exec,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )
                        val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        onResult(soft)
                    }

                    override fun onFailure(errorCode: Int) {
                        android.util.Log.w("OneStep", "screenshot failed: $errorCode")
                        onResult(null)
                    }
                }
            )
        } catch (e: Exception) {
            android.util.Log.w("OneStep", "screenshot api error", e)
            onResult(null)
        }
    }
}

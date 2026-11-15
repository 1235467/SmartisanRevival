package com.smartisanrevival.bang.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * 炸图片框选层的窗口生命周期。窗口参数照抄 SidebarController.showPanel：
 * TYPE_ACCESSIBILITY_OVERLAY + NOT_FOCUSABLE（不抢焦点但收触摸）+ 全屏 NO_LIMITS。
 * 只在主线程 addView / removeView。
 */
class CropOverlayController(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "BangOcr"
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: CropSelectView? = null

    fun show(
        shot: Bitmap,
        initialRect: Rect,
        onConfirm: (Rect) -> Unit,
        onCancel: () -> Unit
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "show must run on main" }
        if (view != null) dismissNow()
        val v = CropSelectView(service)
        v.setScreenshot(shot)
        v.setSelection(initialRect)
        v.onConfirm = onConfirm
        v.onCancel = onCancel
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.TOP }
        try {
            wm.addView(v, lp)
            view = v
            Log.i(TAG, "crop overlay shown rect=$initialRect")
        } catch (t: Throwable) {
            Log.e(TAG, "crop overlay add failed", t)
            onCancel()
        }
    }

    fun setBusy(busy: Boolean) {
        main.post { view?.setBusy(busy) }
    }

    /** 可从任意线程调；实际移除落到主线程。 */
    fun dismiss() {
        main.post { dismissNow() }
    }

    private fun dismissNow() {
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
            Log.i(TAG, "crop overlay dismissed")
        } catch (_: Exception) {
        }
    }
}

package com.smartisanrevival.bang.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import com.smartisanrevival.bang.model.BangResult

class BangOverlayController(private val service: AccessibilityService) {

    private var panel: BangPanelView? = null

    fun show(result: BangResult, onClosed: () -> Unit) {
        if (panel != null) return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val view = BangPanelView(service, result) {
            removePanel(wm)
            onClosed()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.TOP }
        try {
            wm.addView(view, lp)
            panel = view
        } catch (e: Exception) {
            android.util.Log.e("Bang", "overlay add failed", e)
        }
    }

    fun dismiss(immediate: Boolean = false) {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val v = panel ?: return
        if (immediate) {
            removePanel(wm)
        } else {
            v.animate().alpha(0f).setDuration(120).withEndAction { removePanel(wm) }.start()
        }
    }

    private fun removePanel(wm: WindowManager) {
        panel?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        panel = null
    }
}

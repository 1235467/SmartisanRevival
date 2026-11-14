package com.smartisanrevival.probe

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 第0期体检探针：不实现任何功能，只回答四个问题
 * 1. 无障碍能否活过 24h（心跳 + 连接/断开事件）
 * 2. takeScreenshot 是否可用、耗时多少
 * 3. 长按文本时有无 selection / window_state 事件（大爆炸专项 4 问）
 * 4. 截图证据落盘
 */
class ProbeAccessibilityService : AccessibilityService() {

    companion object {
        const val HEARTBEAT_MS = 5 * 60 * 1000L

        @Volatile
        var instance: ProbeAccessibilityService? = null
            private set

        fun logFile(ctx: android.content.Context): File? =
            ctx.getExternalFilesDir(null)?.let { File(it, "probe_log.txt") }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private var lastSelectionAt = 0L
    private var lastContentLogAt = 0L
    private var lastContentPkg = ""
    private var lastWindowStateAt = 0L
    private var shotSeq = 0

    private val heartbeat = object : Runnable {
        override fun run() {
            log("HB,alive")
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        instance = this
        log(
            "SERVICE,connected,api=${android.os.Build.VERSION.SDK_INT}," +
                "device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}"
        )
        handler.post(heartbeat)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        log("SERVICE,unbind")
        instance = null
        handler.removeCallbacks(heartbeat)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = log("SERVICE,interrupt")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: "?"
        val now = SystemClock.uptimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val gap = if (lastSelectionAt == 0L) -1 else now - lastSelectionAt
                log("EVT,selection,$pkg,${event.className},gapMs=$gap")
                lastSelectionAt = now
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                log("EVT,window_state,$pkg,${event.className}")
                lastWindowStateAt = now
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 高频事件限流：同包 2s 内只记一条
                if (pkg != lastContentPkg || now - lastContentLogAt > 2000) {
                    log("EVT,content_changed,$pkg,${event.className}")
                    lastContentPkg = pkg
                    lastContentLogAt = now
                }
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ->
                log("EVT,long_click,$pkg,${event.className}")
        }
    }

    /** 距最近一次 window_state 事件的 uptime，用于"菜单出现→遮罩盖住"延迟测量 */
    fun msSinceLastWindowEvent(): Long =
        if (lastWindowStateAt == 0L) -1 else SystemClock.uptimeMillis() - lastWindowStateAt

    /** 截图测试：耗时/结果写日志，图片落盘为证据 */
    fun testScreenshot(cb: (String) -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val dur = SystemClock.uptimeMillis() - t0
                    var detail = "ok,${dur}ms"
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    if (hw != null) {
                        detail += ",${hw.width}x${hw.height}"
                        try {
                            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                            val f = File(getExternalFilesDir(null), "shot_${++shotSeq}.png")
                            FileOutputStream(f).use { soft.compress(Bitmap.CompressFormat.PNG, 50, it) }
                            detail += ",saved=${f.name}"
                        } catch (e: Exception) {
                            detail += ",saveFail=${e.message}"
                        }
                    }
                    result.hardwareBuffer.close()
                    log("SCREENSHOT,$detail")
                    cb(detail)
                }

                override fun onFailure(errorCode: Int) {
                    val dur = SystemClock.uptimeMillis() - t0
                    log("SCREENSHOT,fail,${dur}ms,code=$errorCode")
                    cb("失败 ${dur}ms code=$errorCode")
                }
        }
        // 注意：API 实测返回 void（不是 boolean），结果全走 callback 的 onSuccess/onFailure
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
    }

    fun logClear() {
        try {
            logFile(this)?.writeText("${fmt.format(Date())},LOG,cleared\n")
        } catch (_: Exception) {}
    }

    private fun log(line: String) {
        try {
            logFile(this)?.appendText("${fmt.format(Date())},$line\n")
        } catch (_: Exception) {}
        Log.d("Probe", line)
    }
}

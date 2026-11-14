package com.smartisanrevival.probe

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun btn(text: String, action: () -> Unit) {
            box.addView(Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            })
        }

        status = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        box.addView(status)

        btn("① 开无障碍（若被拒先走③受限设置）") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("② 悬浮窗权限") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        btn("③ 应用详情（找到「允许受限设置」）") {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
        btn("④ 测截图（需无障碍已开）") {
            val svc = ProbeAccessibilityService.instance
            if (svc == null) toast("无障碍服务未连接，先开启")
            else svc.testScreenshot { runOnUiThread { toast("截图: $it") } }
        }
        btn("⑤ 测悬浮窗（系统门 + Flyme 门）") { testOverlay() }

        logView = TextView(this).apply { textSize = 11f }
        box.addView(logView)
        btn("清空日志") { ProbeAccessibilityService.instance?.logClear(); toast("已清") }

        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun testOverlay() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                toast("系统悬浮窗权限未给，先走②")
                return
            }
            val tv = TextView(this).apply {
                text = " 悬浮窗测试 OK，3 秒后移除 "
                setBackgroundColor(0xFF3366CC.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(40, 24, 40, 24)
            }
            windowManager.addView(
                tv,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
            )
            toast("悬浮窗显示成功——Flyme 第二道门也是开的")
            handler.postDelayed({ try { windowManager.removeView(tv) } catch (_: Exception) {} }, 3000)
        } catch (e: Exception) {
            toast("悬浮窗失败: ${e.javaClass.simpleName} ${e.message}（Flyme 第二道门没开）")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.postDelayed(refresh, 3000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun refreshStatus() {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
        val svcConnected = ProbeAccessibilityService.instance != null
        val installSrc = try {
            packageManager.getInstallSourceInfo(packageName).installingPackageName ?: "null（adb 安装）"
        } catch (e: Exception) {
            "?"
        }
        status.text = listOf(
            "无障碍开启: $enabled（服务连接: $svcConnected）",
            "系统悬浮窗: ${Settings.canDrawOverlays(this)}",
            "安装来源: $installSrc",
            "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}"
        ).joinToString("\n")

        val f = ProbeAccessibilityService.logFile(this)
        val text = try {
            f?.takeIf { it.exists() }?.readText()
        } catch (_: Exception) {
            null
        }
        logView.text = if (text.isNullOrBlank()) {
            "暂无日志"
        } else {
            val lines = text.trim().split("\n")
            lines.takeLast(12).joinToString("\n") + "\n[共 ${lines.size} 行] " + (f?.absolutePath ?: "")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

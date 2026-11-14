package com.smartisanrevival.bang

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.smartisanrevival.bang.service.BangAccessibilityService
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var status: TextView

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

        btn("① 打开无障碍「大爆炸引擎」") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("② 启用拇指按压（需已装 Shizuku）") { enableShizuku() }
        btn("③ 关闭拇指按压") {
            BangPrefs.setPressEnabled(this, false)
            BangAccessibilityService.instance?.stopPressWatcher()
            toast("已关闭，日常长按恢复系统自己的行为")
            refresh()
        }
        btn("④ 手动炸一次（本页文字，调试用）") {
            val svc = BangAccessibilityService.instance
            if (svc == null) toast("先走①打开无障碍")
            else svc.triggerManual()
        }

        val tip = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            text = """
                大爆炸 v1 · 拇指按压

                1. 安装并启动 Shizuku（酷安 / GitHub moe.shizuku）
                   用「无线调试」激活，不用 root
                2. 打开无障碍「大爆炸引擎」
                3. 点上面②授权

                之后：用拇指肚按住文字约半秒即炸（指尖轻点、短按不会炸）。
                按在按钮、空白处不会炸，系统长按菜单照常。

                不会再因为选词、光标、弹窗自己跳出来。
            """.trimIndent()
        }
        box.addView(tip)
        setContentView(ScrollView(this).apply { addView(box) })
        Shizuku.addRequestPermissionResultListener(listener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(listener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
        val connected = BangAccessibilityService.instance != null
        val shizuku = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        status.text = listOf(
            "无障碍: ${if (enabled) "开" else "关"}（服务${if (connected) "已连接" else "未连接"}）",
            "Shizuku: ${if (shizuku) "就绪" else "未安装/未启动"}",
            "拇指按压: ${if (BangPrefs.pressEnabled(this)) "已启用" else "关闭"}",
            "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}"
        ).joinToString("\n")
    }

    private fun enableShizuku() {
        val ping = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        if (!ping) {
            toast("先安装并启动 Shizuku（无线调试激活）")
            return
        }
        val granted = try { Shizuku.checkSelfPermission() == 0 } catch (_: Exception) { false }
        if (granted) onShizukuGranted()
        else Shizuku.requestPermission(114)
    }

    private val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == 0) onShizukuGranted() else toast("Shizuku 授权被拒绝")
    }

    private fun onShizukuGranted() {
        BangPrefs.setPressEnabled(this, true)
        val svc = BangAccessibilityService.instance
        if (svc == null) {
            toast("授权成功。再走①打开无障碍，拇指按压就会开始工作")
        } else {
            try {
                svc.startPressWatcherIfReady()
                toast("拇指按压已启用：按住文字 0.4 秒即炸")
            } catch (e: Exception) {
                toast("启动失败：${e.message}")
            }
        }
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

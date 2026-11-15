package com.smartisanrevival.bang

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import com.smartisanrevival.bang.service.BangAccessibilityService
import com.smartisanrevival.onestep.OneStepPrefs
import com.smartisanrevival.onestep.service.OneStepAccessibilityService
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
        btn("② 安装/授权 Shizuku（拇指按压必须，内置包）") {
            if (shizukuInstalled()) enableShizuku() else installShizuku()
        }
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

        val padHint = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            text = "每个拇指不一样。用你平时按屏幕的那个拇指，按住下面这块约半秒，记下面积。没记下之前门槛偏严，轻点不会炸。"
            setPadding(0, pad, 0, pad / 2)
        }
        box.addView(padHint)
        val thumbPad = com.smartisanrevival.bang.overlay.ThumbPadView(this)
        thumbPad.showSaved(BangPrefs.thumbMajor(this))
        thumbPad.onSaved = { major ->
            BangPrefs.setThumbMajor(this, major)
            BangAccessibilityService.instance?.applyThumbThreshold()
            toast("已记下拇指面积 $major。之后要用差不多这么大的接触面按住才炸")
            refresh()
        }
        box.addView(thumbPad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ===== 一步 OneStep =====
        btn("⑤ 打开无障碍「一步引擎」") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("⑥ 开启一步（侧栏 + 角落热区）") {
            OneStepPrefs.setEnabled(this, true)
            OneStepAccessibilityService.instance?.startTriggers()
            toast("一步已开启：右上角向内划出侧栏；热区位置可点⑩闪现")
            refresh()
        }
        btn("⑦ 关闭一步") {
            OneStepPrefs.setEnabled(this, false)
            OneStepAccessibilityService.instance?.stopTriggers()
            toast("已关闭，屏幕边缘恢复原样")
            refresh()
        }
        btn("⑧ 弹出一步侧栏（调试）") {
            val svc = OneStepAccessibilityService.instance
            if (svc == null) toast("先走⑤打开「一步引擎」")
            else svc.toggleSidebar()
        }
        btn("⑨ 拇指下拉手势（原版手势，需 Shizuku）") {
            val ping = try { Shizuku.pingBinder() } catch (_: Exception) { false }
            if (!ping) {
                toast("先安装并启动 Shizuku（同大爆炸②用的那个）")
            } else {
                val next = !OneStepPrefs.thumbPullEnabled(this)
                OneStepPrefs.setThumbPullEnabled(this, next)
                val svc = OneStepAccessibilityService.instance
                if (next) {
                    svc?.startTriggers()
                    toast("拇指下拉已开启：拇指肚大面积按住上半屏往下拖\n误触就回这里关，或调触发面积")
                } else {
                    svc?.stopTriggers()
                    toast("拇指下拉已关闭")
                }
                refresh()
            }
        }
        btn("⑩ 闪现热区位置（调试）") {
            OneStepAccessibilityService.instance?.flashHotzone()
        }
        btn("⑪ 添加侧栏应用") {
            showAppPicker()
        }
        btn("⑫ 授权相册（照片面板用）") {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ), 118)
        }
        btn("⑬ 换边（栏在左/右）") {
            OneStepPrefs.setSideRight(this, !OneStepPrefs.sideRight(this))
            OneStepAccessibilityService.instance?.let {
                if (it.overlay.isVisible) it.dismissSidebar()
            }
            toast("侧栏热区已换到${if (OneStepPrefs.sideRight(this)) "右上角" else "左上角"}，重新呼出生效")
        }

        val tip = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            text = """
                大爆炸 v1 · 拇指按压

                1. 点②：从内置安装包装 Shizuku（不用联网、不用 root）
                   打开 Shizuku →「通过无线调试启动」→ 按提示配对（一次性）
                2. 打开无障碍「大爆炸引擎」
                3. 再点②授权

                之后：用拇指肚按住文字约半秒即炸（指尖轻点、短按不会炸）。
                按在按钮、空白处不会炸，系统长按菜单照常。

                不会再因为选词、光标、弹窗自己跳出来。
            """.trimIndent()
        }
        box.addView(tip)

        val oneStepTip = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            text = """
                一步 v1 · OS 8 形态

                1. 走⑤打开无障碍「一步引擎」（和大爆炸引擎并存）
                2. 走⑥开启一步
                3. 右上角向内划 → 侧栏滑出；栏外触摸和应用照常
                4. 空槽 "+" 钉当前应用，点图标直接启动，长按移除
                5. 原版拇指下拉手势默认关（⑨），开着若误触就调灵敏度或关掉

                已知折损：侧栏覆盖应用边缘而非挤压窗口；钉住的是图标不是活窗口。
                详见 docs/notes/onestep-spec.md
            """.trimIndent()
        }
        box.addView(oneStepTip)
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
            "拇指按压: ${if (BangPrefs.pressEnabled(this)) "已启用" else "关闭"}" +
                thumbStatus(),
            "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}"
        ).joinToString("\n") + "\n\n" + oneStepStatus()
    }

    private fun showAppPicker() {
        val apps = com.smartisanrevival.onestep.data.AppManager.allLauncherApps(this)
        val pinned = OneStepPrefs.pinnedApps(this).toMutableSet()
        val checked = apps.map { it.packageName in pinned }.toBooleanArray()

        val lv = android.widget.ListView(this).apply {
            choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_checked,
                apps.map { it.label }.toTypedArray()
            )
            setOnItemClickListener { _, _, which, _ ->
                val wasChecked = checked[which]
                if (!wasChecked && pinned.size >= 6) {
                    toast("侧栏最多钉 6 个")
                    return@setOnItemClickListener
                }
                checked[which] = !wasChecked
                if (checked[which]) pinned.add(apps[which].packageName)
                else pinned.remove(apps[which].packageName)
                (adapter as android.widget.ArrayAdapter<*>).notifyDataSetChanged()
                // 重新绑定勾选态
                setItemChecked(which, checked[which])
            }
        }
        apps.indices.forEach { lv.setItemChecked(it, checked[it]) }

        android.app.AlertDialog.Builder(this)
            .setTitle("点选钉入侧栏（上限 6 个）")
            .setView(lv)
            .setPositiveButton("保存") { _, _ ->
                OneStepPrefs.setPinnedApps(this, pinned.toList())
                OneStepAccessibilityService.instance?.dismissSidebar()
                toast("已保存，下次呼出生效")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 118) {
            toast(if (grantResults.any { it == 0 }) "相册授权成功，照片面板可用" else "没有相册权限，照片面板为空")
        }
    }

    private fun oneStepStatus(): String {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val oneStepOn = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name?.contains("OneStepAccessibilityService") == true }
        val connected = OneStepAccessibilityService.instance != null
        return listOf(
            "—— 一步 ——",
            "一步引擎: ${if (oneStepOn) "开" else "关"}（服务${if (connected) "已连接" else "未连接"}）",
            "侧栏: ${if (OneStepPrefs.enabled(this)) "已启用" else "关闭"} / 热区: ${if (OneStepPrefs.hotzoneEnabled(this)) "开" else "关"}",
            "拇指下拉: ${if (OneStepPrefs.thumbPullEnabled(this)) "开（面积档${OneStepPrefs.thumbAreaLevel(this)}）" else "关"}"
        ).joinToString("\n")
    }

    private fun enableShizuku() {
        val ping = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        if (!ping) {
            toast("Shizuku 已装但未启动：打开 Shizuku 应用 →「通过无线调试启动」，按提示配对")
            return
        }
        val granted = try { Shizuku.checkSelfPermission() == 0 } catch (_: Exception) { false }
        if (granted) onShizukuGranted()
        else Shizuku.requestPermission(114)
    }

    private fun shizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: Exception) {
        false
    }

    /** 内置包一键安装：释放 assets 里的官方 shizuku.apk，交给系统安装器。全程不联网 */
    private fun installShizuku() {
        try {
            val dir = File(cacheDir, "install").apply { mkdirs() }
            val f = File(dir, "shizuku.apk")
            assets.open("shizuku.apk").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            toast("弹出系统安装器，确认安装 Shizuku；装好回到本页再点②")
        } catch (e: Exception) {
            toast("触发安装失败：${e.message}。去 设置→应用→本应用 允许「安装未知应用」后重试")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
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
                toast("拇指按压已启用：用记下的拇指按住文字约半秒")
            } catch (e: Exception) {
                toast("启动失败：${e.message}")
            }
        }
        refresh()
    }

    private fun thumbStatus(): String {
        val t = BangPrefs.thumbMajor(this)
        return if (t > 0) " / 拇指面积 $t（门槛 ${BangPrefs.minTouchMajor(this)}）"
        else " / 未校准拇指（门槛 ${BangPrefs.minTouchMajor(this)}，先按住下方区域）"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

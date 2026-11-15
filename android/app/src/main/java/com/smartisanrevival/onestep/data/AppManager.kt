package com.smartisanrevival.onestep.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * 启动器应用索引（官方 AppManager 语义：MAIN/LAUNCHER 枚举 + 上限 + 顺序）。
 * 顺序存 JSON prefs；选择器从这里取全量。
 */
object AppManager {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable?
    )

    private const val MAX_DEFAULT = 20

    /** 官方种子清单（Constants 默认自加列表，去掉不可用项） */
    val SEED_PACKAGES = listOf(
        "com.tencent.mm",            // 微信
        "com.eg.android.AlipayGphone",// 支付宝
        "com.alibaba.android.rimet", // 钉钉
        "com.tencent.mobileqq",      // QQ
        "com.sina.weibo",            // 微博
        "com.taobao.taobao",         // 淘宝
        "com.tencent.mttlite",       // 腾讯新闻
        "com.smartisan.notes",
        "com.android.calendar",
        "com.instagram.android",
        "com.twitter.android",
        "com.whatsapp",
        "com.android.chrome",
        "com.ss.android.ugc.aweme",  // 抖音
        "com.xingin.xhs",            // 小红书
        "com.zhihu.android",
        "com.netease.cloudmusic",    // 网易云
        "tv.danmaku.bili",           // B 站
        "com.baidu.BaiduMap",
        "com.autonavi.minimap"       // 高德
    )

    fun allLauncherApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolve = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return resolve.mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == ctx.packageName) return@mapNotNull null
            AppEntry(
                packageName = pkg,
                label = ri.loadLabel(pm)?.toString() ?: pkg,
                icon = ri.loadIcon(pm)
            )
        }.distinctBy { it.packageName }.sortedBy { it.label }
    }

    /** 种子清单里已安装的包（用于首次默认钉选建议） */
    fun installedSeeds(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        return SEED_PACKAGES.mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                AppEntry(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(pkg))
            }.getOrNull()
        }.take(MAX_DEFAULT)
    }

    /** 定向分享：文本发给指定应用（官方 ResolveInfoGroup 语义） */
    fun sendTextToApp(ctx: Context, packageName: String, text: String, launch: () -> Unit) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = ctx.packageManager.queryIntentActivities(send, 0)
        if (resolved.isEmpty()) {
            launch()
            return
        }
        try {
            ctx.startActivity(send)
        } catch (_: Exception) {
            launch()
        }
    }
}

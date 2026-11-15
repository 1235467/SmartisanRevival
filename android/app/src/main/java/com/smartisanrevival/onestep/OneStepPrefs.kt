package com.smartisanrevival.onestep

import android.content.Context

/** 一步的开关与配置。独立 prefs 文件，不碰 bang 的键。 */
object OneStepPrefs {
    private const val FILE = "onestep"

    const val KEY_ENABLED = "enabled"                 // 一步总开关（侧栏功能本体）
    const val KEY_SIDE = "side"                       // left / right，栏在哪一侧
    const val KEY_HOTZONE = "hotzone_enabled"         // 角落热区触发
    const val KEY_THUMB_PULL = "thumb_pull_enabled"   // 拇指大面积下拉（原版手势，需 Shizuku）
    const val KEY_THUMB_AREA = "thumb_pull_area"      // 触发面积档位 0..4（越小越灵敏）
    const val KEY_PINNED_APPS = "pinned_apps"         // 侧栏钉住的应用（JSON 数组）

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply()
    }

    /** 栏在哪一侧：true=右（原版默认右上角呼出） */
    fun sideRight(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SIDE, "right") == "right"

    fun setSideRight(ctx: Context, right: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_SIDE, if (right) "right" else "left").apply()
    }

    fun hotzoneEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_HOTZONE, true)

    fun setHotzoneEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOTZONE, v).apply()
    }

    fun thumbPullEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_THUMB_PULL, false)

    fun setThumbPullEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_THUMB_PULL, v).apply()
    }

    /** 原版五档：0 最小（最灵敏）… 4 最大 */
    fun thumbAreaLevel(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_THUMB_AREA, 3)

    fun setThumbAreaLevel(ctx: Context, v: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_THUMB_AREA, v.coerceIn(0, 4)).apply()
    }

    fun pinnedApps(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PINNED_APPS, "[]") ?: "[]"
        return mutableListOf<String>().also { out ->
            val body = raw.removeSurrounding("[", "]")
            if (body.isNotBlank()) body.split(",").forEach { out.add(it.trim().removeSurrounding("\"")) }
        }
    }

    fun setPinnedApps(ctx: Context, apps: List<String>) {
        val json = apps.joinToString(",", "[", "]") { "\"$it\"" }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_PINNED_APPS, json).apply()
    }
}

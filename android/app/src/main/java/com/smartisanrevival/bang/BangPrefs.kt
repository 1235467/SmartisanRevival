package com.smartisanrevival.bang

import android.content.Context

object BangPrefs {
    private const val FILE = "bang"
    const val KEY_PRESS = "press_enabled"
    const val KEY_SEARCH_WEB = "search_web"
    const val KEY_SEARCH_DICT = "search_dict"
    const val KEY_SEARCH_WIKI = "search_wiki"
    const val KEY_THUMB_MAJOR = "thumb_major"

    /** 未校准时的门槛。实测 touchpanel 拇指峰值约 14，指尖 5–11。 */
    const val DEFAULT_MIN_MAJOR = 12

    fun pressEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_PRESS, false)

    fun setPressEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_PRESS, v).apply()
    }

    fun searchEngine(ctx: Context, key: String, default: String): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, default) ?: default

    fun setSearchEngine(ctx: Context, key: String, id: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, id).apply()
    }

    fun thumbMajor(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_THUMB_MAJOR, 0)

    fun setThumbMajor(ctx: Context, major: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_THUMB_MAJOR, major).apply()
    }

    /** 运行时门槛：校准值的 60%。不能卡在 48，否则本机拇指永远过不去。 */
    fun minTouchMajor(ctx: Context): Int {
        val thumb = thumbMajor(ctx)
        if (thumb <= 0) return DEFAULT_MIN_MAJOR
        return (thumb * 0.6f).toInt().coerceIn(8, 240)
    }
}

package com.smartisanrevival.bang

import android.content.Context

object BangPrefs {
    private const val FILE = "bang"
    const val KEY_PRESS = "press_enabled"
    const val KEY_SEARCH_WEB = "search_web"
    const val KEY_SEARCH_DICT = "search_dict"
    const val KEY_SEARCH_WIKI = "search_wiki"

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
}

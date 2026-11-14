package com.smartisanrevival.bang.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.smartisanrevival.bang.BangPrefs
import com.smartisanrevival.bang.R

/**
 * 原版 BoomSearchActivity：大爆炸上面一张独立浮卡。
 * 底栏三个引擎图标对应搜索 / 词典 / 百科，长按弹出引擎列表。
 */
class SearchPopupView(
    context: Context,
    private val query: String,
    startGroup: Group,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    enum class Group { WEB, DICT, WIKI }

    data class Engine(
        val id: String,
        val group: Group,
        val name: String,
        val icon: Int,
        val url: (String) -> String
    )

    companion object {
        val ENGINES = listOf(
            Engine("baidu", Group.WEB, "在百度中搜索", R.drawable.boom_win_search_baidu) {
                "https://www.baidu.com/s?wd=$it"
            },
            Engine("google", Group.WEB, "在谷歌中搜索", R.drawable.boom_win_search_google) {
                "https://www.google.com/search?q=$it"
            },
            Engine("bing", Group.WEB, "在必应中搜索", R.drawable.boom_win_search_bing) {
                "https://www.bing.com/search?q=$it"
            },
            Engine("shenma", Group.WEB, "在神马中搜索", R.drawable.boom_win_search_shenma) {
                "https://m.sm.cn/s?q=$it"
            },
            Engine("youdao", Group.DICT, "有道词典", R.drawable.boom_win_search_youdao) {
                "https://m.youdao.com/result?word=$it&lang=auto"
            },
            Engine("iciba", Group.DICT, "金山词霸", R.drawable.boom_win_search_kingsoft) {
                "https://www.iciba.com/word?w=$it"
            },
            Engine("bingdict", Group.DICT, "必应词典", R.drawable.boom_win_search_bingdict) {
                "https://cn.bing.com/dict/search?q=$it"
            },
            Engine("hidict", Group.DICT, "海词词典", R.drawable.boom_win_search_hidict) {
                "https://dict.cn/$it"
            },
            Engine("baike", Group.WIKI, "百度百科", R.drawable.boom_win_search_baike) {
                "https://baike.baidu.com/search?word=$it"
            },
            Engine("hudong", Group.WIKI, "互动百科", R.drawable.boom_win_search_hudongdict) {
                "https://www.baike.com/wiki/$it"
            }
        )

        private val DEFAULTS = mapOf(
            Group.WEB to "baidu",
            Group.DICT to "youdao",
            Group.WIKI to "baike"
        )
    }

    private val web: WebView
    private val progress: ProgressBar
    private val tabViews = HashMap<Group, ImageView>()
    private var currentGroup: Group = startGroup
    private var picker: View? = null
    private var currentUrl: String = ""

    init {
        isClickable = true
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(0x66000000)
        setOnClickListener {
            if (picker != null) hidePicker() else dismiss()
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpF(18)
            }
            elevation = dpF(10)
            clipToOutline = true
        }

        val titleBar = FrameLayout(context).apply {
            setBackgroundColor(0xFFF7F7F7.toInt())
        }
        val title = TextView(context).apply {
            text = "网络搜索结果"
            textSize = 15f
            setTextColor(0x9A000000.toInt())
            gravity = Gravity.CENTER
        }
        val browser = ImageView(context).apply {
            setImageResource(R.drawable.boom_win_browser)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { openInBrowser() }
        }
        titleBar.addView(title, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        titleBar.addView(browser, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        card.addView(titleBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        card.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= 21) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url?.toString().orEmpty()
                    if (u.startsWith("http")) {
                        currentUrl = u
                        return false
                    }
                    return true
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        android.util.Log.i("Bang", "search load error ${error.errorCode} ${error.description}")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@SearchPopupView.progress.progress = newProgress
                    this@SearchPopupView.progress.visibility =
                        if (newProgress in 1..99) VISIBLE else INVISIBLE
                }
            }
        }
        card.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        card.addView(buildBottomBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))

        val w = (resources.displayMetrics.widthPixels - dp(28)).coerceAtMost(dp(380))
        val h = (resources.displayMetrics.heightPixels * 0.72f).toInt().coerceAtMost(dp(560))
        addView(card, FrameLayout.LayoutParams(w, h, Gravity.CENTER))

        refreshTabs()
        loadGroup(startGroup, force = true)
        alpha = 0f
        scaleX = 0.96f
        scaleY = 0.96f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFF7F7F7.toInt())
            setPadding(dp(8), 0, dp(8), 0)
        }
        val back = TextView(context).apply {
            text = "‹"
            textSize = 28f
            setTextColor(0xFF9A9A9A.toInt())
            gravity = Gravity.CENTER
            setOnClickListener {
                if (web.canGoBack()) web.goBack() else dismiss()
            }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))

        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        Group.entries.forEach { group ->
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isLongClickable = true
                setBackgroundResource(R.drawable.boom_win_search_more_bg)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    if (group == currentGroup) {
                        showPicker(group, this)
                    } else {
                        hidePicker()
                        loadGroup(group, force = false)
                    }
                }
                setOnLongClickListener {
                    showPicker(group, this)
                    true
                }
            }
            tabViews[group] = iv
            tabs.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            })
        }
        bar.addView(tabs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val gear = ImageView(context).apply {
            setImageResource(R.drawable.boom_win_setting)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener {
                val tab = tabViews[currentGroup] ?: return@setOnClickListener
                showPicker(currentGroup, tab)
            }
        }
        bar.addView(gear, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        return bar
    }

    private fun refreshTabs() {
        Group.entries.forEach { group ->
            val engine = engineOf(group)
            tabViews[group]?.let {
                it.setImageResource(engine.icon)
                it.isSelected = group == currentGroup
            }
        }
    }

    private fun loadGroup(group: Group, force: Boolean) {
        currentGroup = group
        refreshTabs()
        val engine = engineOf(group)
        val encoded = Uri.encode(query.take(80))
        val url = engine.url(encoded)
        currentUrl = url
        web.clearHistory()
        web.loadUrl(url)
    }

    private fun engineOf(group: Group): Engine {
        val id = BangPrefs.searchEngine(context, prefKey(group), DEFAULTS.getValue(group))
        return ENGINES.firstOrNull { it.group == group && it.id == id }
            ?: ENGINES.first { it.group == group }
    }

    private fun prefKey(group: Group) = when (group) {
        Group.WEB -> BangPrefs.KEY_SEARCH_WEB
        Group.DICT -> BangPrefs.KEY_SEARCH_DICT
        Group.WIKI -> BangPrefs.KEY_SEARCH_WIKI
    }

    private fun showPicker(group: Group, anchor: View) {
        hidePicker()
        val chosen = engineOf(group).id
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.boom_win_popup_bg)
            setPadding(0, dp(6), 0, dp(10))
        }
        ENGINES.filter { it.group == group }.forEach { engine ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true
                setOnClickListener {
                    BangPrefs.setSearchEngine(context, prefKey(group), engine.id)
                    hidePicker()
                    loadGroup(group, force = true)
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(engine.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            val selected = engine.id == chosen
            val name = TextView(context).apply {
                text = engine.name
                textSize = 15f
                setTextColor(if (selected) 0xFF5A87F2.toInt() else 0x9A000000.toInt())
                setPadding(dp(10), 0, dp(8), 0)
            }
            val check = TextView(context).apply {
                text = if (selected) "✓" else ""
                textSize = 18f
                setTextColor(0xFF5A87F2.toInt())
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(check, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)))
        }
        val wrap = ScrollView(context).apply {
            addView(list)
            elevation = dpF(16)
            isClickable = true
        }
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val root = IntArray(2)
        getLocationOnScreen(root)
        val pw = dp(271)
        val params = FrameLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ((loc[0] - root[0] + anchor.width / 2) - pw / 2)
                .coerceIn(dp(12), (width - pw - dp(12)).coerceAtLeast(dp(12)))
            topMargin = (loc[1] - root[1] - dp(8) - ENGINES.count { it.group == group } * dp(45) - dp(16))
                .coerceAtLeast(dp(48))
        }
        addView(wrap, params)
        picker = wrap
    }

    private fun hidePicker() {
        picker?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picker = null
    }

    private fun openInBrowser() {
        val url = currentUrl.ifEmpty { engineOf(currentGroup).url(Uri.encode(query.take(80))) }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun dismiss() {
        hidePicker()
        animate().alpha(0f).setDuration(120).withEndAction {
            teardown()
            onClose()
        }.start()
    }

    fun destroyImmediate() {
        hidePicker()
        teardown()
    }

    private fun teardown() {
        runCatching {
            web.stopLoading()
            web.destroy()
        }
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Int): Float = v * resources.displayMetrics.density
}

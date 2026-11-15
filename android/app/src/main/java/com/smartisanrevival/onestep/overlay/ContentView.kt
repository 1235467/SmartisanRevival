package com.smartisanrevival.onestep.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.smartisanrevival.onestep.data.ContentStores
import com.smartisanrevival.onestep.data.ContentStores.Bucket
import java.io.File

/**
 * 内容面板（官方 ContentView 语义）：照片 / 文件 / 剪贴板 三选一，圆角卡片浮在全屏 dim 上。
 * 官方动效：面板 alpha 300ms 进出；清除 = 列表滑出 100ms + fade 200ms。
 * OS 8 面板为浅色卡片深色字（content_view_title_color #9a000000）。
 */
class ContentView(context: Context) : FrameLayout(context) {

    companion object {
        const val PANEL_PHOTO = 0
        const val PANEL_FILE = 1
        const val PANEL_CLIP = 2
        private const val CONTENT_BG = 0xF2F5F5F5.toInt() // 近官方 content_bg 圆角浅卡
    }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(CONTENT_BG, dp(12))
    }
    private val titleView = TextView(context).apply {
        textSize = 14f
        setTextColor(0x9A000000.toInt())
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
    }
    private val listHost = FrameLayout(context)

    init {
        setBackgroundColor(TitleBarView.DIM_COLOR)
        setOnClickListener { dismiss() }

        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x33000000)
        }
        titleBar.addView(titleView, LinearLayout.LayoutParams(0, dp(36), 1f))
        val clear = TextView(context).apply {
            text = "清除"
            textSize = 12f
            setTextColor(0x66000000)
            setPadding(dp(10), 0, dp(10), 0)
            gravity = Gravity.CENTER
            setOnClickListener { clearCurrent() }
        }
        titleBar.addView(clear, LinearLayout.LayoutParams(dp(48), dp(36)))
        card.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
        card.addView(View(context).apply { setBackgroundColor(0x1A000000) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        card.addView(listHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        // 底部圆角卡片，占屏 42%（官方内容面板形态：面板浮在 dim 上）
        addView(card, LayoutParams(
            LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.42f).toInt(),
            Gravity.BOTTOM))
    }

    var currentPanel: Int = -1
        private set

    /** 动画结束后由 SidebarController 经 WindowManager 真正移除窗口（绝不残留全屏触摸层） */
    var onClosed: (() -> Unit)? = null

    fun showPanel(which: Int) {
        currentPanel = which
        when (which) {
            PANEL_PHOTO -> buildPhotoPanel()
            PANEL_FILE -> buildFilePanel()
            PANEL_CLIP -> buildClipPanel()
        }
        animate().cancel()
        alpha = 0f
        animate().alpha(1f).setDuration(300).withLayer().start()
    }

    fun dismiss() {
        currentPanel = -1
        animate().cancel()
        animate().alpha(0f).setDuration(300).withLayer().withEndAction {
            onClosed?.invoke()
        }.start()
    }

    private fun clearCurrent() {
        when (currentPanel) {
            PANEL_CLIP -> {
                ContentStores.clearClips(context)
                buildClipPanel()
            }
            else -> Toast.makeText(context, "该面板暂无清除项", Toast.LENGTH_SHORT).show()
        }
    }

    private fun emptyState(msg: String) {
        listHost.removeAllViews()
        val tv = TextView(context).apply {
            text = msg
            textSize = 13f
            setTextColor(0x66000000)
            gravity = Gravity.CENTER
        }
        listHost.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ---------- 照片 ----------

    private fun buildPhotoPanel() {
        titleView.text = "最近照片"
        listHost.removeAllViews()
        val buckets = ContentStores.recentPhotos(context)
        if (buckets.isEmpty()) { emptyState("没有照片，或未授权相册"); return }
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val cell = cellWidth()
        buckets.forEach { b ->
            box.addView(header(b.label))
            val rows = b.items.chunked(3)
            rows.forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { rawItem ->
                    val item = rawItem as ContentStores.PhotoItem
                    val iv = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0x22000000)
                        setOnClickListener { openUri(item.uri, "image/*") }
                    }
                    loadThumbnail(iv, item.uri)
                    val lp = LinearLayout.LayoutParams(cell, cell).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    }
                    row.addView(iv, lp)
                }
                // 补齐空位保持三列对齐
                repeat(3 - chunk.size) {
                    row.addView(View(context), LinearLayout.LayoutParams(cell, cell).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                }
                box.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        scroll.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        listHost.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun header(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(0x4D000000.toInt())
        setPadding(dp(10), dp(8), dp(10), dp(4))
    }

    private fun cellWidth(): Int {
        val w = resources.displayMetrics.widthPixels - dp(24)
        return w / 3
    }

    private fun loadThumbnail(iv: ImageView, uri: Uri) {
        Thread {
            try {
                val size = cellWidth()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                var sample = 1
                while (opts.outWidth / sample > size * 2) sample *= 2
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp: Bitmap? = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                }
                iv.post {
                    if (bmp != null) iv.setImageBitmap(bmp)
                    else iv.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            } catch (_: Exception) {
                iv.post { iv.setImageResource(android.R.drawable.ic_menu_report_image) }
            }
        }.start()
    }

    // ---------- 文件 ----------

    private fun buildFilePanel() {
        titleView.text = "最近文件"
        listHost.removeAllViews()
        val buckets = ContentStores.recentFiles(context)
        if (buckets.isEmpty()) { emptyState("没有最近的文件"); return }
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        buckets.forEach { b ->
            box.addView(header(b.label))
            b.items.forEach { item ->
                val f = item as ContentStores.FileItem
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener { openFile(f) }
                }
                val badge = TextView(context).apply {
                    text = ContentStores.mimeIcon(f.mime)
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(0xFF5A6473.toInt())
                    }
                }
                row.addView(badge, LinearLayout.LayoutParams(dp(34), dp(34)))
                val name = TextView(context).apply {
                    text = f.label
                    textSize = 13f
                    setTextColor(0x9A000000.toInt())
                    maxLines = 1
                    setPadding(dp(10), 0, 0, 0)
                }
                row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                box.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        scroll.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        listHost.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun openFile(f: ContentStores.FileItem) {
        val uri = f.mediaUri
        if (uri == null) {
            // MediaStore 索引外的扫描文件没有可共享 URI，targetSdk 35 不允许 file:// 直开
            Toast.makeText(context, "该文件请到文件管理器打开", Toast.LENGTH_SHORT).show()
            return
        }
        openUri(uri, "*/*")
    }

    // ---------- 剪贴板 ----------

    private fun buildClipPanel() {
        titleView.text = "剪贴板"
        listHost.removeAllViews()
        val clips = ContentStores.clipHistory(context)
        if (clips.isEmpty()) {
            emptyState("收纳箱是空的。\n从任意 App 选中文字 → 更多 → 一步，即可存进来")
            return
        }
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        clips.forEach { c ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("onestep", c.text))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            }
            val time = TextView(context).apply {
                text = ContentStores.bucketLabel(c.timeMs)
                textSize = 11f
                setTextColor(0x4D000000.toInt())
            }
            val text = TextView(context).apply {
                text = c.text
                textSize = 13f
                setTextColor(0x9A000000.toInt())
                maxLines = 3
                setPadding(0, dp(2), 0, 0)
            }
            row.addView(time)
            row.addView(text)
            box.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            box.addView(View(context).apply { setBackgroundColor(0x14000000) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
        scroll.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        listHost.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ---------- 工具 ----------

    private fun openUri(uri: Uri, mime: String) {
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(context, "没有能打开它的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun roundRect(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}

package com.smartisanrevival.bang.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 原版 ocr_bluesquare.9.png 的蓝。 */
private val FRAME_BLUE = Color.parseColor("#2165F0")
private const val TAG_TITLE = "框选需要识别的区域"
private const val TAG_BUSY = "正在识别…"
private const val TIP = "此区域不支持文字炸开，请划动选取需要识别的内容"

private const val MODE_NONE = 0
private const val MODE_CORNER = 1
private const val MODE_EDGE = 2
private const val MODE_MOVE = 3
private const val MODE_NEW = 4
private const val CORNER_LT = 0
private const val CORNER_RT = 1
private const val CORNER_LB = 2
private const val CORNER_RB = 3
private const val EDGE_NONE = 0
private const val EDGE_LEFT = 1
private const val EDGE_RIGHT = 2
private const val EDGE_TOP = 3
private const val EDGE_BOTTOM = 4

/**
 * 原版炸图片的框选界面，复刻 OS 8 TextBoom 的 crop_image_ocr_view：
 * 冻结截屏做底，选区外压暗，蓝色选区框 + 四角角标（角标素材取自原版 APK）。
 * 标题栏：取消 | 框选需要识别的区域 | 识别文字。
 * 视口即全屏窗口（gravity TOP|START + LAYOUT_NO_LIMITS），所以视图坐标就是屏幕坐标。
 */
class CropSelectView(context: Context) : FrameLayout(context) {

    var onConfirm: ((Rect) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val titleBar = LinearLayout(context)
    private val btnCancel = TextView(context)
    private val titleText = TextView(context)
    private val btnConfirm = TextView(context)
    private val tipText = TextView(context)
    private val area = CropArea(context)

    private var statusBarH = 0
    private var busy = false

    init {
        statusBarH = readStatusBarHeight()

        area.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(area)

        tipText.text = TIP
        tipText.textSize = 13f
        tipText.setTextColor(Color.WHITE)
        tipText.setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        tipText.background = GradientDrawable().apply {
            cornerRadius = 10 * dp
            setColor(0xCC000000.toInt())
        }
        tipText.isClickable = false
        addView(tipText, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = statusBarH + (52 * dp).toInt() })

        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.setPadding(0, statusBarH, 0, 0)
        titleBar.setBackgroundColor(0xF2000000.toInt())
        fun barBtn(tv: TextView, text: String, weight: Float, bold: Boolean, color: Int, pad: Int) {
            tv.text = text
            tv.textSize = 16f
            tv.maxLines = 1
            tv.typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tv.setTextColor(color)
            tv.setPadding((pad * dp).toInt(), (14 * dp).toInt(), (pad * dp).toInt(), (14 * dp).toInt())
            titleBar.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight))
        }
        barBtn(btnCancel, "取消", 1f, false, Color.WHITE, 14)
        barBtn(titleText, TAG_TITLE, 2f, false, Color.WHITE, 0)
        titleText.gravity = Gravity.CENTER
        barBtn(btnConfirm, "识别文字", 1f, true, FRAME_BLUE, 14)
        btnConfirm.gravity = Gravity.END
        btnCancel.setOnClickListener { if (!busy) onCancel?.invoke() }
        btnConfirm.setOnClickListener { if (!busy) onConfirm?.invoke(selection) }
        addView(titleBar, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        setSelection(Rect(0, 0, 0, 0))
    }

    fun setScreenshot(bmp: Bitmap) {
        area.shot = bmp
    }

    fun setSelection(r: Rect) {
        area.selection = Rect(r)
        area.invalidate()
    }

    val selection: Rect
        get() = Rect(area.selection)

    fun setBusy(b: Boolean) {
        busy = b
        titleText.text = if (b) TAG_BUSY else TAG_TITLE
        val alpha = if (b) 0.4f else 1f
        btnConfirm.alpha = alpha
        btnCancel.alpha = alpha
        if (b) tipText.visibility = GONE
    }

    private fun readStatusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id > 0) resources.getDimensionPixelSize(id) else 0
        return if (h > 0) h else (26 * dp).toInt()
    }

    /**
     * 画背景截屏 + 选区遮罩 + 蓝框 + 角标；触摸划选/移动/缩放。
     * 触摸细节参考 ThumbPadView 的写法。
     */
    private inner class CropArea(ctx: Context) : View(ctx) {

        var shot: Bitmap? = null
        var selection = Rect()

        private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val maskPaint = Paint().apply { color = 0xB3000000.toInt() }
        private val framePaint = Paint().apply {
            color = FRAME_BLUE
            style = Paint.Style.STROKE
            strokeWidth = 2f * dp
        }
        private val brackets: Map<Int, Bitmap?> = mapOf(
            CORNER_LT to safeDecode("crop_view_left_top_conner"),
            CORNER_RT to safeDecode("crop_view_right_top_conner"),
            CORNER_LB to safeDecode("crop_view_left_bottom_conner"),
            CORNER_RB to safeDecode("crop_view_right_bottom_conner")
        )

        private var mode = MODE_NONE
        private var dragCorner = CORNER_LT
        private var dragEdge = EDGE_NONE
        private var moveDx = 0
        private var moveDy = 0
        private var anchorX = 0f
        private var anchorY = 0f

        private val minSize = (48 * dp).toInt()
        private val cornerZone = (28 * dp).toInt()
        private val edgeZone = (14 * dp).toInt()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = shot
            if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), bgPaint)
            }
            val s = selection
            if (s.isEmpty) return
            // 选区外四块压暗
            canvas.drawRect(0f, 0f, width.toFloat(), s.top.toFloat(), maskPaint)
            canvas.drawRect(0f, s.bottom.toFloat(), width.toFloat(), height.toFloat(), maskPaint)
            canvas.drawRect(0f, s.top.toFloat(), s.left.toFloat(), s.bottom.toFloat(), maskPaint)
            canvas.drawRect(s.right.toFloat(), s.top.toFloat(), width.toFloat(), s.bottom.toFloat(), maskPaint)
            // 蓝框 + 四角角标
            canvas.drawRect(s.left.toFloat(), s.top.toFloat(), s.right.toFloat(), s.bottom.toFloat(), framePaint)
            val bs = (25 * dp).toInt()
            brackets[CORNER_LT]?.let { canvas.drawBitmap(it, null, Rect(s.left, s.top, s.left + bs, s.top + bs), bgPaint) }
            brackets[CORNER_RT]?.let { canvas.drawBitmap(it, null, Rect(s.right - bs, s.top, s.right, s.top + bs), bgPaint) }
            brackets[CORNER_LB]?.let { canvas.drawBitmap(it, null, Rect(s.left, s.bottom - bs, s.left + bs, s.bottom), bgPaint) }
            brackets[CORNER_RB]?.let { canvas.drawBitmap(it, null, Rect(s.right - bs, s.bottom - bs, s.right, s.bottom), bgPaint) }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (busy) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (tipText.visibility == VISIBLE) tipText.visibility = GONE
                    anchorX = event.x
                    anchorY = event.y
                    when {
                        hitCorner(event.x.toInt(), event.y.toInt()) != null -> {
                            mode = MODE_CORNER
                            dragCorner = hitCorner(event.x.toInt(), event.y.toInt())!!
                        }
                        hitEdge(event.x.toInt(), event.y.toInt()) -> {
                            mode = MODE_EDGE
                            dragEdge = edgeOf(event.x.toInt(), event.y.toInt())
                        }
                        selection.contains(event.x.toInt(), event.y.toInt()) -> {
                            mode = MODE_MOVE
                            moveDx = event.x.toInt() - selection.left
                            moveDy = event.y.toInt() - selection.top
                        }
                        else -> {
                            mode = MODE_NEW
                            selection = Rect(event.x.toInt(), event.y.toInt(), event.x.toInt(), event.y.toInt())
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> when (mode) {
                    MODE_CORNER -> resizeCorner(event.x.toInt(), event.y.toInt())
                    MODE_EDGE -> resizeEdge(event.x.toInt(), event.y.toInt())
                    MODE_MOVE -> moveRect(event.x.toInt() - moveDx, event.y.toInt() - moveDy)
                    MODE_NEW -> {
                        val l = minOf(anchorX, event.x).toInt()
                        val t = minOf(anchorY, event.y).toInt()
                        val r = maxOf(anchorX, event.x).toInt()
                        val b = maxOf(anchorY, event.y).toInt()
                        selection = clampRect(Rect(l, t, r, b))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == MODE_NEW || mode == MODE_CORNER || mode == MODE_EDGE) ensureMinSize()
                    mode = MODE_NONE
                }
            }
            invalidate()
            return true
        }

        private fun hitCorner(x: Int, y: Int): Int? {
            val corners = listOf(
                CORNER_LT to listOf(selection.left, selection.top),
                CORNER_RT to listOf(selection.right, selection.top),
                CORNER_LB to listOf(selection.left, selection.bottom),
                CORNER_RB to listOf(selection.right, selection.bottom)
            )
            for ((c, p) in corners) {
                if (kotlin.math.abs(x - p[0]) <= cornerZone && kotlin.math.abs(y - p[1]) <= cornerZone) return c
            }
            return null
        }

        private fun hitEdge(x: Int, y: Int): Boolean = edgeOf(x, y) != EDGE_NONE

        private fun edgeOf(x: Int, y: Int): Int {
            val inV = y in selection.top..selection.bottom
            val inH = x in selection.left..selection.right
            return when {
                inV && kotlin.math.abs(x - selection.left) <= edgeZone -> EDGE_LEFT
                inV && kotlin.math.abs(x - selection.right) <= edgeZone -> EDGE_RIGHT
                inH && kotlin.math.abs(y - selection.top) <= edgeZone -> EDGE_TOP
                inH && kotlin.math.abs(y - selection.bottom) <= edgeZone -> EDGE_BOTTOM
                else -> EDGE_NONE
            }
        }

        private fun resizeCorner(x: Int, y: Int) {
            val s = Rect(selection)
            when (dragCorner) {
                CORNER_LT -> { s.left = x; s.top = y }
                CORNER_RT -> { s.right = x; s.top = y }
                CORNER_LB -> { s.left = x; s.bottom = y }
                CORNER_RB -> { s.right = x; s.bottom = y }
            }
            selection = clampRect(normalize(s))
        }

        private fun resizeEdge(x: Int, y: Int) {
            val s = Rect(selection)
            when (dragEdge) {
                EDGE_LEFT -> s.left = x
                EDGE_RIGHT -> s.right = x
                EDGE_TOP -> s.top = y
                EDGE_BOTTOM -> s.bottom = y
            }
            selection = clampRect(normalize(s))
        }

        private fun moveRect(left: Int, top: Int) {
            val w = selection.width()
            val h = selection.height()
            val l = left.coerceIn(0, width - w)
            val t = top.coerceIn(0, height - h)
            selection = Rect(l, t, l + w, t + h)
        }

        /** 左上角可能被拖到右下角，先归一化再夹回屏内。 */
        private fun normalize(s: Rect): Rect =
            Rect(minOf(s.left, s.right), minOf(s.top, s.bottom), maxOf(s.left, s.right), maxOf(s.top, s.bottom))

        private fun clampRect(s: Rect): Rect {
            val l = s.left.coerceIn(0, width)
            val t = s.top.coerceIn(0, height)
            val r = s.right.coerceIn(0, width)
            val b = s.bottom.coerceIn(0, height)
            return Rect(l, t, r, b)
        }

        private fun ensureMinSize() {
            if (selection.width() < minSize || selection.height() < minSize) {
                val cx = selection.centerX()
                val cy = selection.centerY()
                val l = (cx - minSize / 2).coerceIn(0, width)
                val t = (cy - minSize / 2).coerceIn(0, height)
                selection = Rect(l, t,
                    (l + minSize).coerceAtMost(width),
                    (t + minSize).coerceAtMost(height))
            }
        }

        private fun safeDecode(name: String): Bitmap? = try {
            val id = resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) android.graphics.BitmapFactory.decodeResource(resources, id) else null
        } catch (_: Throwable) {
            null
        }
    }
}

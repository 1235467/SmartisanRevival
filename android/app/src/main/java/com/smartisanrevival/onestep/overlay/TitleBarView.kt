package com.smartisanrevival.onestep.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.hypot
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.smartisanrevival.bang.R
import com.smartisanrevival.onestep.anim.OneStepAnim

/**
 * OS 8 一步标题条：第一行 [退出][设置] … One Step … [←][→]，第二行 dock（横向图标行）。
 * 布局参照 R2 实测截屏（r2_gest_tr.png / r2_mini_tap.png）：靠栏一侧是退出+设置，靠应用一侧是切换箭头。
 */
class TitleBarView(
    context: Context,
    private val railOnRight: Boolean,
    private val onExit: () -> Unit,
    private val onSettings: () -> Unit,
    private val onOpenPanel: (Int) -> Unit = {}
) : LinearLayout(context) {

    val dockRow: HorizontalScrollView
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dismissThreshold = dp(24).toFloat()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragOut = 0f
    private var inTitleRow = false

    init {
        orientation = VERTICAL
        setBackgroundColor(SIDEBAR_BG)

        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }

        val railCluster = LinearLayout(context).apply { orientation = HORIZONTAL }
        val appCluster = LinearLayout(context).apply { orientation = HORIZONTAL }

        // 退出（栏侧）
        railCluster.addView(iconButton(if (railOnRight) R.drawable.onestep_exit_right else R.drawable.onestep_exit_left) {
            onExit()
        })
        // 设置（栏侧）
        railCluster.addView(iconButton(R.drawable.onestep_gear_normal) { onSettings() })

        // 应用切换（应用侧）。原版语义 = switch_app（切上一个应用），v1 暂置灰待接
        val prev = iconButton(0) { }
        val next = iconButton(0) { }
        prev.setImageBitmap(drawArrow(true))
        next.setImageBitmap(drawArrow(false))
        prev.isEnabled = false
        next.isEnabled = false
        appCluster.addView(prev)
        appCluster.addView(next)

        val title = TextView(context).apply {
            text = "One Step"
            textSize = 17f
            setTextColor(0xB3FFFFFF.toInt())
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            gravity = Gravity.CENTER
        }

        titleRow.addView(railCluster, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        titleRow.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, 1f))
        titleRow.addView(appCluster, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        addView(titleRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))

        addView(View(context).apply { setBackgroundColor(0x1FFFFFFF) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))

        // dock 行：v1 先放 照片/文件/剪贴板 三个面板入口（OS 3.x 顶栏语义，面板第 3 步实现）
        dockRow = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        val dockInner = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        dockRow.addView(dockInner, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        addDockEntry(dockInner, R.drawable.onestep_topbar_photo, "照片", ContentView.PANEL_PHOTO)
        addDockEntry(dockInner, R.drawable.onestep_topbar_file, "文件", ContentView.PANEL_FILE)
        addDockEntry(dockInner, R.drawable.onestep_topbar_clipboard, "剪贴板", ContentView.PANEL_CLIP)

        addView(dockRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(72)))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inTitleRow = ev.y <= dp(48)
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                dragOut = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!inTitleRow) return false
                if (dragging) return true
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                val outward = if (railOnRight) dx else -dx
                if (outward > touchSlop && outward > abs(dy) * 0.5f) {
                    dragging = true
                    Log.i("OneStep", "title swipe start dx=$dx dy=$dy")
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) inTitleRow = false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inTitleRow = ev.y <= dp(48)
                if (!inTitleRow) return false
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                dragOut = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!inTitleRow) return false
                if (!dragging) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    val outward = if (railOnRight) dx else -dx
                    if (outward > touchSlop && outward > abs(dy) * 0.5f) {
                        dragging = true
                        Log.i("OneStep", "title swipe start dx=$dx dy=$dy")
                    }
                }
                if (dragging) dragOut = outwardDistance(ev.rawX)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return true
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                val outNow = outwardDistance(ev.rawX)
                val dist = hypot(dx, dy)
                dragOut = outNow
                dragging = false
                if (outNow > 0f && dist >= dismissThreshold) {
                    Log.i("OneStep", "title swipe dismiss dist=$dist dx=$dx dy=$dy")
                    onExit()
                }
                return true
            }
        }
        return true
    }

    private fun outwardDistance(rawX: Float): Float =
        (if (railOnRight) rawX - downX else downX - rawX).coerceAtLeast(0f)

    private fun addDockEntry(parent: LinearLayout, iconRes: Int, label: String, panel: Int) {
        val cell = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        val iv = ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val tv = TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            maxWidth = dp(50)
        }
        cell.addView(iv, LayoutParams(dp(45), dp(45)))
        cell.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        cell.setPadding(dp(4), dp(2), dp(4), dp(2))
        cell.setOnClickListener { onOpenPanel(panel) }
        parent.addView(cell, LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun iconButton(res: Int, onClick: () -> Unit): ImageView {
        val iv = ImageView(context).apply {
          if (res != 0) setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { onClick() }
        }
        return iv
    }

    /** 应用切换箭头：原版无独立图标资源，OS 8 为代码绘制，这里同样用 Canvas 画 */
    private fun drawArrow(left: Boolean) = android.graphics.Bitmap.createBitmap(dp(28), dp(28), android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val mid = dp(14).toFloat()
        val a = dp(8).toFloat()
        val b = dp(20).toFloat()
        val head = dp(6).toFloat()
        val x0 = if (left) b else a
        val x1 = if (left) a else b
        c.drawLine(x0, mid, x1, mid, p)
        val dir = if (left) 1f else -1f
        c.drawLine(x1, mid, x1 + head * dir, mid - head, p)
        c.drawLine(x1, mid, x1 + head * dir, mid + head, p)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** 官方 sidebar_root_background / disable_dim_color */
        const val SIDEBAR_BG = -0x4D000000 // #b3000000
        const val DIM_COLOR = 0x66000000.toInt()
    }
}

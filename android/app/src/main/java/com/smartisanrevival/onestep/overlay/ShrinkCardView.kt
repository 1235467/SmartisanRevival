package com.smartisanrevival.onestep.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 「画面收缩到左下角」卡片（OneStep 1.0 形态的视觉近似）。
 *
 * 原版是 framework 把应用窗口真缩小（活的、可交互）；第三方没有窗口权，
 * 这里用呼出瞬间的截屏缩小进圆角卡片替代——形态 1:1，内容是静态预览，点卡片退出。
 */
class ShrinkCardView(
    context: Context,
    private val screenshot: Bitmap?,
    private val railWidthPx: Int,
    private val onTap: () -> Unit
) : FrameLayout(context) {

    init {
        setBackgroundColor(TitleBarView.DIM_COLOR)
        setOnClickListener { onTap() }

        if (screenshot != null) {
            val card = ImageView(context).apply {
                // 原版「收缩」= 整屏等比缩小进卡片区域（FIT_CENTER，整张截图完整可见）
                scaleType = ImageView.ScaleType.FIT_CENTER
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                    }
                }
                elevation = dp(8).toFloat()
                setImageDrawable(BitmapDrawable(resources, screenshot))
                setOnClickListener { onTap() }
            }
            // 卡片几何：贴左下，右侧给侧栏留位（原版发布会形态 ≈ 屏宽 2/3 × 高 3/5）
            val dm = resources.displayMetrics
            val w = dm.widthPixels - railWidthPx - dp(28)
            val h = ((dm.heightPixels - statusBarHeight()) * 0.58f).toInt()
            addView(card, LayoutParams(w, h, Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(16)
                bottomMargin = navbarHeight() + dp(12)
            })
        } else {
            // 截屏失败（黑屏限制等）：只留 dim，点按退出
            addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    }

    private fun navbarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}

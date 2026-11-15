package com.smartisanrevival.onestep.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.smartisanrevival.onestep.OneStepPrefs
import com.smartisanrevival.onestep.anim.OneStepAnim

/**
 * 侧栏本体（OS 8 形态）：钉住的应用槽位 + 空槽（"+"）。
 * 原版钉住的是 framework 缩小的活窗口，第三方做不到，v1 用应用图标替位（已知折损）。
 */
class SideRailView(
    context: Context,
    private val railOnRight: Boolean,
    private val currentPackage: () -> String?
) : LinearLayout(context) {

    private val slotsContainer = LinearLayout(context).apply {
        orientation = VERTICAL
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(TitleBarView.SIDEBAR_BG)
        addView(slotsContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rebuild()
    }

    fun rebuild() {
        slotsContainer.removeAllViews()
        val pinned = OneStepPrefs.pinnedApps(context)
        pinned.forEach { pkg -> slotsContainer.addView(appCell(pkg)) }
        // 空槽：原版始终留有 "+" 位；显示数量 = 钉住数之外补 2 个
        repeat((2 - pinned.size).coerceAtLeast(1)) { slotsContainer.addView(emptyCell()) }
    }

    /** 钉住的应用格：图标居中，点击启动 */
    private fun appCell(pkg: String): View {
        val cell = FrameCell(context)
        val pm = context.packageManager
        val icon = try {
            pm.getApplicationIcon(pkg)
        } catch (_: Exception) {
            ColorDrawable(Color.TRANSPARENT)
        }
        val iv = android.widget.ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        cell.addView(iv, cellChildParams(dp(40)))
        cell.setOnClickListener {
            launchApp(pkg)
        }
        // 原版：拖到垃圾桶移除（第 4 步），v1 长按移除
        cell.setOnLongClickListener {
            removePinned(pkg)
            true
        }
        return cell
    }

    /** 空槽："+" 圆钮，点击钉当前前台应用 */
    private fun emptyCell(): View {
        val cell = FrameCell(context)
        val plus = object : View(context) {
            private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
                color = 0x66FFFFFF
            }
            private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
                strokeCap = Paint.Cap.ROUND
                color = 0x66FFFFFF
            }

            override fun onDraw(c: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r = dp(22f)
                c.drawCircle(cx, cy, r, ring)
                val h = dp(8f)
                c.drawLine(cx - h, cy, cx + h, cy, cross)
                c.drawLine(cx, cy - h, cx, cy + h, cross)
            }
        }
        cell.addView(plus, cellChildParams(dp(46)))
        cell.setOnClickListener {
            val pkg = currentPackage()
            if (pkg == null) {
                Toast.makeText(context, "取不到当前应用", Toast.LENGTH_SHORT).show()
            } else {
                pinApp(pkg)
            }
        }
        return cell
    }

    private fun pinApp(pkg: String) {
        val pinned = OneStepPrefs.pinnedApps(context).toMutableList()
        if (pinned.contains(pkg)) return
        if (pinned.size >= MAX_SLOTS) {
            Toast.makeText(context, "边栏应用最多 ${MAX_SLOTS} 个", Toast.LENGTH_SHORT).show()
            return
        }
        pinned.add(pkg)
        OneStepPrefs.setPinnedApps(context, pinned)
        rebuild()
        // 官方加条目 pop：fade + scale 0.3→1.2→1
        val last = slotsContainer.getChildAt(slotsContainer.childCount - 1 - ((2 - pinned.size).coerceAtLeast(1)))
        last?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(0)?.start()
        last?.let { popAnim(it) }
    }

    private fun removePinned(pkg: String) {
        val pinned = OneStepPrefs.pinnedApps(context).toMutableList()
        pinned.remove(pkg)
        OneStepPrefs.setPinnedApps(context, pinned)
        rebuild()
    }

    private fun popAnim(v: View) {
        v.scaleX = 0.3f
        v.scaleY = 0.3f
        v.alpha = 0f
        v.animate().scaleX(1.2f).scaleY(1.2f).alpha(1f).setDuration(OneStepAnim.ITEM_POP)
            .interpolator = OneStepAnim.CubicOut()
        v.animate().scaleX(1f).scaleY(1f).setDuration(OneStepAnim.ITEM_POP)
            .setStartDelay(OneStepAnim.ITEM_POP).start()
    }

    private fun launchApp(pkg: String) {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "打不开 $pkg", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "没有可启动入口", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cellChildParams(size: Int): LayoutParams {
        return LayoutParams(size, size).apply { gravity = Gravity.CENTER }
    }

    /** OS 8 槽位格：高约为宽的 1.6 倍，格间 1px 分隔（实测 r2 截屏比例） */
    private inner class FrameCell(ctx: Context) : LinearLayout(ctx) {
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = ColorDrawable(Color.TRANSPARENT)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val p = Paint().apply { color = 0x22FFFFFF; strokeWidth = resources.displayMetrics.density }
            c.drawLine(0f, height - 1f, width.toFloat(), height - 1f, p)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val MAX_SLOTS = 6
    }
}

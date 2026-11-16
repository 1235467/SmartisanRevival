package com.smartisanrevival.bang.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.smartisanrevival.bang.BangPrefs
import com.smartisanrevival.bang.R
import com.smartisanrevival.bang.model.BangResult
import com.smartisanrevival.bang.model.Granularity
import com.smartisanrevival.bang.model.WordBlock
import com.smartisanrevival.bang.text.WordSegmenter

/**
 * 词块面板。结构对齐 OS 8 原版：
 * 顶部条、字数、词块区、底部关闭键各自固定，词块区单独滚动（左右留白 17dp/16dp）。
 *
 * 工具栏在布局里占固定一格，没选中时透明不可点。选中只改这一格的透明度，
 * 词块位置不动——原版靠这条保证「选东西时字不会跑」。
 */
class BangPanelView(
    context: Context,
    bangResult: BangResult,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    companion object {
        const val SHEET_BG = 0xFFF0EEEF.toInt()
        const val TEXT_DARK = 0xFF1A1A1A.toInt()
        const val TEXT_GRAY = 0xFF9E9E9E.toInt()
        const val ICON_TINT = 0xFF5A5A5A.toInt()
        private const val ROW_STAGGER_MS = 18L
        private const val ROW_STAGGER_CAP_MS = 260L
        private const val CHIP_TEXT_SP = 15f

        // 原版 BoomOcrActivity 的圆圈参数
        private const val TOUCH_SCALE_FROM = 2f
        private const val TOUCH_SCALE_TO_1 = 0.2f
        private const val TOUCH_SCALE_TO_2 = 1.15f
        private const val TOUCH_ALPHA_FROM = 0.4f
        private const val TOUCH_ALPHA_TO = 1f
        private const val SCALE_1_DURATION = 400L
        private const val SCALE_2_DURATION = 200L
        private const val CIRCLE_END_SCALE = 4f
        private const val CIRCLE_END_DURATION = 100L

        // 分段提示，文案取自原版 top_warning_1 / bottom_warning_1
        private const val HINT_PREV = "下拉爆炸上一段内容"
        private const val HINT_NEXT = "上拉爆炸下一段内容"
    }

    private var blocks: List<WordBlock> = bangResult.blocks
    private var sourceText = bangResult.sourceText
    private val touchX = bangResult.touchX
    private val touchY = bangResult.touchY
    private val chipViews = ArrayList<ChipView>()
    private val rowsHost = ChipHost(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
    }
    private val rowsScroller: ScrollView
    private lateinit var sheet: FrameLayout
    private lateinit var dimView: View
    private var circleView: FrameLayout? = null
    /** 圆圈还在手指上转的时候，词块还没出来，这次按压的后续事件不能拿去点面板。 */
    private var introPlaying = true
    private var soundPlayed = false
    private var soundWanted = false
    private var hintView: TextView? = null
    private var segments: List<String> = bangResult.segments.ifEmpty { listOf(bangResult.sourceText) }
    /** 已经追加进面板的段落区间（含端点）。原版是往已有词块里追加，不替换当前段。 */
    private var loadedFrom: Int = bangResult.segmentIndex.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
    private var loadedTo: Int = loadedFrom
    private var overscroll = 0
    private var overscrollDir = 0
    private var overscrollStartY = 0f
    private var hintShown = false
    private var downY = 0f
    private var downScrollY = 0
    private var tappedBlank = false
    private var copyAnimating = false
    private var searchPopup: SearchPopupView? = null
    private lateinit var copyBtn: ImageView
    private val actionStrip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        alpha = 0f
        visibility = View.INVISIBLE
        clipChildren = false
        clipToPadding = false
        setPadding(dp(8), 0, dp(8), 0)
    }
    private val counterView: TextView
    private var selected = LinkedHashSet<Int>()
    private var soundId = 0
    private val wordPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spF(CHIP_TEXT_SP)
    }
    private val puncPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spF(CHIP_TEXT_SP)
    }
    private val wordPadH: Int = drawablePaddingH(R.drawable.boom_chips_bg_normal)
    private val puncPadH: Int = drawablePaddingH(R.drawable.boom_chips_punctuate_normal)
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()
        ).build()

    private class ChipView(val block: WordBlock, val tv: TextView, val row: Int) {
        var selected = false
    }

    /**
     * 词块容器。只接管落在词块上的按压（划选），其余事件交回给外层 ScrollView：
     * 原版 SwipeSelectView 是 LinearLayout 的子类，滚动由外面的 CustomScrollView 负责，
     * 滑选和滚动才能同时成立。
     */
    private inner class ChipHost(context: Context) : LinearLayout(context) {
        private var anchorId = -1
        private var additive = true
        private var moved = false
        private var boundLo = -1
        private var boundHi = -1
        private var autoVelocity = 0
        private val edgeZone = dpF(56)
        private val autoScroll = object : Runnable {
            override fun run() {
                if (autoVelocity == 0) return
                rowsScroller.scrollBy(0, autoVelocity)
                postDelayed(this, 20)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = chipAt(ev.rawX, ev.rawY) ?: return false
                    anchorId = hit.block.id
                    additive = !hit.selected
                    moved = false
                    boundLo = hit.block.id
                    boundHi = hit.block.id
                    // 原版语义：从已选中的词块起滑 = 取消这一段；从未选中的起滑 = 选中
                    if (hit.selected) deselect(hit.block.id) else select(hit.block.id)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchorId == -1) return true
                    // 拖到上下边缘时自己滚，原版 SwipeSelectView 也是这套
                    val loc = IntArray(2)
                    rowsScroller.getLocationOnScreen(loc)
                    val top = loc[1] + edgeZone
                    val bottom = loc[1] + rowsScroller.height - edgeZone
                    autoVelocity = when {
                        ev.rawY < top -> ((ev.rawY - top) / 2).toInt()
                        ev.rawY > bottom -> ((ev.rawY - bottom) / 2).toInt()
                        else -> 0
                    }
                    removeCallbacks(autoScroll)
                    if (autoVelocity != 0) postDelayed(autoScroll, 20)

                    val hit = chipAt(ev.rawX, ev.rawY) ?: return true
                    boundLo = minOf(boundLo, hit.block.id)
                    boundHi = maxOf(boundHi, hit.block.id)
                    if (!moved && hit.block.id != anchorId) {
                        moved = true
                        // 进入划选就别让滚动器把事件抢走，否则拖一半会变成滚动
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    val lo = minOf(anchorId, hit.block.id)
                    val hi = maxOf(anchorId, hit.block.id)
                    chipViews.forEach { cv ->
                        val id = cv.block.id
                        if (id < boundLo || id > boundHi) return@forEach
                        val inRange = id in lo..hi
                        if (additive) {
                            if (inRange) select(id)
                        } else {
                            if (inRange) deselect(id) else select(id)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 竖向拖动被滚动器接手时，单个词块的那次「按下即选中」要撤回
                    if (ev.actionMasked == MotionEvent.ACTION_CANCEL && !moved && anchorId != -1) {
                        if (additive) deselect(anchorId) else select(anchorId)
                    }
                    anchorId = -1
                    autoVelocity = 0
                    removeCallbacks(autoScroll)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return false
        }
    }

    init {
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0 && soundWanted) playBangSound()
        }
        soundId = soundPool.load(context, R.raw.bang, 1)
        clipChildren = false
        setOnClickListener { dismiss() }

        dimView = View(context).apply { setBackgroundColor(0x59000000); alpha = 0f }
        addView(dimView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        sheet = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(SHEET_BG)
                cornerRadii = floatArrayOf(dpF(16), dpF(16), dpF(16), dpF(16), 0f, 0f, 0f, 0f)
            }
            isClickable = true
            alpha = 0f
        }
        addView(
            sheet,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dp(72)
                gravity = Gravity.BOTTOM
            }
        )

        counterView = TextView(context).apply {
            textSize = 12f
            setTextColor(TEXT_GRAY)
            gravity = Gravity.END
            setPadding(0, dp(6), dp(16), dp(6))
        }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            buildHeader(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        )
        column.addView(
            counterView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        actionStrip.addView(iconBtn(R.drawable.sel_btn_search) {
            openSearch(selectedText(), SearchPopupView.Group.WEB)
        })
        actionStrip.addView(iconBtn(R.drawable.sel_btn_dict) {
            openSearch(selectedText(), SearchPopupView.Group.DICT)
        })
        actionStrip.addView(iconBtn(R.drawable.sel_btn_cut) { splitSelected() })
        actionStrip.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        actionStrip.addView(iconBtn(R.drawable.sel_btn_share) { shareText(selectedText()) })
        copyBtn = iconBtn(R.drawable.sel_btn_copy) { copySelected() }
        actionStrip.addView(copyBtn)
        actionStrip.addView(iconBtn(R.drawable.sel_btn_close) { clearSelection() })
        column.addView(
            actionStrip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        )

        val scroller = object : ScrollView(context) {
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                handleOverscroll(ev)
                return super.onTouchEvent(ev)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        rowsScroller = scroller
        rowsHost.setPadding(dp(17), dp(8), dp(16), dp(24))
        scroller.addView(
            rowsHost,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        column.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 分段提示条：贴在词块区上下缘，拉过头时浮现
        hintView = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF6B6B6B.toInt())
            gravity = Gravity.CENTER
            alpha = 0f
            visibility = View.INVISIBLE
            setPadding(0, dp(10), 0, dp(10))
        }
        column.addView(
            hintView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val bottom = FrameLayout(context).apply { minimumHeight = dp(56) }
        val close = ImageView(context).apply {
            setImageResource(R.drawable.bigbang_btn_close_normal)
            setOnClickListener { dismiss() }
        }
        bottom.addView(close, FrameLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER })
        column.addView(
            bottom,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        )

        sheet.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // 词块先排好但先藏起来。圆圈单独浮在手指上，原界面还看得到。
        // 圆圈散开的同一刻，面板和词块才从这一点飞出来，只炸一次。
        buildChips(animate = false)
        val circle = buildCircleOverlay()
        val circleSize = dp(120)
        addView(circle, FrameLayout.LayoutParams(circleSize, circleSize))
        circleView = circle
        circle.post {
            placeCircle(circle)
            playCircleIntro()
        }
        vibrate(15)
    }

    /**
     * 进入动画，对齐原版 BoomOcrActivity：
     * 圆圈先出现在按压点，底下还是原来的界面。
     * 2f → 0.2f（400ms，透明度 0.4→1）→ 弹回 1.15f（200ms）。
     * 字在按下时就已经拿到了，所以不再空转呼吸；接着涨到 4f 淡出，
     * 同时面板露出来、词块从这一点飞出。原版也是识别完成后立刻散开再打开结果页。
     */
    private fun buildCircleOverlay(): FrameLayout {
        val wrap = FrameLayout(context)
        val size = dp(120)
        val ring = ImageView(context).apply {
            setImageResource(R.drawable.boom_loop)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val light = ImageView(context).apply {
            setImageResource(R.drawable.boom_light)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val lp = FrameLayout.LayoutParams(size, size)
        wrap.addView(ring, FrameLayout.LayoutParams(lp))
        wrap.addView(light, FrameLayout.LayoutParams(lp))
        wrap.alpha = TOUCH_ALPHA_FROM
        wrap.scaleX = TOUCH_SCALE_FROM
        wrap.scaleY = TOUCH_SCALE_FROM
        light.animate().rotation(360f).setDuration(SCALE_1_DURATION + SCALE_2_DURATION)
            .setInterpolator(android.view.animation.LinearInterpolator()).start()
        return wrap
    }

    private fun placeCircle(circle: FrameLayout) {
        val size = circle.width.takeIf { it > 0 } ?: dp(120)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val x = if (touchX >= 0) touchX else loc[0] + width / 2
        val y = if (touchY >= 0) touchY else loc[1] + height / 2
        circle.x = x - loc[0] - size / 2f
        circle.y = y - loc[1] - size / 2f
    }

    private fun playCircleIntro() {
        val circle = circleView ?: return
        val cubic = android.view.animation.PathInterpolator(0.55f, 0f, 1f, 0.45f)
        circle.animate()
            .scaleX(TOUCH_SCALE_TO_1).scaleY(TOUCH_SCALE_TO_1)
            .alpha(TOUCH_ALPHA_TO)
            .setDuration(SCALE_1_DURATION)
            .setInterpolator(cubic)
            .withEndAction {
                circle.animate()
                    .scaleX(TOUCH_SCALE_TO_2).scaleY(TOUCH_SCALE_TO_2)
                    .setDuration(SCALE_2_DURATION)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .withEndAction { bloomAndOpen(circle) }
                    .start()
            }
            .start()
    }

    /** 圆圈散开和词块飞出是同一次动作。 */
    private fun bloomAndOpen(circle: FrameLayout) {
        dimView.animate().alpha(1f).setDuration(200).start()
        sheet.animate().alpha(1f).setDuration(200).start()
        playBoomAnim()
        soundWanted = true
        playBangSound()
        circle.animate()
            .scaleX(CIRCLE_END_SCALE).scaleY(CIRCLE_END_SCALE)
            .alpha(0f)
            .setDuration(CIRCLE_END_DURATION)
            .withEndAction {
                removeCircle(circle)
                introPlaying = false
            }
            .start()
    }

    private fun playBangSound() {
        if (soundPlayed || soundId == 0 || !BangPrefs.soundEnabled(context)) return
        val stream = soundPool.play(soundId, 0.9f, 0.9f, 1, 0, 1f)
        if (stream != 0) soundPlayed = true
    }

    private fun removeCircle(circle: FrameLayout) {
        (circle.parent as? ViewGroup)?.removeView(circle)
        circleView = null
    }

    /**
     * 原版的分段触发：到顶继续下拉 → 往上方追加上一段；到底继续上拉 → 往下方追加下一段。
     * 追加而不是替换——当前已经炸出来的词块全部保留。
     * 内容不满一屏时滚不动，所以整块面板的竖向拖动也走同一条判定。
     * 拉过约 56dp 才认，避免普通滚动就误触发。松手时追加。
     */
    private fun handleOverscroll(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                overscroll = 0
                overscrollDir = 0
                overscrollStartY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - overscrollStartY
                val atTop = !rowsScroller.canScrollVertically(-1)
                val atBottom = !rowsScroller.canScrollVertically(1)
                if (atTop && dy > 0 && loadedFrom > 0) {
                    overscrollDir = -1
                    overscroll = dy.toInt()
                    showHint(HINT_PREV, dy / dp(150).toFloat())
                } else if (atBottom && dy < 0 && loadedTo < segments.size - 1) {
                    overscrollDir = 1
                    overscroll = (-dy).toInt()
                    showHint(HINT_NEXT, (-dy) / dp(150).toFloat())
                } else {
                    overscrollDir = 0
                    overscroll = 0
                    hideHint()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (overscroll > dp(56) && overscrollDir != 0) {
                    appendSegment(overscrollDir)
                }
                overscroll = 0
                overscrollDir = 0
                hideHint()
            }
        }
        return false
    }

    private fun showHint(text: String, progress: Float) {
        val hv = hintView ?: return
        hintShown = true
        hv.text = text
        if (hv.visibility != View.VISIBLE) hv.visibility = View.VISIBLE
        hv.alpha = progress.coerceIn(0f, 1f)
    }

    private fun hideHint() {
        if (!hintShown) return
        hintShown = false
        hintView?.animate()?.alpha(0f)?.setDuration(100)?.withEndAction {
            hintView?.visibility = View.INVISIBLE
        }?.start()
    }

    /** 追加相邻段落：往下追加接在末尾，往上追加插在最前面；已有词块的内容和选中状态都保留。 */
    private fun appendSegment(dir: Int) {
        val target = if (dir < 0) loadedFrom - 1 else loadedTo + 1
        if (target !in segments.indices) return

        val wasAtBottom = dir > 0
        val oldHeight = rowsHost.height
        val oldScroll = rowsScroller.scrollY

        if (dir < 0) loadedFrom = target else loadedTo = target
        sourceText = joinLoaded()
        val anchor = android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, dp(40))
        blocks = WordSegmenter.toBlocks(sourceText, anchor, com.smartisanrevival.bang.model.Source.NODE)

        // 重建时沿用原有选中集合：文本按顺序拼接，索引会整体平移，所以按文字重新对上
        val keep = chipViews.filter { it.selected }.map { it.block.text }
        selected.clear()
        buildChips(animate = false)
        restoreSelection(keep)

        if (wasAtBottom) {
            rowsScroller.post { rowsScroller.scrollTo(0, oldScroll + (rowsHost.height - oldHeight)) }
        } else {
            val added = rowsHost.height - oldHeight
            rowsScroller.post { rowsScroller.scrollTo(0, oldScroll + added.coerceAtLeast(0)) }
        }
        vibrate(12)
    }

    private fun joinLoaded(): String {
        if (segments.isEmpty()) return sourceText
        val from = loadedFrom.coerceIn(0, segments.size - 1)
        val to = loadedTo.coerceIn(from, segments.size - 1)
        return segments.subList(from, to + 1).joinToString("\n")
    }

    /** 按文字把选中态尽量还原到新词块上（同一段文字重复出现时按顺序认）。 */
    private fun restoreSelection(texts: List<String>) {
        if (texts.isEmpty()) return
        val pending = ArrayList(texts)
        chipViews.forEach { cv ->
            if (pending.isNotEmpty() && cv.block.text == pending.first()) {
                select(cv.block.id)
                pending.removeAt(0)
            }
        }
    }

    private fun buildHeader(): FrameLayout {
        val header = FrameLayout(context).apply { minimumHeight = dp(56) }
        header.addView(tintedIcon(R.drawable.action_btn_write_normal), FrameLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = dp(12)
        })
        header.addView(tintedIcon(R.drawable.action_btn_choosetext_normal), FrameLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = dp(56)
        })
        val title = TextView(context).apply {
            text = "大爆炸"
            textSize = 17f
            setTextColor(0x33000000)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }
        header.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        header.addView(tintedIcon(R.drawable.action_btn_more_normal), FrameLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dp(8)
        })
        val share = tintedIcon(R.drawable.action_btn_share_normal)
        share.setOnClickListener { shareText(allText()) }
        header.addView(share, FrameLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dp(52)
        })
        return header
    }

    private fun tintedIcon(res: Int): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(ICON_TINT, PorterDuff.Mode.SRC_IN)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    private fun iconBtn(res: Int, action: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(res)
        setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(dp(46), dp(46))
        lp.leftMargin = dp(4)
        lp.rightMargin = dp(4)
        layoutParams = lp
    }

    private fun buildChips(animate: Boolean) {
        if (sourceText.contains('\r')) {
            sourceText = sourceText.replace("\r\n", "\n").replace('\r', '\n')
        }
        val pageWidth = resources.displayMetrics.widthPixels - dp(17) - dp(16)
        val anchor = android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, dp(40))
        val text = sourceText.ifEmpty { blocks.joinToString("") { it.text } }
        val rows = WordSegmenter.layoutRows(text, anchor, pageWidth) { measureChip(it) }
        blocks = rows.flatten()
        renderRows(rows, animate)
    }

    private fun renderRows(rows: List<List<WordBlock>>, animate: Boolean) {
        rowsHost.removeAllViews()
        chipViews.clear()
        selected.clear()
        syncActionStrip()
        for ((lineIndex, row) in rows.withIndex()) {
            if (row.isEmpty()) {
                rowsHost.addView(
                    View(context),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14))
                )
                continue
            }
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { block ->
                val tv = makeChip(block)
                rowView.addView(tv)
                chipViews.add(ChipView(block, tv, lineIndex))
            }
            rowsHost.addView(
                rowView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        if (animate) {
            rowsHost.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    rowsHost.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    playBoomAnim()
                }
            })
        }
        val chars = sourceText.ifEmpty { blocks.joinToString("") { it.text } }.length
        counterView.text = "共 $chars 字"
    }

    /**
     * 从按压点炸开。原版逐行错开，不是整屏同时出现；
     * 行号越大延迟越长，但封顶，否则长文要等好几秒。
     */
    private fun playBoomAnim() {
        val tx = if (touchX >= 0) touchX else resources.displayMetrics.widthPixels / 2
        val ty = if (touchY >= 0) touchY else resources.displayMetrics.heightPixels / 2
        val interp = DecelerateInterpolator(1.5f)
        chipViews.forEach { cv ->
            val loc = IntArray(2)
            cv.tv.getLocationOnScreen(loc)
            val cx = loc[0] + cv.tv.width / 2
            val cy = loc[1] + cv.tv.height / 2
            cv.tv.translationX = (tx - cx).toFloat()
            cv.tv.translationY = (ty - cy).toFloat()
            cv.tv.scaleX = 0.15f
            cv.tv.scaleY = 0.15f
            cv.tv.alpha = 0f
            cv.tv.animate()
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(minOf(cv.row * ROW_STAGGER_MS, ROW_STAGGER_CAP_MS))
                .setDuration(200)
                .setInterpolator(interp)
                .start()
        }
    }

    private fun measureChip(block: WordBlock): Int {
        val paint = if (block.isPunc) puncPaint else wordPaint
        val minPad = if (block.isPunc) dp(8) else dp(18)
        val bgPad = if (block.isPunc) puncPadH else wordPadH
        val min = if (block.isPunc) dp(21) else dp(33)
        val pad = maxOf(minPad, bgPad) + dp(2)
        return maxOf(min, pad + kotlin.math.ceil(paint.measureText(block.text)).toInt())
    }

    private fun makeChip(block: WordBlock): TextView = TextView(context).apply {
        tag = block.id
        text = block.text
        textSize = CHIP_TEXT_SP
        setTextColor(if (block.isPunc) 0xFF888888.toInt() else TEXT_DARK)
        gravity = Gravity.CENTER
        maxLines = 1
        isSingleLine = true
        setBackgroundResource(
            if (block.isPunc) R.drawable.boom_chips_punctuate_normal else R.drawable.boom_chips_bg_normal
        )
        val w = measureChip(block)
        layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
        }
        minimumHeight = dp(36)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    /**
     * 面板本身不吃触摸，只在旁边观察「点空白」。
     * 划选交给 ChipHost，滚动交给 ScrollView，底部关闭键是普通子视图。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (introPlaying) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                downScrollY = rowsScroller.scrollY
                tappedBlank = inWordArea(ev.rawX, ev.rawY) && chipAt(ev.rawX, ev.rawY) == null
            }
            MotionEvent.ACTION_UP -> {
                if (tappedBlank &&
                    kotlin.math.abs(ev.rawY - downY) <= dp(8) &&
                    rowsScroller.scrollY == downScrollY &&
                    inWordArea(ev.rawX, ev.rawY) && chipAt(ev.rawX, ev.rawY) == null
                ) {
                    post { onBlankTap() }
                }
                tappedBlank = false
            }
            MotionEvent.ACTION_CANCEL -> tappedBlank = false
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 落点是否在词块区内（不含顶部条、工具栏、底部关闭键） */
    private fun inWordArea(x: Float, y: Float): Boolean {
        val l = IntArray(2)
        rowsScroller.getLocationOnScreen(l)
        return x >= l[0] && x <= l[0] + rowsScroller.width &&
            y >= l[1] && y <= l[1] + rowsScroller.height
    }

    /** 原版点空白：有选中先清掉，没选中才关面板。 */
    private fun onBlankTap() {
        if (selected.isEmpty()) dismiss() else clearSelection()
    }

    private fun chipAt(x: Float, y: Float): ChipView? {
        for (cv in chipViews) {
            val l = IntArray(2)
            cv.tv.getLocationOnScreen(l)
            if (x >= l[0] && x <= l[0] + cv.tv.width && y >= l[1] && y <= l[1] + cv.tv.height) return cv
        }
        return null
    }

    private fun select(id: Int) {
        val cv = chipViews.find { it.block.id == id } ?: return
        if (cv.selected) return
        cv.selected = true
        cv.tv.setBackgroundResource(R.drawable.boom_chips_bg_pressed)
        cv.tv.setTextColor(Color.WHITE)
        selected.add(id)
        syncActionStrip()
    }

    private fun deselect(id: Int) {
        val cv = chipViews.find { it.block.id == id } ?: return
        if (!cv.selected) return
        cv.selected = false
        cv.tv.setBackgroundResource(
            if (cv.block.isPunc) R.drawable.boom_chips_punctuate_normal else R.drawable.boom_chips_bg_normal
        )
        cv.tv.setTextColor(if (cv.block.isPunc) 0xFF888888.toInt() else TEXT_DARK)
        selected.remove(id)
        syncActionStrip()
    }

    private fun clearSelection() {
        chipViews.forEach { if (it.selected) deselect(it.block.id) }
    }

    /** 工具栏始终占同一格，只改透明度，词块位置不动。 */
    private fun syncActionStrip() {
        val show = selected.isNotEmpty() || copyAnimating
        if (show) {
            if (actionStrip.visibility != View.VISIBLE) {
                actionStrip.visibility = View.VISIBLE
                actionStrip.animate().alpha(1f).setDuration(150).start()
            }
        } else if (actionStrip.visibility == View.VISIBLE) {
            actionStrip.animate().alpha(0f).setDuration(120)
                .withEndAction { actionStrip.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun selectedText(): String {
        if (selected.isEmpty()) return ""
        if (selected.size == chipViews.size) return allText()
        val sb = StringBuilder()
        var prev = -1
        for (id in selected.sorted()) {
            val cv = chipViews.find { it.block.id == id } ?: continue
            if (prev != -1 && id == prev + 1) {
                // 相邻词块之间把中间的空格和标点一起带上，复制出来才是原文
                val from = chipViews[prev].block.start + chipViews[prev].block.text.length
                val to = cv.block.start
                if (sourceText.isNotEmpty() && from in 0..sourceText.length && to in from..sourceText.length) {
                    sb.append(sourceText, from, to)
                }
            }
            sb.append(cv.block.text)
            prev = id
        }
        return sb.toString()
    }

    private fun allText(): String = sourceText.ifEmpty { chipViews.joinToString("") { it.block.text } }

    private fun copySelected() {
        val text = selectedText()
        if (text.isEmpty() || copyAnimating) return
        val cm = ctx().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("大爆炸", text))
        val flying = chipViews.filter { it.selected }
        absorbIntoCopy(flying)
        playCopyMouth()
    }

    /** 选中的词块飞进复制钮。飞的是影子，原词块还在，面板不关。 */
    private fun absorbIntoCopy(chips: List<ChipView>) {
        if (chips.isEmpty()) return
        val root = IntArray(2)
        getLocationOnScreen(root)
        val dest = IntArray(2)
        copyBtn.getLocationOnScreen(dest)
        val dx = dest[0] + copyBtn.width / 2f - root[0]
        val dy = dest[1] + copyBtn.height / 2f - root[1]
        chips.forEach { cv ->
            val loc = IntArray(2)
            cv.tv.getLocationOnScreen(loc)
            val w = cv.tv.width.coerceAtLeast(dp(33))
            val h = cv.tv.height.coerceAtLeast(dp(36))
            val ghost = TextView(context).apply {
                text = cv.tv.text
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.boom_chips_bg_pressed)
            }
            addView(ghost, FrameLayout.LayoutParams(w, h))
            ghost.x = (loc[0] - root[0]).toFloat()
            ghost.y = (loc[1] - root[1]).toFloat()
            ghost.animate()
                .x(dx - w / 2f)
                .y(dy - h / 2f)
                .scaleX(0.12f)
                .scaleY(0.12f)
                .alpha(0.1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction { removeView(ghost) }
                .start()
        }
    }

    /**
     * 原版 copy_animation：复制钮开口吸字，停一下，再合回原来的圆钮。
     * 序列帧 434ms，合上之后清选中，面板留着。
     */
    private fun playCopyMouth() {
        copyAnimating = true
        val root = IntArray(2)
        getLocationOnScreen(root)
        val dest = IntArray(2)
        copyBtn.getLocationOnScreen(dest)
        val size = copyBtn.width.coerceAtLeast(dp(46))
        val mouth = ImageView(context).apply {
            setImageResource(R.drawable.copy_animation)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        addView(mouth, FrameLayout.LayoutParams(size, size))
        mouth.x = (dest[0] - root[0]).toFloat()
        mouth.y = (dest[1] - root[1]).toFloat()
        copyBtn.alpha = 0f
        mouth.post {
            (mouth.drawable as? AnimationDrawable)?.start()
        }
        mouth.postDelayed({
            removeView(mouth)
            copyBtn.alpha = 1f
            copyAnimating = false
            clearSelection()
        }, 450)
    }

    private fun openSearch(raw: String, group: SearchPopupView.Group) {
        val text = raw.replace('\n', ' ').trim()
        if (text.isEmpty()) {
            toast(if (group == SearchPopupView.Group.DICT) "先选中要查的词" else "先选中要搜索的字")
            return
        }
        searchPopup?.dismiss()
        val popup = try {
            SearchPopupView(context, text, group) { searchPopup = null }
        } catch (e: Exception) {
            android.util.Log.e("Bang", "search popup failed", e)
            toast("打不开搜索")
            return
        }
        searchPopup = popup
        addView(popup, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun shareText(text: String) {
        if (text.isEmpty()) return
        ctx().startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
                "分享"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun splitSelected() {
        if (selected.isEmpty()) return
        val byRow = chipViews.groupBy { it.row }
        val maxRow = chipViews.maxOf { it.row }
        val newRows = ArrayList<List<WordBlock>>()
        val selectAfter = ArrayList<Int>()
        var nextId = 0
        for (r in 0..maxRow) {
            val row = byRow[r]
            if (row == null) {
                newRows.add(emptyList())
                continue
            }
            val line = ArrayList<WordBlock>()
            for (cv in row) {
                val cut = cv.selected && !cv.block.isPunc && cv.block.text.length > 1
                if (!cut) {
                    line.add(cv.block.copy(id = nextId))
                    if (cv.selected) selectAfter.add(nextId)
                    nextId++
                    continue
                }
                var offset = cv.block.start
                cv.block.text.forEach { ch ->
                    line.add(
                        cv.block.copy(
                            id = nextId,
                            text = ch.toString(),
                            granular = Granularity.CHAR,
                            isPunc = WordSegmenter.isPuncChar(ch),
                            start = offset
                        )
                    )
                    selectAfter.add(nextId)
                    nextId++
                    offset++
                }
            }
            newRows.add(line)
        }
        blocks = newRows.flatten()
        renderRows(newRows, animate = false)
        selectAfter.forEach { select(it) }
        vibrate(10)
    }

    private fun ctx(): Context = context
    private fun toast(s: String) = Toast.makeText(ctx(), s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Int): Float = v * resources.displayMetrics.density
    private fun spF(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun drawablePaddingH(res: Int): Int {
        val d = context.getDrawable(res) ?: return 0
        val r = android.graphics.Rect()
        return if (d.getPadding(r)) r.left + r.right else 0
    }
    private fun vibrate(ms: Long) {
        val v = ctx().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun dismiss() {
        searchPopup?.destroyImmediate()
        searchPopup = null
        soundPool.release()
        onDismiss()
    }
}

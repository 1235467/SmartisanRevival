package com.smartisanrevival.bang.text

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.smartisanrevival.bang.model.BangResult
import com.smartisanrevival.bang.model.Source
import com.smartisanrevival.bang.model.WordBlock

/**
 * 从无障碍树取字。只走按压点所在的那个窗口：
 * 遍历全部窗口在实机上要好几秒，按压后等这么久等于没反应。
 */
object NodeTextExtractor {

    private const val MAX_NODES = 2500
    private const val MIN_TEXT_LEN = 1
    /** 一段大约多少行。手机上词块面板一屏约 10 行，取这个量刚好不用滚就能看全。 */
    private const val SEGMENT_CHARS = 10

    /** 命中的是 (去空白文本, 原始文本, 边界, 包名) */
    internal data class Hit(
        val text: String,
        val raw: String,
        val bounds: Rect,
        val pkg: String,
        /** 按压点落在全文的第几个字符。-1 表示节点没给出每个字的位置。 */
        val charIndex: Int = -1
    )

    fun extractAtPoint(service: AccessibilityService, x: Int, y: Int): BangResult? {
        val t0 = System.currentTimeMillis()
        val hit = findTextNodeAt(service, x, y) ?: return null
        val nodeText = hit.raw
        val bounds = hit.bounds
        val pkg = hit.pkg

        // 备忘录/浏览器常把整篇文章塞进一个节点，单个节点几千字。
        // 原版一次只炸「当前这一段」，上下拉再炸相邻段。这里按行分组，
        // 每组大致填满一屏，既能保持原版那种「一段一段」的观感，又不依赖空行。
        val segments = splitByScreen(nodeText)
        val useSegments = segments.size > 1
        val text = if (useSegments) {
            val lineH = estimateLineHeight(service, x, y)
            val at = if (hit.charIndex >= 0) hit.charIndex
            else paragraphIndexAt(nodeText, bounds, y, lineH)
            val picked = segments.firstOrNull { at in it.second }?.first ?: segments.first().first
            android.util.Log.i(
                "Bang",
                "seg: lineH=$lineH chars=${nodeText.length} top=${bounds.top} y=$y at=$at " +
                    "char=${hit.charIndex} segs=${segments.size} pick=${picked.take(16)}"
            )
            picked
        } else nodeText

        val blocks = WordSegmenter.toBlocks(text, bounds, Source.NODE)
        if (blocks.isEmpty()) return null

        val segTexts: List<String>
        val index: Int
        if (useSegments) {
            segTexts = segments.map { it.first }
            index = segTexts.indexOf(text).coerceAtLeast(0)
        } else {
            val byNode = collectSegments(service, x, y, bounds, text)
            segTexts = byNode.map { it.second }
            index = byNode.indexOfFirst { it.first }.coerceAtLeast(0)
        }

        return BangResult(
            blocks = blocks,
            sourceWindowPkg = pkg,
            extractMs = System.currentTimeMillis() - t0,
            touchX = x,
            touchY = y,
            sourceText = text,
            segments = segTexts,
            segmentIndex = index
        )
    }

    /**
     * 按压点命中的最小无字节点边界。原版炸图片时默认框住整张图——
     * 无障碍树读不到字，但按住的 ImageView / 容器有自己的 bounds，拿它当初始框选。
     * 只走按压点所在窗口，复用 findTextNodeAt 的那套遍历。
     */
    fun hitBoundsAtPoint(service: AccessibilityService, x: Int, y: Int): Rect? {
        var best: Rect? = null
        var bestArea = Int.MAX_VALUE
        val slop = (service.resources.displayMetrics.density * 8).toInt()
        for (root in appWindowRoots(service, x to y)) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == "com.smartisanrevival.bang") continue
            walkContaining(root, x, y, slop) { node ->
                if (isChromeControl(node)) return@walkContaining
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.isEmpty || !b.contains(x, y)) return@walkContaining
                val area = b.width() * b.height()
                if (area < bestArea) {
                    bestArea = area
                    best = Rect(b)
                }
            }
        }
        return best
    }

    /**
     * 按行分组，每组约 SEGMENT_CHARS 行。返回 (段落文本, 在原文里的字符区间)。
     */
    private fun splitByScreen(text: String): List<Pair<String, IntRange>> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Pair<String, IntRange>>()
        var start = 0
        var lines = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                lines++
                if (lines >= SEGMENT_CHARS) {
                    val seg = text.substring(start, i)
                    if (seg.isNotBlank()) out.add(seg to (start until i))
                    start = i + 1
                    lines = 0
                }
            }
            i++
        }
        if (start < text.length) {
            val seg = text.substring(start)
            if (seg.isNotBlank()) out.add(seg to (start until text.length))
        }
        return out
    }

    /**
     * 估算按压点对应原文的哪个字符。
     *
     * 长节点有个坑：text 里是整篇文章（实测 2376 字），但 bounds 只覆盖屏幕上可见的那一块。
     * 所以不能按 bounds 高度占比去算。这里用同一窗口里单行节点的高度作为行高，
     * 再把 (按压点 - 节点顶) 换成行号；节点被滚出屏幕时用负的 top 补回偏移。
     */
    private fun paragraphIndexAt(
        text: String,
        bounds: Rect,
        y: Int,
        lineHeightPx: Int
    ): Int {
        val totalLines = text.count { it == '\n' } + 1
        if (totalLines <= 1) return 0
        val lh = lineHeightPx.coerceAtLeast(1)

        val line = ((y - bounds.top).toFloat() / lh).toInt().coerceIn(0, totalLines - 1)

        var cur = 0
        for (idx in text.indices) {
            if (cur == line) return idx
            if (text[idx] == '\n') cur++
        }
        return 0
    }

    /** 用同窗口单行文字节点的高度中位数估算行高，拿不到就用 60px 兜底。 */
    private fun estimateLineHeight(service: AccessibilityService, x: Int, y: Int): Int {
        val heights = ArrayList<Int>()
        for (root in appWindowRoots(service, x to y)) {
            walk(root) { node ->
                val t = node.text?.toString()?.trim().orEmpty()
                if (t.isEmpty() || t.contains('\n')) return@walk
                val b = Rect()
                node.getBoundsInScreen(b)
                if (!b.isEmpty && b.height() in 20..200) heights.add(b.height())
            }
        }
        if (heights.isEmpty()) return 60
        heights.sort()
        return heights[heights.size / 2]
    }

    fun extractLargest(service: AccessibilityService): BangResult? {
        val t0 = System.currentTimeMillis()
        var bestText = ""
        var bestBounds = Rect()
        var bestPkg = ""
        for (root in appWindowRoots(service, null)) {
            val pkg = root.packageName?.toString() ?: continue
            walk(root) { node ->
                val t = node.text?.toString()?.trim().orEmpty()
                if (t.length > bestText.length && node.isVisibleToUser) {
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    if (!b.isEmpty && b.height() < 4000) {
                        bestText = t
                        bestBounds = b
                        bestPkg = pkg
                    }
                }
            }
        }
        if (bestText.length < MIN_TEXT_LEN) return null
        val blocks = WordSegmenter.toBlocks(bestText, bestBounds, Source.NODE)
        if (blocks.isEmpty()) return null
        return BangResult(blocks, bestPkg, System.currentTimeMillis() - t0, -1, -1, bestText)
    }

    /**
     * 把按压点所在窗口里的文字节点按屏幕纵向顺序排好，相邻且间距不大的归为一段，
     * 空隙大的位置切开——正好对应原文里的空行分段。
     * 返回 (是否命中的那个节点, 该段文本)。
     */
    private fun collectSegments(
        service: AccessibilityService,
        x: Int,
        y: Int,
        hitBounds: Rect,
        hitText: String
    ): List<Pair<Boolean, String>> {
        val items = ArrayList<Hit>()
        for (root in appWindowRoots(service, x to y)) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == "com.smartisanrevival.bang") continue
            walk(root) { node ->
                val raw = readableText(node) ?: return@walk
                val t = raw.trim()
                if (!node.isVisibleToUser) return@walk
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.isEmpty) return@walk
                items.add(Hit(t, raw, Rect(b), pkg))
            }
        }
        if (items.size <= 1) return listOf(true to hitText)

        // 父节点的 contentDescription 和子节点的正文会叠在同一块区域，只留最里面那层
        val innermost = items.filter { item ->
            items.none { other ->
                other !== item && item.bounds.contains(other.bounds) && other.bounds != item.bounds
            }
        }
        items.clear()
        items.addAll(innermost)
        items.sortWith(compareBy({ it.bounds.top }, { it.bounds.left }))

        // 行距明显大于常态就当作新的一段
        val heights = items.map { it.bounds.height() }.sorted()
        val medianH = heights.getOrElse(heights.size / 2) { 60 }
        val gapLimit = (medianH * 1.6f).toInt().coerceAtLeast(24)

        val segments = ArrayList<Pair<Boolean, String>>()
        var cur = StringBuilder()
        var curHit = false
        var prevBottom = Int.MIN_VALUE

        for (it in items) {
            val isHit = it.bounds == hitBounds || (it.text == hitText && it.bounds.contains(x, y))
            val gap = if (prevBottom == Int.MIN_VALUE) 0 else it.bounds.top - prevBottom
            if (prevBottom != Int.MIN_VALUE && gap > gapLimit && cur.isNotEmpty()) {
                segments.add(curHit to cur.toString())
                cur = StringBuilder()
                curHit = false
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(it.text)
            if (isHit) curHit = true
            prevBottom = maxOf(prevBottom, it.bounds.bottom)
        }
        if (cur.isNotEmpty()) segments.add(curHit to cur.toString())

        return if (segments.isEmpty()) listOf(true to hitText) else segments
    }

    /**
     * 备忘录的字在 EditText.text 里，离根很近。微信 / QQ 的一条消息往往在很深的列表项上，
     * 而且经常只写在 contentDescription 里，text 是空的。
     * 只沿着「边界包住按压点」的那一支往下走，避免整棵树的前几百个节点把预算耗光。
     * 同一个点上优先用 text，没有 text 再用 contentDescription。
     */
    private fun findTextNodeAt(service: AccessibilityService, x: Int, y: Int): Hit? {
        var bestText: Hit? = null
        var bestTextNode: AccessibilityNodeInfo? = null
        var bestTextArea = Int.MAX_VALUE
        var bestDesc: Hit? = null
        var bestDescNode: AccessibilityNodeInfo? = null
        var bestDescArea = Int.MAX_VALUE
        var nearText: Hit? = null
        var nearTextNode: AccessibilityNodeInfo? = null
        var nearTextDist = Int.MAX_VALUE
        var nearDesc: Hit? = null
        var nearDescNode: AccessibilityNodeInfo? = null
        var nearDescDist = Int.MAX_VALUE
        var visited = 0
        var refreshes = 0
        // 拇指经常按在气泡留白上。QQ 还会把「某某的资料卡」铺在整行上，
        // 留白够大才够得着真正的消息 TextView。
        val slop = (service.resources.displayMetrics.density * 64).toInt()
        for (root in appWindowRoots(service, x to y)) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == "com.smartisanrevival.bang") continue
            visited += walkContaining(root, x, y, slop) { node ->
                if (isChromeControl(node)) return@walkContaining
                var rawText = node.text?.toString()
                var rawDesc = node.contentDescription?.toString()
                val cls = node.className?.toString().orEmpty()
                if (rawText.isNullOrBlank() && rawDesc.isNullOrBlank() &&
                    cls.contains("Text") && refreshes < 8 && node.refresh()
                ) {
                    refreshes++
                    rawText = node.text?.toString()
                    rawDesc = node.contentDescription?.toString()
                }
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.isEmpty) return@walkContaining
                val strict = b.contains(x, y)
                val area = b.width() * b.height()
                val dist = dist2(b, x, y)
                if (!rawText.isNullOrBlank() && rawText.trim().length >= MIN_TEXT_LEN) {
                    if (strict && area in 1 until bestTextArea) {
                        bestTextArea = area
                        bestText = Hit(rawText.trim(), rawText, Rect(b), pkg)
                        bestTextNode = node
                    } else if (!strict && dist < nearTextDist) {
                        nearTextDist = dist
                        nearText = Hit(rawText.trim(), rawText, Rect(b), pkg)
                        nearTextNode = node
                    }
                } else if (!rawDesc.isNullOrBlank() && rawDesc.trim().length >= MIN_TEXT_LEN &&
                    !isNoiseDescription(rawDesc)
                ) {
                    if (strict && area in 1 until bestDescArea) {
                        bestDescArea = area
                        bestDesc = Hit(rawDesc.trim(), rawDesc, Rect(b), pkg)
                        bestDescNode = node
                    } else if (!strict && dist < nearDescDist) {
                        nearDescDist = dist
                        nearDesc = Hit(rawDesc.trim(), rawDesc, Rect(b), pkg)
                        nearDescNode = node
                    }
                }
            }
        }
        // 附近有真正的 text（消息正文）时，不要被头像上的 contentDescription 抢走
        val hit = bestText ?: nearText ?: bestDesc ?: nearDesc
        val screen = service.resources.displayMetrics
        val screenArea = screen.widthPixels * screen.heightPixels
        // 整窗只有一句很短的说明（「微信」「QQ」）时，那不是消息正文
        val titleOnly = hit != null && bestText == null && nearText == null &&
            hit.text.length < 30 &&
            hit.bounds.width().toLong() * hit.bounds.height() > screenArea * 0.7
        if (hit == null || titleOnly) {
            val wins = appWindowRoots(service, x to y).joinToString { root ->
                val wb = Rect()
                root.getBoundsInScreen(wb)
                "${root.packageName}@${wb.flattenToString()}"
            }
            android.util.Log.i(
                "Bang",
                "no text at ($x,$y) visited=$visited titleOnly=$titleOnly wins=$wins"
            )
            return null
        } else {
            val via = when {
                bestText != null && hit == bestText -> "text"
                nearText != null && hit == nearText -> "near-text"
                bestDesc != null && hit == bestDesc -> "desc"
                else -> "near-desc"
            }
            val sourceNode = when (via) {
                "text" -> bestTextNode
                "desc" -> bestDescNode
                "near-text" -> nearTextNode
                else -> nearDescNode
            }
            val charIndex = sourceNode?.let { indexOfCharAt(it, hit.raw.length, x, y) } ?: -1
            android.util.Log.i(
                "Bang",
                "hit pkg=${hit.pkg} via=$via len=${hit.raw.length} char=$charIndex '${hit.text.take(24)}'"
            )
            return hit.copy(charIndex = charIndex)
        }
    }

    /**
     * 备忘录这种 WebView 把整篇文字塞在一个节点里，bounds 只是屏幕上那一块，
     * 滚到哪一行不会反映在 top 上。向系统要每个字的屏幕位置，才能对上拇指。
     */
    private fun indexOfCharAt(node: AccessibilityNodeInfo, length: Int, x: Int, y: Int): Int {
        if (length <= 0) return -1
        val slop = 56f
        var closest = -1
        var closestD = slop * slop
        val args = android.os.Bundle()
        var start = 0
        while (start < length) {
            val n = minOf(400, length - start)
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, n)
            val ok = try {
                node.refreshWithExtraData(
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                    args
                )
            } catch (_: Exception) {
                false
            }
            if (!ok) return closest
            val rects = readCharRects(node) ?: return closest
            val fx = x.toFloat()
            val fy = y.toFloat()
            for (i in rects.indices) {
                val r = rects[i] ?: continue
                if (r.isEmpty) continue
                if (r.contains(fx, fy)) return start + i
                val dx = fx - r.centerX()
                val dy = fy - r.centerY()
                val d = dx * dx + dy * dy
                if (d < closestD) {
                    closestD = d
                    closest = start + i
                }
            }
            start += n
        }
        return closest
    }

    private fun readCharRects(node: AccessibilityNodeInfo): Array<out android.graphics.RectF?>? {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        val extra = node.extras ?: return null
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            extra.getParcelableArray(key, android.graphics.RectF::class.java)
        } else {
            @Suppress("DEPRECATION")
            extra.getParcelableArray(key)?.map { it as? android.graphics.RectF }?.toTypedArray()
        }
    }

    private fun dist2(b: Rect, x: Int, y: Int): Int {
        val cx = x.coerceIn(b.left, b.right)
        val cy = y.coerceIn(b.top, b.bottom)
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy
    }

    /** QQ 把头像说明写成「某某的资料卡」，盖住气泡留白。那不是消息。 */
    private fun isNoiseDescription(raw: String): Boolean {
        val t = raw.trim()
        return t.endsWith("的资料卡") || t.endsWith("的头像") || t.endsWith("头像")
    }

    /** 按钮、图标的说明文字不是正文。消息气泡本身往往是普通 View，不能按「可点击」排除。 */
    private fun isChromeControl(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        return cls.endsWith("ImageView") || cls.endsWith("ImageButton") ||
            cls.endsWith(".Button") || cls.endsWith("CheckBox") || cls.endsWith("Switch") ||
            cls.endsWith("SeekBar") || cls.endsWith("ProgressBar")
    }

    private fun readableText(node: AccessibilityNodeInfo): String? {
        if (isChromeControl(node)) return null
        val raw = node.text?.toString()
        if (!raw.isNullOrBlank() && raw.trim().length >= MIN_TEXT_LEN) return raw
        if (node.childCount == 0) {
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank() && desc.trim().length >= MIN_TEXT_LEN) return desc
        }
        return null
    }

    private fun appWindowRoots(
        service: AccessibilityService,
        point: Pair<Int, Int>?
    ): List<AccessibilityNodeInfo> {
        val windows: List<AccessibilityWindowInfo>? = try {
            service.windows
        } catch (_: Exception) {
            null
        }
        if (windows.isNullOrEmpty()) {
            return service.rootInActiveWindow?.let { listOf(it) } ?: emptyList()
        }

        val candidates = ArrayList<AccessibilityWindowInfo>()
        for (w in windows) {
            when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                AccessibilityWindowInfo.TYPE_SYSTEM -> continue
            }
            if (point != null) {
                val b = Rect()
                w.getBoundsInScreen(b)
                // 微信在这台机器上窗口 bounds 经常是 0×0，仍要进去走子树 / 交给 OCR。
                if (!b.isEmpty && !b.contains(point.first, point.second)) continue
            } else if (!w.isActive) {
                continue
            }
            candidates.add(w)
        }
        if (candidates.isEmpty()) {
            windows.firstOrNull { it.isActive }?.let { candidates.add(it) }
        }
        return candidates.mapNotNull { it.root }
    }

    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val seen = HashSet<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (queue.isNotEmpty() && n < MAX_NODES) {
            val node = queue.removeFirst()
            if (!seen.add(node)) continue
            n++
            visit(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
    }

    /**
     * 父节点的屏幕范围包不住按压点时，子节点也不会包住（正常布局）。
     * 范围是空的才继续往下，有些容器自己不报 bounds。
     */
    private fun walkContaining(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        slop: Int,
        visit: (AccessibilityNodeInfo) -> Unit
    ): Int {
        var n = 0
        fun rec(node: AccessibilityNodeInfo) {
            if (n >= MAX_NODES) return
            val b = Rect()
            node.getBoundsInScreen(b)
            val unknown = b.isEmpty
            val near = !unknown &&
                x >= b.left - slop && x < b.right + slop &&
                y >= b.top - slop && y < b.bottom + slop
            if (!unknown && !near) return
            n++
            visit(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                rec(child)
            }
        }
        try {
            root.refresh()
        } catch (_: Exception) {
        }
        rec(root)
        return n
    }
}

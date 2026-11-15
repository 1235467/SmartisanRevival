package com.smartisanrevival.bang.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import com.smartisanrevival.bang.model.BangResult
import com.smartisanrevival.bang.model.Granularity
import com.smartisanrevival.bang.model.Source
import com.smartisanrevival.bang.model.WordBlock
import com.smartisanrevival.bang.text.WordSegmenter

object OcrTextExtractor {
    private const val TAG = "BangOcr"

    fun extract(service: AccessibilityService, x: Int, y: Int): BangResult? {
        val t0 = System.currentTimeMillis()
        val shot = ScreenCapture.take(service)
        if (shot == null) {
            Log.i(TAG, "ocr no screenshot")
            return null
        }
        val lines = try {
            ScreenOcr.recognize(service, shot)
        } finally {
            shot.recycle()
        }
        if (lines.isEmpty()) return null

        val slop = (service.resources.displayMetrics.density * 96).toInt()
        val near = lines.filter { boxNear(it, x, y, slop) }.ifEmpty {
            val closest = lines.minByOrNull { dist(it, x, y) }
            if (closest != null) listOf(closest) else lines
        }
        val picked = if (near.size == 1) {
            val band = near.first()
            lines.filter { kotlin.math.abs(it.top - band.top) < ((band.bottom - band.top) * 1.6f).toInt().coerceAtLeast(40) }
                .sortedBy { it.left }
        } else near.sortedWith(compareBy({ it.top }, { it.left }))

        val blocks = ArrayList<WordBlock>()
        val sb = StringBuilder()
        var id = 0
        for (line in picked.sortedWith(compareBy({ it.top }, { it.left }))) {
            if (sb.isNotEmpty()) sb.append('\n')
            val tokens = WordSegmenter.tokenize(line.text)
            val total = tokens.sumOf { it.text.length }.coerceAtLeast(1)
            var acc = 0
            val w = (line.right - line.left).coerceAtLeast(1)
            for (tok in tokens) {
                val frac0 = acc.toFloat() / total
                acc += tok.text.length
                val frac1 = acc.toFloat() / total
                val left = line.left + (w * frac0).toInt()
                val right = line.left + (w * frac1).toInt()
                blocks.add(
                    WordBlock(
                        id = id++,
                        text = tok.text,
                        bounds = Rect(left, line.top, right.coerceAtLeast(left + 8), line.bottom),
                        source = Source.OCR,
                        lineIndex = 0,
                        granular = Granularity.WORD,
                        isPunc = tok.isPunc,
                        start = sb.length + tok.start
                    )
                )
            }
            sb.append(line.text)
        }
        if (blocks.isEmpty()) return null
        val pkg = foregroundPkg(service)
        Log.i(TAG, "ocr bang lines=${picked.size} blocks=${blocks.size} ${System.currentTimeMillis() - t0}ms")
        return BangResult(
            blocks = blocks,
            sourceWindowPkg = pkg,
            extractMs = System.currentTimeMillis() - t0,
            touchX = x,
            touchY = y,
            sourceText = sb.toString()
        )
    }

    private fun boxNear(line: ScreenOcr.Line, x: Int, y: Int, slop: Int): Boolean {
        return x >= line.left - slop && x <= line.right + slop &&
            y >= line.top - slop && y <= line.bottom + slop
    }

    private fun foregroundPkg(service: AccessibilityService): String {
        val self = service.packageName
        try {
            val focused = service.windows?.firstOrNull { w ->
                w.isFocused && w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            }?.root?.packageName?.toString()
            if (!focused.isNullOrBlank() && focused != self) return focused
            val any = service.windows?.mapNotNull { w ->
                if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
                w.root?.packageName?.toString()
            }?.firstOrNull { it != self }
            if (!any.isNullOrBlank()) return any
        } catch (_: Exception) {
        }
        val active = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        return if (active == self) "ocr" else active
    }

    private fun dist(line: ScreenOcr.Line, x: Int, y: Int): Int {
        val cx = (line.left + line.right) / 2
        val cy = (line.top + line.bottom) / 2
        return kotlin.math.abs(cx - x) + kotlin.math.abs(cy - y)
    }
}

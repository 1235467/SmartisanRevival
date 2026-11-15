package com.smartisanrevival.bang.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.smartisanrevival.bang.model.BangResult
import com.smartisanrevival.bang.model.Granularity
import com.smartisanrevival.bang.model.Source
import com.smartisanrevival.bang.model.WordBlock
import com.smartisanrevival.bang.text.WordSegmenter

/**
 * OCR 行 → 词块 + BangResult。
 * 原来是「整屏盲 OCR 挑按压点附近的行」，实测按在图片上挑不到东西，只会炸出空面板。
 * 现在的取字路径是原版炸图片的框选流（见 CropSelectView）：用户框好区域，
 * 服务把选区内的行映射回屏幕坐标后交给 buildBangResult。
 */
object OcrTextExtractor {
    private const val TAG = "BangOcr"

    /**
     * @param lines 已在屏幕坐标系里的行（调用方负责从选区位图空间映射回来）
     */
    fun buildBangResult(
        lines: List<ScreenOcr.Line>,
        touchX: Int,
        touchY: Int,
        pkg: String,
        t0: Long
    ): BangResult? {
        if (lines.isEmpty()) return null
        val blocks = ArrayList<WordBlock>()
        val sb = StringBuilder()
        var id = 0
        for (line in lines.sortedWith(compareBy({ it.top }, { it.left }))) {
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
        // t0 用 uptime 时钟（调用方 startCropOcr 传的是 SystemClock.uptimeMillis()）
        Log.i(TAG, "ocr bang lines=${lines.size} blocks=${blocks.size} ${SystemClock.uptimeMillis() - t0}ms")
        return BangResult(
            blocks = blocks,
            sourceWindowPkg = pkg,
            extractMs = SystemClock.uptimeMillis() - t0,
            touchX = touchX,
            touchY = touchY,
            sourceText = sb.toString()
        )
    }

    fun foregroundPkg(service: AccessibilityService): String {
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
}

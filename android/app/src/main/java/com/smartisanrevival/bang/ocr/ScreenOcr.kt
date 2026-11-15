package com.smartisanrevival.bang.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.github.hzkitty.RapidOCR
import io.github.hzkitty.entity.OcrResult

object ScreenOcr {
    private const val TAG = "BangOcr"
    @Volatile private var engine: RapidOCR? = null
    @Volatile private var failed = false
    @Volatile private var app: Context? = null

    fun warmup(ctx: Context) {
        app = ctx.applicationContext
        engine()
    }

    @Synchronized
    private fun engine(): RapidOCR? {
        if (failed) return null
        engine?.let { return it }
        val c = app ?: return null
        return try {
            val t0 = System.currentTimeMillis()
            val e = RapidOCR.create(c)
            engine = e
            Log.i(TAG, "rapidocr ready ${System.currentTimeMillis() - t0}ms")
            e
        } catch (t: Throwable) {
            Log.i(TAG, "rapidocr init failed: ${t.message}")
            failed = true
            null
        }
    }

    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float)

    fun recognize(ctx: Context, src: Bitmap): List<Line> {
        app = ctx.applicationContext
        val ocr = engine() ?: return emptyList()
        val scaled = scaleForOcr(src)
        val scale = src.width.toFloat() / scaled.width
        val result: OcrResult = try {
            ocr.run(scaled)
        } catch (t: Throwable) {
            Log.i(TAG, "ocr run failed: ${t.message}")
            if (scaled !== src) scaled.recycle()
            return emptyList()
        }
        if (scaled !== src) scaled.recycle()
        val lines = ArrayList<Line>()
        val recs = result.recRes ?: return emptyList()
        for (rec in recs) {
            val text = rec.text?.trim().orEmpty()
            if (text.isEmpty() || rec.confidence < 0.45f) continue
            val pts = rec.dtBoxes ?: continue
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = 0
            var maxY = 0
            for (p in pts) {
                val x = (p.x * scale).toInt()
                val y = (p.y * scale).toInt()
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
            if (maxX <= minX || maxY <= minY) continue
            lines.add(Line(text, minX, minY, maxX, maxY, rec.confidence))
        }
        Log.i(TAG, "ocr lines=${lines.size} ${"%.0f".format(result.elapseTime * 1000)}ms")
        return lines
    }

    private fun scaleForOcr(src: Bitmap): Bitmap {
        val maxW = 1080
        if (src.width <= maxW) return src
        val h = (src.height * (maxW.toFloat() / src.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxW, h, true)
    }
}

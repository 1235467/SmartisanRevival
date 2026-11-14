package com.smartisanrevival.bang.model

import android.graphics.Rect

data class WordBlock(
    val id: Int,
    val text: String,
    val bounds: Rect,
    val source: Source,
    val lineIndex: Int = 0,
    val granular: Granularity = Granularity.WORD,
    val isPunc: Boolean = false,
    val start: Int = 0
)

enum class Source { NODE, OCR }

enum class Granularity { WORD, CHAR }

data class BangResult(
    val blocks: List<WordBlock>,
    val sourceWindowPkg: String,
    val extractMs: Long,
    val touchX: Int = -1,
    val touchY: Int = -1,
    val sourceText: String = "",
    /** 同屏可继续炸的段落，按屏幕从上到下排列；为空表示只有这一段 */
    val segments: List<String> = emptyList(),
    /** segments 里当前显示的那一段 */
    val segmentIndex: Int = 0
)

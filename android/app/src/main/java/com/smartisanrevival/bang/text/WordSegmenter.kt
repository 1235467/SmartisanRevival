package com.smartisanrevival.bang.text

import android.util.Log
import com.huaban.analysis.jieba.JiebaSegmenter
import com.huaban.analysis.jieba.SegToken
import com.smartisanrevival.bang.model.Granularity
import com.smartisanrevival.bang.model.Source
import com.smartisanrevival.bang.model.WordBlock
import android.graphics.Rect

/**
 * 词级分词。优先 jieba SEARCH（和 baoyz/BigBang 第三方复刻同一套），
 * 标点单独成块（官方 BoomWordsLayout.punc）。
 */
object WordSegmenter {
    private const val TAG = "BangSeg"
    @Volatile private var jieba: JiebaSegmenter? = null
    @Volatile private var jiebaFailed = false

    private fun seg(): JiebaSegmenter? {
        if (jiebaFailed) return null
        jieba?.let { return it }
        return try {
            val s = JiebaSegmenter()
            jieba = s
            s
        } catch (t: Throwable) {
            Log.i(TAG, "jieba init failed, fallback", t)
            jiebaFailed = true
            null
        }
    }

    data class Token(val text: String, val start: Int, val isPunc: Boolean)

    fun tokenize(text: String, charGranular: Boolean = false): List<Token> {
        if (text.isEmpty()) return emptyList()
        if (charGranular) return charTokens(text)
        val jb = seg()
        val raw: List<Pair<String, Int>> = if (jb != null) {
            try {
                jb.process(text, JiebaSegmenter.SegMode.SEARCH).map { t: SegToken ->
                    t.word to t.startOffset
                }
            } catch (t: Throwable) {
                Log.i(TAG, "jieba process failed", t)
                fallbackTokens(text)
            }
        } else fallbackTokens(text)
        return splitPunc(raw, text)
    }

    fun toBlocks(text: String, container: Rect, source: Source = Source.NODE): List<WordBlock> {
        return layoutRows(text, container, Int.MAX_VALUE, source) { 1 }.flatten()
    }

    /**
     * 按原文换行排词块。同一行里放不下才折行；空行留成空行。
     * 原版炸开后诗、备忘录、聊天里的换行都还在，不是把全文重新灌成一整段。
     */
    fun layoutRows(
        text: String,
        container: Rect,
        pageWidthPx: Int,
        source: Source = Source.NODE,
        measure: (WordBlock) -> Int
    ): List<List<WordBlock>> {
        if (text.isEmpty() || pageWidthPx <= 0) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val pieces = normalized.split('\n')
        val lines = if (normalized.endsWith('\n') && pieces.size > 1) pieces.dropLast(1) else pieces
        val rows = ArrayList<List<WordBlock>>()
        var id = 0
        var lineStart = 0
        for (line in lines) {
            val tokens = tokenize(line).filter { it.text.isNotBlank() }
            if (tokens.isEmpty()) {
                rows.add(emptyList())
            } else {
                val lineBlocks = tokens.map { tok ->
                    WordBlock(
                        id = id++,
                        text = tok.text,
                        bounds = Rect(container),
                        source = source,
                        granular = Granularity.WORD,
                        isPunc = tok.isPunc,
                        start = lineStart + tok.start
                    )
                }
                rows.addAll(ChipLayoutEngine.pack(lineBlocks, pageWidthPx, measure))
            }
            lineStart += line.length + 1
        }
        while (rows.isNotEmpty() && rows.last().isEmpty()) rows.removeAt(rows.lastIndex)
        return rows
    }

    private fun splitPunc(raw: List<Pair<String, Int>>, origin: String): List<Token> {
        val out = ArrayList<Token>()
        for ((word, start) in raw) {
            if (word.isEmpty()) continue
            var i = 0
            while (i < word.length) {
                val c = word[i]
                if (c.isWhitespace()) {
                    i++
                    continue
                }
                if (isPuncChar(c)) {
                    var j = i + 1
                    while (j < word.length && isPuncChar(word[j])) j++
                    out.add(Token(word.substring(i, j), start + i, true))
                    i = j
                } else {
                    var j = i + 1
                    while (j < word.length && !isPuncChar(word[j]) && !word[j].isWhitespace()) j++
                    out.add(Token(word.substring(i, j), start + i, false))
                    i = j
                }
            }
        }
        if (out.isEmpty() && origin.isNotBlank()) {
            return fallbackTokens(origin).map { Token(it.first, it.second, it.first.all { c -> isPuncChar(c) }) }
        }
        return out
    }

    private fun charTokens(text: String): List<Token> {
        val out = ArrayList<Token>()
        for (i in text.indices) {
            val c = text[i]
            if (c.isWhitespace()) continue
            out.add(Token(c.toString(), i, isPuncChar(c)))
        }
        return out
    }

    private fun fallbackTokens(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                isPuncChar(c) -> {
                    out.add(c.toString() to i); i++
                }
                isLatin(c) -> {
                    val s = i
                    while (i < text.length && isLatin(text[i])) i++
                    out.add(text.substring(s, i) to s)
                }
                else -> {
                    val s = i
                    var j = i
                    while (j < text.length && !isLatin(text[j]) && !text[j].isWhitespace() && !isPuncChar(text[j])) j++
                    var k = s
                    while (k < j) {
                        val end = minOf(k + 2, j)
                        out.add(text.substring(k, end) to k)
                        k = end
                    }
                    i = j
                }
            }
        }
        return out
    }

    private fun isLatin(c: Char) = c.isLetterOrDigit() && c.code < 0x2E80

    fun isPuncChar(c: Char): Boolean {
        if (c.isLetterOrDigit()) return false
        if (c.isWhitespace()) return false
        val t = Character.getType(c)
        return t == Character.CONNECTOR_PUNCTUATION.toInt() ||
            t == Character.DASH_PUNCTUATION.toInt() ||
            t == Character.START_PUNCTUATION.toInt() ||
            t == Character.END_PUNCTUATION.toInt() ||
            t == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            t == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            t == Character.OTHER_PUNCTUATION.toInt() ||
            t == Character.MATH_SYMBOL.toInt() ||
            t == Character.CURRENCY_SYMBOL.toInt() ||
            c in "·—…、，。！？：；「」『』《》【】（）"
    }
}

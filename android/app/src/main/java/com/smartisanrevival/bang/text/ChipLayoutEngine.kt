package com.smartisanrevival.bang.text

import com.smartisanrevival.bang.model.WordBlock

/** 官方 BoomWordsLayout.generateLayout：贪心装箱，单块超行宽则独占一行。 */
object ChipLayoutEngine {
    fun pack(chips: List<WordBlock>, pageWidthPx: Int, measure: (WordBlock) -> Int): List<List<WordBlock>> {
        val rows = ArrayList<List<WordBlock>>()
        if (chips.isEmpty() || pageWidthPx <= 0) return rows
        var row = ArrayList<WordBlock>()
        var remain = pageWidthPx
        var i = 0
        while (i < chips.size) {
            val chip = chips[i]
            val w = measure(chip).coerceAtLeast(1)
            if (w > remain) {
                if (row.isEmpty()) {
                    rows.add(listOf(chip))
                    i++
                } else {
                    rows.add(row)
                    row = ArrayList()
                    remain = pageWidthPx
                }
            } else {
                row.add(chip)
                remain -= w
                i++
            }
        }
        if (row.isNotEmpty()) rows.add(row)
        return rows
    }
}

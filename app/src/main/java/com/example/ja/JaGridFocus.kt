package com.example.ja

import androidx.compose.foundation.lazy.grid.LazyGridState

/** Use the actual laid-out final title row, including incomplete adaptive rows. */
internal fun lastLoadedRowIndex(state: LazyGridState, count: Int, firstTitleIndex: Int): Int {
    if (count == 0) return -1
    val visible = state.layoutInfo.visibleItemsInfo
    val last = visible.firstOrNull { it.index == firstTitleIndex + count - 1 }
    if (last != null) {
        val first = visible.filter { it.row == last.row && it.index >= firstTitleIndex &&
            it.index < firstTitleIndex + count }.minByOrNull { it.column }
        if (first != null) return first.index - firstTitleIndex
    }
    val columns = (visible.maxOfOrNull { it.column } ?: 0) + 1
    return JaWatchRules.lastRowStart(count, columns)
}

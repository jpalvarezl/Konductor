package com.konductor.tui.text

data class InputTextLayout(
    val lines: List<String>,
    val firstVisibleLine: Int,
    val visibleLines: List<String>,
    val cursorLine: Int,
    val cursorColumn: Int,
) {
    val totalLines: Int get() = lines.size
    val visibleHeight: Int get() = visibleLines.size
}

fun layoutInputText(text: String, cursor: Int, width: Int, maxVisibleLines: Int): InputTextLayout {
    if (width <= 0 || maxVisibleLines <= 0) {
        return InputTextLayout(listOf(""), 0, emptyList(), 0, 0)
    }

    val safeCursor = cursor.coerceIn(0, text.length)
    val lines = hardWrapInput(text, width)
    val (cursorLine, cursorColumn) = clampCaretToRealLine(cursorWrappedPosition(text, safeCursor, width), lines)
    val visibleHeight = lines.size.coerceAtMost(maxVisibleLines).coerceAtLeast(1)
    val maxFirstVisible = (lines.size - visibleHeight).coerceAtLeast(0)
    val firstVisibleLine = (cursorLine - visibleHeight + 1).coerceIn(0, maxFirstVisible)

    return InputTextLayout(
        lines = lines,
        firstVisibleLine = firstVisibleLine,
        visibleLines = lines.drop(firstVisibleLine).take(visibleHeight),
        cursorLine = cursorLine,
        cursorColumn = cursorColumn,
    )
}

private fun hardWrapInput(text: String, width: Int): List<String> {
    if (text.isEmpty()) return listOf("")

    val lines = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        lines += current.toString()
        current.setLength(0)
    }

    for (char in text) {
        if (char == '\n') {
            flush()
            continue
        }
        if (current.length == width) flush()
        current.append(char)
    }
    flush()

    return lines
}

private fun cursorWrappedPosition(text: String, cursor: Int, width: Int): Pair<Int, Int> {
    var line = 0
    var column = 0

    repeat(cursor) { index ->
        val char = text[index]
        if (char == '\n') {
            line += 1
            column = 0
        } else {
            column += 1
            if (column == width) {
                line += 1
                column = 0
            }
        }
    }

    return line to column
}

/**
 * [cursorWrappedPosition] wraps eagerly, so when the text ends exactly at a wrap boundary (its length is a
 * positive multiple of [width] with no trailing newline) the caret points one line past the last real line —
 * a row [hardWrapInput] never emits. Pin it to the end of the last real line instead, so the caret renders at
 * the end of the full line rather than jumping to a non-existent row (which would otherwise be mis-coerced to
 * column 0 of the visible area).
 */
private fun clampCaretToRealLine(position: Pair<Int, Int>, lines: List<String>): Pair<Int, Int> {
    val (line, column) = position
    return if (line > lines.lastIndex) lines.lastIndex to lines[lines.lastIndex].length else line to column
}

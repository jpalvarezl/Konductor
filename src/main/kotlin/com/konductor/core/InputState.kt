package com.konductor.core

class InputState {
    var text: String = ""
        private set

    var cursor: Int = 0
        private set

    // The horizontal column to return to while moving up/down across lines of differing length (sticky column);
    // null resets it to the cursor's current column on the next vertical move.
    private var targetColumn: Int? = null

    fun insert(character: Char) {
        text = text.substring(0, cursor) + character + text.substring(cursor)
        cursor += 1
        targetColumn = null
    }

    fun insert(value: String) {
        if (value.isEmpty()) return
        text = text.substring(0, cursor) + value + text.substring(cursor)
        cursor += value.length
        targetColumn = null
    }

    fun insertNewline() {
        insert('\n')
    }

    fun backspace() {
        if (cursor == 0) return
        text = text.removeRange(cursor - 1, cursor)
        cursor -= 1
        targetColumn = null
    }

    fun delete() {
        if (cursor >= text.length) return
        text = text.removeRange(cursor, cursor + 1)
        targetColumn = null
    }

    fun moveLeft() {
        cursor = (cursor - 1).coerceAtLeast(0)
        targetColumn = null
    }

    fun moveRight() {
        cursor = (cursor + 1).coerceAtMost(text.length)
        targetColumn = null
    }

    fun moveHome() {
        cursor = currentLineStart()
        targetColumn = null
    }

    fun moveEnd() {
        cursor = currentLineEnd()
        targetColumn = null
    }

    fun moveUp() {
        val lineStart = currentLineStart()
        if (lineStart == 0) return

        val column = targetColumn ?: (cursor - lineStart)
        val previousLineEnd = lineStart - 1
        val previousLineStart = text.lastIndexOf('\n', (previousLineEnd - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        cursor = (previousLineStart + column).coerceAtMost(previousLineEnd)
        targetColumn = column
    }

    fun moveDown() {
        val lineEnd = currentLineEnd()
        if (lineEnd >= text.length) return

        val lineStart = currentLineStart()
        val column = targetColumn ?: (cursor - lineStart)
        val nextLineStart = lineEnd + 1
        val nextLineEnd = text.indexOf('\n', nextLineStart).let { if (it < 0) text.length else it }
        cursor = (nextLineStart + column).coerceAtMost(nextLineEnd)
        targetColumn = column
    }

    fun clear() {
        text = ""
        cursor = 0
        targetColumn = null
    }

    fun hasMultipleLines(): Boolean = text.contains('\n')

    private fun currentLineStart(): Int =
        if (cursor <= 0) 0 else text.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }

    private fun currentLineEnd(): Int =
        text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
}

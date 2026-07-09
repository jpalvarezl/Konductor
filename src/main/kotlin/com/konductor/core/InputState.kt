package com.konductor.core

class InputState {
    var text: String = ""
        private set

    var cursor: Int = 0
        private set

    fun setText(newText: String) {
        text = newText
        cursor = newText.length
    }

    fun insert(character: Char) {
        text = text.substring(0, cursor) + character + text.substring(cursor)
        cursor += 1
    }

    fun backspace() {
        if (cursor == 0) return
        text = text.removeRange(cursor - 1, cursor)
        cursor -= 1
    }

    fun delete() {
        if (cursor >= text.length) return
        text = text.removeRange(cursor, cursor + 1)
    }

    fun moveLeft() {
        cursor = (cursor - 1).coerceAtLeast(0)
    }

    fun moveRight() {
        cursor = (cursor + 1).coerceAtMost(text.length)
    }

    fun moveHome() {
        cursor = 0
    }

    fun moveEnd() {
        cursor = text.length
    }

    fun clear() {
        text = ""
        cursor = 0
    }
}

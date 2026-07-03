package com.konductor.tui.text

fun wrapText(text: String, width: Int): List<String> {
    if (width <= 0) return emptyList()
    if (text.isEmpty()) return listOf("")

    return text
        .split('\n')
        .flatMap { paragraph -> wrapParagraph(paragraph, width) }
}

private fun wrapParagraph(paragraph: String, width: Int): List<String> {
    if (paragraph.isEmpty()) return listOf("")

    val lines = mutableListOf<String>()
    var remaining = paragraph

    while (remaining.length > width) {
        val breakAt = remaining
            .take(width + 1)
            .lastIndexOf(' ')
            .takeIf { it > 0 }
            ?: width

        lines += remaining.take(breakAt).trimEnd()
        remaining = remaining.drop(breakAt).trimStart()
    }

    lines += remaining
    return lines
}

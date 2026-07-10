package com.konductor.tui.text

import com.googlecode.lanterna.SGR
import com.konductor.i18n.AppStrings

enum class MarkdownStyle {
    Normal,
    Emphasis,
    Strong,
    InlineCode,
    CodeBlock,
    Heading,
    ListMarker,
}

data class StyledTextSegment(
    val text: String,
    val style: MarkdownStyle = MarkdownStyle.Normal,
    val modifiers: Set<SGR> = emptySet(),
)

data class StyledTextLine(
    val segments: List<StyledTextSegment>,
)

object MarkdownRenderer {
    fun render(
        markdown: String,
        width: Int,
        strings: AppStrings = AppStrings.english(),
    ): List<StyledTextLine> {
        if (width <= 0) return emptyList()
        if (markdown.isEmpty()) return listOf(StyledTextLine(listOf(StyledTextSegment(""))))

        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val rendered = mutableListOf<StyledTextLine>()
        var inFence = false
        var fenceLanguage = ""

        for (rawLine in lines) {
            val trimmed = rawLine.trimStart()
            if (trimmed.startsWith("```")) {
                if (inFence) {
                    rendered += StyledTextLine(listOf(StyledTextSegment("└─", MarkdownStyle.CodeBlock)))
                    inFence = false
                    fenceLanguage = ""
                } else {
                    fenceLanguage = trimmed.removePrefix("```").trim()
                    val label = strings.markdownCodeLabel(fenceLanguage)
                    rendered += StyledTextLine(listOf(StyledTextSegment("┌─ $label", MarkdownStyle.CodeBlock)))
                    inFence = true
                }
                continue
            }

            if (inFence) {
                rendered += wrapSegments(
                    listOf(StyledTextSegment(rawLine, MarkdownStyle.CodeBlock)),
                    width = width,
                    firstPrefix = "│ ",
                    continuationPrefix = "│ ",
                    prefixStyle = MarkdownStyle.CodeBlock,
                )
                continue
            }

            rendered += renderMarkdownLine(rawLine, width)
        }

        return rendered
    }

    private fun renderMarkdownLine(line: String, width: Int): List<StyledTextLine> {
        if (line.isBlank()) return listOf(StyledTextLine(emptyList()))

        headingRegex.matchEntire(line)?.let { match ->
            val level = match.groupValues[1].length
            val prefix = "${"▌".repeat(level.coerceAtMost(3))} "
            return wrapSegments(
                parseInline(match.groupValues[2], MarkdownStyle.Heading),
                width = width,
                firstPrefix = prefix,
                continuationPrefix = " ".repeat(prefix.length),
                prefixStyle = MarkdownStyle.Heading,
            )
        }

        bulletRegex.matchEntire(line)?.let { match ->
            val indent = match.groupValues[1]
            val prefix = "$indent• "
            return wrapSegments(
                parseInline(match.groupValues[2]),
                width = width,
                firstPrefix = prefix,
                continuationPrefix = "$indent  ",
                prefixStyle = MarkdownStyle.ListMarker,
            )
        }

        numberedRegex.matchEntire(line)?.let { match ->
            val indent = match.groupValues[1]
            val marker = "${match.groupValues[2]}. "
            val prefix = indent + marker
            return wrapSegments(
                parseInline(match.groupValues[3]),
                width = width,
                firstPrefix = prefix,
                continuationPrefix = " ".repeat(prefix.length),
                prefixStyle = MarkdownStyle.ListMarker,
            )
        }

        return wrapSegments(parseInline(line), width = width)
    }

    private fun parseInline(text: String, baseStyle: MarkdownStyle = MarkdownStyle.Normal): List<StyledTextSegment> {
        val segments = mutableListOf<StyledTextSegment>()
        var index = 0

        fun add(value: String, style: MarkdownStyle, modifiers: Set<SGR> = emptySet()) {
            if (value.isNotEmpty()) segments += StyledTextSegment(value, style, modifiers)
        }

        while (index < text.length) {
            when {
                text.startsWith("**", index) -> {
                    val end = text.indexOf("**", index + 2)
                    if (end < 0) {
                        add(text.substring(index), baseStyle)
                        index = text.length
                    } else {
                        add(text.substring(index + 2, end), MarkdownStyle.Strong, setOf(SGR.BOLD))
                        index = end + 2
                    }
                }
                text[index] == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end < 0) {
                        add(text.substring(index), baseStyle)
                        index = text.length
                    } else {
                        add(text.substring(index + 1, end), MarkdownStyle.InlineCode)
                        index = end + 1
                    }
                }
                text[index] == '*' -> {
                    val end = nextSingleStar(text, index + 1)
                    if (end < 0) {
                        add(text.substring(index), baseStyle)
                        index = text.length
                    } else {
                        add(text.substring(index + 1, end), MarkdownStyle.Emphasis, setOf(SGR.ITALIC))
                        index = end + 1
                    }
                }
                else -> {
                    val next = listOf(
                        text.indexOf("**", index).takeIf { it >= 0 } ?: text.length,
                        text.indexOf('`', index).takeIf { it >= 0 } ?: text.length,
                        nextSingleStar(text, index).takeIf { it >= 0 } ?: text.length,
                    ).min()
                    add(text.substring(index, next), baseStyle)
                    index = next
                }
            }
        }

        return segments.ifEmpty { listOf(StyledTextSegment("")) }
    }

    private fun wrapSegments(
        segments: List<StyledTextSegment>,
        width: Int,
        firstPrefix: String = "",
        continuationPrefix: String = "",
        prefixStyle: MarkdownStyle = MarkdownStyle.Normal,
    ): List<StyledTextLine> {
        val lines = mutableListOf<StyledTextLine>()
        var current = mutableListOf<StyledTextSegment>()
        var currentWidth = 0
        var currentPrefixWidth = 0
        var firstLine = true
        var pendingSpace: StyledTextSegment? = null

        fun beginLine() {
            val prefix = if (firstLine) firstPrefix else continuationPrefix
            current = mutableListOf()
            currentWidth = 0
            currentPrefixWidth = 0
            if (prefix.isNotEmpty()) {
                current += StyledTextSegment(prefix, prefixStyle)
                currentWidth += prefix.length
                currentPrefixWidth = prefix.length
            }
            firstLine = false
            pendingSpace = null
        }

        fun flushLine() {
            lines += StyledTextLine(current)
            beginLine()
        }

        fun append(text: String, source: StyledTextSegment) {
            current += source.copy(text = text)
            currentWidth += text.length
        }

        beginLine()

        for (segment in segments) {
            tokenRegex.findAll(segment.text).forEach { match ->
                val token = match.value
                if (token.isBlank()) {
                    if (currentWidth > currentPrefixWidth) {
                        pendingSpace = segment.copy(text = " ")
                    }
                    return@forEach
                }

                var word = token
                while (word.isNotEmpty()) {
                    val space = if (pendingSpace != null && currentWidth > currentPrefixWidth) 1 else 0
                    if (currentWidth + space + word.length > width && currentWidth > currentPrefixWidth) {
                        flushLine()
                        continue
                    }

                    pendingSpace?.takeIf { currentWidth > currentPrefixWidth }?.let {
                        append(" ", it)
                    }
                    pendingSpace = null

                    val room = (width - currentWidth).coerceAtLeast(1)
                    val chunk = word.take(room)
                    append(chunk, segment)
                    word = word.drop(chunk.length)
                    if (word.isNotEmpty()) flushLine()
                }
            }
        }

        if (current.isNotEmpty() || lines.isEmpty()) lines += StyledTextLine(current)
        return lines
    }

    private fun nextSingleStar(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            if (text[index] == '*') {
                val previousIsStar = index > 0 && text[index - 1] == '*'
                val nextIsStar = index + 1 < text.length && text[index + 1] == '*'
                if (!previousIsStar && !nextIsStar) return index
            }
            index += 1
        }
        return -1
    }

    private val headingRegex = Regex("""^(#{1,6})\s+(.+)$""")
    private val bulletRegex = Regex("""^(\s*)[-+*]\s+(.+)$""")
    private val numberedRegex = Regex("""^(\s*)(\d+)[.)]\s+(.+)$""")
    private val tokenRegex = Regex("""\S+|\s+""")
}

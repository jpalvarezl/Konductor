package com.konductor.tui.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownRendererTest {
    @Test
    fun `renders headings and inline emphasis without markdown delimiters`() {
        val lines = MarkdownRenderer.render("# Hello **bold** and *it* with `code`", width = 80)

        val text = lines.single().segments.joinToString("") { it.text }
        assertEquals("▌ Hello bold and it with code", text)
        assertTrue(lines.single().segments.any { it.text == "bold" && it.style == MarkdownStyle.Strong })
        assertTrue(lines.single().segments.any { it.text == "it" && it.style == MarkdownStyle.Emphasis })
        assertTrue(lines.single().segments.any { it.text == "code" && it.style == MarkdownStyle.InlineCode })
    }

    @Test
    fun `renders bullet and numbered list markers with continuation wrapping`() {
        val lines = MarkdownRenderer.render("- one two three four\n12. alpha beta gamma", width = 12)
            .map { line -> line.segments.joinToString("") { it.text } }

        assertEquals(listOf("• one two", "  three four", "12. alpha", "    beta", "    gamma"), lines)
    }

    @Test
    fun `renders fenced code blocks and tolerates an unclosed streaming fence`() {
        val lines = MarkdownRenderer.render("```kotlin\nval x = 1", width = 80)

        assertEquals("┌─ code kotlin", lines[0].segments.joinToString("") { it.text })
        assertEquals("│ val x = 1", lines[1].segments.joinToString("") { it.text })
        assertTrue(lines[1].segments.all { it.style == MarkdownStyle.CodeBlock })
    }

    @Test
    fun `leaves partial inline markdown literal while streaming`() {
        val lines = MarkdownRenderer.render("This is **part", width = 80)

        assertEquals("This is **part", lines.single().segments.joinToString("") { it.text })
    }
}

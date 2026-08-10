package com.konductor.agent

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ContextBlockRendererTest {
    @Test
    fun `empty record list omits the complete block`() {
        assertNull(ContextBlockRenderer.render(emptyList()))
    }

    @Test
    fun `renders exact boundaries escapes every path attribute character and preserves content`() {
        val content = "body <&>\r\nnot normalized here"
        val rendered = ContextBlockRenderer.render(
            listOf(ContextFileRecord("/a&<b>\"c'd", content)),
        )

        assertEquals(
            "<project_context>\n" +
                "<project_instructions path=\"/a&amp;&lt;b&gt;&quot;c&apos;d\">\n" +
                "$content\n" +
                "</project_instructions>\n" +
                "</project_context>",
            rendered,
        )
    }

    @Test
    fun `empty and whitespace-only contents retain exact content lines without indentation`() {
        val rendered = ContextBlockRenderer.render(
            listOf(
                ContextFileRecord("/empty", ""),
                ContextFileRecord("/spaces", "  \n\t"),
            ),
        )

        assertEquals(
            "<project_context>\n" +
                "<project_instructions path=\"/empty\">\n\n</project_instructions>\n" +
                "<project_instructions path=\"/spaces\">\n  \n\t\n</project_instructions>\n" +
                "</project_context>",
            rendered,
        )
    }

    @Test
    fun `display paths are absolute lexically normalized and use host-independent separators`(@TempDir temp: Path) {
        val display = ContextBlockRenderer.displayPath(temp.resolve("a/../b"))

        assertEquals(ContextBlockRenderer.displayPath(temp.resolve("b")), display)
        assertFalse(display.contains("/../"))
        if (temp.fileSystem.separator == "\\") assertFalse(display.contains('\\'))
    }
}

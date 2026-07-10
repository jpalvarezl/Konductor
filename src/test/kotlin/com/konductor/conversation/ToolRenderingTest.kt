package com.konductor.conversation

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRenderingTest {
    @Test
    fun `renders read calls and results concisely without leading raw json`() {
        val call = ToolCall("call-1", "read", """{"path":"src/main/kotlin/Client.kt","offset":2,"limit":3}""")

        assertEquals("read Client.kt:2-4", renderToolCall(call).summary)
        assertEquals(
            "read Client.kt:2-4 (2 lines)",
            renderToolResult(call, ToolResult("call-1", "     2\tone\n     3\ttwo")).summary,
        )
        assertTrue(renderToolCall(call).rawDetail!!.startsWith("{"))
    }

    @Test
    fun `renders edit success and errors as human summaries`() {
        val call = ToolCall("call-1", "edit", """{"path":"Client.kt","oldString":"a","newString":"b"}""")

        assertEquals("edit Client.kt", renderToolCall(call).summary)
        assertEquals("edit Client.kt (1 change)", renderToolResult(call, ToolResult("call-1", "edited Client.kt")).summary)
        assertEquals(
            "edit Client.kt failed: edit: oldString not found in Client.kt",
            renderToolResult(call, ToolResult("call-1", "edit: oldString not found in Client.kt", isError = true)).summary,
        )
    }

    @Test
    fun `renders search style tools with counts`() {
        val call = ToolCall("call-1", "grep", """{"pattern":"TODO","path":"src","glob":"*.kt"}""")

        assertEquals("""grep "TODO" in src""", renderToolCall(call).summary)
        assertEquals("""grep "TODO" in src (2 matches)""", renderToolResult(call, ToolResult("call-1", "a:1:x\nb:2:y")).summary)
    }
}

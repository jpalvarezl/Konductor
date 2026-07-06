package com.konductor.conversation

import com.konductor.core.AppState
import com.konductor.core.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationControllerTest {
    @Test
    fun `blank input is ignored and keeps the app running`() {
        val state = AppState()
        val controller = ConversationController(state)

        val shouldContinue = controller.submit("   ")

        assertTrue(shouldContinue)
        assertEquals(emptyList(), state.messages)
    }

    @Test
    fun `quit commands stop the app without adding messages`() {
        val state = AppState()
        val controller = ConversationController(state)

        assertFalse(controller.submit("/quit"))
        assertFalse(controller.submit("/EXIT"))
        assertEquals(emptyList(), state.messages)
    }

    @Test
    fun `submitted text creates user and placeholder assistant messages`() {
        val state = AppState()
        val controller = ConversationController(state)

        val shouldContinue = controller.submit("  hello  ")

        assertTrue(shouldContinue)
        assertEquals(2, state.messages.size)
        assertEquals(MessageRole.User, state.messages[0].role)
        assertEquals("hello", state.messages[0].content)
        assertEquals(MessageRole.Assistant, state.messages[1].role)
        assertTrue(state.messages[1].content.startsWith("Echo: hello"))
    }
}

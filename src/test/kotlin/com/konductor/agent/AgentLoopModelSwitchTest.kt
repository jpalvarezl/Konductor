package com.konductor.agent

import com.konductor.core.models.AgentContext
import com.konductor.core.models.Session
import com.konductor.provider.PromptProvider
import com.konductor.provider.inference.MockFoundryResponsesClient
import com.konductor.session.NoOpSessionStore
import com.konductor.session.SessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentLoopModelSwitchTest {
    @Test
    fun rollsBackFailedPersistence() {
        val context = AgentContext("system", emptyList(), "current-model", null)
        val loop = AgentLoop(
            PromptProvider(MockFoundryResponsesClient()),
            NoToolExecutor,
            context,
            MockFailingSessionStore,
        )

        val result = loop.switchModel("next-model")

        assertIs<ModelSwitchResult.Invalid>(result)
        assertEquals("current-model", loop.modelName)
        assertEquals("current-model", loop.session.modelName)
    }
}

private object MockFailingSessionStore : SessionStore by NoOpSessionStore {
    override fun persistHeader(session: Session) {
        throw IllegalStateException("disk unavailable")
    }
}

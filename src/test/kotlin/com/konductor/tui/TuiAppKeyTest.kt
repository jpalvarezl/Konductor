package com.konductor.tui

import com.googlecode.lanterna.input.KeyStroke
import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.core.models.Session
import com.konductor.foundry.project.connection.FoundryConnection
import com.konductor.foundry.project.connection.FoundryConnectionCatalog
import com.konductor.foundry.project.deployment.FoundryDeployment
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.i18n.AppStrings
import com.konductor.provider.PromptProvider
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.inference.MockFoundryResponsesClient
import java.nio.file.Path
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TuiAppKeyTest {
    @Test
    fun slashOpensOnlyForEmptyComposer() {
        assertTrue(shouldOpenCommandPalette('/', ctrlDown = false, composerEmpty = true))
        assertFalse(shouldOpenCommandPalette('/', ctrlDown = false, composerEmpty = false))
    }

    @Test
    fun ctrlKOpensWithComposerContent() {
        assertTrue(shouldOpenCommandPalette('k', ctrlDown = true, composerEmpty = false))
        assertTrue(shouldOpenCommandPalette('K', ctrlDown = true, composerEmpty = true))
        assertFalse(shouldOpenCommandPalette('k', ctrlDown = false, composerEmpty = true))
    }

    @Test
    fun shortcutsStayInertDuringWork() {
        assertFalse(shouldOpenCommandPalette('/', false, true, inputAvailable = false))
        assertFalse(shouldOpenCommandPalette('k', true, true, inputAvailable = false))
    }

    @Test
    fun startupMessagesArePresentationOnlySystemMessages() {
        val session = Session(Uuid.random(), null, Path.of("."), "model", Clock.System.now())

        val messages = initialTuiMessages(
            session,
            AppStrings.english(),
            startupSystemMessages = listOf("Automatically selected deployment-a."),
        )

        assertEquals(
            listOf(AppStrings.english().welcomeMessage, "Automatically selected deployment-a."),
            messages.map { it.content },
        )
        assertTrue(messages.all { it.role == MessageRole.System })
        assertTrue(session.entries.isEmpty())
    }

    @Test
    @Suppress("DEPRECATION")
    fun resumingInitialSessionConstructorRemainsSourceCompatible() {
        val runtime = ProviderRuntime(PromptProvider(MockFoundryResponsesClient()))
        val loop = AgentLoop(
            runtime,
            NoToolExecutor,
            AgentContext("system", emptyList(), "model", null),
        )
        val deployments = object : FoundryDeploymentCatalog {
            override fun listDeployments(): List<FoundryDeployment> = emptyList()
            override fun getDeployment(name: String): FoundryDeployment = error("unused")
        }
        val connections = object : FoundryConnectionCatalog {
            override fun listConnections(): List<FoundryConnection> = emptyList()
            override fun getConnection(name: String): FoundryConnection = error("unused")
        }

        val app = TuiApp(
            loop,
            runtime,
            deployments,
            connections,
            resumingInitialSession = true,
        )

        assertNotNull(app)
    }

    @Test
    fun consumesModifiedEnterSuffix() {
        val keys = ArrayDeque("13;2u".map { KeyStroke(it, false, false) })
        val deferred = mutableListOf<KeyStroke>()

        val keycode = consumeCsiuSuffix({ keys.removeFirstOrNull() }, deferred::add)

        assertEquals(CSI_U_ENTER_KEYCODE, keycode)
        assertTrue(keys.isEmpty())
        assertTrue(deferred.isEmpty())
    }
}

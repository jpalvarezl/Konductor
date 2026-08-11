package com.konductor.provider.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchableFoundryResponsesClientTest {
    private val request = FoundryResponsesRequest("model", "system", emptyList(), emptyList())

    @Test
    fun `prepare preserves an exact validated name without publishing then commit swaps one holder`() = runBlocking {
        val created = mutableListOf<TrackingClient>()
        val switchable = SwitchableFoundryResponsesClient({ name -> TrackingClient(name).also(created::add) })
        val initial = created.single()

        val prepared = switchable.prepareBinding("Billing")

        assertEquals("Billing", prepared.agentName)
        assertNull(switchable.activeAgent)
        assertEquals("ephemeral", switchable.respond(request).text)
        assertFalse(initial.closed)

        prepared.commit()

        assertEquals("Billing", switchable.activeAgent)
        assertEquals("Billing", switchable.respond(request).text)
        assertTrue(initial.closed)
        assertFalse(created.last().closed)
    }

    @Test
    fun `explicit null prepares ephemeral and abort leaves old delegate routable`() = runBlocking {
        val created = mutableListOf<TrackingClient>()
        val switchable = SwitchableFoundryResponsesClient(
            { name -> TrackingClient(name).also(created::add) },
            initialAgent = "old",
        )

        val prepared = switchable.prepareBinding(null)
        val candidate = created.last()
        prepared.close()

        assertNull(prepared.agentName)
        assertEquals("old", switchable.activeAgent)
        assertEquals("old", switchable.respond(request).text)
        assertTrue(candidate.closed)
        assertFalse(created.first().closed)
    }

    @Test
    fun `same exact name allocates and closes nothing`() {
        val created = mutableListOf<TrackingClient>()
        val switchable = SwitchableFoundryResponsesClient(
            { name -> TrackingClient(name).also(created::add) },
            initialAgent = "billing",
        )

        val prepared = switchable.prepareBinding("billing")
        prepared.commit()

        assertEquals(1, created.size)
        assertFalse(created.single().closed)
        assertEquals("billing", switchable.activeAgent)
    }

    @Test
    fun `binder rejects blank and padded names without allocation or mutation`() {
        val created = mutableListOf<TrackingClient>()
        val switchable = SwitchableFoundryResponsesClient(
            { name -> TrackingClient(name).also(created::add) },
            initialAgent = "old",
        )

        listOf("", "   ", " billing", "billing ").forEach { invalidName ->
            assertFailsWith<IllegalArgumentException> { switchable.prepareBinding(invalidName) }
        }

        assertEquals(1, created.size)
        assertEquals("old", switchable.activeAgent)
        assertFailsWith<IllegalArgumentException> {
            SwitchableFoundryResponsesClient(::TrackingClient, initialAgent = " padded ")
        }
    }

    @Test
    fun `candidate and superseded close failures are suppressed`() {
        val created = mutableListOf<TrackingClient>()
        val switchable = SwitchableFoundryResponsesClient(
            { name -> TrackingClient(name, closeFailure = true).also(created::add) },
            initialAgent = "old",
        )

        switchable.prepareBinding("aborted").close()
        val committed = switchable.prepareBinding("new")
        committed.commit()

        assertEquals("new", switchable.activeAgent)
        assertTrue(created[1].closeAttempts == 1)
        assertTrue(created[0].closeAttempts == 1)
    }

    @Test
    fun `commit failure aborts candidate best effort and leaves handle completed`() {
        var aborts = 0
        var cleanupHeldHandleMonitor = true
        lateinit var prepared: PreparedPromptAgentBinding
        prepared = PreparedPromptAgentBinding(
            agentName = "new",
            commitAction = { error("commit failed") },
            abortAction = {
                aborts++
                cleanupHeldHandleMonitor = Thread.holdsLock(prepared)
            },
        )

        val failure = assertFailsWith<IllegalStateException> { prepared.commit() }

        assertEquals("commit failed", failure.message)
        assertEquals(1, aborts)
        assertFalse(cleanupHeldHandleMonitor)
        assertFailsWith<IllegalStateException> { prepared.commit() }
        assertFailsWith<IllegalStateException> { prepared.close() }
    }

    @Test
    fun `prepared handle rejects commit abort and repeated use after completion`() {
        val switchable = SwitchableFoundryResponsesClient(::TrackingClient)
        val committed = switchable.prepareBinding("new")
        committed.commit()

        assertFailsWith<IllegalStateException> { committed.commit() }
        assertFailsWith<IllegalStateException> { committed.close() }

        val aborted = switchable.prepareBinding("other")
        aborted.close()
        assertFailsWith<IllegalStateException> { aborted.commit() }
        assertFailsWith<IllegalStateException> { aborted.close() }
    }
}

private class TrackingClient(
    private val name: String?,
    private val closeFailure: Boolean = false,
) : FoundryResponsesClient {
    var closed: Boolean = false
    var closeAttempts: Int = 0

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult =
        FoundryResponsesResult(name ?: "ephemeral", emptyList(), null)

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = emptyFlow()

    override suspend fun close() {
        closeAttempts++
        if (closeFailure) error("close failed")
        closed = true
    }
}

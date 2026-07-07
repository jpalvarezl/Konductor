package com.konductor.agent

import com.konductor.core.models.AgentContext
import com.konductor.core.models.Entry
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentProvider
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The agent-loop layer between the UI and the [AgentProvider]. It owns the in-memory transcript for a run:
 * each [runTurn] appends a [UserEntry], drives one provider turn to completion, and folds the resulting
 * `AssistantEntry` (and, from M2, tool entries) back into the history so the next turn re-sends the full
 * reconstructed transcript.
 *
 * M1 keeps history in memory only; JSONL persistence via `SessionStore` lands in M3, and the coroutine
 * `submit(text): Job` + cancellation shape (docs/spec/architecture.md#threading--concurrency) in M6.
 */
class AgentLoop(
    private val provider: AgentProvider,
    private val toolExecutor: ToolExecutor,
    val context: AgentContext,
) {
    private val entries: MutableList<Entry> = mutableListOf()

    /** Snapshot of the transcript reconstructed so far. */
    val history: List<Entry> get() = entries.toList()

    val modelName: String get() = context.modelName

    /**
     * Run one user turn: append the [UserEntry], stream the provider's [AgentEvent]s, and append the
     * completed assistant entry to the history. The returned flow is cold — collecting it drives the turn.
     */
    fun runTurn(userText: String): Flow<AgentEvent> = flow {
        val userEntry = UserEntry(
            id = Uuid.random(),
            parentId = entries.lastOrNull()?.id,
            timestamp = Clock.System.now(),
            text = userText,
        )
        entries += userEntry

        provider.runTurn(TurnRequest(context = context, history = entries.toList()), toolExecutor)
            .collect { event ->
                if (event is AgentEvent.TurnCompleted) {
                    entries += event.assistant
                }
                emit(event)
            }
    }

    suspend fun close() = provider.close()
}

package com.konductor.provider

import com.konductor.provider.inference.InferenceClient
import kotlinx.coroutines.flow.Flow

/**
 * Prompt-kind [AgentProvider]: owns the client-side tool loop while staying vendor-neutral by
 * delegating each individual model call to an injected [InferenceClient] — the sole SDK chokepoint.
 *
 * This sits on the loop-ownership axis (above [InferenceClient], not an instance of it); see the
 * two-seam design in docs/spec/architecture.md#two-axes-two-seams. The `runTurn` loop body lands in
 * M1 (docs/spec/providers.md#the-harness-owned-loop-vendor-neutral).
 */
class PromptProvider(private val inference: InferenceClient) : AgentProvider {
    override val kind: AgentKind = AgentKind.Prompt

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> =
        TODO("M1: drive the loop over InferenceClient.respond(...) and emit AgentEvents")

    override suspend fun close() = inference.close()
}

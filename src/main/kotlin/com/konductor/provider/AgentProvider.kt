package com.konductor.provider

import kotlinx.coroutines.flow.Flow

interface AgentProvider {
    val kind: AgentKind
    val capabilities: ProviderCapabilities
    fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent>
    suspend fun close()
}

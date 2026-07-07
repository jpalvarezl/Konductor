package com.konductor

import com.konductor.acp.runAcpAgent
import com.konductor.agent.AgentContextFactory
import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.config.Configuration
import com.konductor.config.EnvFile
import com.konductor.provider.PromptProvider
import com.konductor.provider.inference.AzureInferenceClient
import com.konductor.tui.TuiApp
import com.konductor.tui.TuiExitCode
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // The Azure/openai SDK leaves non-daemon threads alive (the HTTP pool + an "openai-stream-handler-thread-*"
    // parked on a queue), which would otherwise keep a `java -jar`/jpackage process — or `mvn exec:java` —
    // running long after the UI exits. Our own resources are released above, so force a prompt, clean shutdown.
    exitProcess(runKonductor(args).code)
}

private fun runKonductor(args: Array<String>) : TuiExitCode {
    // Env vars win; a gitignored cwd `.env` fills gaps so `mvn` / `java -jar` work without exporting first.
    val configuration = Configuration.load(env = EnvFile.overlay())
    // Both frontends share one Prompt inference stack; the ACP path mints an AgentLoop per session.
    val provider = PromptProvider(AzureInferenceClient(configuration))
    val context = AgentContextFactory.build(configuration)

    return try {
        if (args.shouldRunAcp()) {
            runAcpAgent(provider, context) // headless ACP frontend (real streamed inference)
        } else {
            TuiApp(AgentLoop(provider, NoToolExecutor, context)).run() // interactive TUI (default)
        }
        TuiExitCode.SUCCESS
    } catch (t: Throwable) {
        // TODO: how do I log this?
        TuiExitCode.FAILURE
    } finally {
        runBlocking { provider.close() }
    }
}

fun Array<String>.shouldRunAcp(): Boolean = any { it == "acp" || it == "--acp" }

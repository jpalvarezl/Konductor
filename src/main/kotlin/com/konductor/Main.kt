package com.konductor

import com.konductor.acp.runAcpAgent
import com.konductor.agent.AgentContextFactory
import com.konductor.agent.AgentLoop
import com.konductor.config.Configuration
import com.konductor.config.EnvFile
import com.konductor.provider.AgentProvider
import com.konductor.provider.PromptProvider
import com.konductor.provider.inference.AzureInferenceClient
import com.konductor.tool.BuiltinTools
import com.konductor.tool.RegistryToolExecutor
import com.konductor.tool.ToolContext
import com.konductor.tui.TuiApp
import com.konductor.tui.TuiExitCode
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // runKonductor releases its resources first (it closes the inference client in a `finally`). exitProcess
    // then guarantees a prompt, deterministic exit across `java -jar`, jpackage, and `mvn exec:java` — a
    // belt-and-suspenders guard in case a dependency ever leaves a lingering non-daemon executor thread.
    exitProcess(runKonductor(args).code)
}

private fun runKonductor(args: Array<String>): TuiExitCode {
    // Nullable + assigned inside the try so that a failure while building the stack (config resolution, SDK
    // client) still reaches the finally (release the client) and returns a code (so main's exitProcess runs).
    var provider: AgentProvider? = null
    return try {
        // Env vars win; a gitignored cwd `.env` fills gaps so `mvn` / `java -jar` work without exporting first.
        val configuration = Configuration.load(env = EnvFile.overlay())
        // Both frontends share one Prompt inference stack; the ACP path mints an AgentLoop per session.
        val agentProvider = PromptProvider(AzureInferenceClient(configuration)).also { provider = it }

        // Build the tool surface once: the same registry supplies the advertised specs (into the context) and
        // the cwd-scoped executor (into the loop). `configuration.toolAllow` enables read-only mode.
        val cwd = Path.of("").toAbsolutePath()
        val registry = BuiltinTools.registry(configuration.toolAllow)
        val toolExecutor = RegistryToolExecutor(registry, ToolContext(cwd))
        val context = AgentContextFactory.build(configuration, cwd = cwd, tools = registry.enabled().map { it.spec })

        if (args.shouldRunAcp()) {
            runAcpAgent(agentProvider, context, toolExecutor) // headless ACP frontend (real streamed inference)
        } else {
            TuiApp(AgentLoop(agentProvider, toolExecutor, context)).run() // interactive TUI (default)
        }
        TuiExitCode.SUCCESS
    } catch (t: Throwable) {
        // stdout is the TUI/ACP protocol channel, so report fatal errors on stderr (the TUI screen and ACP
        // transport are already torn down by now). Returning FAILURE maps to a non-zero process exit code.
        System.err.println("Konductor exited with an error: ${t.message ?: t::class.qualifiedName}")
        t.printStackTrace(System.err)
        TuiExitCode.FAILURE
    } finally {
        provider?.let { runBlocking { it.close() } }
    }
}

fun Array<String>.shouldRunAcp(): Boolean = any { it == "acp" || it == "--acp" }

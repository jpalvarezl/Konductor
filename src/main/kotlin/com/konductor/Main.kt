package com.konductor

import com.konductor.acp.runAcpAgent
import com.konductor.agent.AgentContextFactory
import com.konductor.agent.AgentLoop
import com.konductor.config.Configuration
import com.konductor.config.ConfigurationException
import com.konductor.config.EnvFile
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.ProviderFactory
import com.konductor.tool.BuiltinTools
import com.konductor.tool.RegistryToolExecutor
import com.konductor.tool.ToolContext
import com.konductor.tui.TuiApp
import com.konductor.tui.TuiExitCode
import com.konductor.core.models.Session
import com.konductor.session.InMemorySessionStore
import com.konductor.session.JsonlSessionStore
import com.konductor.session.SessionStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.uuid.Uuid

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
        // Precedence: CLI flags (`--agent-kind`, `--model`) win, then env vars; a gitignored cwd `.env` fills
        // gaps so `mvn` / `java -jar` work without exporting first.
        val cli = args.parseCliOverrides()
        val env = EnvFile.overlay()
        val configuration = Configuration.load(
            env = env,
            agentKindOverride = cli.agentKind,
            modelOverride = cli.model,
        )
        // One provider stack (Prompt or Hosted, per config) shared by both frontends; the ACP path mints an
        // AgentLoop per session.
        val agentProvider = ProviderFactory.create(configuration).also { provider = it }

        // Build the tool surface once: the same registry supplies the advertised specs (into the context) and
        // the cwd-scoped executor (into the loop). `configuration.toolAllow` enables read-only mode. The Hosted
        // provider ignores the client-side executor (its container owns tools), but wiring it is harmless.
        val cwd = Path.of("").toAbsolutePath()
        val registry = BuiltinTools.registry(configuration.toolAllow)
        val toolExecutor = RegistryToolExecutor(registry, ToolContext(cwd))
        val context = AgentContextFactory.build(configuration, cwd = cwd, tools = registry.enabled().map { it.spec })

        if (args.shouldRunAcp()) {
            runAcpAgent(agentProvider, context, toolExecutor) // headless ACP frontend (real streamed inference)
        } else {
            // Persisted sessions back the interactive TUI: JSONL under the config dir, or in-memory for
            // --no-session. The ACP frontend keeps its own per-protocol sessions (session/load is ACP Phase C).
            val store = sessionStore(cli, env)
            val session = resolveInitialSession(store, cwd, configuration.model, cli)
            TuiApp(AgentLoop(agentProvider, toolExecutor, context, store, session)).run() // interactive TUI (default)
        }
        TuiExitCode.SUCCESS
    } catch (configError: ConfigurationException) {
        // Config problems are user-actionable (missing env/settings), not bugs — show a clean message and a
        // hint, with no stack trace, so the first-run experience is friendly.
        System.err.println("Konductor configuration error: ${configError.message}")
        System.err.println(
            "Set the required Foundry settings (e.g. FOUNDRY_PROJECT_ENDPOINT, FOUNDRY_MODEL_NAME) and run " +
                "`az login`. See docs/spec/configuration.md.",
        )
        TuiExitCode.FAILURE
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

private data class CliOverrides(
    val agentKind: AgentKind? = null,
    val model: String? = null,
    val noSession: Boolean = false,
    val continueLatest: Boolean = false,
    val resumeId: String? = null,
    val name: String? = null,
)

private fun Array<String>.parseCliOverrides(): CliOverrides {
    var agentKind: AgentKind? = null
    var model: String? = null
    var noSession = false
    var continueLatest = false
    var resumeId: String? = null
    var name: String? = null
    var index = 0
    while (index < size) {
        when (val arg = this[index]) {
            "--agent-kind" -> {
                agentKind = parseAgentKindArgument(valueAfter(arg, index))
                index += 2
            }
            "--model" -> {
                model = valueAfter(arg, index)
                index += 2
            }
            "--no-session" -> {
                noSession = true
                index += 1
            }
            "--continue", "-c" -> {
                continueLatest = true
                index += 1
            }
            "--resume", "-r" -> {
                resumeId = valueAfter(arg, index)
                index += 2
            }
            "--name" -> {
                name = valueAfter(arg, index)
                index += 2
            }
            else -> index += 1
        }
    }
    return CliOverrides(agentKind, model, noSession, continueLatest, resumeId, name)
}

private fun Array<String>.valueAfter(flag: String, index: Int): String =
    getOrNull(index + 1) ?: throw IllegalArgumentException("Missing value after $flag.")

private fun parseAgentKindArgument(value: String): AgentKind =
    AgentKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown --agent-kind '$value'; expected prompt or hosted.")

/** JSONL-backed sessions under the config dir, or the ephemeral in-memory store for `--no-session`. */
private fun sessionStore(cli: CliOverrides, env: (String) -> String?): SessionStore =
    if (cli.noSession) InMemorySessionStore else JsonlSessionStore(sessionsRoot(env))

private fun sessionsRoot(env: (String) -> String?): Path {
    val configDir = env(Configuration.ENV_CONFIG_DIR)?.trim()?.ifBlank { null }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".konductor")
    return configDir.resolve("sessions")
}

/**
 * Pick the session the TUI starts in: `--resume <id>` loads a specific one, `--continue`/`-c` reopens the
 * most recent for this cwd (falling back to a fresh one), otherwise a new session. `--name` labels it.
 */
private fun resolveInitialSession(store: SessionStore, cwd: Path, model: String, cli: CliOverrides): Session {
    if (cli.noSession) return store.create(cwd, model, cli.name)
    val session = when {
        cli.resumeId != null -> store.load(parseSessionId(cli.resumeId))
        cli.continueLatest -> store.mostRecentForCwd(cwd)?.let { store.load(it.id) } ?: store.create(cwd, model, cli.name)
        else -> store.create(cwd, model, cli.name)
    }
    if (cli.name != null && session.name != cli.name) store.rename(session, cli.name)
    return session
}

private fun parseSessionId(raw: String): Uuid =
    runCatching { Uuid.parse(raw.trim()) }.getOrElse {
        throw IllegalArgumentException("Invalid --resume session id '$raw' (expected a session UUID).")
    }

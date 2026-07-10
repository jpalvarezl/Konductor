package com.konductor

import com.konductor.acp.ConfigurationAcpSessionRuntimeFactory
import com.konductor.acp.runAcpAgent
import com.konductor.agent.AgentContextFactory
import com.konductor.agent.AgentLoop
import com.konductor.config.Configuration
import com.konductor.config.ConfigurationException
import com.konductor.config.EnvFile
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.PromptProvider
import com.konductor.provider.ProviderFactory
import com.konductor.provider.inference.AzurePromptAgentClient
import com.konductor.tool.BuiltinTools
import com.konductor.tool.RegistryToolExecutor
import com.konductor.tool.ToolContext
import com.konductor.tui.TuiApp
import com.konductor.tui.TuiExitCode
import com.konductor.core.models.Session
import com.konductor.session.NoOpSessionStore
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
        val cli = parseCliArgs(args)
        when (cli.action) {
            CliAction.Help -> {
                println(KonductorCli.help)
                return TuiExitCode.SUCCESS
            }
            CliAction.Version -> {
                println("Konductor ${KonductorCli.version}")
                return TuiExitCode.SUCCESS
            }
            CliAction.Run -> Unit
        }

        // Precedence: CLI flags win, then env vars; a gitignored cwd `.env` fills gaps so `mvn` /
        // `java -jar` work without exporting first.
        val env = EnvFile.overlay()
        val configuration = Configuration.load(
            env = env,
            agentKindOverride = cli.agentKind,
            modelOverride = cli.model,
        )
        if (configuration.agentKind == AgentKind.Hosted && cli.toolSelection != null) {
            throw CliException(
                "CLI tool gates apply only to the Prompt provider; Hosted agents own their tool surface.",
            )
        }
        val cwd = Path.of("").toAbsolutePath()
        val toolAllow = cli.resolveToolAllow(configuration.toolAllow)

        if (cli.mode == CliMode.Acp) {
            // ACP sessions own their provider, cwd-bound prompt context, and tool executor. The factory closes all
            // session providers when the protocol connection ends.
            runAcpAgent(
                ConfigurationAcpSessionRuntimeFactory(configuration, toolAllow),
                JsonlSessionStore(sessionsRoot(env)),
                configuration.compaction,
            )
        } else {
            val agentProvider = ProviderFactory.create(configuration).also { provider = it }
            // The TUI remains one runtime bound to the launch cwd.
            val registry = BuiltinTools.registry(toolAllow)
            val toolExecutor = RegistryToolExecutor(registry, ToolContext(cwd))
            val context = AgentContextFactory.build(
                configuration,
                cwd = cwd,
                tools = registry.enabled().map { it.spec },
            )
            // Persisted sessions back the interactive TUI: JSONL under the config dir, or in-memory for
            // --no-session. The ACP frontend keeps its own per-protocol sessions (session/load is ACP Phase C).
            val store = sessionStore(cli, env)
            val session = resolveInitialSession(store, cwd, configuration.model, cli)
            // Expose the persisted-agent surface to the TUI `/agent` command when the provider is Prompt-kind: the
            // binder (hot-swap the bound agent) comes from the provider; the lifecycle client is built here.
            val agentBinder = (agentProvider as? PromptProvider)?.agentBinder
            val agentLifecycle =
                if (configuration.agentKind == AgentKind.Prompt) AzurePromptAgentClient(configuration) else null
            TuiApp(
                AgentLoop(agentProvider, toolExecutor, context, store, session, configuration.compaction),
                agentBinder,
                agentLifecycle,
                configuration.compaction.contextWindow,
            ).run()
        }
        TuiExitCode.SUCCESS
    } catch (cliError: CliException) {
        System.err.println("Konductor CLI error: ${cliError.message}")
        System.err.println("Run again with `--help` for usage.")
        TuiExitCode.FAILURE
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

/** JSONL-backed sessions under the config dir, or the ephemeral in-memory store for `--no-session`. */
private fun sessionStore(cli: CliOptions, env: (String) -> String?): SessionStore =
    if (cli.noSession) NoOpSessionStore else JsonlSessionStore(sessionsRoot(env))

private fun sessionsRoot(env: (String) -> String?): Path {
    val configDir = env(Configuration.ENV_CONFIG_DIR)?.trim()?.ifBlank { null }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".konductor")
    return configDir.resolve("sessions")
}

/**
 * Pick the session the TUI starts in: `--resume <id>` loads a specific one, `--continue`/`-c` reopens the
 * most recent for this cwd (falling back to a fresh one), otherwise a new session. `--name` labels it.
 */
private fun resolveInitialSession(store: SessionStore, cwd: Path, model: String, cli: CliOptions): Session {
    if (cli.noSession) return store.create(cwd, model, cli.name)
    val session = when {
        cli.resumeId != null -> store.load(parseSessionId(cli.resumeId))
        cli.continueLatest ->
            store.mostRecentForCwd(cwd)?.let { store.load(it.id) } ?: store.create(cwd, model, cli.name)
        else -> store.create(cwd, model, cli.name)
    }
    if (cli.name != null && session.name != cli.name) store.rename(session, cli.name)
    return session
}

private fun parseSessionId(raw: String): Uuid =
    runCatching { Uuid.parse(raw.trim()) }.getOrElse {
        throw IllegalArgumentException("Invalid --resume session id '$raw' (expected a session UUID).")
    }

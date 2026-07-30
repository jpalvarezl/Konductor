package com.konductor

import com.konductor.acp.ConfigurationAcpSessionRuntimeFactory
import com.konductor.acp.runAcpAgent
import com.konductor.agent.AgentContextFactory
import com.konductor.agent.AgentLoop
import com.konductor.config.Configuration
import com.konductor.config.ConfigurationException
import com.konductor.config.EnvFile
import com.konductor.foundry.project.FoundryProjectRuntime
import com.konductor.i18n.AppStrings
import com.konductor.i18n.LocalizationException
import com.konductor.provider.ProviderFactory
import com.konductor.provider.ProviderRuntime
import com.konductor.tool.createToolRuntime
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
    // runKonductor releases its resources first (it closes the Foundry Responses client in a `finally`). exitProcess
    // then guarantees a prompt, deterministic exit across `java -jar`, jpackage, and `mvn exec:java` — a
    // belt-and-suspenders guard in case a dependency ever leaves a lingering non-daemon executor thread.
    exitProcess(runKonductor(args).code)
}

private fun runKonductor(args: Array<String>): TuiExitCode {
    // Nullable + assigned inside the try so that a failure while building the stack (config resolution, SDK
    // client) still reaches the finally (release the client) and returns a code (so main's exitProcess runs).
    var runtime: ProviderRuntime? = null
    var strings = AppStrings.english()
    return try {
        // Locale is frontend bootstrap state: resolve it before CLI/config so help and TUI copy do not depend on
        // Foundry settings. The cwd `.env` overlay can provide KONDUCTOR_LOCALE just like the existing runtime vars.
        val env = EnvFile.overlay()
        strings = AppStrings.load(env)
        val cli = parseCliArgs(args, strings)
        when (cli.action) {
            CliAction.Help -> {
                println(KonductorCli.help(strings))
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
        val configuration = Configuration.load(
            env = env,
            agentKindOverride = cli.agentKind,
            modelOverride = cli.model,
        )
        val capabilities = ProviderFactory.capabilities(configuration)
        if (!capabilities.localTools && cli.toolSelection != null) {
            throw CliException(strings.cliToolsPromptOnly)
        }
        val foundryProject = FoundryProjectRuntime.create(configuration)
        val cwd = Path.of("").toAbsolutePath()
        val toolAllow = cli.resolveToolAllow(configuration.toolAllow)

        if (cli.mode == CliMode.Acp) {
            // ACP sessions own their provider, cwd-bound prompt context, and tool executor. The factory closes all
            // session providers when the protocol connection ends.
            runAcpAgent(
                ConfigurationAcpSessionRuntimeFactory(configuration, toolAllow, foundryProject),
                JsonlSessionStore(sessionsRoot(env)),
                configuration.compaction,
            )
        } else {
            val providerRuntime = foundryProject.createProvider(configuration).also { runtime = it }
            // The TUI remains one runtime bound to the launch cwd. Shared composition decides whether the local
            // tool surface exists; Hosted's container-owned tools are not represented as client ToolSpecs.
            val tools = providerRuntime.capabilities.createToolRuntime(toolAllow, cwd)
            val context = AgentContextFactory.build(configuration, cwd = cwd, tools = tools.specs)
            // Persisted sessions back the interactive TUI: JSONL under the config dir, or in-memory for
            // --no-session. The ACP frontend keeps its own per-protocol sessions (session/load is ACP Phase C).
            val store = sessionStore(cli, env)
            val initial = resolveInitialSession(store, cwd, configuration.model, cli)
            val loop = AgentLoop(
                providerRuntime,
                tools.executor,
                context,
                store,
                initial.session,
                configuration.compaction,
            )
            runBlocking { loop.activateInitialSession(initial.resuming) }
            TuiApp(
                loop,
                providerRuntime,
                foundryProject.deployments,
                foundryProject.connections,
                configuration.compaction.contextWindow,
                strings = strings,
            ).run()
        }
        TuiExitCode.SUCCESS
    } catch (cliError: CliException) {
        System.err.println(strings.cliError(cliError.message.orEmpty()))
        System.err.println(strings.cliUsageHint)
        TuiExitCode.FAILURE
    } catch (localeError: LocalizationException) {
        System.err.println(strings.localeError(localeError.message.orEmpty()))
        TuiExitCode.FAILURE
    } catch (configError: ConfigurationException) {
        // Config problems are user-actionable (missing env/settings), not bugs — show a clean message and a
        // hint, with no stack trace, so the first-run experience is friendly.
        System.err.println(strings.configurationError(configError.message.orEmpty()))
        System.err.println(strings.configurationHint)
        TuiExitCode.FAILURE
    } catch (t: Throwable) {
        // stdout is the TUI/ACP protocol channel, so report fatal errors on stderr (the TUI screen and ACP
        // transport are already torn down by now). Returning FAILURE maps to a non-zero process exit code.
        System.err.println(strings.fatalError(t.message ?: t::class.qualifiedName ?: strings.unknownError))
        t.printStackTrace(System.err)
        TuiExitCode.FAILURE
    } finally {
        runtime?.let { runBlocking { it.close() } }
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
private data class InitialSession(val session: Session, val resuming: Boolean)

private fun resolveInitialSession(store: SessionStore, cwd: Path, model: String, cli: CliOptions): InitialSession {
    if (cli.noSession) return InitialSession(newPersistedSession(store, cwd, model, cli.name), resuming = false)
    val initial = when {
        cli.resumeId != null -> InitialSession(store.load(parseSessionId(cli.resumeId)), resuming = true)
        cli.continueLatest -> store.mostRecentForCwd(cwd)?.let {
            InitialSession(store.load(it.id), resuming = true)
        } ?: InitialSession(newPersistedSession(store, cwd, model, cli.name), resuming = false)
        else -> InitialSession(newPersistedSession(store, cwd, model, cli.name), resuming = false)
    }
    if (cli.name != null && initial.session.name != cli.name) store.rename(initial.session, cli.name)
    return initial
}

private fun newPersistedSession(store: SessionStore, cwd: Path, model: String, name: String?): Session =
    store.newCandidate(cwd, model, name).also(store::persistNew)

private fun parseSessionId(raw: String): Uuid =
    runCatching { Uuid.parse(raw.trim()) }.getOrElse {
        throw IllegalArgumentException("Invalid --resume session id '$raw' (expected a session UUID).")
    }

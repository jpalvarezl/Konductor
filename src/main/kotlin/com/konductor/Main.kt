package com.konductor

import com.konductor.acp.AcpProcessInputs
import com.konductor.acp.ConfigurationAcpSessionRuntimeFactory
import com.konductor.acp.runAcpAgent
import com.konductor.agent.AgentContextFactory
import com.konductor.agent.AgentLoop
import com.konductor.agent.ContextFileLoader
import com.konductor.config.Configuration
import com.konductor.config.ConfigurationCandidate
import com.konductor.config.ConfigurationException
import com.konductor.config.EnvFile
import com.konductor.config.PersistedConfigurationIdentity
import com.konductor.config.WorkspaceConfigurationLoader
import com.konductor.config.WorkspaceTrustCoordinator
import com.konductor.config.WorkspaceTrustDecision
import com.konductor.config.WorkspaceTrustOutcome
import com.konductor.config.WorkspaceTrustOverride
import com.konductor.config.WorkspaceTrustStore
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.Session
import com.konductor.core.models.SessionHeader
import com.konductor.foundry.project.FoundryProjectRuntime
import com.konductor.i18n.AppStrings
import com.konductor.i18n.LocalizationException
import com.konductor.provider.AgentKind
import com.konductor.provider.ProviderFactory
import com.konductor.provider.ProviderManagement
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ProviderSessionLifecycle
import com.konductor.session.JsonlSessionStore
import com.konductor.session.NoOpSessionStore
import com.konductor.session.SessionStore
import com.konductor.tool.createToolRuntime
import com.konductor.tui.TuiApp
import com.konductor.tui.TuiExitCode
import com.konductor.tui.WorkspaceTrustPrompt
import com.konductor.tui.WorkspaceTrustPromptResult
import com.konductor.workspace.ResolvedConfigDirectory
import com.konductor.workspace.ResolvedWorkspace
import com.konductor.workspace.WorkspaceResolutionException
import com.konductor.workspace.WorkspaceResolver
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.uuid.Uuid

fun main(args: Array<String>) {
    exitProcess(runKonductor(args).code)
}

/** Process entry kept internal so focused startup tests can exercise ordering without exporting an application API. */
internal fun runKonductor(args: Array<String>): TuiExitCode {
    var runtime: ProviderRuntime? = null
    var strings = AppStrings.english()
    return try {
        // Frontend bootstrap is process-only. Help/version and config-directory selection never open cwd `.env`.
        val processEnvironment = EnvFile.processSources().processLookup()
        strings = AppStrings.load(processEnvironment)
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
        if (cli.agentKind == AgentKind.Hosted && cli.model != null) {
            throw CliException(strings.cliHostedModelConflict)
        }

        val workspaceResolver = WorkspaceResolver()
        val configDirectory = workspaceResolver.resolveConfigDirectory(
            applicationInput = cli.configDir,
            processEnvironment = processEnvironment,
        )
        val store = sessionStore(cli, configDirectory)

        if (cli.mode == CliMode.Acp) {
            // ACP intentionally retains only process inputs here. Every method resolves its authoritative cwd, trust,
            // eligible files, runtime, provider, context, and tools independently; it never constructs a TUI prompt.
            runAcpAgent(
                ConfigurationAcpSessionRuntimeFactory(
                    AcpProcessInputs(
                        configDirectory = configDirectory,
                        processEnvironment = processEnvironment,
                        agentKindOverride = cli.agentKind,
                        modelOverride = cli.model,
                        trustOverride = cli.trustOverride,
                        includeContextFiles = !cli.noContextFiles,
                        resolveToolAllow = cli::resolveToolAllow,
                    ),
                ),
                store,
            )
        } else {
            val workspace = workspaceResolver.resolve(Path.of(""))
            workspaceResolver.requireConfigOutsideWorkspace(configDirectory, workspace)
            val selection = resolveInitialSession(store, workspace, cli, strings)
            if (selection is InitialSessionSelection.Existing && cli.model != null) {
                throw CliException(strings.cliResumeModelConflict)
            }

            val trust = resolveTuiTrust(workspace, configDirectory, cli.trustOverride, strings)
            val loadedSources = WorkspaceConfigurationLoader().load(
                workspace = workspace,
                configDirectory = configDirectory,
                processEnvironment = processEnvironment,
                projectSourcesTrusted = trust == WorkspaceTrustDecision.Trusted,
                agentKindOverride = cli.agentKind,
                modelOverride = cli.model,
            )
            // A trusted dotenv may select locale only after trust. The prompt itself always used process locale.
            strings = AppStrings.load(loadedSources.environment.effectiveLookup())
            val configuration = resolveTuiConfiguration(loadedSources.candidate, selection, strings)
            validateFreshHostedModel(selection, configuration, cli, strings)
            val capabilities = ProviderFactory.capabilities(configuration)
            if (!capabilities.localTools && cli.toolSelection != null) {
                throw CliException(strings.cliToolsPromptOnly)
            }
            val toolAllow = cli.resolveToolAllow(configuration.toolAllow)
            val contextFiles = ContextFileLoader().load(
                workspace,
                configDirectory.canonicalPath,
                includeContextFiles = !cli.noContextFiles,
            )

            val foundryProject = FoundryProjectRuntime.create(configuration)
            val providerRuntime = foundryProject.createProvider(configuration).also { runtime = it }
            val tools = providerRuntime.capabilities.createToolRuntime(toolAllow, workspace.canonicalCwd)
            val context = AgentContextFactory.build(
                configuration,
                cwd = workspace.canonicalCwd,
                tools = tools.specs,
                contextFiles = contextFiles,
            )
            val initial = materializeInitialSession(store, selection, workspace, configuration, providerRuntime)
            val loop = AgentLoop(
                providerRuntime,
                tools.executor,
                context,
                store,
                initial.session,
                configuration.compaction,
            )
            runBlocking {
                loop.activateInitialSession(initial.resuming, candidatePublished = initial.resuming)
            }
            if (!initial.resuming) store.persistNew(initial.session)
            if (initial.resuming && cli.name != null && initial.session.name != cli.name) {
                store.rename(initial.session, cli.name)
            }
            TuiApp(
                loop,
                providerRuntime,
                foundryProject.deployments,
                foundryProject.connections,
                configuration.compaction.contextWindow,
                strings = strings,
                resumingInitialSession = initial.resuming,
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
        System.err.println(strings.configurationError(configError.message.orEmpty()))
        System.err.println(strings.configurationHint)
        TuiExitCode.FAILURE
    } catch (workspaceError: WorkspaceResolutionException) {
        System.err.println(strings.configurationError(workspaceError.message.orEmpty()))
        System.err.println(strings.configurationHint)
        TuiExitCode.FAILURE
    } catch (t: Throwable) {
        System.err.println(strings.fatalError(t.message ?: t::class.qualifiedName ?: strings.unknownError))
        t.printStackTrace(System.err)
        TuiExitCode.FAILURE
    } finally {
        runtime?.let { runBlocking { it.close() } }
    }
}

private fun sessionStore(cli: CliOptions, configDirectory: ResolvedConfigDirectory): SessionStore =
    if (cli.noSession) NoOpSessionStore else JsonlSessionStore(configDirectory.canonicalPath.resolve("sessions"))

internal sealed interface InitialSessionSelection {
    data class Existing(val header: SessionHeader) : InitialSessionSelection
    data class Fresh(val name: String?) : InitialSessionSelection
}

private data class InitialSession(val session: Session, val resuming: Boolean)

/** Header-only, cwd-bound TUI selection. No trust/config/runtime/provider operation belongs above this boundary. */
internal fun resolveInitialSession(
    store: SessionStore,
    workspace: ResolvedWorkspace,
    cli: CliOptions,
    strings: AppStrings,
): InitialSessionSelection {
    if (cli.noSession) return InitialSessionSelection.Fresh(cli.name)
    val header = when {
        cli.resumeId != null -> store.loadHeader(parseSessionId(cli.resumeId))
        cli.continueLatest -> store.mostRecentForCwd(workspace.canonicalCwd)?.let { store.loadHeader(it.id) }
        else -> null
    } ?: return InitialSessionSelection.Fresh(cli.name)
    val persistedCwd = try {
        WorkspaceResolver().canonicalDirectory(header.cwd, "persisted session cwd")
    } catch (error: Exception) {
        throw ConfigurationException(
            strings.sessionWorkspaceInvalid(header.cwd.toString(), error.message.orEmpty()),
            error,
        )
    }
    if (persistedCwd != workspace.canonicalCwd) {
        throw ConfigurationException(
            strings.sessionWorkspaceMismatch(persistedCwd.toString(), workspace.canonicalCwd.toString()),
        )
    }
    return InitialSessionSelection.Existing(header)
}

private fun resolveTuiConfiguration(
    candidate: ConfigurationCandidate,
    selection: InitialSessionSelection,
    strings: AppStrings,
): Configuration = when (selection) {
    is InitialSessionSelection.Fresh -> Configuration.resolveCandidate(candidate)
    is InitialSessionSelection.Existing -> {
        val header = selection.header
        val persistedKind = if (header.hostedBinding == null) AgentKind.Prompt else AgentKind.Hosted
        val effectiveKind = Configuration.resolveAgentKind(candidate)
        if (persistedKind != effectiveKind) {
            throw ConfigurationException(strings.sessionKindMismatch(persistedKind.name, effectiveKind.name))
        }
        Configuration.resolveCandidate(
            candidate,
            PersistedConfigurationIdentity(
                model = header.modelName,
                agentKind = persistedKind,
                promptAgentName = header.promptAgentName,
                hostedAgentName = header.hostedBinding?.agentName,
            ),
        )
    }
}

internal fun validateFreshHostedModel(
    selection: InitialSessionSelection,
    configuration: Configuration,
    cli: CliOptions,
    strings: AppStrings,
) {
    if (
        selection is InitialSessionSelection.Fresh &&
        configuration.agentKind == AgentKind.Hosted &&
        cli.model != null
    ) {
        throw CliException(strings.cliHostedModelConflict)
    }
}

private fun materializeInitialSession(
    store: SessionStore,
    selection: InitialSessionSelection,
    workspace: ResolvedWorkspace,
    configuration: Configuration,
    providerRuntime: ProviderRuntime,
): InitialSession = when (selection) {
    is InitialSessionSelection.Existing -> {
        val session = store.load(selection.header.id)
        require(session.header == selection.header) {
            "Session '${session.id}' changed between header inspection and transcript load."
        }
        InitialSession(session, resuming = true)
    }
    is InitialSessionSelection.Fresh -> {
        val candidate = store.newCandidate(workspace.canonicalCwd, configuration.model, selection.name)
        when (val lifecycle = providerRuntime.sessionLifecycle) {
            ProviderSessionLifecycle.None -> {
                candidate.promptAgentName =
                    (providerRuntime.management as? ProviderManagement.PromptAgents)?.binder?.activeAgent
            }
            is ProviderSessionLifecycle.Hosted -> if (store.persistsSessions) {
                candidate.hostedBinding = HostedSessionBinding(
                    lifecycle.controller.hostedAgentName,
                    candidate.id.toString(),
                )
            }
        }
        InitialSession(candidate, resuming = false)
    }
}

private fun resolveTuiTrust(
    workspace: ResolvedWorkspace,
    configDirectory: ResolvedConfigDirectory,
    override: WorkspaceTrustOverride,
    strings: AppStrings,
): WorkspaceTrustDecision {
    val coordinator = WorkspaceTrustCoordinator(
        WorkspaceTrustStore(configDirectory.canonicalPath, workspace.canonicalRoot),
    )
    var outcome = coordinator.resolve(workspace.canonicalCwd, override)
    while (true) {
        when (outcome) {
            is WorkspaceTrustOutcome.Saved -> return outcome.decision
            is WorkspaceTrustOutcome.SessionOnly -> {
                outcome.warning?.let {
                    System.err.println(
                        strings.trustPersistenceError(
                            configDirectory.canonicalPath.resolve(WorkspaceTrustStore.STORE_FILE_NAME).toString(),
                            it,
                        ),
                    )
                }
                return outcome.decision
            }
            is WorkspaceTrustOutcome.Override -> return outcome.decision
            is WorkspaceTrustOutcome.ChoiceRequired -> {
                outcome = when (val selected = WorkspaceTrustPrompt(strings).prompt(outcome)) {
                    is WorkspaceTrustPromptResult.Choice -> coordinator.choose(outcome, selected.choice)
                    WorkspaceTrustPromptResult.ContinueUntrusted,
                    WorkspaceTrustPromptResult.Quit,
                    -> return WorkspaceTrustDecision.Untrusted
                }
            }
            is WorkspaceTrustOutcome.Error -> {
                if (!outcome.mayContinueUntrustedForRun) {
                    throw ConfigurationException(
                        if (override == WorkspaceTrustOverride.Approve) {
                            strings.trustApproveError(outcome.snapshot.storePath.toString(), outcome.snapshot.problem)
                        } else {
                            strings.trustStoreError(outcome.snapshot.storePath.toString(), outcome.snapshot.problem)
                        },
                        outcome.snapshot.cause,
                    )
                }
                when (WorkspaceTrustPrompt(strings).prompt(outcome)) {
                    WorkspaceTrustPromptResult.ContinueUntrusted ->
                        outcome = coordinator.continueUntrustedForRun(outcome)
                    WorkspaceTrustPromptResult.Quit -> throw ConfigurationException("Workspace trust was not accepted.")
                    is WorkspaceTrustPromptResult.Choice -> error("Repair prompt returned a normal trust choice")
                }
            }
        }
    }
}

private fun parseSessionId(raw: String): Uuid = runCatching { Uuid.parse(raw.trim()) }.getOrElse {
    throw IllegalArgumentException("Invalid --resume session id '$raw' (expected a session UUID).")
}

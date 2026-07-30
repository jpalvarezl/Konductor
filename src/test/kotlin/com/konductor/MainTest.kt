package com.konductor

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import com.konductor.config.ConfigurationException
import com.konductor.config.WorkspaceTrustConflictException
import com.konductor.config.WorkspaceTrustLockTimeoutException
import com.konductor.config.WorkspaceTrustOutcome
import com.konductor.config.WorkspaceTrustOverride
import com.konductor.config.WorkspaceTrustSnapshot
import com.konductor.config.WorkspaceTrustStore
import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.SessionHeader
import com.konductor.core.models.SessionMetadata
import com.konductor.i18n.AppStrings
import com.konductor.provider.AgentKind
import com.konductor.session.SessionStore
import com.konductor.session.SessionSummary
import com.konductor.tui.WorkspaceTrustPromptResult
import com.konductor.workspace.ResolvedConfigDirectory
import com.konductor.workspace.ResolvedWorkspace
import com.konductor.workspace.WorkspaceResolutionException
import com.konductor.workspace.WorkspaceResolutionFailureKind
import com.konductor.workspace.WorkspaceResolver
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.Locale
import reactor.core.publisher.Mono
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class MainTest {
    @Test
    fun `explicit cross-cwd resume rejects after header inspection without full load`(@TempDir root: Path) {
        val launch = Files.createDirectory(root.resolve("launch"))
        val foreign = Files.createDirectory(root.resolve("foreign"))
        val id = Uuid.random()
        val store = RecordingSelectionStore(
            SessionHeader(id, null, foreign.toRealPath(), "model", Clock.System.now()),
        )
        val workspace = WorkspaceResolver().resolve(launch)

        val constructionCounters = linkedMapOf(
            "trust" to 0,
            "configuration" to 0,
            "runtime" to 0,
            "provider" to 0,
            "context" to 0,
            "tools" to 0,
        )
        val failure = assertFailsWith<ConfigurationException> {
            withInitialSessionSelection(
                store,
                workspace,
                CliOptions(resumeId = id.toString()),
                AppStrings.english(),
            ) {
                constructionCounters.keys.forEach { phase -> constructionCounters[phase] = constructionCounters.getValue(phase) + 1 }
            }
        }

        assertTrue(failure.message.orEmpty().contains(foreign.toRealPath().toString()))
        assertTrue(failure.message.orEmpty().contains(launch.toRealPath().toString()))
        assertEquals(1, store.headerLoads)
        assertEquals(0, store.fullLoads)
        assertTrue(constructionCounters.values.all { it == 0 }, constructionCounters.toString())
    }

    @Test
    fun `workspace and config directory failures use distinct localized production messages`(@TempDir root: Path) {
        val strings = AppStrings.forLocale(Locale.FRENCH)
        val config = root.resolve("config")
        val workspace = root.resolve("workspace")

        assertEquals(
            "Répertoire de configuration '$config' indisponible : cannot create",
            workspaceResolutionMessage(
                strings,
                WorkspaceResolutionException(
                    config,
                    "cannot create",
                    kind = WorkspaceResolutionFailureKind.ConfigDirectory,
                ),
            ),
        )
        assertEquals(
            "Le répertoire '$config' doit être hors de l’espace '$workspace'.",
            workspaceResolutionMessage(
                strings,
                WorkspaceResolutionException(
                    config,
                    "inside",
                    kind = WorkspaceResolutionFailureKind.ConfigDirectoryInsideWorkspace,
                    workspaceRoot = workspace,
                ),
            ),
        )
        assertEquals(
            "Chemin d’espace '$workspace' indisponible : cannot resolve",
            workspaceResolutionMessage(strings, WorkspaceResolutionException(workspace, "cannot resolve")),
        )
    }

    @Test
    fun `trust lock and conflict failures use semantic localized production messages`(@TempDir root: Path) {
        val strings = AppStrings.forLocale(Locale.FRENCH)
        val configPath = Files.createDirectory(root.resolve("config")).toRealPath()
        val workspacePath = Files.createDirectory(root.resolve("workspace")).toRealPath()
        val config = ResolvedConfigDirectory(configPath, Files.readAttributes(
            configPath,
            java.nio.file.attribute.BasicFileAttributes::class.java,
        ).fileKey())
        val workspace = ResolvedWorkspace(workspacePath, workspacePath)
        fun outcome(cause: Throwable) = WorkspaceTrustOutcome.Error(
            WorkspaceTrustSnapshot.Error(configPath.resolve(WorkspaceTrustStore.STORE_FILE_NAME), "detail", cause),
            mayContinueUntrustedForRun = false,
        )

        assertEquals(
            "Délai dépassé pour le verrou '${configPath.resolve(WorkspaceTrustStore.LOCK_FILE_NAME)}'.",
            trustFailureMessage(
                strings,
                config,
                workspace,
                WorkspaceTrustOverride.None,
                outcome(WorkspaceTrustLockTimeoutException("timeout")),
            ),
        )
        assertEquals(
            "Confiance modifiée simultanément pour '$workspacePath'.",
            trustFailureMessage(
                strings,
                config,
                workspace,
                WorkspaceTrustOverride.None,
                outcome(WorkspaceTrustConflictException("conflict")),
            ),
        )
    }

    @Test
    fun `repair quit uses localized semantic text in production trust flow`(@TempDir root: Path) {
        val workspacePath = Files.createDirectory(root.resolve("workspace")).toRealPath()
        val configPath = Files.createDirectory(root.resolve("config")).toRealPath()
        Files.writeString(configPath.resolve(WorkspaceTrustStore.STORE_FILE_NAME), "{ malformed")
        val config = ResolvedConfigDirectory(configPath, Files.readAttributes(
            configPath,
            java.nio.file.attribute.BasicFileAttributes::class.java,
        ).fileKey())
        val strings = AppStrings.forLocale(Locale.FRENCH)

        val failure = assertFailsWith<ConfigurationException> {
            resolveTuiTrust(
                ResolvedWorkspace(workspacePath, workspacePath),
                config,
                WorkspaceTrustOverride.None,
                strings,
                prompt = { WorkspaceTrustPromptResult.Quit },
            )
        }

        assertEquals("La confiance de l’espace n’a pas été acceptée.", failure.message)
    }

    @Test
    fun `effective Hosted kind rejects an explicit model for a fresh session`() {
        val credential = TokenCredential { Mono.just(AccessToken("token", OffsetDateTime.now().plusHours(1))) }
        val configuration = Configuration(
            projectEndpoint = "https://example.test",
            tokenCredential = credential,
            model = "hosted",
            agentKind = AgentKind.Hosted,
        )

        assertFailsWith<CliException> {
            validateFreshHostedModel(
                InitialSessionSelection.Fresh(null),
                configuration,
                CliOptions(model = "prompt-model"),
                com.konductor.i18n.AppStrings.english(),
            )
        }
    }

    @Test
    fun `continue queries only canonical launch cwd and does not allocate when absent`(@TempDir root: Path) {
        val launch = Files.createDirectory(root.resolve("launch"))
        val store = RecordingSelectionStore(null)
        val workspace = WorkspaceResolver().resolve(launch)

        val selection = resolveInitialSession(
            store,
            workspace,
            CliOptions(continueLatest = true, name = "fresh"),
            com.konductor.i18n.AppStrings.english(),
        )

        assertIs<InitialSessionSelection.Fresh>(selection)
        assertEquals("fresh", selection.name)
        assertEquals(listOf(launch.toRealPath()), store.listedCwds)
        assertEquals(0, store.allocations)
    }
}

private class RecordingSelectionStore(private val header: SessionHeader?) : SessionStore {
    var headerLoads = 0
    var fullLoads = 0
    var allocations = 0
    val listedCwds = mutableListOf<Path>()

    override fun newCandidate(cwd: Path, model: String, name: String?): Session {
        allocations++
        error("selection must not allocate")
    }
    override fun persistNew(candidate: Session) = error("selection must not publish")
    override fun loadHeader(id: Uuid): SessionHeader {
        headerLoads++
        return requireNotNull(header)
    }
    override fun append(session: Session, entry: Entry) = Unit
    override fun load(id: Uuid): Session {
        fullLoads++
        error("selection must not fully load")
    }
    override fun listForCwd(cwd: Path): List<SessionSummary> {
        listedCwds.add(cwd)
        val selected = header ?: return emptyList()
        return listOf(SessionSummary(selected.id, selected.name, selected.createdAt, selected.createdAt, 0))
    }
    override fun persistMetadata(session: Session, candidate: SessionMetadata) = Unit
    override fun rewrite(session: Session, candidateEntries: List<Entry>) = Unit
}

package com.konductor

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.SessionHeader
import com.konductor.core.models.SessionMetadata
import com.konductor.provider.AgentKind
import com.konductor.session.SessionStore
import com.konductor.session.SessionSummary
import com.konductor.workspace.WorkspaceResolver
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
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

        val failure = assertFailsWith<com.konductor.config.ConfigurationException> {
            resolveInitialSession(
                store,
                workspace,
                CliOptions(resumeId = id.toString()),
                com.konductor.i18n.AppStrings.english(),
            )
        }

        assertTrue(failure.message.orEmpty().contains(foreign.toRealPath().toString()))
        assertTrue(failure.message.orEmpty().contains(launch.toRealPath().toString()))
        assertEquals(1, store.headerLoads)
        assertEquals(0, store.fullLoads)
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

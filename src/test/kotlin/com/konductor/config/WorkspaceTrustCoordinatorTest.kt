package com.konductor.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceTrustCoordinatorTest {
    @Test
    fun `valid unknown only asks when a gated source is present and defaults untrusted`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val coordinator = WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace))

        val noFiles = assertIs<WorkspaceTrustOutcome.SessionOnly>(
            coordinator.resolve(gatedProjectSourcePresent = false),
        )
        assertEquals(WorkspaceTrustDecision.Untrusted, noFiles.decision)

        val required = assertIs<WorkspaceTrustOutcome.ChoiceRequired>(
            coordinator.resolve(gatedProjectSourcePresent = true),
        )
        assertEquals(WorkspaceTrustChoice.DoNotTrustForSession, required.defaultChoice)
    }

    @Test
    fun `noninteractive unknown is untrusted and never requires a choice`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val coordinator = WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace))

        val outcome = assertIs<WorkspaceTrustOutcome.SessionOnly>(
            coordinator.resolveNonInteractive(gatedProjectSourcePresent = true),
        )
        assertEquals(WorkspaceTrustDecision.Untrusted, outcome.decision)
    }

    @Test
    fun `session choices do not create store or lock while persistent choice saves`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val store = WorkspaceTrustStore(config, workspace)
        val coordinator = WorkspaceTrustCoordinator(store)
        val required = assertIs<WorkspaceTrustOutcome.ChoiceRequired>(
            coordinator.resolve(gatedProjectSourcePresent = true),
        )

        val trustedForRun = assertIs<WorkspaceTrustOutcome.SessionOnly>(
            coordinator.choose(required, WorkspaceTrustChoice.TrustForSession),
        )
        assertEquals(WorkspaceTrustDecision.Trusted, trustedForRun.decision)
        assertTrue(store.storePath.notExists())
        assertTrue(store.lockPath.notExists())

        val untrustedForRun = assertIs<WorkspaceTrustOutcome.SessionOnly>(
            coordinator.choose(required, WorkspaceTrustChoice.DoNotTrustForSession),
        )
        assertEquals(WorkspaceTrustDecision.Untrusted, untrustedForRun.decision)
        assertTrue(store.storePath.notExists())
        assertTrue(store.lockPath.notExists())

        val saved = assertIs<WorkspaceTrustOutcome.Saved>(
            coordinator.choose(required, WorkspaceTrustChoice.Trust),
        )
        assertEquals(WorkspaceTrustDecision.Trusted, saved.decision)
        assertTrue(store.storePath.toFile().isFile)
        assertTrue(store.lockPath.toFile().isFile)
        assertIs<WorkspaceTrustOutcome.Saved>(coordinator.resolve(gatedProjectSourcePresent = true))
    }

    @Test
    fun `overrides and noninteractive resolution never perform a gated presence probe`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val coordinator = WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace))
        var probes = 0

        assertIs<WorkspaceTrustOutcome.Override>(
            coordinator.resolve(WorkspaceTrustOverride.NoApprove) { probes += 1; true },
        )
        assertEquals(0, probes)
        assertIs<WorkspaceTrustOutcome.SessionOnly>(coordinator.resolveNonInteractive())
        assertEquals(0, probes)
    }

    @Test
    fun `cwd probe finds gated entries without following an invalid project config entry`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val coordinator = WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace))
        workspace.resolve(".env").writeText("X=1")

        assertIs<WorkspaceTrustOutcome.ChoiceRequired>(coordinator.resolve(workspace))
    }

    @Test
    fun `no approve bypasses corrupt content while approve rejects it`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val store = WorkspaceTrustStore(config, workspace)
        store.storePath.writeText("not json")
        val coordinator = WorkspaceTrustCoordinator(store)

        val noApprove = assertIs<WorkspaceTrustOutcome.Override>(
            coordinator.resolve(WorkspaceTrustOverride.NoApprove, gatedProjectSourcePresent = true),
        )
        assertEquals(WorkspaceTrustDecision.Untrusted, noApprove.decision)

        val approve = assertIs<WorkspaceTrustOutcome.Error>(
            coordinator.resolve(WorkspaceTrustOverride.Approve, gatedProjectSourcePresent = true),
        )
        assertEquals(false, approve.mayContinueUntrustedForRun)

        val normal = assertIs<WorkspaceTrustOutcome.Error>(
            coordinator.resolve(gatedProjectSourcePresent = false),
        )
        assertTrue(normal.mayContinueUntrustedForRun)
        assertEquals(
            WorkspaceTrustDecision.Untrusted,
            coordinator.continueUntrustedForRun(normal).decision,
        )
    }

    @Test
    fun `approve overrides valid saved denial without writing`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val store = WorkspaceTrustStore(config, workspace)
        val coordinator = WorkspaceTrustCoordinator(store)
        val required = assertIs<WorkspaceTrustOutcome.ChoiceRequired>(
            coordinator.resolve(gatedProjectSourcePresent = true),
        )
        coordinator.choose(required, WorkspaceTrustChoice.DoNotTrust)
        val before = store.storePath.toFile().readText()

        val outcome = assertIs<WorkspaceTrustOutcome.Override>(
            coordinator.resolve(WorkspaceTrustOverride.Approve, gatedProjectSourcePresent = true),
        )
        assertEquals(WorkspaceTrustDecision.Trusted, outcome.decision)
        assertEquals(before, store.storePath.toFile().readText())
        assertNotNull(store.read())
    }
}

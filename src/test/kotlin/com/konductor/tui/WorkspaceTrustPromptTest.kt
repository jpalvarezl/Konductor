package com.konductor.tui

import com.konductor.config.WorkspaceTrustChoice
import com.konductor.config.WorkspaceTrustCoordinator
import com.konductor.config.WorkspaceTrustOutcome
import com.konductor.config.WorkspaceTrustStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkspaceTrustPromptTest {
    @Test
    fun `normal prompt exposes four localized choices and defaults session-only untrusted`(@TempDir root: Path) {
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val config = Files.createDirectory(root.resolve("config"))
        val required = assertIs<WorkspaceTrustOutcome.ChoiceRequired>(
            WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace)).resolve(gatedProjectSourcePresent = true),
        )

        val model = WorkspaceTrustPrompt().model(required)

        assertEquals(4, model.options.size)
        assertEquals(3, model.defaultIndex)
        assertEquals(
            WorkspaceTrustPromptResult.Choice(WorkspaceTrustChoice.DoNotTrustForSession),
            model.options[model.defaultIndex].result,
        )
        assertEquals(
            WorkspaceTrustChoice.entries,
            model.options.map { (it.result as WorkspaceTrustPromptResult.Choice).choice },
        )
    }

    @Test
    fun `repair prompt offers only continue and quit and defaults quit`(@TempDir root: Path) {
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val config = Files.createDirectory(root.resolve("config"))
        Files.writeString(config.resolve(WorkspaceTrustStore.STORE_FILE_NAME), "{ malformed")
        val error = assertIs<WorkspaceTrustOutcome.Error>(
            WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace)).resolve(gatedProjectSourcePresent = false),
        )

        val model = WorkspaceTrustPrompt().model(error)

        assertEquals(2, model.options.size)
        assertEquals(WorkspaceTrustPromptResult.ContinueUntrusted, model.options[0].result)
        assertEquals(WorkspaceTrustPromptResult.Quit, model.options[1].result)
        assertEquals(1, model.defaultIndex)
        assertEquals(WorkspaceTrustPromptResult.Quit, model.cancelResult)
    }
}

package com.konductor.tui

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.konductor.config.WorkspaceTrustChoice
import com.konductor.config.WorkspaceTrustOutcome
import com.konductor.i18n.AppStrings

/** Presentation-only result from the bootstrap trust screen. */
sealed interface WorkspaceTrustPromptResult {
    data class Choice(val choice: WorkspaceTrustChoice) : WorkspaceTrustPromptResult
    data object ContinueUntrusted : WorkspaceTrustPromptResult
    data object Quit : WorkspaceTrustPromptResult
}

/**
 * Minimal localized Lanterna chooser used before the main TUI exists. Filesystem and persistence policy remain in the
 * trust coordinator. Every terminal/screen is restored and closed before the selected semantic result is returned.
 */
class WorkspaceTrustPrompt(
    private val strings: AppStrings = AppStrings.english(),
    private val terminalFactory: DefaultTerminalFactory = DefaultTerminalFactory()
        .setTerminalEmulatorTitle("Konductor workspace trust"),
) {
    fun prompt(outcome: WorkspaceTrustOutcome): WorkspaceTrustPromptResult {
        val model = model(outcome)
        val terminal = terminalFactory.createTerminal()
        val screen = TerminalScreen(terminal)
        try {
            screen.startScreen()
            var selected = model.defaultIndex
            while (true) {
                render(screen, model, selected)
                val key = screen.readInput()
                when (key.keyType) {
                    KeyType.ArrowUp -> selected = (selected - 1).coerceAtLeast(0)
                    KeyType.ArrowDown -> selected = (selected + 1).coerceAtMost(model.options.lastIndex)
                    KeyType.Enter -> return model.options[selected].result
                    KeyType.Escape, KeyType.EOF -> return model.cancelResult
                    else -> Unit
                }
            }
        } finally {
            runCatching { screen.stopScreen() }
            runCatching { terminal.close() }
        }
    }

    internal fun model(outcome: WorkspaceTrustOutcome): WorkspaceTrustPromptModel = when (outcome) {
        is WorkspaceTrustOutcome.ChoiceRequired -> WorkspaceTrustPromptModel(
            title = strings.trustPromptTitle,
            body = strings.trustPromptBody(outcome.workspaceRoot.toString()),
            options = listOf(
                WorkspaceTrustPromptOption(
                    strings.trustChoiceTrust,
                    WorkspaceTrustPromptResult.Choice(WorkspaceTrustChoice.Trust),
                ),
                WorkspaceTrustPromptOption(
                    strings.trustChoiceTrustForSession,
                    WorkspaceTrustPromptResult.Choice(WorkspaceTrustChoice.TrustForSession),
                ),
                WorkspaceTrustPromptOption(
                    strings.trustChoiceDoNotTrust,
                    WorkspaceTrustPromptResult.Choice(WorkspaceTrustChoice.DoNotTrust),
                ),
                WorkspaceTrustPromptOption(
                    strings.trustChoiceDoNotTrustForSession,
                    WorkspaceTrustPromptResult.Choice(WorkspaceTrustChoice.DoNotTrustForSession),
                ),
            ),
            defaultIndex = 3,
            cancelResult = WorkspaceTrustPromptResult.Choice(WorkspaceTrustChoice.DoNotTrustForSession),
        )
        is WorkspaceTrustOutcome.Error -> WorkspaceTrustPromptModel(
            title = strings.trustRepairTitle,
            body = strings.trustRepairBody(outcome.snapshot.storePath.toString(), outcome.snapshot.problem),
            options = listOf(
                WorkspaceTrustPromptOption(
                    strings.trustErrorContinueUntrusted,
                    WorkspaceTrustPromptResult.ContinueUntrusted,
                ),
                WorkspaceTrustPromptOption(strings.trustErrorQuit, WorkspaceTrustPromptResult.Quit),
            ),
            defaultIndex = 1,
            cancelResult = WorkspaceTrustPromptResult.Quit,
        )
        else -> throw IllegalArgumentException("Trust outcome does not require a prompt: $outcome")
    }

    private fun render(screen: TerminalScreen, model: WorkspaceTrustPromptModel, selected: Int) {
        screen.clear()
        val graphics = screen.newTextGraphics()
        val width = screen.terminalSize.columns.coerceAtLeast(1)
        graphics.setForegroundColor(TextColor.ANSI.WHITE)
        graphics.enableModifiers(SGR.BOLD)
        graphics.putString(0, 0, model.title.take(width))
        graphics.disableModifiers(SGR.BOLD)
        wrap(model.body, width).forEachIndexed { index, line -> graphics.putString(0, index + 2, line) }
        val optionsTop = wrap(model.body, width).size + 3
        model.options.forEachIndexed { index, option ->
            if (index == selected) graphics.enableModifiers(SGR.REVERSE) else graphics.disableModifiers(SGR.REVERSE)
            val prefix = if (index == selected) "> " else "  "
            graphics.putString(
                0,
                optionsTop + index,
                prefix + option.label.take((width - 2).coerceAtLeast(0)),
            )
        }
        graphics.disableModifiers(SGR.REVERSE)
        screen.refresh()
    }

    private fun wrap(text: String, width: Int): List<String> = text.lineSequence().flatMap { line ->
        if (line.isEmpty()) sequenceOf("") else line.chunked(width).asSequence()
    }.toList()
}

internal data class WorkspaceTrustPromptModel(
    val title: String,
    val body: String,
    val options: List<WorkspaceTrustPromptOption>,
    val defaultIndex: Int,
    val cancelResult: WorkspaceTrustPromptResult,
)

internal data class WorkspaceTrustPromptOption(
    val label: String,
    val result: WorkspaceTrustPromptResult,
)

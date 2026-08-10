package com.konductor.tui

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.TerminalFactory
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
    private val terminalFactory: TerminalFactory = DefaultTerminalFactory()
        .setTerminalEmulatorTitle(strings.trustWindowTitle),
) {
    fun prompt(outcome: WorkspaceTrustOutcome): WorkspaceTrustPromptResult {
        val model = model(outcome)
        terminalFactory.createTerminal().use { terminal ->
            TerminalScreen(terminal).use { screen ->
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
            }
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

    internal fun render(screen: TerminalScreen, model: WorkspaceTrustPromptModel, selected: Int) {
        screen.doResizeIfNecessary()
        screen.clear()
        val width = screen.terminalSize.columns
        val height = screen.terminalSize.rows
        if (width <= 0 || height <= 0) {
            screen.refresh()
            return
        }

        val graphics = screen.newTextGraphics()
        graphics.setForegroundColor(TextColor.ANSI.WHITE)
        val optionCapacity = minOf(model.options.size, height)
        val firstOption = (selected - optionCapacity + 1)
            .coerceIn(0, model.options.size - optionCapacity)
        val visibleOptions = model.options.indices.drop(firstOption).take(optionCapacity)
        val optionsTop = height - visibleOptions.size
        val bodyTop = if (optionsTop > 0) 1 else 0
        val bodyCapacity = (optionsTop - bodyTop).coerceAtLeast(0)

        if (optionsTop > 0) {
            graphics.enableModifiers(SGR.BOLD)
            graphics.putString(0, 0, TerminalTextUtils.fitString(model.title, width))
            graphics.disableModifiers(SGR.BOLD)
        }
        wrap(model.body, width).take(bodyCapacity).forEachIndexed { index, line ->
            graphics.putString(0, bodyTop + index, TerminalTextUtils.fitString(line, width))
        }
        visibleOptions.forEachIndexed { row, index ->
            if (index == selected) graphics.enableModifiers(SGR.REVERSE) else graphics.disableModifiers(SGR.REVERSE)
            val prefix = if (index == selected) "> " else "  "
            graphics.putString(
                0,
                optionsTop + row,
                TerminalTextUtils.fitString(prefix + model.options[index].label, width),
            )
        }
        graphics.disableModifiers(SGR.REVERSE)
        screen.refresh()
    }

    private fun wrap(text: String, width: Int): List<String> = text.lineSequence().flatMap { line ->
        if (line.isEmpty()) sequenceOf("") else TerminalTextUtils.getWordWrappedText(width, line).asSequence()
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

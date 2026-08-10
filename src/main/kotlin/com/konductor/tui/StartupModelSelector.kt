package com.konductor.tui

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.TerminalFactory
import com.konductor.conversation.CommandOption
import com.konductor.i18n.AppStrings
import com.konductor.tui.palette.FuzzyMatcher
import com.konductor.tui.palette.PaletteKey

/** Exact process-local result from the pre-provider model selector. */
sealed interface StartupModelSelectorResult {
    data class Selected(val value: String) : StartupModelSelectorResult
    data object Cancelled : StartupModelSelectorResult
}

/**
 * Minimal Lanterna selector used before a provider, session, [com.konductor.core.AppState], or [TuiApp] exists.
 * It operates only on SDK-free [CommandOption] values and never stages or dispatches command text.
 */
class StartupModelSelector(
    private val strings: AppStrings = AppStrings.english(),
    private val terminalFactory: TerminalFactory = DefaultTerminalFactory()
        .setTerminalEmulatorTitle(strings.startupModelSelectorTitle),
) {
    fun select(options: List<CommandOption>): StartupModelSelectorResult {
        val controller = StartupModelSelectorController(options)
        terminalFactory.createTerminal().use { terminal ->
            TerminalScreen(terminal).use { screen ->
                screen.startScreen()
                while (true) {
                    render(screen, controller.state)
                    val key = screen.readInput()
                    val paletteKey = when (key.keyType) {
                        KeyType.Escape, KeyType.EOF -> PaletteKey.Escape
                        KeyType.Enter -> PaletteKey.Enter
                        KeyType.Backspace -> PaletteKey.Backspace
                        KeyType.ArrowUp -> PaletteKey.ArrowUp
                        KeyType.ArrowDown -> PaletteKey.ArrowDown
                        KeyType.Character -> key.character?.let(PaletteKey::Character)
                        else -> null
                    }
                    paletteKey?.let(controller::handle)?.let { return it }
                }
            }
        }
    }

    internal fun render(screen: Screen, state: StartupModelSelectorState) {
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

        var top = 0
        graphics.enableModifiers(SGR.BOLD)
        graphics.putString(0, top++, TerminalTextUtils.fitString(strings.startupModelSelectorTitle, width))
        graphics.disableModifiers(SGR.BOLD)

        if (top < height) {
            val query = if (state.query.isEmpty()) strings.paletteQueryHint else state.query
            graphics.putString(0, top++, TerminalTextUtils.fitString("⌕ $query", width))
        }

        var bottom = height
        if (bottom > top) {
            graphics.putString(0, --bottom, TerminalTextUtils.fitString(strings.startupModelSelectorKeyHint, width))
        }
        if (state.isTruncated && bottom > top) {
            graphics.putString(
                0,
                --bottom,
                TerminalTextUtils.fitString(
                    strings.startupModelSelectorTruncation(state.retainedOptions.size, state.totalOptionCount),
                    width,
                ),
            )
        }

        val itemCapacity = (bottom - top).coerceAtLeast(0)
        if (state.filteredOptions.isEmpty()) {
            if (itemCapacity > 0) {
                graphics.putString(0, top, TerminalTextUtils.fitString(strings.startupModelSelectorNoMatches, width))
            }
        } else {
            val first = (state.selectedIndex - itemCapacity + 1)
                .coerceIn(0, (state.filteredOptions.size - itemCapacity).coerceAtLeast(0))
            state.filteredOptions.drop(first).take(itemCapacity).forEachIndexed { row, option ->
                val selected = first + row == state.selectedIndex
                if (selected) graphics.enableModifiers(SGR.REVERSE) else graphics.disableModifiers(SGR.REVERSE)
                val line = buildString {
                    append(if (selected) "> " else "  ")
                    append(option.label)
                    option.detail?.let { append(" — ").append(it) }
                }
                graphics.putString(0, top + row, TerminalTextUtils.fitString(line, width))
            }
            graphics.disableModifiers(SGR.REVERSE)
        }
        screen.refresh()
    }
}

/** Lanterna-free bounded state machine for deterministic filtering, navigation, selection, and cancellation. */
internal class StartupModelSelectorController(options: List<CommandOption>) {
    val state = StartupModelSelectorState(
        retainedOptions = options.take(FuzzyMatcher.MAX_INSPECTED_CANDIDATES),
        totalOptionCount = options.size,
    ).also(::refresh)

    fun handle(key: PaletteKey): StartupModelSelectorResult? = when (key) {
        is PaletteKey.Character -> {
            if (!key.value.isISOControl()) {
                state.query += key.value
                refresh(state)
            }
            null
        }
        PaletteKey.Backspace -> {
            if (state.query.isNotEmpty()) {
                val end = state.query.offsetByCodePoints(state.query.length, -1)
                state.query = state.query.substring(0, end)
                refresh(state)
            }
            null
        }
        PaletteKey.ArrowUp -> {
            moveSelection(-1)
            null
        }
        PaletteKey.ArrowDown -> {
            moveSelection(1)
            null
        }
        PaletteKey.Enter -> state.selectedOption?.let { StartupModelSelectorResult.Selected(it.value) }
        PaletteKey.Escape -> StartupModelSelectorResult.Cancelled
    }

    private fun refresh(state: StartupModelSelectorState) {
        state.filteredOptions = FuzzyMatcher.rank(state.query, state.retainedOptions) { option ->
            listOf(option.label, option.value) + listOfNotNull(option.detail)
        }
        state.selectedIndex = state.selectedIndex.coerceIn(0, (state.filteredOptions.size - 1).coerceAtLeast(0))
    }

    private fun moveSelection(delta: Int) {
        if (state.filteredOptions.isEmpty()) return
        state.selectedIndex = (state.selectedIndex + delta).coerceIn(0, state.filteredOptions.lastIndex)
    }
}

internal data class StartupModelSelectorState(
    val retainedOptions: List<CommandOption>,
    val totalOptionCount: Int,
    var query: String = "",
    var filteredOptions: List<CommandOption> = emptyList(),
    var selectedIndex: Int = 0,
) {
    val isTruncated: Boolean get() = totalOptionCount > retainedOptions.size
    val selectedOption: CommandOption? get() = filteredOptions.getOrNull(selectedIndex)
}

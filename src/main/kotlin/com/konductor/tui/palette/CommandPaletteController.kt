package com.konductor.tui.palette

import com.konductor.conversation.CommandAvailability
import com.konductor.conversation.CommandOption
import com.konductor.conversation.CommandOptionProvider
import com.konductor.conversation.CommandRegistry
import com.konductor.core.AppState
import com.konductor.core.CommandPaletteItem
import com.konductor.core.CommandPaletteMode
import com.konductor.core.CommandPaletteState
import com.konductor.core.CommandPaletteStatus
import com.konductor.i18n.AppStrings

/** Lanterna-free keys understood while the command palette owns input. */
sealed interface PaletteKey {
    data class Character(val value: Char) : PaletteKey
    data object Backspace : PaletteKey
    data object ArrowUp : PaletteKey
    data object ArrowDown : PaletteKey
    data object Enter : PaletteKey
    data object Escape : PaletteKey
}

/** Side effects that remain owned by [com.konductor.tui.TuiApp]. */
sealed interface PaletteAction {
    data object None : PaletteAction
    data object Closed : PaletteAction
    data class LoadOptions(
        val requestId: Long,
        val provider: CommandOptionProvider,
    ) : PaletteAction
}

/**
 * Input/state controller for the command palette. It consumes the canonical registry without dispatching commands;
 * Enter only stages stable text in the normal composer. Asynchronous provider execution stays with the TUI owner.
 */
class CommandPaletteController(
    private val registry: CommandRegistry,
    private val strings: AppStrings = AppStrings.english(),
    private val optionProviders: Map<String, CommandOptionProvider> = emptyMap(),
) {
    private var nextRequestId: Long = 1

    fun open(state: AppState) {
        val items = registry.entries().map { entry ->
            val disabledReason = (entry.availability as? CommandAvailability.Disabled)?.reason
            CommandPaletteItem(
                id = entry.descriptor.name,
                label = entry.descriptor.name,
                usage = entry.descriptor.usage,
                description = entry.descriptor.describe(strings),
                insertionText = entry.descriptor.insertionPrefix,
                searchTerms = listOf(entry.descriptor.name) + entry.descriptor.aliases + entry.descriptor.usage,
                enabled = entry.availability is CommandAvailability.Enabled,
                disabledReason = disabledReason,
            )
        }
        state.commandPalette = CommandPaletteState(
            mode = CommandPaletteMode.Commands,
            title = strings.paletteTitle,
            allItems = items,
        )
        refresh(state.commandPalette ?: return)
    }

    fun handle(state: AppState, key: PaletteKey): PaletteAction {
        val palette = state.commandPalette ?: return PaletteAction.None
        return when (key) {
            is PaletteKey.Character -> {
                if (!key.value.isISOControl() && palette.query.text.length < MAX_QUERY_LENGTH) {
                    palette.query.insert(key.value)
                    refresh(palette)
                }
                PaletteAction.None
            }
            PaletteKey.Backspace -> {
                palette.query.backspace()
                refresh(palette)
                PaletteAction.None
            }
            PaletteKey.ArrowUp -> PaletteAction.None.also { moveSelection(palette, -1) }
            PaletteKey.ArrowDown -> PaletteAction.None.also { moveSelection(palette, 1) }
            PaletteKey.Enter -> select(state, palette)
            PaletteKey.Escape -> {
                state.commandPalette = null
                PaletteAction.Closed
            }
        }
    }

    /** Apply a completed option request only if its palette generation is still visible. */
    fun completeOptions(state: AppState, requestId: Long, result: Result<List<CommandOption>>) {
        val palette = state.commandPalette ?: return
        if (palette.mode != CommandPaletteMode.Options || palette.requestId != requestId) return

        result.fold(
            onSuccess = { options ->
                if (options.isEmpty()) {
                    palette.status = CommandPaletteStatus.Empty(strings.paletteModelEmpty)
                    palette.allItems = emptyList()
                    palette.items = emptyList()
                } else {
                    palette.status = CommandPaletteStatus.Ready
                    palette.allItems = options.map { option ->
                        CommandPaletteItem(
                            id = option.value,
                            label = option.label,
                            description = option.detail,
                            insertionText = requireNotNull(palette.optionPrefix) + option.value,
                            searchTerms = listOf(option.label, option.value) + listOfNotNull(option.detail),
                        )
                    }
                    refresh(palette)
                }
            },
            onFailure = {
                palette.status = CommandPaletteStatus.Error(strings.paletteModelError)
                palette.allItems = emptyList()
                palette.items = emptyList()
            },
        )
        palette.selectedIndex = 0
    }

    private fun select(state: AppState, palette: CommandPaletteState): PaletteAction {
        if (palette.status != CommandPaletteStatus.Ready) return PaletteAction.None
        val selected = palette.selectedItem ?: return PaletteAction.None
        if (!selected.enabled) return PaletteAction.None

        if (palette.mode == CommandPaletteMode.Commands) {
            val provider = optionProviders[selected.id]
            if (provider != null) {
                val requestId = nextRequestId++
                state.commandPalette = CommandPaletteState(
                    mode = CommandPaletteMode.Options,
                    title = strings.paletteModelTitle,
                    optionPrefix = selected.insertionText,
                    requestId = requestId,
                    status = CommandPaletteStatus.Loading,
                )
                return PaletteAction.LoadOptions(requestId, provider)
            }
        }

        state.input.replace(selected.insertionText)
        state.commandPalette = null
        return PaletteAction.Closed
    }

    private fun refresh(palette: CommandPaletteState) {
        if (palette.status != CommandPaletteStatus.Ready) return
        palette.items = FuzzyMatcher.rank(palette.query.text, palette.allItems, CommandPaletteItem::searchTerms)
        palette.selectedIndex = palette.selectedIndex.coerceIn(0, (palette.items.size - 1).coerceAtLeast(0))
    }

    private fun moveSelection(palette: CommandPaletteState, delta: Int) {
        if (palette.status != CommandPaletteStatus.Ready || palette.items.isEmpty()) return
        palette.selectedIndex = (palette.selectedIndex + delta).coerceIn(0, palette.items.lastIndex)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 128
    }
}

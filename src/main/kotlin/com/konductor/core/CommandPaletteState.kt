package com.konductor.core

/** Which catalog the command palette is currently presenting. */
enum class CommandPaletteMode {
    Commands,
    Options,
}

/** One application-owned row rendered by the TUI command palette. */
data class CommandPaletteItem(
    val id: String,
    val label: String,
    val usage: String? = null,
    val description: String? = null,
    val insertionText: String,
    val searchTerms: List<String> = listOf(label),
    val enabled: Boolean = true,
    val disabledReason: String? = null,
)

/** Explicit non-result states for asynchronous option loading. */
sealed interface CommandPaletteStatus {
    data object Ready : CommandPaletteStatus
    data object Loading : CommandPaletteStatus
    data class Empty(val message: String) : CommandPaletteStatus
    data class Error(val message: String) : CommandPaletteStatus
}

/**
 * Mutable frontend state for the modal command palette. Components only render this state; input handling lives in
 * the TUI controller. [allItems] retains the source catalog while [items] holds its deterministic fuzzy projection.
 */
class CommandPaletteState(
    val mode: CommandPaletteMode,
    val title: String,
    val optionPrefix: String? = null,
    val requestId: Long = 0,
    status: CommandPaletteStatus = CommandPaletteStatus.Ready,
    allItems: List<CommandPaletteItem> = emptyList(),
) {
    val query: InputState = InputState()

    var status: CommandPaletteStatus = status
    var allItems: List<CommandPaletteItem> = allItems
    var items: List<CommandPaletteItem> = allItems
    var selectedIndex: Int = 0

    val selectedItem: CommandPaletteItem?
        get() = items.getOrNull(selectedIndex)
}

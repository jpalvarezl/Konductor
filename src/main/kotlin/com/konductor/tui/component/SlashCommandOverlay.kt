package com.konductor.tui.component

import com.konductor.core.AppState
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme

/**
 * Lightweight overlay shown above the composer when the user starts typing a slash-command.
 *
 * MVP:
 * - Filters by prefix match on the current input (first token only)
 * - Shows up to [maxItems] results
 * - Does not support selection yet; this is incremental UX over "remember commands"
 */
class SlashCommandOverlay(
    private val theme: Theme,
    private val commands: List<String>,
    private val subcommands: Map<String, List<String>> = emptyMap(),
    private val maxItems: Int = 8,
) {
    data class Model(
        val visible: Boolean,
        val header: String,
        val items: List<String>,
        val selectedIndex: Int,
    )

    private var selectedIndex: Int = 0

    /** Whether the overlay should currently be shown. */
    fun isActive(state: AppState): Boolean = compute(state).visible

    /** Move selection up/down when overlay is active. */
    fun moveSelection(delta: Int, state: AppState) {
        val model = compute(state)
        if (!model.visible || model.items.isEmpty()) return
        selectedIndex = (model.selectedIndex + delta).coerceIn(0, model.items.lastIndex)
    }

    /**
     * Apply the currently selected item into the input.
     *
     * Returns true if it applied a completion.
     */
    fun acceptSelection(state: AppState): Boolean {
        val model = compute(state)
        if (!model.visible || model.items.isEmpty()) return false
        val chosen = model.items[model.selectedIndex]

        val text = state.input.text
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return false

        val parts = trimmed.split(Regex("\\s+"), limit = 3)
        val cmd = parts.getOrNull(0).orEmpty()

        // Completing a top-level command.
        if (parts.size <= 1) {
            state.input.setText(chosen + " ")
            return true
        }

        // Completing a subcommand: replace the 2nd token.
        val restAfterCmd = trimmed.removePrefix(cmd).trimStart()
        val currentSub = restAfterCmd.split(Regex("\\s+"), limit = 2).getOrNull(0).orEmpty()
        val tail = restAfterCmd.removePrefix(currentSub).trimStart()

        val rebuilt = buildString {
            append(cmd)
            append(' ')
            append(chosen)
            if (tail.isNotEmpty()) {
                append(' ')
                append(tail)
            } else {
                append(' ')
            }
        }
        state.input.setText(rebuilt)
        return true
    }

    fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        if (bounds.isEmpty) return

        val model = compute(state)
        if (!model.visible) return

        // Draw a simple panel: background fill + lines.
        canvas.fill(bounds, theme.inputBackground)

        canvas.write(
            x = bounds.left,
            y = bounds.top,
            text = model.header,
            foreground = theme.mutedText,
            background = theme.inputBackground,
            maxWidth = bounds.width,
        )

        val availableRows = (bounds.height - 1).coerceAtLeast(0)
        val visible = model.items.take(availableRows)
        visible.forEachIndexed { i, item ->
            val prefix = if (i == model.selectedIndex) "> " else "  "
            canvas.write(
                x = bounds.left,
                y = bounds.top + 1 + i,
                text = (prefix + item),
                foreground = theme.normalText,
                background = theme.inputBackground,
                maxWidth = bounds.width,
            )
        }
    }

    private fun compute(state: AppState): Model {
        val query = state.input.text
        // Show suggestions whenever the user is entering a single-line slash command.
        // Do not require the very first character to be '/', because the input renderer preserves
        // leading spaces but many users will type " /cmd".
        val trimmedStart = query.trimStart()
        if (!trimmedStart.startsWith("/")) return Model(false, "", emptyList(), 0)
        if (query.contains('\n')) return Model(false, "", emptyList(), 0)

        // Keep end spaces out of the matching tokens, but preserve leading-trim behavior above.
        val trimmed = trimmedStart.trimEnd()
        if (trimmed == "/") return Model(false, "", emptyList(), 0)

        val parts = trimmed.split(Regex("\\s+"), limit = 3)
        val cmdToken = parts.getOrNull(0).orEmpty()

        // Top-level completion: "/ag"
        if (parts.size <= 1) {
            val items = commands
                .asSequence()
                .filter { it.startsWith(cmdToken, ignoreCase = true) }
                .take(maxItems)
                .toList()
            if (items.isEmpty()) return Model(false, "", emptyList(), 0)
            val sel = selectedIndex.coerceIn(0, items.lastIndex)
            selectedIndex = sel
            return Model(true, "Commands", items, sel)
        }

        // Subcommand completion: "/agent cr"
        val subs = subcommands[cmdToken.lowercase()] ?: subcommands[cmdToken] ?: emptyList()
        if (subs.isEmpty()) return Model(false, "", emptyList(), 0)

        val subToken = parts.getOrNull(1).orEmpty()
        val items = subs
            .asSequence()
            .filter { it.startsWith(subToken, ignoreCase = true) }
            .take(maxItems)
            .toList()
        if (items.isEmpty()) return Model(false, "", emptyList(), 0)

        val sel = selectedIndex.coerceIn(0, items.lastIndex)
        selectedIndex = sel
        return Model(true, "$cmdToken …", items, sel)
    }
}

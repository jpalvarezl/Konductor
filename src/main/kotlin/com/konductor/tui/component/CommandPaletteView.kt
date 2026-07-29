package com.konductor.tui.component

import com.googlecode.lanterna.TerminalPosition
import com.konductor.core.AppState
import com.konductor.core.CommandPaletteItem
import com.konductor.core.CommandPaletteStatus
import com.konductor.i18n.AppStrings
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.CommandPaletteLayout
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.layout.layoutCommandPalette
import com.konductor.tui.style.Theme

/** Stateless renderer for the command and dynamic-option palette overlay. */
class CommandPaletteView(
    private val theme: Theme,
    private val strings: AppStrings = AppStrings.english(),
) : TuiComponent {
    override fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        val palette = state.commandPalette ?: return
        val layout = layout(bounds, state) ?: return
        canvas.fill(layout.frame, theme.paletteBackground)

        canvas.write(
            layout.frame.left,
            layout.titleRow,
            " ${palette.title}",
            theme.paletteText,
            theme.paletteBackground,
            layout.frame.width,
        )
        layout.queryRow?.let { row ->
            val queryText = visibleQuery(layout, state)
            canvas.write(
                layout.frame.left,
                row,
                QUERY_PREFIX + queryText,
                if (palette.query.text.isEmpty()) theme.paletteDisabledText else theme.paletteText,
                theme.paletteBackground,
                layout.frame.width,
            )
        }

        when (val status = palette.status) {
            CommandPaletteStatus.Loading -> renderStatus(canvas, layout, strings.paletteModelLoading)
            is CommandPaletteStatus.Empty -> renderStatus(canvas, layout, status.message)
            is CommandPaletteStatus.Error -> renderStatus(canvas, layout, status.message)
            CommandPaletteStatus.Ready -> {
                if (palette.items.isEmpty()) {
                    renderStatus(canvas, layout, strings.paletteNoCommands)
                } else {
                    renderItems(canvas, layout, palette.items, palette.selectedIndex)
                }
            }
        }

        layout.footerRow?.let { row ->
            canvas.write(
                layout.frame.left,
                row,
                " ${strings.paletteKeyHint}",
                theme.paletteDisabledText,
                theme.paletteBackground,
                layout.frame.width,
            )
        }
    }

    fun cursorPosition(bounds: Rectangle, state: AppState): TerminalPosition? {
        val palette = state.commandPalette ?: return null
        val layout = layout(bounds, state) ?: return null
        val row = layout.queryRow ?: return null
        val queryWidth = (layout.frame.width - QUERY_PREFIX.length).coerceAtLeast(1)
        val queryStart = (palette.query.cursor - queryWidth + 1).coerceAtLeast(0)
        val visibleCursor = palette.query.cursor - queryStart
        val column = (layout.frame.left + QUERY_PREFIX.length + visibleCursor)
            .coerceAtMost(layout.frame.rightExclusive - 1)
        return TerminalPosition(column, row)
    }

    private fun visibleQuery(layout: CommandPaletteLayout, state: AppState): String {
        val palette = state.commandPalette ?: return ""
        if (palette.query.text.isEmpty()) return strings.paletteQueryHint
        val width = (layout.frame.width - QUERY_PREFIX.length).coerceAtLeast(1)
        val start = (palette.query.cursor - width + 1).coerceAtLeast(0)
        return palette.query.text.substring(start).take(width)
    }

    private fun renderStatus(canvas: TerminalCanvas, layout: CommandPaletteLayout, message: String) {
        if (layout.itemBounds.isEmpty) return
        canvas.write(
            layout.itemBounds.left,
            layout.itemBounds.top,
            " $message",
            theme.paletteDisabledText,
            theme.paletteBackground,
            layout.itemBounds.width,
        )
    }

    private fun renderItems(
        canvas: TerminalCanvas,
        layout: CommandPaletteLayout,
        items: List<CommandPaletteItem>,
        selectedIndex: Int,
    ) {
        if (layout.itemBounds.isEmpty) return
        val first = (selectedIndex - layout.itemBounds.height + 1).coerceAtLeast(0)
        items.drop(first).take(layout.itemBounds.height).forEachIndexed { row, item ->
            val selected = first + row == selectedIndex
            val background = if (selected) theme.paletteSelectedBackground else theme.paletteBackground
            val foreground = if (item.enabled) theme.paletteText else theme.paletteDisabledText
            canvas.write(
                layout.itemBounds.left,
                layout.itemBounds.top + row,
                itemLine(item, selected),
                foreground,
                background,
                layout.itemBounds.width,
            )
        }
    }

    private fun itemLine(item: CommandPaletteItem, selected: Boolean): String = buildString {
        append(if (selected) "> " else "  ")
        append(item.label)
        item.usage?.takeIf { it != item.label }?.let { append("  ").append(it) }
        item.description?.let { append(" — ").append(it) }
        item.disabledReason?.let { append(" [").append(it).append(']') }
    }

    private fun layout(bounds: Rectangle, state: AppState): CommandPaletteLayout? {
        val palette = state.commandPalette ?: return null
        val requestedRows = when (palette.status) {
            CommandPaletteStatus.Ready -> palette.items.size.coerceAtLeast(1)
            else -> 1
        }
        return layoutCommandPalette(bounds, requestedRows)
    }

    private companion object {
        const val QUERY_PREFIX = "⌕ "
    }
}

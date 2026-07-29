package com.konductor.tui.layout

import kotlin.math.min

/** Bounded overlay geometry shared by palette rendering and cursor placement. */
data class CommandPaletteLayout(
    val frame: Rectangle,
    val titleRow: Int,
    val queryRow: Int?,
    val itemBounds: Rectangle,
    val footerRow: Int?,
)

/** Center a palette without ever escaping [terminal], including one- and two-row terminals. */
fun layoutCommandPalette(
    terminal: Rectangle,
    requestedItemRows: Int,
    maximumWidth: Int = 72,
    maximumItemRows: Int = 10,
): CommandPaletteLayout? {
    if (terminal.isEmpty) return null

    val width = min(terminal.width, maximumWidth.coerceAtLeast(1))
    val itemRows = requestedItemRows.coerceIn(1, maximumItemRows.coerceAtLeast(1))
    val height = min(terminal.height, itemRows + 3)
    val left = terminal.left + (terminal.width - width) / 2
    val top = terminal.top + (terminal.height - height) / 2
    val frame = Rectangle(left, top, width, height)

    val queryRow = (top + 1).takeIf { it < frame.bottomExclusive }
    val footerRow = (frame.bottomExclusive - 1).takeIf { it > (queryRow ?: titleRow(top)) }
    val itemTop = (queryRow?.plus(1) ?: top + 1).coerceAtMost(frame.bottomExclusive)
    val itemBottom = (footerRow ?: frame.bottomExclusive).coerceAtLeast(itemTop)

    return CommandPaletteLayout(
        frame = frame,
        titleRow = titleRow(top),
        queryRow = queryRow,
        itemBounds = Rectangle(left, itemTop, width, itemBottom - itemTop),
        footerRow = footerRow,
    )
}

private fun titleRow(top: Int): Int = top

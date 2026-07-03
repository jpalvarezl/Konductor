package com.konductor.tui.layout

/**
 * Small app-level layout primitive.
 *
 * Lanterna has TerminalRectangle, but keeping our own rectangle type makes component APIs read like UI layout code and
 * gives us a stable place to add helpers such as split/inset/padding later without leaking those choices everywhere.
 */
data class Rectangle(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val rightExclusive: Int get() = left + width
    val bottomExclusive: Int get() = top + height

    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

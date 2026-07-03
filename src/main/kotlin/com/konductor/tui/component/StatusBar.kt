package com.konductor.tui.component

import com.konductor.core.AppState
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme

class StatusBar(
    private val theme: Theme,
) : TuiComponent {
    override fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        if (bounds.isEmpty) return

        canvas.fill(bounds, theme.statusBackground)

        val status = buildString {
            append(" ↑/↓ scroll ")
            append(" PgUp/PgDn page ")
            append(" Enter send ")
            append(" /quit exit ")
            if (state.transcriptScrollback > 0) {
                append(" scrolled +${state.transcriptScrollback} ")
            }
        }

        canvas.write(
            x = bounds.left,
            y = bounds.top,
            text = status,
            foreground = theme.statusText,
            background = theme.statusBackground,
            maxWidth = bounds.width,
        )
    }
}

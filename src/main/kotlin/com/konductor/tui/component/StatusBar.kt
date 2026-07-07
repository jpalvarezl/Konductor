package com.konductor.tui.component

import com.konductor.core.AppState
import com.konductor.core.models.Usage
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme

class StatusBar(
    private val theme: Theme,
) : TuiComponent {
    override fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        if (bounds.isEmpty) return

        canvas.fill(bounds, theme.statusBackground)

        val left = buildString {
            append(' ')
            state.modelName?.let { append(it).append("  ·  ") }
            append(usageText(state.lastUsage))
            when {
                state.isAwaitingResponse -> append("  ·  working…")
                state.transcriptScrollback > 0 -> append("  ·  scrolled +${state.transcriptScrollback}")
            }
            append(' ')
        }
        val hint = " ↑/↓ scroll · Enter send · /quit exit "

        canvas.write(
            x = bounds.left,
            y = bounds.top,
            text = left,
            foreground = theme.statusText,
            background = theme.statusBackground,
            maxWidth = bounds.width,
        )

        // Right-align the key hint only when it fits without overwriting the model/usage segment.
        val hintStart = bounds.left + bounds.width - hint.length
        if (hintStart > bounds.left + left.length) {
            canvas.write(
                x = hintStart,
                y = bounds.top,
                text = hint,
                foreground = theme.statusText,
                background = theme.statusBackground,
                maxWidth = hint.length,
            )
        }
    }

    private fun usageText(usage: Usage?): String =
        if (usage == null) {
            "0 tokens"
        } else {
            "${usage.totalTokens} tokens (${usage.inputTokens} in / ${usage.outputTokens} out)"
        }
}

package com.konductor.tui.component

import com.konductor.core.AppState
import com.konductor.core.ModelContextWindow
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
            state.activeAgentName?.let { append("agent: ").append(it).append("  ·  ") }
            append(usageText(state.lastUsage, contextWindow(state)))
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

    /** The active model's max context window (from the per-model table), or the configured fallback. */
    private fun contextWindow(state: AppState): Int =
        ModelContextWindow.forModel(state.modelName) ?: state.contextWindowTokens

    private fun usageText(usage: Usage?, contextWindow: Int): String {
        val window = "${formatTokens(contextWindow)} ctx"
        return if (usage == null) {
            "0 tokens · $window"
        } else {
            "${usage.totalTokens} tokens (${usage.inputTokens} in / ${usage.outputTokens} out) · " +
                "$window (${contextPercent(usage, contextWindow)})"
        }
    }

    private fun contextPercent(usage: Usage, contextWindowTokens: Int): String {
        if (contextWindowTokens <= 0) return "n/a"
        val percent = usage.totalTokens.toDouble() * 100.0 / contextWindowTokens.toDouble()
        return if (percent < 1.0 && usage.totalTokens > 0) "<1%" else "${percent.toInt()}%"
    }

    /** Compact token count for the status bar: 400_000 → "400K", 1_047_576 → "1M". */
    private fun formatTokens(tokens: Int): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }
}

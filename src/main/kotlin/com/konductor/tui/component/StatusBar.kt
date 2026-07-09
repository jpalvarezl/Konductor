package com.konductor.tui.component

import com.konductor.core.AppState
import com.konductor.core.ModelCostEstimator
import com.konductor.core.models.Usage
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import java.util.Locale

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
            append(usageText(state.lastUsage, state.contextWindowTokens))
            append("  ·  ")
            append(costText(state.modelName, state.lastUsage))
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

    private fun usageText(usage: Usage?, contextWindowTokens: Int): String =
        if (usage == null) {
            "0 tokens · ctx 0%"
        } else {
            "${usage.totalTokens} tokens (${usage.inputTokens} in / ${usage.outputTokens} out) · " +
                "ctx ${contextPercent(usage, contextWindowTokens)}"
        }

    private fun contextPercent(usage: Usage, contextWindowTokens: Int): String {
        if (contextWindowTokens <= 0) return "n/a"
        val percent = usage.totalTokens.toDouble() * 100.0 / contextWindowTokens.toDouble()
        return if (percent < 1.0 && usage.totalTokens > 0) "<1%" else "${percent.toInt()}%"
    }

    private fun costText(modelName: String?, usage: Usage?): String =
        ModelCostEstimator.estimateUsd(modelName, usage)?.let { "cost ~$${String.format(Locale.US, "%.4f", it)}" }
            ?: "cost n/a"
}

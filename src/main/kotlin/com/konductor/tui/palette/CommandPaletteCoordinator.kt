package com.konductor.tui.palette

import com.konductor.core.AppState
import com.konductor.tui.shouldOpenCommandPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How the palette was opened, used only to preserve the consumed slash as a cancelable composer draft. */
enum class PaletteOpenOrigin {
    Slash,
    Shortcut,
}

/**
 * Minimal production seam between TUI key routing, [CommandPaletteController], and suspend option loading. It reuses
 * the TUI-owned scope, cancels replaced loads, and applies completions under the same state lock as rendering.
 */
class CommandPaletteCoordinator(
    private val state: AppState,
    private val controller: CommandPaletteController,
    private val scope: CoroutineScope,
    private val stateLock: Any = state,
    private val onStateChanged: () -> Unit = {},
) {
    @Volatile
    internal var activeLoad: Job? = null
        private set

    /** Open from a TUI trigger if input is available; returns true when the trigger was consumed. */
    fun tryOpen(character: Char, ctrlDown: Boolean, inputAvailable: Boolean = true): Boolean {
        if (!shouldOpenCommandPalette(character, ctrlDown, state.input.text.isEmpty(), inputAvailable)) return false
        val origin = if (ctrlDown) PaletteOpenOrigin.Shortcut else PaletteOpenOrigin.Slash
        open(origin)
        return true
    }

    /** Replace any visible palette generation and cancel its option load. */
    fun open(origin: PaletteOpenOrigin) {
        cancelLoad()
        synchronized(stateLock) {
            val cancelDraft = when (origin) {
                PaletteOpenOrigin.Slash -> "/"
                PaletteOpenOrigin.Shortcut -> state.commandPalette?.cancelDraft
            }
            controller.open(state, cancelDraft)
            onStateChanged()
        }
    }

    /**
     * Route one palette key. [selectionVisible] is supplied by the TUI layout boundary so Enter cannot activate a
     * result when a tiny terminal has no item rows; geometry remains outside the Lanterna-free controller.
     */
    fun handle(key: PaletteKey, selectionVisible: Boolean = true) {
        if (key == PaletteKey.Enter && !selectionVisible) return
        val action = synchronized(stateLock) {
            controller.handle(state, key).also { onStateChanged() }
        }
        apply(action)
    }

    /** Cancel palette-only work during TUI shutdown. This does not affect command/turn cancellation semantics. */
    fun close() = cancelLoad()

    private fun apply(action: PaletteAction) {
        when (action) {
            PaletteAction.None -> Unit
            PaletteAction.Closed -> cancelLoad()
            is PaletteAction.LoadOptions -> launchLoad(action)
        }
    }

    private fun launchLoad(action: PaletteAction.LoadOptions) {
        cancelLoad()
        activeLoad = scope.launch {
            val result = try {
                Result.success(action.provider.loadOptions())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Result.failure(error)
            }
            synchronized(stateLock) {
                controller.completeOptions(state, action.requestId, result)
                onStateChanged()
            }
        }
    }

    private fun cancelLoad() {
        activeLoad?.cancel()
        activeLoad = null
    }
}

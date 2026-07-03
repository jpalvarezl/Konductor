package com.konductor.tui.component

import com.konductor.core.AppState
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle

interface TuiComponent {
    fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState)
}

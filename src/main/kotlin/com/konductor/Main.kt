package com.konductor

import com.konductor.acp.runAcpAgent
import com.konductor.tui.TuiApp

fun main(args: Array<String>) {
    if (args.any { it == "acp" || it == "--acp" }) {
        // Headless mode: speak the Agent Client Protocol over stdin/stdout instead of drawing the TUI.
        runAcpAgent()
    } else {
        TuiApp().run()
    }
}

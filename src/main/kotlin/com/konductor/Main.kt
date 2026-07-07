package com.konductor

import com.konductor.acp.runAcpAgent
import com.konductor.config.Configuration
import com.konductor.tui.TuiApp

fun main(args: Array<String>) {
    val configuration = Configuration.load()
    if (args.shouldRunAcp()) {
        // Headless
        runAcpAgent()
    } else {
        // TUI is default
        TuiApp().run()
    }
}

fun Array<String>.shouldRunAcp(): Boolean = any { it == "acp" || it == "--acp" }

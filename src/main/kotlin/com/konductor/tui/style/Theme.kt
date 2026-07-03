package com.konductor.tui.style

import com.googlecode.lanterna.TextColor

data class Theme(
    val background: TextColor = TextColor.ANSI.DEFAULT,
    val transcriptBackground: TextColor = TextColor.ANSI.DEFAULT,
    val inputBackground: TextColor = TextColor.ANSI.DEFAULT,
    val statusBackground: TextColor = TextColor.ANSI.BLUE,
    val normalText: TextColor = TextColor.ANSI.WHITE,
    val mutedText: TextColor = TextColor.ANSI.BLACK_BRIGHT,
    val divider: TextColor = TextColor.ANSI.BLACK_BRIGHT,
    val userText: TextColor = TextColor.ANSI.GREEN_BRIGHT,
    val assistantText: TextColor = TextColor.ANSI.WHITE,
    val systemText: TextColor = TextColor.ANSI.CYAN_BRIGHT,
    val prompt: TextColor = TextColor.ANSI.GREEN_BRIGHT,
    val statusText: TextColor = TextColor.ANSI.WHITE,
)

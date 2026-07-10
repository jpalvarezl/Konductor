package com.konductor.tui

/**
 * Modern terminals (Windows Terminal, Kitty, foot, …) encode modified keys with the CSI-u escape
 * `ESC [ <keycode> ; <modifiers> u` (the "fixterms" / Kitty keyboard protocol). Lanterna doesn't decode these:
 * it reads the leading `ESC [` as `Alt+[` and then delivers `<keycode> ; <modifiers> u` as plain characters, so
 * a Shift+Enter leaks into the input as `[13;2u`. We reconstruct the sequence from those leaked characters.
 *
 * [parseCsiuKeycode] returns the keycode (e.g. [CSI_U_ENTER_KEYCODE] = 13 for a modified Enter such as
 * Shift+Enter = `13;2u`) when [params] is a well-formed `<code>;<mods>u`, or null otherwise. It deliberately does
 * NOT include the leading `[`, which the caller has already consumed.
 */
internal fun parseCsiuKeycode(params: String): Int? =
    CSI_U_PARAMS.matchEntire(params)?.groupValues?.get(1)?.toIntOrNull()

private val CSI_U_PARAMS = Regex("""(\d+);\d+u""")

/** CSI-u keycode for Enter/Return; a modified Enter (Shift+Enter, …) should insert a newline, not submit. */
internal const val CSI_U_ENTER_KEYCODE = 13

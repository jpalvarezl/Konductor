package com.konductor.tui

enum class TuiExitCode {
    SUCCESS(0),
    FAILURE(1),
    USER_EXIT(2);

    val code: Int

    constructor(code: Int) {
        this.code = code
    }
}

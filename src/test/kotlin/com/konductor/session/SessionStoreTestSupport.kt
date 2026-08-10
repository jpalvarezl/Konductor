package com.konductor.session

import com.konductor.core.models.Session
import java.nio.file.Path

/** Preserve the legacy immediately-durable test setup while exercising the provisional API explicitly. */
internal fun SessionStore.persistedCandidate(cwd: Path, model: String, name: String?): Session =
    newCandidate(cwd, model, name).also { candidate -> persistNew(candidate) }

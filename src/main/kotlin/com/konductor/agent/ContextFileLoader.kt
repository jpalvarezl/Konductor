package com.konductor.agent

import com.konductor.workspace.BoundedWorkspaceFileReader
import com.konductor.workspace.ResolvedWorkspace
import com.konductor.workspace.WorkspaceFileReadException
import com.konductor.workspace.WorkspaceFileReadFailure
import com.konductor.workspace.WorkspaceResolver
import com.konductor.workspace.readAttributesIfPresent
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

/** One canonical, path-attributed context file in deterministic discovery order. */
data class ContextFileRecord(
    val displayPath: String,
    val content: String,
)

/** A candidate-selection, file-count, aggregate-bound, or UTF-8 failure. */
class ContextFileLoadException(
    val path: Path?,
    message: String,
    cause: Throwable? = null,
) : IOException(if (path == null) message else "$message: $path", cause)

/**
 * Discovers global and workspace instruction files and returns ordered, canonical-target-deduplicated records.
 * Context discovery is deliberately independent of project trust.
 */
class ContextFileLoader(
    private val workspaceResolver: WorkspaceResolver = WorkspaceResolver(),
    private val fileReader: BoundedWorkspaceFileReader = BoundedWorkspaceFileReader(),
) {
    fun load(
        cwd: Path,
        configDirectory: Path,
        includeContextFiles: Boolean = true,
    ): List<ContextFileRecord> {
        if (!includeContextFiles) return emptyList()
        val workspace = workspaceResolver.resolve(cwd)
        return load(workspace, configDirectory, includeContextFiles = true)
    }

    fun load(
        workspace: ResolvedWorkspace,
        configDirectory: Path,
        includeContextFiles: Boolean = true,
    ): List<ContextFileRecord> {
        if (!includeContextFiles) return emptyList()
        val canonicalConfig = workspaceResolver.requireConfigOutsideWorkspace(configDirectory, workspace)
        val locations = buildList {
            add(CandidateLocation(canonicalConfig, canonicalConfig))
            workspaceResolver.directoriesFromRoot(workspace).forEach {
                add(CandidateLocation(it, workspace.canonicalRoot))
            }
        }

        val selections = mutableListOf<Selection>()
        val canonicalTargets = linkedSetOf<Path>()
        for (location in locations) {
            val entry = selectCandidate(location.directory) ?: continue
            val target = fileReader.resolveTarget(entry, location.allowedRoot)
            if (canonicalTargets.add(target)) {
                selections += Selection(entry, location.allowedRoot, target)
            }
        }
        if (selections.size > MAX_FILES) {
            throw ContextFileLoadException(
                null,
                "Context file count ${selections.size} exceeds the $MAX_FILES-file limit",
            )
        }

        val records = ArrayList<ContextFileRecord>(selections.size)
        var totalBytes = 0
        for (selection in selections) {
            val remaining = MAX_TOTAL_BYTES - totalBytes
            val limit = minOf(MAX_FILE_BYTES, remaining)
            val file = try {
                fileReader.read(
                    selectedEntry = selection.entry,
                    allowedRoot = selection.allowedRoot,
                    maxBytes = limit,
                    expectedTarget = selection.canonicalTarget,
                )
            } catch (error: WorkspaceFileReadException) {
                if (remaining < MAX_FILE_BYTES && error.failure == WorkspaceFileReadFailure.BoundsExceeded) {
                    throw ContextFileLoadException(
                        selection.entry,
                        "Context files exceed the $MAX_TOTAL_BYTES-byte aggregate limit",
                        error,
                    )
                }
                throw error
            }
            totalBytes += file.bytes.size
            val content = decode(file.bytes, file.canonicalPath)
            records += ContextFileRecord(
                displayPath = ContextBlockRenderer.displayPath(file.canonicalPath),
                content = normalizeNewlines(content.removePrefix(UTF8_BOM)),
            )
        }
        return records
    }

    private fun selectCandidate(directory: Path): Path? {
        val preferred = directory.resolve(PREFERRED_NAME)
        if (isPresent(preferred)) return preferred
        val fallback = directory.resolve(FALLBACK_NAME)
        return fallback.takeIf(::isPresent)
    }

    private fun isPresent(path: Path): Boolean =
        try {
            readAttributesIfPresent(path) != null
        } catch (error: IOException) {
            throw ContextFileLoadException(path, "Cannot inspect context file candidate", error)
        }

    private fun decode(bytes: ByteArray, path: Path): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw ContextFileLoadException(path, "Context file is not valid UTF-8", error)
        }

    private data class CandidateLocation(val directory: Path, val allowedRoot: Path)

    private data class Selection(
        val entry: Path,
        val allowedRoot: Path,
        val canonicalTarget: Path,
    )

    companion object {
        const val MAX_FILES: Int = 32
        const val MAX_FILE_BYTES: Int = 64 * 1024
        const val MAX_TOTAL_BYTES: Int = 256 * 1024

        private const val PREFERRED_NAME = "AGENTS.md"
        private const val FALLBACK_NAME = "CLAUDE.md"
        private const val UTF8_BOM = "\uFEFF"

        internal fun normalizeNewlines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')
    }
}

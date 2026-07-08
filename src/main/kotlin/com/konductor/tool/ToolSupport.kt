package com.konductor.tool

import com.konductor.core.models.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists

/** Shared lenient JSON reader for parsing tool argument payloads. */
internal val toolJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Default per-call output cap (docs/spec/tools.md). Tool output re-enters the model's context, so it is
 * bounded; beyond this the executor cuts it and appends a truncation marker.
 */
const val MAX_TOOL_OUTPUT_BYTES: Int = 16 * 1024

/**
 * Resolve [raw] against [cwd] and guarantee it stays inside it. Rejects `..` escapes and absolute paths that
 * point elsewhere (lexical check), then resolves the real path of the nearest existing ancestor and re-checks
 * containment, so a **symlinked** directory cannot smuggle the path outside [cwd] (e.g. `cwd/sub` → `/etc`).
 * Returns the normalized absolute path (not fully real-path-resolved, so it works for not-yet-created files).
 *
 * @throws IllegalArgumentException if the resolved path escapes [cwd] lexically or via a symlink.
 */
fun resolveInCwd(cwd: Path, raw: String): Path {
    val root = cwd.toRealPath()
    val resolved = root.resolve(raw).normalize()
    require(resolved == root || resolved.startsWith(root)) {
        "path '$raw' escapes the working directory"
    }
    // Follow symlinks on the deepest existing ancestor (the target file itself may not exist yet, e.g. write).
    val realAncestor = generateSequence(resolved) { it.parent }.first { it.exists() }.toRealPath()
    require(realAncestor == root || realAncestor.startsWith(root)) {
        "path '$raw' escapes the working directory (via a symlink)"
    }
    return resolved
}

/** Path displayed to the model: relative to [cwd] when inside it, else the absolute path. */
fun displayPath(cwd: Path, path: Path): String {
    val root = cwd.toAbsolutePath().normalize()
    val abs = path.toAbsolutePath().normalize()
    return if (abs.startsWith(root)) root.relativize(abs).toString().ifEmpty { "." } else abs.toString()
}

/**
 * Decode [bytes] as **strict** UTF-8: returns the text, or `null` if the bytes contain malformed/unmappable
 * sequences (i.e. the content is not valid UTF-8). Unlike `String(bytes, UTF_8)`, this does not silently
 * substitute `U+FFFD`, so tools can refuse or skip binary / non-UTF-8 files per their contract.
 */
fun decodeUtf8OrNull(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

/**
 * Cap [result]'s output at [maxBytes] UTF-8 bytes **including** the truncation marker, recording the number
 * of dropped bytes so the model can narrow its request instead of silently losing data.
 */
fun truncateToolResult(result: ToolResult, maxBytes: Int = MAX_TOOL_OUTPUT_BYTES): ToolResult {
    val bytes = result.output.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return result

    fun marker(shown: Int) = "\n\n[output truncated: showed $shown of ${bytes.size} bytes]"
    // Reserve room for the marker (its length is bounded by the maxBytes-digit form) so the total stays <= maxBytes.
    val markerBudget = marker(maxBytes).toByteArray(Charsets.UTF_8).size
    val contentCap = (maxBytes - markerBudget).coerceAtLeast(0)
    val shown = String(bytes, 0, contentCap, Charsets.UTF_8)
    val shownBytes = shown.toByteArray(Charsets.UTF_8).size

    return result.copy(
        output = shown + marker(shownBytes),
        truncatedBytes = bytes.size - shownBytes,
    )
}

/** Build a JSON-schema `object` for a tool's parameters. */
internal fun objectSchema(
    required: List<String>,
    properties: Map<String, JsonObject>,
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties))
    putJsonArray("required") { required.forEach { add(it) } }
    put("additionalProperties", false)
}

/** Build a single JSON-schema property (`{ "type": ..., "description": ... }`). */
internal fun prop(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}

/** Directory names pruned by `find`/`grep`: VCS metadata and common build-output / dependency dirs (noise). */
internal val IGNORED_DIR_NAMES: Set<String> = setOf(
    ".git", ".hg", ".svn", "node_modules", "target", "build", ".gradle", ".idea",
)

/**
 * Walk the regular files under [base], pruning [IGNORED_DIR_NAMES] subtrees (so huge/noisy dirs like
 * `node_modules` and `.git` are never descended) and skipping unreadable entries. [onFile] returns `false`
 * to stop the walk early (e.g. a result cap was reached).
 */
internal fun walkFilesSkippingIgnored(base: Path, onFile: (Path) -> Boolean) {
    Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (dir != base && dir.fileName?.toString() in IGNORED_DIR_NAMES) {
                FileVisitResult.SKIP_SUBTREE
            } else {
                FileVisitResult.CONTINUE
            }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (attrs.isRegularFile && !onFile(file)) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
    })
}

/**
 * A glob predicate for cwd-relative paths. Works around a Java NIO glob quirk where a leading doublestar
 * directory prefix must match at least one directory (so a top-level file would otherwise not match): when
 * the pattern starts with that prefix, we also test it with the prefix stripped.
 */
internal fun globMatcher(pattern: String): (Path) -> Boolean {
    val fileSystem = FileSystems.getDefault()
    val primary: PathMatcher = fileSystem.getPathMatcher("glob:$pattern")
    val zeroDepth: PathMatcher? =
        if (pattern.startsWith("**/")) fileSystem.getPathMatcher("glob:${pattern.removePrefix("**/")}") else null
    return { path -> primary.matches(path) || zeroDepth?.matches(path) == true }
}

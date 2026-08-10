package com.konductor.workspace

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** Successful bounded read of the canonical target actually opened. */
data class BoundedWorkspaceFile(
    val canonicalPath: Path,
    val bytes: ByteArray,
)

/** Machine-readable category for callers that compose per-file and aggregate policies. */
enum class WorkspaceFileReadFailure {
    Other,
    BoundsExceeded,
}

/** A selected-file validation, race, bounds, or read failure. */
class WorkspaceFileReadException(
    val selectedEntry: Path,
    message: String,
    cause: Throwable? = null,
    val failure: WorkspaceFileReadFailure = WorkspaceFileReadFailure.Other,
) : IOException("$message: $selectedEntry", cause)

/**
 * Canonical-target, containment-checked, no-follow bounded reader shared by workspace-controlled plain-text files.
 *
 * The selected entry itself may be a symlink, but its canonical target must be a regular file under [allowedRoot].
 * The canonical target is opened with `NOFOLLOW_LINKS`, and both the selected entry and target are checked before and
 * after reading. On providers offering [SecureDirectoryStream], target opening and an additional attribute comparison
 * are performed relative to a handle for the canonical parent directory.
 *
 * This portable contract detects every observed path or stable-attribute change. It cannot detect an ABA replacement
 * that restores all facts exposed by a provider without a stronger handle primitive.
 */
class BoundedWorkspaceFileReader {
    /** Resolve and validate a selected target without reading it. Useful for canonical-target deduplication. */
    fun resolveTarget(selectedEntry: Path, allowedRoot: Path): Path =
        inspect(selectedEntry, canonicalAllowedRoot(allowedRoot)).canonicalTarget

    /**
     * Read at most [maxBytes] bytes; one extra byte is probed so truncation is never reported as success.
     * [expectedTarget], when supplied after selection/deduplication, also detects a target change before opening.
     */
    fun read(
        selectedEntry: Path,
        allowedRoot: Path,
        maxBytes: Int,
        expectedTarget: Path? = null,
    ): BoundedWorkspaceFile {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        require(maxBytes < Int.MAX_VALUE) { "maxBytes must leave room for the overflow probe" }

        val root = canonicalAllowedRoot(allowedRoot)
        val entry = selectedEntry.toAbsolutePath().normalize()
        try {
            val before = inspect(entry, root)
            if (expectedTarget != null && before.canonicalTarget != expectedTarget.toAbsolutePath().normalize()) {
                throw WorkspaceFileReadException(entry, "Selected file target changed before reading")
            }
            if (before.targetAttributes.size() > maxBytes.toLong()) {
                throw WorkspaceFileReadException(
                    entry,
                    "Selected file exceeds the $maxBytes-byte limit",
                    failure = WorkspaceFileReadFailure.BoundsExceeded,
                )
            }

            val bytes = readCanonicalTarget(before, root, maxBytes)
            if (bytes.size > maxBytes) {
                throw WorkspaceFileReadException(
                    entry,
                    "Selected file exceeds the $maxBytes-byte limit",
                    failure = WorkspaceFileReadFailure.BoundsExceeded,
                )
            }

            val after = inspect(entry, root)
            requireUnchanged(before, after, entry)
            return BoundedWorkspaceFile(before.canonicalTarget, bytes)
        } catch (error: WorkspaceFileReadException) {
            throw error
        } catch (error: IOException) {
            throw WorkspaceFileReadException(entry, "Cannot safely read selected file", error)
        } catch (error: SecurityException) {
            throw WorkspaceFileReadException(entry, "Cannot safely read selected file", error)
        }
    }

    private fun canonicalAllowedRoot(allowedRoot: Path): Path {
        val absolute = allowedRoot.toAbsolutePath().normalize()
        val canonical = try {
            absolute.toRealPath()
        } catch (error: IOException) {
            throw WorkspaceFileReadException(absolute, "Cannot resolve allowed root", error)
        }
        val attributes = try {
            Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: IOException) {
            throw WorkspaceFileReadException(canonical, "Cannot inspect allowed root", error)
        }
        if (!attributes.isDirectory) {
            throw WorkspaceFileReadException(canonical, "Allowed root is not a directory")
        }
        return canonical
    }

    private fun inspect(selectedEntry: Path, canonicalRoot: Path): FileSnapshot {
        val entry = selectedEntry.toAbsolutePath().normalize()
        val entryAttributes = try {
            readAttributesIfPresent(entry)
                ?: throw WorkspaceFileReadException(entry, "Selected file disappeared")
        } catch (error: WorkspaceFileReadException) {
            throw error
        } catch (error: IOException) {
            throw WorkspaceFileReadException(entry, "Cannot inspect selected file entry", error)
        }

        val target = try {
            entry.toRealPath()
        } catch (error: IOException) {
            throw WorkspaceFileReadException(entry, "Cannot canonicalize selected file target", error)
        }
        if (target != canonicalRoot && !target.startsWith(canonicalRoot)) {
            throw WorkspaceFileReadException(entry, "Selected file target escapes allowed root $canonicalRoot")
        }

        val targetAttributes = try {
            Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: IOException) {
            throw WorkspaceFileReadException(entry, "Cannot inspect canonical selected file target $target", error)
        }
        if (!targetAttributes.isRegularFile || targetAttributes.isSymbolicLink) {
            throw WorkspaceFileReadException(entry, "Selected file target is not a regular file")
        }
        return FileSnapshot(entry, target, stable(entryAttributes), stable(targetAttributes), targetAttributes)
    }

    private fun readCanonicalTarget(snapshot: FileSnapshot, canonicalRoot: Path, maxBytes: Int): ByteArray {
        val parent = snapshot.canonicalTarget.parent
            ?: throw WorkspaceFileReadException(snapshot.selectedEntry, "Canonical selected file has no parent")
        val name = snapshot.canonicalTarget.fileName
            ?: throw WorkspaceFileReadException(snapshot.selectedEntry, "Canonical selected file has no name")
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

        // Unix providers normally expose a SecureDirectoryStream here. Traverse from the already canonical allowed
        // root and keep each directory handle alive so an ancestor rename cannot redirect a later component lookup.
        Files.newDirectoryStream(canonicalRoot).use { rootDirectory ->
            if (rootDirectory is SecureDirectoryStream<*>) {
                @Suppress("UNCHECKED_CAST")
                val secureRoot = rootDirectory as SecureDirectoryStream<Path>
                val opened = mutableListOf<SecureDirectoryStream<Path>>()
                try {
                    var secureParent = secureRoot
                    if (parent != canonicalRoot) {
                        for (component in canonicalRoot.relativize(parent)) {
                            secureParent = secureParent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)
                            opened.add(secureParent)
                        }
                    }
                    val handleBefore = secureAttributes(secureParent, name, snapshot.selectedEntry)
                    if (stable(handleBefore) != snapshot.targetStable) {
                        throw WorkspaceFileReadException(snapshot.selectedEntry, "Selected file changed before opening")
                    }
                    val bytes = secureParent.newByteChannel(name, options).use { readBounded(it, maxBytes) }
                    val handleAfter = secureAttributes(secureParent, name, snapshot.selectedEntry)
                    if (stable(handleBefore) != stable(handleAfter)) {
                        throw WorkspaceFileReadException(snapshot.selectedEntry, "Selected file changed while reading")
                    }
                    return bytes
                } finally {
                    opened.asReversed().forEach { it.close() }
                }
            }
        }

        // The Windows default provider does not expose SecureDirectoryStream; retain the specified portable fallback.
        return Files.newByteChannel(snapshot.canonicalTarget, options).use { readBounded(it, maxBytes) }
    }

    private fun secureAttributes(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        selectedEntry: Path,
    ): BasicFileAttributes {
        val view = directory.getFileAttributeView(
            name,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: throw WorkspaceFileReadException(selectedEntry, "Cannot inspect selected file through parent handle")
        val attributes = view.readAttributes()
        if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            throw WorkspaceFileReadException(selectedEntry, "Selected file target is not a regular file")
        }
        return attributes
    }

    private fun readBounded(channel: SeekableByteChannel, maxBytes: Int): ByteArray {
        val probeLimit = maxBytes + 1
        val output = ByteArrayOutputStream(probeLimit.coerceAtMost(BUFFER_SIZE))
        val buffer = ByteBuffer.allocate(BUFFER_SIZE.coerceAtMost(probeLimit.coerceAtLeast(1)))
        var total = 0
        while (total < probeLimit) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity(), probeLimit - total))
            val count = channel.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer.array(), 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private fun requireUnchanged(before: FileSnapshot, after: FileSnapshot, selectedEntry: Path) {
        if (
            before.canonicalTarget != after.canonicalTarget ||
            before.entryStable != after.entryStable ||
            before.targetStable != after.targetStable
        ) {
            throw WorkspaceFileReadException(selectedEntry, "Selected file changed while reading")
        }
    }

    private data class FileSnapshot(
        val selectedEntry: Path,
        val canonicalTarget: Path,
        val entryStable: StableAttributes,
        val targetStable: StableAttributes,
        val targetAttributes: BasicFileAttributes,
    )

    private data class StableAttributes(
        val regularFile: Boolean,
        val directory: Boolean,
        val symbolicLink: Boolean,
        val other: Boolean,
        val size: Long,
        val creationMillis: Long,
        val modifiedMillis: Long,
        val fileKey: Any?,
    )

    private fun stable(attributes: BasicFileAttributes): StableAttributes = StableAttributes(
        regularFile = attributes.isRegularFile,
        directory = attributes.isDirectory,
        symbolicLink = attributes.isSymbolicLink,
        other = attributes.isOther,
        size = attributes.size(),
        creationMillis = attributes.creationTime().toMillis(),
        modifiedMillis = attributes.lastModifiedTime().toMillis(),
        fileKey = attributes.fileKey(),
    )

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}

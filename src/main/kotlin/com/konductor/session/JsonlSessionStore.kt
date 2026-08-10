package com.konductor.session

import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.SessionHeader
import com.konductor.core.models.SessionMetadata
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readLines
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * File-backed [SessionStore]: append-only JSONL, one file per session, grouped by working directory.
 *
 * ```
 * <root>/<cwd-hash>/<session-id>.jsonl
 * ```
 *
 * [root] is `~/.konductor/sessions` in production (injectable for tests). The `cwd-hash` keeps sessions from
 * different projects apart without leaking absolute paths into directory names. Transcript writes remain append-only.
 * Metadata changes write a complete candidate header plus the existing transcript to a forced-and-closed sibling
 * temporary file, then atomically replace the live file. Append, rewrite, and metadata replacement are serialized per
 * session within this store instance; multiple store instances and cross-process writers are unsupported.
 */
class JsonlSessionStore private constructor(
    private val root: Path,
    private val fileOperations: SessionFileOperations,
) : SessionStore {
    constructor(root: Path) : this(root, NioSessionFileOperations)

    private val sessionLocks = Array(SESSION_LOCK_STRIPES) { Any() }

    override val persistsSessions: Boolean = true

    override fun newCandidate(cwd: Path, model: String, name: String?): Session =
        Session(
            id = Uuid.random(),
            name = name,
            cwd = cwd.toAbsolutePath().normalize(),
            modelName = model,
            createdAt = Clock.System.now(),
        )

    override fun persistNew(candidate: Session) = withSessionLock(candidate) {
        require(candidate.entries.isEmpty()) { "A new session candidate cannot contain transcript entries." }
        val header = SessionCodec.encodeHeader(candidate)
        val file = fileFor(candidate)
        Files.createDirectories(file.parent)
        var temporary: Path? = null
        try {
            temporary = fileOperations.createSiblingTemp(file)
            fileOperations.writeNewHeader(temporary, header)
            fileOperations.publishNewAtomically(temporary, file)
            fileOperations.forceDirectoryIfSupported(file.parent)
        } finally {
            // Once publication succeeds both names may briefly refer to the same forced inode. Removing the sibling
            // is best-effort and cannot turn an accepted complete header into a reported publication failure.
            temporary?.let { runCatching { fileOperations.deleteIfExists(it) } }
        }
    }

    override fun loadHeader(id: Uuid): SessionHeader {
        val file = findFileById(id) ?: throw NoSuchElementException("no persisted session '$id' under $root")
        return readHeader(file).also { require(it.id == id) { "session header id '${it.id}' does not match '$id'" } }
    }

    override fun append(session: Session, entry: Entry) = withSessionLock(session) {
        val file = requirePublished(session)
        Files.writeString(file, SessionCodec.encodeEntry(entry) + "\n", StandardOpenOption.APPEND)
        Unit
    }

    override fun load(id: Uuid): Session {
        val file = findFileById(id) ?: throw NoSuchElementException("no persisted session '$id' under $root")
        return readSession(file).also { require(it.id == id) { "session header id '${it.id}' does not match '$id'" } }
    }

    override fun listForCwd(cwd: Path): List<SessionSummary> {
        val dir = dirFor(cwd)
        if (dir.notExists()) return emptyList()
        return dir.listDirectoryEntries("*.jsonl")
            .mapNotNull { runCatching { summarize(it) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    override fun persistMetadata(session: Session, candidate: SessionMetadata) = withSessionLock(session) {
        val file = requirePublished(session)
        val candidateHeader = SessionCodec.encodeHeader(session, candidate)
        var temporary: Path? = null
        try {
            temporary = fileOperations.createSiblingTemp(file)
            fileOperations.writeCandidate(file, temporary, candidateHeader)
            fileOperations.replaceAtomically(temporary, file)
            temporary = null
        } finally {
            // Cleanup is best-effort and deliberately cannot mask the persistence error. A failed deletion can leave
            // the sibling temporary file behind for later manual cleanup.
            temporary?.let { runCatching { fileOperations.deleteIfExists(it) } }
        }
    }

    override fun rewrite(session: Session, candidateEntries: List<Entry>) = withSessionLock(session) {
        val file = requirePublished(session)
        val candidateLines = buildList(candidateEntries.size + 1) {
            add(SessionCodec.encodeHeader(session))
            candidateEntries.mapTo(this) { SessionCodec.encodeEntry(it) }
        }
        var temporary: Path? = null
        try {
            temporary = fileOperations.createSiblingTemp(file)
            fileOperations.writeTranscriptCandidate(temporary, candidateLines)
            fileOperations.replaceAtomically(temporary, file)
            temporary = null
        } finally {
            temporary?.let { runCatching { fileOperations.deleteIfExists(it) } }
        }
    }

    override fun locate(session: Session): Path? =
        fileFor(session).takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }

    private fun readHeader(file: Path): SessionHeader {
        val line = Files.newBufferedReader(file, Charsets.UTF_8).use { it.readLine() }
        require(!line.isNullOrBlank()) { "empty session file: $file" }
        return SessionCodec.decodeHeader(line)
    }

    private fun readSession(file: Path): Session {
        val lines = file.readLines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "empty session file: $file" }
        val session = SessionCodec.decodeHeader(lines.first()).toSession()
        lines.drop(1).forEach { session.entries += SessionCodec.decodeEntry(it) }
        return session
    }

    private fun summarize(file: Path): SessionSummary {
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = SessionCodec.decodeHeader(lines.first())
        val entryLines = lines.drop(1)
        val updatedAt = entryLines.lastOrNull()?.let { SessionCodec.decodeEntry(it).timestamp } ?: header.createdAt
        return SessionSummary(header.id, header.name, header.createdAt, updatedAt, entryLines.size)
    }

    private fun findFileById(id: Uuid): Path? {
        if (root.notExists()) return null
        val target = "$id.jsonl"
        return root.listDirectoryEntries()
            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .firstNotNullOfOrNull { dir ->
                dir.resolve(target).takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            }
    }

    private fun fileFor(session: Session): Path = dirFor(session.cwd).resolve("${session.id}.jsonl")

    private fun requirePublished(session: Session): Path {
        val file = fileFor(session)
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "session file does not exist: $file" }
        val header = readHeader(file)
        require(header.id == session.id && header.cwd == session.cwd && header.createdAt == session.createdAt) {
            "session candidate is not the published session at $file"
        }
        return file
    }

    private fun <T> withSessionLock(session: Session, operation: () -> T): T =
        synchronized(sessionLocks[Math.floorMod(session.id.hashCode(), sessionLocks.size)], operation)

    private fun dirFor(cwd: Path): Path = root.resolve(cwdHash(cwd))

    private fun cwdHash(cwd: Path): String {
        val normalized = cwd.toAbsolutePath().normalize().toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, CWD_HASH_LENGTH)
    }

    companion object {
        internal fun withFileOperations(root: Path, fileOperations: SessionFileOperations): JsonlSessionStore =
            JsonlSessionStore(root, fileOperations)

        private const val CWD_HASH_LENGTH = 16
        private const val SESSION_LOCK_STRIPES = 64
    }
}

/** Focused file-operation seam for deterministic atomic replacement failure tests. */
internal interface SessionFileOperations {
    fun createSiblingTemp(target: Path): Path

    fun writeNewHeader(temporary: Path, header: String)

    /** Atomically creates [target] without replacing an existing entry. */
    fun publishNewAtomically(temporary: Path, target: Path)

    fun forceDirectoryIfSupported(directory: Path)

    fun writeCandidate(source: Path, temporary: Path, candidateHeader: String)

    fun writeTranscriptCandidate(temporary: Path, candidateLines: List<String>)

    fun replaceAtomically(temporary: Path, target: Path)

    fun deleteIfExists(path: Path)
}

internal object NioSessionFileOperations : SessionFileOperations {
    override fun createSiblingTemp(target: Path): Path =
        Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")

    override fun writeNewHeader(temporary: Path, header: String) {
        FileChannel.open(
            temporary,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val output = Channels.newOutputStream(channel)
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            channel.force(true)
        }
    }

    override fun publishNewAtomically(temporary: Path, target: Path) {
        // Java ATOMIC_MOVE cannot promise no-replacement on POSIX. A same-directory hard-link publication is one
        // atomic create-new directory-entry operation: it either exposes the complete forced inode or fails if the
        // final name already exists. Unsupported filesystems fail rather than taking a non-atomic fallback.
        Files.createLink(target, temporary)
    }

    override fun forceDirectoryIfSupported(directory: Path) {
        runCatching {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    override fun writeCandidate(source: Path, temporary: Path, candidateHeader: String) {
        Files.newInputStream(source).use { input ->
            var previous = -1
            while (true) {
                val current = input.read()
                require(current >= 0) { "session file has no complete header line: $source" }
                if (current == '\n'.code) break
                previous = current
            }
            val separator = if (previous == '\r'.code) "\r\n" else "\n"

            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val output = Channels.newOutputStream(channel)
                output.write(candidateHeader.toByteArray(Charsets.UTF_8))
                output.write(separator.toByteArray(Charsets.UTF_8))
                input.copyTo(output)
                output.flush()
                channel.force(true)
            }
        }
    }

    override fun writeTranscriptCandidate(temporary: Path, candidateLines: List<String>) {
        FileChannel.open(
            temporary,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val output = Channels.newOutputStream(channel)
            candidateLines.forEach { line ->
                output.write(line.toByteArray(Charsets.UTF_8))
                output.write('\n'.code)
            }
            output.flush()
            channel.force(true)
        }
    }

    override fun replaceAtomically(temporary: Path, target: Path) {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}

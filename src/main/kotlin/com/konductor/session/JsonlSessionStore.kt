package com.konductor.session

import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
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
 * different projects apart without leaking absolute paths into directory names. Writes are append-only so a
 * crash mid-turn still leaves a valid partial session ([rename] is the one exception — it rewrites the whole
 * file to replace the header line, which is cheap for the small files sessions produce).
 */
class JsonlSessionStore(private val root: Path) : SessionStore {

    override fun create(cwd: Path, model: String, name: String?): Session {
        val session = Session(
            id = Uuid.random(),
            name = name,
            cwd = cwd.toAbsolutePath().normalize(),
            modelName = model,
            createdAt = Clock.System.now(),
        )
        val file = fileFor(session)
        file.parent.createDirectories()
        file.writeText(SessionCodec.encodeHeader(session) + "\n")
        return session
    }

    override fun append(session: Session, entry: Entry) {
        val file = fileFor(session)
        file.parent.createDirectories()
        Files.writeString(
            file,
            SessionCodec.encodeEntry(entry) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    override fun load(id: Uuid): Session {
        val file = findFileById(id) ?: throw NoSuchElementException("no persisted session '$id' under $root")
        return readSession(file)
    }

    override fun listForCwd(cwd: Path): List<SessionSummary> {
        val dir = dirFor(cwd)
        if (dir.notExists()) return emptyList()
        return dir.listDirectoryEntries("*.jsonl")
            .mapNotNull { runCatching { summarize(it) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    override fun rename(session: Session, name: String) {
        session.name = name
        val file = fileFor(session)
        if (file.exists()) {
            val body = file.readLines().drop(1)
            file.writeText((listOf(SessionCodec.encodeHeader(session)) + body).joinToString("\n", postfix = "\n"))
        }
    }

    override fun locate(session: Session): Path = fileFor(session)

    private fun readSession(file: Path): Session {
        val lines = file.readLines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "empty session file: $file" }
        val session = SessionCodec.decodeHeader(lines.first())
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
            .filter { it.isDirectory() }
            .firstNotNullOfOrNull { dir -> dir.resolve(target).takeIf { it.exists() } }
    }

    private fun fileFor(session: Session): Path = dirFor(session.cwd).resolve("${session.id}.jsonl")

    private fun dirFor(cwd: Path): Path = root.resolve(cwdHash(cwd))

    private fun cwdHash(cwd: Path): String {
        val normalized = cwd.toAbsolutePath().normalize().toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, CWD_HASH_LENGTH)
    }

    private companion object {
        const val CWD_HASH_LENGTH = 16
    }
}

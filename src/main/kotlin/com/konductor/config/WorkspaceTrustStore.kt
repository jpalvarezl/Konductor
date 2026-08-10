package com.konductor.config

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadFeature
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.util.UUID
import kotlin.io.path.absolute
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString

/** A decision persisted in the strict workspace-trust v1 document. */
enum class WorkspaceTrustDecision(val wireValue: String) {
    Trusted("trusted"),
    Untrusted("untrusted");

    companion object {
        internal fun fromWire(value: String): WorkspaceTrustDecision? = entries.firstOrNull { it.wireValue == value }
    }
}

/** The complete result of a trust-store read. Invalid stores never expose partially recovered decisions. */
sealed interface WorkspaceTrustSnapshot {
    val storePath: Path

    data class Valid internal constructor(
        override val storePath: Path,
        val decisions: Map<Path, WorkspaceTrustDecision>,
        val storeExists: Boolean,
        internal val fingerprint: StoreFingerprint,
    ) : WorkspaceTrustSnapshot {
        fun decisionFor(workspace: Path): WorkspaceTrustDecision? = decisions[workspace.toAbsolutePath().normalize()]
    }

    data class Error(
        override val storePath: Path,
        val problem: String,
        val cause: Throwable? = null,
    ) : WorkspaceTrustSnapshot
}

/** A stale writer observed an opposite decision or another change to the accepted store. */
class WorkspaceTrustConflictException(message: String) : IOException(message)

/** A local-boundary, parsing, lock, or publication failure while writing trust. */
open class WorkspaceTrustStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The five-second persistent-lock deadline elapsed. */
class WorkspaceTrustLockTimeoutException(message: String) : WorkspaceTrustStoreException(message)

internal fun readEffectiveUserId(path: Path, lookup: () -> Long): Long = try {
    lookup()
} catch (error: Exception) {
    throw WorkspaceTrustStoreException("Cannot determine the effective POSIX user for $path", error)
} catch (error: LinkageError) {
    throw WorkspaceTrustStoreException("Cannot determine the effective POSIX user for $path", error)
}

internal data class StoreFingerprint(
    val exists: Boolean,
    val fileKey: Any?,
    val size: Long,
    val modifiedMillis: Long,
    val createdMillis: Long,
)

private data class PinnedDirectory(
    val requested: Path,
    val canonical: Path,
    val fileKey: Any?,
)

internal interface WorkspaceTrustFileOperations {
    fun createCandidate(path: Path, options: Set<OpenOption>): FileChannel
    fun forceCandidate(channel: FileChannel)
    fun replaceAtomically(candidate: Path, target: Path)
    fun deleteIfExists(path: Path)
}

private object SystemWorkspaceTrustFileOperations : WorkspaceTrustFileOperations {
    override fun createCandidate(path: Path, options: Set<OpenOption>): FileChannel = FileChannel.open(path, options)
    override fun forceCandidate(channel: FileChannel) = channel.force(true)
    override fun replaceAtomically(candidate: Path, target: Path) {
        Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}

internal fun interface WorkspaceTrustPathIdentity {
    fun fileKey(path: Path): Any?
}

private val systemWorkspaceTrustPathIdentity = WorkspaceTrustPathIdentity { path ->
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
}

internal data class WindowsSecurityFacts(
    val owner: String?,
    val writablePrincipals: List<String>?,
)

internal fun validateWindowsSecurityFacts(
    path: Path,
    facts: WindowsSecurityFacts,
    isBroadPrincipal: (String) -> Boolean,
) {
    if (facts.owner?.let(isBroadPrincipal) == true) {
        throw WorkspaceTrustStoreException("Path has a broadly shared Windows owner ${facts.owner}: $path")
    }
    val unsafe = facts.writablePrincipals?.firstOrNull(isBroadPrincipal)
    if (unsafe != null) {
        throw WorkspaceTrustStoreException("Path grants Windows write access to $unsafe: $path")
    }
}

private data class StableAttributes(
    val fileKey: Any?,
    val size: Long,
    val modifiedMillis: Long,
    val createdMillis: Long,
) {
    fun fingerprint() = StoreFingerprint(true, fileKey, size, modifiedMillis, createdMillis)
}

/**
 * Strict v1 workspace trust persistence.
 *
 * The store is deliberately independent of frontends. It pins an external config directory, performs bounded
 * no-follow reads, validates local POSIX/Windows filesystem facts that the JVM can reliably expose, and coordinates
 * writers through a persistent sibling lock. [persist] always rereads and merges under that lock and publishes only
 * through an atomic replacement of a forced create-new candidate.
 */
class WorkspaceTrustStore private constructor(
    configDirectory: Path,
    workspaceRoot: Path,
    private val lockTimeout: Duration,
    private val fileOperations: WorkspaceTrustFileOperations,
    private val pathIdentity: WorkspaceTrustPathIdentity,
) {
    constructor(
        configDirectory: Path,
        workspaceRoot: Path,
        lockTimeout: Duration = Duration.ofSeconds(5),
    ) : this(
        configDirectory,
        workspaceRoot,
        lockTimeout,
        SystemWorkspaceTrustFileOperations,
        systemWorkspaceTrustPathIdentity,
    )

    companion object {
        const val STORE_FILE_NAME: String = "workspace-trust.json"
        const val LOCK_FILE_NAME: String = "workspace-trust.lock"
        const val MAX_STORE_BYTES: Int = 1024 * 1024

        internal fun testing(
            configDirectory: Path,
            workspaceRoot: Path,
            fileOperations: WorkspaceTrustFileOperations = SystemWorkspaceTrustFileOperations,
            pathIdentity: WorkspaceTrustPathIdentity = systemWorkspaceTrustPathIdentity,
            lockTimeout: Duration = Duration.ofSeconds(5),
        ): WorkspaceTrustStore = WorkspaceTrustStore(
            configDirectory,
            workspaceRoot,
            lockTimeout,
            fileOperations,
            pathIdentity,
        )

        private val jsonFactory = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
        private val ownerOnlyPermissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }

    private val requestedConfigDirectory = configDirectory.toAbsolutePath().normalize()
    val workspaceRoot: Path = canonicalDirectory(workspaceRoot, "workspace root")
    val storePath: Path get() = requestedConfigDirectory.resolve(STORE_FILE_NAME)
    val lockPath: Path get() = requestedConfigDirectory.resolve(LOCK_FILE_NAME)

    @Volatile
    private var pinnedDirectory: PinnedDirectory? = null

    /** Validate only structural config-directory safety; this intentionally does not inspect store contents. */
    fun validateBoundary(): Path = pinAndValidateDirectory().canonical

    /** Read and strictly validate one complete snapshot. A missing file is a valid empty snapshot. */
    fun read(): WorkspaceTrustSnapshot = try {
        val directory = pinAndValidateDirectory()
        readValid(directory)
    } catch (e: Exception) {
        WorkspaceTrustSnapshot.Error(storePath, e.message ?: e.javaClass.simpleName, e)
    }

    /**
     * Persist [decision] based on [accepted]. Unrelated concurrent updates are merged; an identical update is
     * idempotent; an opposite or otherwise changed same-workspace decision is rejected as stale.
     */
    @Throws(WorkspaceTrustStoreException::class, WorkspaceTrustConflictException::class)
    fun persist(
        accepted: WorkspaceTrustSnapshot.Valid,
        decision: WorkspaceTrustDecision,
    ): WorkspaceTrustSnapshot.Valid {
        if (accepted.storePath.toAbsolutePath().normalize() != storePath) {
            throw WorkspaceTrustStoreException("Accepted snapshot belongs to ${accepted.storePath}, not $storePath.")
        }
        val directory = pinAndValidateDirectory()
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        return withPersistentLock(directory) {
            val latest = readValid(directory)
            val acceptedDecision = accepted.decisions[workspace]
            val latestDecision = latest.decisions[workspace]

            if (latestDecision == decision) return@withPersistentLock latest
            if (latestDecision != acceptedDecision) {
                throw WorkspaceTrustConflictException(
                    "Workspace trust changed concurrently for $workspace; refusing to overwrite it.",
                )
            }
            if (acceptedDecision != null && acceptedDecision != decision) {
                throw WorkspaceTrustConflictException(
                    "Refusing to replace the saved ${acceptedDecision.wireValue} decision for $workspace.",
                )
            }

            val merged = LinkedHashMap(latest.decisions)
            merged[workspace] = decision
            val bytes = serialize(merged)
            val candidate = writeForcedCandidate(directory, bytes)
            try {
                // Validate the exact bytes/file that will be published, not just the in-memory representation.
                val candidateSnapshot = readFile(directory, candidate, allowMissing = false)
                if (candidateSnapshot.decisions != merged) {
                    throw WorkspaceTrustStoreException("Trust-store candidate changed before publication: $candidate")
                }

                // A same-user process is inside the threat boundary, but an observed race still fails closed.
                val beforeReplace = readValid(directory)
                if (beforeReplace.fingerprint != latest.fingerprint || beforeReplace.decisions != latest.decisions) {
                    throw WorkspaceTrustConflictException(
                        "Workspace trust store changed before publication: $storePath",
                    )
                }
                atomicReplace(candidate, directory.canonical.resolve(STORE_FILE_NAME))
                readValid(directory).also {
                    if (it.decisions != merged) {
                        throw WorkspaceTrustStoreException(
                            "Published trust store did not reread as written: $storePath",
                        )
                    }
                }
            } finally {
                runCatching { fileOperations.deleteIfExists(candidate) }
            }
        }
    }

    @Synchronized
    private fun pinAndValidateDirectory(): PinnedDirectory {
        requireLiteralDirectoryChain(requestedConfigDirectory)
        val prospective = prospectiveCanonicalPath(requestedConfigDirectory)
        rejectWorkspaceContainment(prospective)
        Files.createDirectories(requestedConfigDirectory)
        requireLiteralDirectoryChain(requestedConfigDirectory)
        val canonical = requestedConfigDirectory.toRealPath()
        if (canonical != prospective) {
            throw WorkspaceTrustStoreException(
                "Config directory changed while it was created: $requestedConfigDirectory",
            )
        }
        if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
            throw WorkspaceTrustStoreException("Config path is not a directory: $canonical")
        }
        rejectWorkspaceContainment(canonical)
        validateDirectorySecurity(canonical)
        val current = PinnedDirectory(requestedConfigDirectory, canonical, pathIdentity.fileKey(canonical))
        val pinned = pinnedDirectory
        if (pinned != null && (pinned.canonical != current.canonical || keysDiffer(pinned.fileKey, current.fileKey))) {
            throw WorkspaceTrustStoreException(
                "Config directory changed after it was pinned: $requestedConfigDirectory",
            )
        }
        pinnedDirectory = pinned ?: current
        return pinned ?: current
    }

    private fun revalidateDirectory(directory: PinnedDirectory) {
        val canonical = directory.requested.toRealPath()
        if (canonical != directory.canonical || keysDiffer(directory.fileKey, pathIdentity.fileKey(canonical))) {
            throw WorkspaceTrustStoreException(
                "Config directory changed during trust-store access: ${directory.requested}",
            )
        }
        validateDirectorySecurity(canonical)
    }

    private fun validateDirectorySecurity(path: Path) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (posix != null) {
            val attrs = posix.readAttributes()
            val unsafe = PosixFilePermission.GROUP_WRITE in attrs.permissions() ||
                PosixFilePermission.OTHERS_WRITE in attrs.permissions()
            if (unsafe) throw WorkspaceTrustStoreException("Config directory is group/other writable: $path")
            validateEffectiveOwner(path)
            return
        }
        if (Platform.isWindows()) validateWindowsSecurity(path)
    }

    private fun validateEffectiveOwner(path: Path) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (posix != null) {
            val uid = runCatching {
                Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number
            }.getOrNull()
            if (uid != null) {
                val effectiveUid = readEffectiveUserId(path) { EffectiveUserLib.INSTANCE.geteuid().toLong() }
                if (uid.toLong() != effectiveUid) {
                    throw WorkspaceTrustStoreException(
                        "Path owner uid ${uid.toLong()} is not effective uid $effectiveUid: $path",
                    )
                }
            }
            return
        }
        if (Platform.isWindows()) validateWindowsSecurity(path)
    }

    private fun validateWindowsSecurity(path: Path) {
        val owner = runCatching { Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name }.getOrNull()
        val aclView = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        val writePermissions = setOf(
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.DELETE,
        )
        val writablePrincipals = aclView?.let {
            runCatching { it.acl }.getOrNull()?.filter { entry ->
                entry.type() == AclEntryType.ALLOW && entry.permissions().any(writePermissions::contains)
            }?.map { entry -> entry.principal().name }
        }
        validateWindowsSecurityFacts(
            path,
            WindowsSecurityFacts(owner, writablePrincipals),
            ::isBroadWindowsPrincipal,
        )
    }

    private fun isBroadWindowsPrincipal(name: String): Boolean {
        val broadSids = setOf(
            "S-1-1-0", // Everyone
            "S-1-5-11", // Authenticated Users
            "S-1-5-32-545", // BUILTIN\\Users
        )
        return runCatching { Advapi32Util.getAccountByName(name).sidString in broadSids }.getOrDefault(false)
    }

    private interface EffectiveUserLib : Library {
        fun geteuid(): Int

        companion object {
            val INSTANCE: EffectiveUserLib by lazy {
                Native.load(Platform.C_LIBRARY_NAME, EffectiveUserLib::class.java)
            }
        }
    }

    private fun readValid(directory: PinnedDirectory): WorkspaceTrustSnapshot.Valid =
        readFile(directory, directory.canonical.resolve(STORE_FILE_NAME), allowMissing = true)

    private fun readFile(
        directory: PinnedDirectory,
        path: Path,
        allowMissing: Boolean,
    ): WorkspaceTrustSnapshot.Valid {
        requireDirectChild(directory, path)
        revalidateDirectory(directory)
        val before = try {
            stableAttributes(path)
        } catch (e: NoSuchFileException) {
            if (!allowMissing) throw WorkspaceTrustStoreException("Trust-store entry disappeared: $path", e)
            revalidateDirectory(directory)
            return WorkspaceTrustSnapshot.Valid(storePath, emptyMap(), false, StoreFingerprint(false, null, 0, 0, 0))
        }
        validateFinalFile(path, before)
        if (before.size > MAX_STORE_BYTES) {
            throw WorkspaceTrustStoreException("Trust store exceeds $MAX_STORE_BYTES bytes: $path")
        }
        val bytes = try {
            noFollowRead(path, MAX_STORE_BYTES)
        } catch (e: NoSuchFileException) {
            throw WorkspaceTrustStoreException("Trust-store entry changed while opening: $path", e)
        }
        val after = stableAttributes(path)
        validateFinalFile(path, after)
        revalidateDirectory(directory)
        if (before != after) throw WorkspaceTrustStoreException("Trust-store entry changed while it was read: $path")
        val decisions = parseStrict(bytes, path)
        return WorkspaceTrustSnapshot.Valid(storePath, decisions, true, after.fingerprint())
    }

    private fun stableAttributes(path: Path): StableAttributes {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return StableAttributes(
            pathIdentity.fileKey(path),
            attrs.size(),
            attrs.lastModifiedTime().toMillis(),
            attrs.creationTime().toMillis(),
        )
    }

    private fun validateFinalFile(path: Path, attrs: StableAttributes) {
        val basic = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (basic.isSymbolicLink || !basic.isRegularFile) {
            throw WorkspaceTrustStoreException("Trust-store entry must be a no-follow regular file: $path")
        }
        validateEffectiveOwner(path)
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (posix != null) {
            val permissions = posix.readAttributes().permissions()
            if (PosixFilePermission.GROUP_WRITE in permissions || PosixFilePermission.OTHERS_WRITE in permissions) {
                throw WorkspaceTrustStoreException("Trust-store entry is group/other writable: $path")
            }
            val links = runCatching { Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number }
                .getOrNull()
            if (links != null && links.toLong() > 1) {
                throw WorkspaceTrustStoreException("Trust-store entry has ${links.toLong()} hard links: $path")
            }
        }
        val currentFileKey = pathIdentity.fileKey(path)
        if (attrs.fileKey != currentFileKey && attrs.fileKey != null && currentFileKey != null) {
            throw WorkspaceTrustStoreException("Trust-store file identity changed: $path")
        }
    }

    private fun noFollowRead(path: Path, limit: Int): ByteArray {
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        FileChannel.open(path, options).use { channel ->
            val probeLimit = limit + 1
            val output = java.io.ByteArrayOutputStream(minOf(probeLimit, 8192))
            val buffer = ByteBuffer.allocate(minOf(probeLimit.coerceAtLeast(1), 8192))
            var count = 0
            while (count < probeLimit) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity(), probeLimit - count))
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                output.write(buffer.array(), 0, read)
            }
            if (count > limit) throw WorkspaceTrustStoreException("Trust store exceeds $limit bytes: $path")
            return output.toByteArray()
        }
    }

    private fun parseStrict(bytes: ByteArray, source: Path): Map<Path, WorkspaceTrustDecision> {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            throw WorkspaceTrustStoreException("Trust store is not valid UTF-8: $source", e)
        }
        try {
            jsonFactory.createParser(text).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) invalid(source, "root must be an object")
                var version: Int? = null
                var workspaces: LinkedHashMap<Path, WorkspaceTrustDecision>? = null
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken != JsonToken.FIELD_NAME) invalid(source, "expected a top-level member")
                    when (val member = parser.currentName()) {
                        "version" -> {
                            if (parser.nextToken() != JsonToken.VALUE_NUMBER_INT) {
                                invalid(source, "version must be integer 1")
                            }
                            version = runCatching { parser.intValue }
                                .getOrElse { invalid(source, "version must be integer 1") }
                        }
                        "workspaces" -> {
                            if (parser.nextToken() != JsonToken.START_OBJECT) {
                                invalid(source, "workspaces must be an object")
                            }
                            val parsed = LinkedHashMap<Path, WorkspaceTrustDecision>()
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                if (parser.currentToken != JsonToken.FIELD_NAME) {
                                    invalid(source, "invalid workspace member")
                                }
                                val rawPath = parser.currentName()
                                val path = parseWorkspacePath(rawPath, source)
                                if (parsed.keys.any { it == path }) {
                                    invalid(source, "duplicate host workspace path '$rawPath'")
                                }
                                if (parser.nextToken() != JsonToken.VALUE_STRING) {
                                    invalid(source, "decision for '$rawPath' must be trusted or untrusted")
                                }
                                val value = WorkspaceTrustDecision.fromWire(parser.text)
                                    ?: invalid(source, "decision for '$rawPath' must be trusted or untrusted")
                                parsed[path] = value
                            }
                            workspaces = parsed
                        }
                        else -> invalid(source, "unknown top-level member '$member'")
                    }
                }
                if (parser.nextToken() != null) invalid(source, "trailing JSON is not allowed")
                if (version != 1) {
                    invalid(source, if (version == null) "missing version" else "unsupported version $version")
                }
                return workspaces ?: invalid(source, "missing workspaces")
            }
        } catch (e: WorkspaceTrustStoreException) {
            throw e
        } catch (e: Exception) {
            throw WorkspaceTrustStoreException("Invalid workspace trust store at $source: ${e.message}", e)
        }
    }

    private fun parseWorkspacePath(raw: String, source: Path): Path {
        if (raw.isEmpty()) invalid(source, "workspace path must not be empty")
        val path = try {
            Path.of(raw)
        } catch (e: Exception) {
            throw WorkspaceTrustStoreException("Invalid workspace path '$raw' in $source", e)
        }
        if (!path.isAbsolute || path.normalize() != path) {
            invalid(source, "workspace path must be absolute and lexically normalized: '$raw'")
        }
        return path
    }

    private fun invalid(source: Path, problem: String): Nothing =
        throw WorkspaceTrustStoreException("Invalid workspace trust store at $source: $problem.")

    private fun serialize(decisions: Map<Path, WorkspaceTrustDecision>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        jsonFactory.createGenerator(output).use { generator ->
            generator.writeStartObject()
            generator.writeNumberField("version", 1)
            generator.writeObjectFieldStart("workspaces")
            decisions.entries.sortedBy { it.key.pathString }.forEach { (workspace, decision) ->
                generator.writeStringField(workspace.pathString, decision.wireValue)
            }
            generator.writeEndObject()
            generator.writeEndObject()
        }
        return output.toByteArray()
    }

    private fun writeForcedCandidate(directory: PinnedDirectory, bytes: ByteArray): Path {
        repeat(32) {
            val candidate = directory.canonical.resolve(".workspace-trust.${UUID.randomUUID()}.tmp")
            var owned = false
            try {
                val options = setOf<OpenOption>(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                fileOperations.createCandidate(candidate, options).use { channel ->
                    owned = true
                    setOwnerOnlyIfSupported(candidate)
                    var buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    fileOperations.forceCandidate(channel)
                }
                validateFinalFile(candidate, stableAttributes(candidate))
                revalidateDirectory(directory)
                return candidate
            } catch (_: FileAlreadyExistsException) {
                // UUID collisions or pre-created entries are never followed; choose another candidate.
            } catch (e: Exception) {
                if (owned) runCatching { fileOperations.deleteIfExists(candidate) }
                throw WorkspaceTrustStoreException(
                    "Could not create forced trust-store candidate in ${directory.canonical}",
                    e,
                )
            }
        }
        throw WorkspaceTrustStoreException(
            "Could not allocate a unique trust-store candidate in ${directory.canonical}",
        )
    }

    private fun atomicReplace(candidate: Path, target: Path) {
        try {
            fileOperations.replaceAtomically(candidate, target)
        } catch (e: AtomicMoveNotSupportedException) {
            throw WorkspaceTrustStoreException("Atomic trust-store replacement is not supported for $target", e)
        } catch (e: Exception) {
            throw WorkspaceTrustStoreException("Could not atomically replace trust store $target", e)
        }
    }

    private fun <T> withPersistentLock(directory: PinnedDirectory, body: () -> T): T {
        val channel = openPersistentLock(directory)
        try {
            val deadline = System.nanoTime() + lockTimeout.toNanos()
            var lock: FileLock? = null
            while (lock == null) {
                lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    if (System.nanoTime() >= deadline) {
                        throw WorkspaceTrustLockTimeoutException(
                            "Timed out after ${lockTimeout.toMillis()}ms waiting for trust-store lock: " +
                                directory.canonical.resolve(LOCK_FILE_NAME),
                        )
                    }
                    Thread.sleep(10)
                }
            }
            lock.use {
                revalidateDirectory(directory)
                val lockPath = directory.canonical.resolve(LOCK_FILE_NAME)
                validateFinalFile(lockPath, stableAttributes(lockPath))
                return body()
            }
        } finally {
            channel.close()
        }
    }

    private fun openPersistentLock(directory: PinnedDirectory): FileChannel {
        val path = directory.canonical.resolve(LOCK_FILE_NAME)
        requireDirectChild(directory, path)
        val channel = try {
            FileChannel.open(
                path,
                setOf<OpenOption>(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            ).also { setOwnerOnlyIfSupported(path) }
        } catch (_: FileAlreadyExistsException) {
            try {
                FileChannel.open(
                    path,
                    setOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                )
            } catch (e: Exception) {
                throw WorkspaceTrustStoreException("Trust-store lock is not a safe regular file: $path", e)
            }
        } catch (e: Exception) {
            throw WorkspaceTrustStoreException("Could not create trust-store lock: $path", e)
        }
        try {
            validateFinalFile(path, stableAttributes(path))
            revalidateDirectory(directory)
            return channel
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    private fun setOwnerOnlyIfSupported(path: Path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(path, ownerOnlyPermissions)
        }
    }

    private fun requireDirectChild(directory: PinnedDirectory, path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized.parent != directory.canonical || normalized.name.isEmpty()) {
            throw WorkspaceTrustStoreException("Trust-store path is not a direct config-directory child: $path")
        }
    }

    private fun requireLiteralDirectoryChain(path: Path) {
        var current = path.root
            ?: throw WorkspaceTrustStoreException("Config directory must be absolute: $path")
        for (component in path) {
            current = current.resolve(component)
            val attributes = try {
                Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return
            }
            if (!attributes.isDirectory || attributes.isSymbolicLink) {
                throw WorkspaceTrustStoreException("Config path component is not a literal directory: $current")
            }
        }
    }

    private fun prospectiveCanonicalPath(path: Path): Path {
        var existing = path
        val missing = ArrayDeque<String>()
        while (true) {
            try {
                Files.readAttributes(existing, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                break
            } catch (_: NoSuchFileException) {
                missing.addFirst(existing.fileName?.toString() ?: break)
                existing = existing.parent ?: break
            }
        }
        var prospective = existing.toRealPath()
        missing.forEach { prospective = prospective.resolve(it) }
        return prospective.normalize()
    }

    private fun rejectWorkspaceContainment(path: Path) {
        if (path == workspaceRoot || path.startsWith(workspaceRoot)) {
            throw WorkspaceTrustStoreException("Config directory $path must be outside workspace $workspaceRoot.")
        }
    }

    private fun keysDiffer(first: Any?, second: Any?): Boolean = first != null && second != null && first != second

    private fun canonicalDirectory(path: Path, label: String): Path {
        val absolute = path.absolute().normalize()
        if (!absolute.isDirectory()) throw WorkspaceTrustStoreException("$label is not a directory: $absolute")
        return try {
            absolute.toRealPath()
        } catch (e: Exception) {
            throw WorkspaceTrustStoreException("Could not canonicalize $label: $absolute", e)
        }
    }
}

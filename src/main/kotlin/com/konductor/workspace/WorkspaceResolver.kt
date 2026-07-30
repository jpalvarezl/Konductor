package com.konductor.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** The canonical working directory and the nearest canonical Git workspace root that contains it. */
data class ResolvedWorkspace(
    val canonicalCwd: Path,
    val canonicalRoot: Path,
) {
    init {
        require(canonicalCwd.isAbsolute) { "canonicalCwd must be absolute" }
        require(canonicalRoot.isAbsolute) { "canonicalRoot must be absolute" }
        require(canonicalCwd == canonicalRoot || canonicalCwd.startsWith(canonicalRoot)) {
            "canonicalCwd must be contained in canonicalRoot"
        }
    }
}

/**
 * Canonical config directory pinned to the available filesystem identity after safe creation and containment checks.
 */
data class ResolvedConfigDirectory(
    val canonicalPath: Path,
    internal val fileKey: Any?,
)

/** A path-specific workspace resolution failure suitable for a startup/request diagnostic. */
class WorkspaceResolutionException(
    val path: Path,
    message: String,
    cause: Throwable? = null,
) : IOException("$message: $path", cause)

/**
 * Resolves the process/session cwd and its nearest literal, no-follow `.git` workspace marker.
 *
 * This class deliberately never scans a directory and never follows a `.git` link. A lookup is considered absent
 * only when the filesystem reports [NoSuchFileException]; every other inspection failure is propagated as an error.
 */
class WorkspaceResolver {
    fun resolve(cwd: Path): ResolvedWorkspace {
        val canonicalCwd = canonicalDirectory(cwd, "working directory")
        var current: Path? = canonicalCwd
        while (current != null) {
            if (isWorkspaceMarker(current.resolve(GIT_MARKER))) {
                return ResolvedWorkspace(canonicalCwd, current)
            }
            current = current.parent
        }
        return ResolvedWorkspace(canonicalCwd, canonicalCwd)
    }

    /** Canonicalize an existing path and require its target to be a directory. */
    fun canonicalDirectory(path: Path, description: String = "directory"): Path {
        val absolute = path.toAbsolutePath().normalize()
        val canonical = try {
            absolute.toRealPath()
        } catch (error: IOException) {
            throw WorkspaceResolutionException(absolute, "Cannot resolve $description", error)
        }
        val attributes = try {
            Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: IOException) {
            throw WorkspaceResolutionException(canonical, "Cannot inspect $description", error)
        }
        if (!attributes.isDirectory) {
            throw WorkspaceResolutionException(canonical, "$description is not a directory")
        }
        return canonical
    }

    /**
     * Resolve the process config directory from application input, real process environment, or the home default.
     * Relative inputs are rooted at canonical home. Every existing component must be a literal directory; missing
     * suffixes are created one literal, no-follow component at a time. Both prospective and final targets must remain
     * outside the workspace.
     */
    fun resolveConfigDirectory(
        applicationInput: Path? = null,
        processEnvironment: (String) -> String? = System::getenv,
        homeDirectory: Path = Path.of(System.getProperty("user.home")),
        workspace: ResolvedWorkspace,
    ): ResolvedConfigDirectory = resolveConfigDirectoryInternal(
        applicationInput,
        processEnvironment,
        homeDirectory,
        workspace,
    )

    /**
     * Resolve and pin the process-wide config directory without consulting a workspace. ACP uses this bootstrap before
     * transport startup, then applies [requireConfigOutsideWorkspace] independently to every authoritative session cwd.
     */
    fun resolveConfigDirectory(
        applicationInput: Path? = null,
        processEnvironment: (String) -> String? = System::getenv,
        homeDirectory: Path = Path.of(System.getProperty("user.home")),
    ): ResolvedConfigDirectory = resolveConfigDirectoryInternal(
        applicationInput,
        processEnvironment,
        homeDirectory,
        workspace = null,
    )

    private fun resolveConfigDirectoryInternal(
        applicationInput: Path?,
        processEnvironment: (String) -> String?,
        homeDirectory: Path,
        workspace: ResolvedWorkspace?,
    ): ResolvedConfigDirectory {
        if (applicationInput != null && applicationInput.toString().isBlank()) {
            throw WorkspaceResolutionException(applicationInput, "Config-directory input must contain a path")
        }
        val canonicalHome = canonicalDirectory(homeDirectory, "user home")
        val environmentInput = processEnvironment(CONFIG_DIR_ENV)?.trim()?.ifBlank { null }?.let {
            try {
                Path.of(it)
            } catch (error: RuntimeException) {
                throw WorkspaceResolutionException(
                    Path.of(CONFIG_DIR_ENV),
                    "Invalid path value '$it' from environment variable",
                    error,
                )
            }
        }
        val selected = applicationInput ?: environmentInput ?: canonicalHome.resolve(DEFAULT_CONFIG_DIRECTORY)
        val requested = (if (selected.isAbsolute) selected else canonicalHome.resolve(selected))
            .toAbsolutePath()
            .normalize()
        requireLiteralDirectoryChain(requested)
        val prospective = prospectiveCanonicalDirectory(requested)
        workspace?.let { requireOutsideWorkspace(prospective, it) }
        createMissingDirectories(prospective)
        requireLiteralDirectoryChain(requested)
        val canonical = canonicalDirectory(requested, "config directory")
        if (canonical != prospective) {
            throw WorkspaceResolutionException(requested, "Config directory changed while it was created")
        }
        workspace?.let { requireOutsideWorkspace(canonical, it) }
        val attributes = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return ResolvedConfigDirectory(canonical, attributes.fileKey())
    }

    /** Revalidate a pinned config directory and reject placement at or below [workspace]'s root. */
    fun requireConfigOutsideWorkspace(
        configDirectory: ResolvedConfigDirectory,
        workspace: ResolvedWorkspace,
    ): Path {
        val canonicalConfig = canonicalDirectory(configDirectory.canonicalPath, "config directory")
        val attributes = Files.readAttributes(
            canonicalConfig,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (canonicalConfig != configDirectory.canonicalPath || keysDiffer(configDirectory.fileKey, attributes.fileKey())) {
            throw WorkspaceResolutionException(configDirectory.canonicalPath, "Config directory changed after bootstrap")
        }
        requireOutsideWorkspace(canonicalConfig, workspace)
        return canonicalConfig
    }

    /** Canonicalize an existing config directory and reject placement at or below [workspace]'s root. */
    fun requireConfigOutsideWorkspace(configDirectory: Path, workspace: ResolvedWorkspace): Path {
        val canonicalConfig = canonicalDirectory(configDirectory, "config directory")
        requireOutsideWorkspace(canonicalConfig, workspace)
        return canonicalConfig
    }

    /** Canonical root-to-cwd directory order, inclusive. */
    fun directoriesFromRoot(workspace: ResolvedWorkspace): List<Path> {
        val reverse = mutableListOf<Path>()
        var current: Path? = workspace.canonicalCwd
        while (current != null) {
            reverse.add(current)
            if (current == workspace.canonicalRoot) break
            current = current.parent
        }
        check(reverse.lastOrNull() == workspace.canonicalRoot) { "Workspace cwd is not below its root" }
        return reverse.asReversed()
    }

    private fun requireLiteralDirectoryChain(requested: Path) {
        var current = requested.root
            ?: throw WorkspaceResolutionException(requested, "Config directory must be absolute")
        for (component in requested) {
            current = current.resolve(component)
            val attributes = try {
                readAttributesIfPresent(current) ?: return
            } catch (error: IOException) {
                throw WorkspaceResolutionException(current, "Cannot inspect config path component", error)
            }
            if (!attributes.isDirectory || attributes.isSymbolicLink) {
                throw WorkspaceResolutionException(current, "Config path component is not a literal directory")
            }
        }
    }

    private fun prospectiveCanonicalDirectory(requested: Path): Path {
        var existing = requested
        val missing = ArrayDeque<String>()
        while (readAttributesIfPresent(existing) == null) {
            val name = existing.fileName
                ?: throw WorkspaceResolutionException(requested, "Cannot locate an existing config-directory ancestor")
            missing.addFirst(name.toString())
            existing = existing.parent
                ?: throw WorkspaceResolutionException(requested, "Cannot locate an existing config-directory ancestor")
        }
        var prospective = try {
            existing.toRealPath()
        } catch (error: IOException) {
            throw WorkspaceResolutionException(existing, "Cannot canonicalize config-directory ancestor", error)
        }
        missing.forEach { prospective = prospective.resolve(it) }
        return prospective.normalize()
    }

    private fun createMissingDirectories(path: Path) {
        val missing = ArrayDeque<Path>()
        var current = path
        while (readAttributesIfPresent(current) == null) {
            missing.addFirst(current)
            current = current.parent
                ?: throw WorkspaceResolutionException(path, "Cannot create config directory")
        }
        for (directory in missing) {
            try {
                Files.createDirectory(directory)
            } catch (_: FileAlreadyExistsException) {
                // A cooperating creator is accepted only when it produced a literal ordinary directory.
            } catch (error: IOException) {
                throw WorkspaceResolutionException(directory, "Cannot create config directory", error)
            }
            val attributes = try {
                Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (error: IOException) {
                throw WorkspaceResolutionException(directory, "Cannot revalidate created config directory", error)
            }
            if (!attributes.isDirectory || attributes.isSymbolicLink) {
                throw WorkspaceResolutionException(directory, "Created config path is not a literal directory")
            }
        }
    }

    private fun requireOutsideWorkspace(configDirectory: Path, workspace: ResolvedWorkspace) {
        if (configDirectory == workspace.canonicalRoot || configDirectory.startsWith(workspace.canonicalRoot)) {
            throw WorkspaceResolutionException(
                configDirectory,
                "Config directory must be outside workspace root ${workspace.canonicalRoot}",
            )
        }
    }

    private fun isWorkspaceMarker(marker: Path): Boolean {
        val attributes = try {
            readAttributesIfPresent(marker) ?: return false
        } catch (error: IOException) {
            throw WorkspaceResolutionException(marker, "Cannot inspect workspace marker", error)
        }
        // NOFOLLOW_LINKS makes a symlink (including a junction/reparse point exposed as one) neither kind below.
        return !attributes.isSymbolicLink && (attributes.isRegularFile || attributes.isDirectory)
    }

    private fun keysDiffer(first: Any?, second: Any?): Boolean = first != null && second != null && first != second

    companion object {
        private const val GIT_MARKER = ".git"
        private const val CONFIG_DIR_ENV = "KONDUCTOR_CONFIG_DIR"
        private const val DEFAULT_CONFIG_DIRECTORY = ".konductor"
    }
}

/** Null means definite no-follow absence. All indeterminate inspection failures are thrown. */
internal fun readAttributesIfPresent(path: Path): BasicFileAttributes? =
    try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

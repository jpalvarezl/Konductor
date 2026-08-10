package com.konductor.workspace

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceResolverTest {
    private val resolver = WorkspaceResolver()

    @Test
    fun `nearest regular file or directory git marker defines canonical root`(@TempDir temp: Path) {
        val outer = Files.createDirectories(temp.resolve("outer"))
        Files.createDirectory(outer.resolve(".git"))
        val nested = Files.createDirectories(outer.resolve("a/repo"))
        Files.writeString(nested.resolve(".git"), "gitdir: elsewhere")
        val cwd = Files.createDirectories(nested.resolve("src/main"))

        val workspace = resolver.resolve(cwd.resolve("..").resolve("main"))

        assertEquals(cwd.toRealPath(), workspace.canonicalCwd)
        assertEquals(nested.toRealPath(), workspace.canonicalRoot)
        assertEquals(
            listOf(nested.toRealPath(), nested.resolve("src").toRealPath(), cwd.toRealPath()),
            resolver.directoriesFromRoot(workspace),
        )
    }

    @Test
    fun `cwd is root when no git marker exists`(@TempDir temp: Path) {
        val cwd = Files.createDirectories(temp.resolve("plain/nested"))

        val workspace = resolver.resolve(cwd)

        assertEquals(cwd.toRealPath(), workspace.canonicalRoot)
    }

    @Test
    fun `symlinked git marker does not redirect workspace discovery`(@TempDir temp: Path) {
        val outer = Files.createDirectories(temp.resolve("outer"))
        Files.createDirectory(outer.resolve(".git"))
        val nested = Files.createDirectories(outer.resolve("nested/work"))
        val markerTarget = Files.createDirectories(temp.resolve("marker-target"))
        try {
            Files.createSymbolicLink(nested.resolve(".git"), markerTarget)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }

        assertEquals(outer.toRealPath(), resolver.resolve(nested).canonicalRoot)
    }

    @Test
    fun `config directory must be outside canonical workspace root`(@TempDir temp: Path) {
        val root = Files.createDirectories(temp.resolve("workspace"))
        Files.createDirectory(root.resolve(".git"))
        val cwd = Files.createDirectories(root.resolve("src"))
        val outside = Files.createDirectories(temp.resolve("config"))
        val workspace = resolver.resolve(cwd)

        assertEquals(outside.toRealPath(), resolver.requireConfigOutsideWorkspace(outside, workspace))
        val internal = Files.createDirectory(root.resolve("internal-config"))
        assertFailsWith<WorkspaceResolutionException> {
            resolver.requireConfigOutsideWorkspace(internal, workspace)
        }
    }

    @Test
    fun `config directory precedence uses application then real environment then home and creates missing paths`(
        @TempDir temp: Path,
    ) {
        val home = Files.createDirectory(temp.resolve("home"))
        val workspaceRoot = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(workspaceRoot.resolve(".git"))
        val workspace = resolver.resolve(workspaceRoot)
        val environment = mapOf("KONDUCTOR_CONFIG_DIR" to "from-env")::get

        val explicit = resolver.resolveConfigDirectory(Path.of("explicit/nested"), environment, home, workspace)
        assertEquals(home.resolve("explicit/nested").toRealPath(), explicit.canonicalPath)

        val fromEnvironment = resolver.resolveConfigDirectory(null, environment, home, workspace)
        assertEquals(home.resolve("from-env").toRealPath(), fromEnvironment.canonicalPath)

        val byDefault = resolver.resolveConfigDirectory(null, { null }, home, workspace)
        assertEquals(home.resolve(".konductor").toRealPath(), byDefault.canonicalPath)
        assertTrue(Files.isDirectory(byDefault.canonicalPath))
    }

    @Test
    fun `invalid config environment path identifies its source`(@TempDir temp: Path) {
        val home = Files.createDirectory(temp.resolve("home"))
        val workspaceRoot = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(workspaceRoot.resolve(".git"))

        val failure = assertFailsWith<WorkspaceResolutionException> {
            resolver.resolveConfigDirectory(
                applicationInput = null,
                processEnvironment = { name -> if (name == "KONDUCTOR_CONFIG_DIR") "\u0000" else null },
                homeDirectory = home,
                workspace = resolver.resolve(workspaceRoot),
            )
        }

        assertEquals(Path.of("KONDUCTOR_CONFIG_DIR"), failure.path)
        assertTrue(failure.message.orEmpty().contains("Invalid path value"))
        assertTrue(failure.message.orEmpty().endsWith("environment variable: KONDUCTOR_CONFIG_DIR"))
    }

    @Test
    fun `config directory is rejected before creation when prospective path is inside workspace`(@TempDir temp: Path) {
        val home = Files.createDirectory(temp.resolve("home"))
        val workspaceRoot = Files.createDirectory(home.resolve("workspace"))
        Files.createDirectory(workspaceRoot.resolve(".git"))
        val workspace = resolver.resolve(workspaceRoot)
        val unsafe = workspaceRoot.resolve("not-created/config")

        assertFailsWith<WorkspaceResolutionException> {
            resolver.resolveConfigDirectory(unsafe, { null }, home, workspace)
        }
        assertTrue(Files.notExists(unsafe))
    }

    @Test
    fun `config directory rejects symlinked path components before target access`(@TempDir temp: Path) {
        val canonicalTemp = temp.toRealPath()
        val home = Files.createDirectory(canonicalTemp.resolve("home"))
        val workspaceRoot = Files.createDirectory(canonicalTemp.resolve("workspace"))
        Files.createDirectory(workspaceRoot.resolve(".git"))
        val outsideTarget = Files.createDirectory(canonicalTemp.resolve("outside-target"))
        val redirected = workspaceRoot.resolve("redirected")
        try {
            Files.createSymbolicLink(redirected, outsideTarget)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }

        assertFailsWith<WorkspaceResolutionException> {
            resolver.resolveConfigDirectory(redirected.resolve("config"), { null }, home, resolver.resolve(workspaceRoot))
        }
        assertTrue(Files.notExists(outsideTarget.resolve("config")))
    }

    @Test
    fun `cwd must exist and be a directory`(@TempDir temp: Path) {
        val file = Files.writeString(temp.resolve("file"), "x")
        assertFailsWith<WorkspaceResolutionException> { resolver.resolve(file) }
        assertFailsWith<WorkspaceResolutionException> { resolver.resolve(temp.resolve("missing")) }
    }
}

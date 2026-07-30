package com.konductor.config

import com.konductor.workspace.WorkspaceResolver
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceConfigurationLoaderTest {
    @Test
    fun `untrusted project files are never opened while global settings remain eligible`(@TempDir root: Path) {
        val home = Files.createDirectory(root.resolve("home"))
        val workspaceDir = Files.createDirectory(root.resolve("workspace"))
        Files.createDirectory(workspaceDir.resolve(".git"))
        Files.write(workspaceDir.resolve(".env"), byteArrayOf(0xC3.toByte(), 0x28))
        val projectConfig = Files.createDirectories(workspaceDir.resolve(".konductor"))
        Files.writeString(projectConfig.resolve("settings.json"), "{ malformed")
        val configDir = Files.createDirectory(home.resolve("config"))
        Files.writeString(configDir.resolve("settings.json"), """{ "provider": { "model": "global" } }""")
        val resolver = WorkspaceResolver()
        val workspace = resolver.resolve(workspaceDir)
        val config = resolver.resolveConfigDirectory(configDir, { null }, home, workspace)

        val loaded = WorkspaceConfigurationLoader().load(
            workspace,
            config,
            processEnvironment = mapOf(Configuration.ENV_PROJECT_ENDPOINT to "https://example.test")::get,
            projectSourcesTrusted = false,
        )

        assertEquals("global", Configuration.resolveCandidate(loaded.candidate).model)
    }

    @Test
    fun `trusted dotenv and settings use strict bounded reads and precedence`(@TempDir root: Path) {
        val home = Files.createDirectory(root.resolve("home"))
        val workspaceDir = Files.createDirectory(root.resolve("workspace"))
        Files.createDirectory(workspaceDir.resolve(".git"))
        Files.writeString(workspaceDir.resolve(".env"), "FOUNDRY_MODEL_NAME=dotenv\r\n")
        val projectConfig = Files.createDirectories(workspaceDir.resolve(".konductor"))
        Files.writeString(projectConfig.resolve("settings.json"), """{ "provider": { "model": "settings" } }""")
        val configDir = Files.createDirectory(home.resolve("config"))
        val resolver = WorkspaceResolver()
        val workspace = resolver.resolve(workspaceDir)
        val config = resolver.resolveConfigDirectory(configDir, { null }, home, workspace)

        val loaded = WorkspaceConfigurationLoader().load(
            workspace,
            config,
            processEnvironment = mapOf(Configuration.ENV_PROJECT_ENDPOINT to "https://example.test")::get,
            projectSourcesTrusted = true,
        )

        assertEquals("dotenv", Configuration.resolveCandidate(loaded.candidate).model)
        Files.write(workspaceDir.resolve(".env"), byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<ConfigurationException> {
            WorkspaceConfigurationLoader().load(
                workspace,
                config,
                processEnvironment = { null },
                projectSourcesTrusted = true,
            )
        }
    }

    @Test
    fun `trusted project file over bound is rejected`(@TempDir root: Path) {
        val home = Files.createDirectory(root.resolve("home"))
        val workspaceDir = Files.createDirectory(root.resolve("workspace"))
        Files.createDirectory(workspaceDir.resolve(".git"))
        Files.write(
            workspaceDir.resolve(".env"),
            ByteArray(WorkspaceConfigurationLoader.MAX_PROJECT_FILE_BYTES + 1) { 'x'.code.toByte() },
        )
        val configDir = Files.createDirectory(home.resolve("config"))
        val resolver = WorkspaceResolver()
        val workspace = resolver.resolve(workspaceDir)
        val config = resolver.resolveConfigDirectory(configDir, { null }, home, workspace)

        assertFailsWith<Exception> {
            WorkspaceConfigurationLoader().load(workspace, config, { null }, projectSourcesTrusted = true)
        }
    }
}

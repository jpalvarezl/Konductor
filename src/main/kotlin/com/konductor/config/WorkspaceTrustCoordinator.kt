package com.konductor.config

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Process-only trust override. Overrides never prompt or persist. */
enum class WorkspaceTrustOverride {
    None,
    Approve,
    NoApprove,
}

/** The four normal choices offered by an interactive frontend for valid, unknown trust. */
enum class WorkspaceTrustChoice(val decision: WorkspaceTrustDecision, val persistent: Boolean) {
    Trust(WorkspaceTrustDecision.Trusted, true),
    TrustForSession(WorkspaceTrustDecision.Trusted, false),
    DoNotTrust(WorkspaceTrustDecision.Untrusted, true),
    DoNotTrustForSession(WorkspaceTrustDecision.Untrusted, false),
}

/** Semantic coordinator outcomes; frontends own presentation and selection only. */
sealed interface WorkspaceTrustOutcome {
    val decision: WorkspaceTrustDecision?

    data class Saved(override val decision: WorkspaceTrustDecision) : WorkspaceTrustOutcome

    /** [warning] reports a failed persistent untrusted choice; project sources remain disabled. */
    data class SessionOnly(
        override val decision: WorkspaceTrustDecision,
        val warning: String? = null,
    ) : WorkspaceTrustOutcome

    data class Override(
        override val decision: WorkspaceTrustDecision,
        val override: WorkspaceTrustOverride,
    ) : WorkspaceTrustOutcome

    data class ChoiceRequired internal constructor(
        val workspaceRoot: Path,
        internal val accepted: WorkspaceTrustSnapshot.Valid,
        val defaultChoice: WorkspaceTrustChoice = WorkspaceTrustChoice.DoNotTrustForSession,
    ) : WorkspaceTrustOutcome {
        override val decision: WorkspaceTrustDecision? = null
    }

    /** Invalid/unreadable trust state. It is never silently repaired or overwritten. */
    data class Error(
        val snapshot: WorkspaceTrustSnapshot.Error,
        val mayContinueUntrustedForRun: Boolean,
    ) : WorkspaceTrustOutcome {
        override val decision: WorkspaceTrustDecision? = null
    }
}

/**
 * Applies process overrides, saved decisions, four normal TUI choices, and corrupt-store outcomes. Filesystem/store
 * policy stays here and in [WorkspaceTrustStore], never in a frontend. ACP uses [resolveNonInteractive].
 */
class WorkspaceTrustCoordinator(
    private val store: WorkspaceTrustStore,
) {
    /** Resolve TUI trust and lazily probe gated project sources only for a valid unknown workspace. */
    fun resolve(
        override: WorkspaceTrustOverride = WorkspaceTrustOverride.None,
        gatedProjectSourceProbe: () -> Boolean,
    ): WorkspaceTrustOutcome {
        if (override == WorkspaceTrustOverride.NoApprove) {
            return try {
                store.validateBoundary()
                WorkspaceTrustOutcome.Override(WorkspaceTrustDecision.Untrusted, override)
            } catch (error: Exception) {
                errorOutcome(error, mayContinue = false)
            }
        }

        return when (val snapshot = store.read()) {
            is WorkspaceTrustSnapshot.Error -> WorkspaceTrustOutcome.Error(
                snapshot,
                mayContinueUntrustedForRun = override == WorkspaceTrustOverride.None,
            )
            is WorkspaceTrustSnapshot.Valid -> {
                if (override == WorkspaceTrustOverride.Approve) {
                    WorkspaceTrustOutcome.Override(WorkspaceTrustDecision.Trusted, override)
                } else {
                    val saved = snapshot.decisionFor(store.workspaceRoot)
                    if (saved != null) {
                        WorkspaceTrustOutcome.Saved(saved)
                    } else {
                        val present = try {
                            gatedProjectSourceProbe()
                        } catch (error: Exception) {
                            return errorOutcome(error, mayContinue = false)
                        }
                        if (present) {
                            WorkspaceTrustOutcome.ChoiceRequired(store.workspaceRoot, snapshot)
                        } else {
                            WorkspaceTrustOutcome.SessionOnly(WorkspaceTrustDecision.Untrusted)
                        }
                    }
                }
            }
        }
    }

    /** Convenience TUI entry point with the contract's literal no-follow gated-file presence probe. */
    fun resolve(
        cwd: Path,
        override: WorkspaceTrustOverride = WorkspaceTrustOverride.None,
    ): WorkspaceTrustOutcome = resolve(override) { gatedProjectSourcePresent(cwd) }

    /** Compatibility/testing overload; production callers should use the lazy or cwd form. */
    fun resolve(
        override: WorkspaceTrustOverride = WorkspaceTrustOverride.None,
        gatedProjectSourcePresent: Boolean,
    ): WorkspaceTrustOutcome = resolve(override) { gatedProjectSourcePresent }

    /**
     * ACP/headless resolution never probes project files merely to decide whether to prompt: valid unknown is always
     * untrusted, errors reject the request, and no override ever writes.
     */
    fun resolveNonInteractive(
        override: WorkspaceTrustOverride = WorkspaceTrustOverride.None,
    ): WorkspaceTrustOutcome {
        if (override == WorkspaceTrustOverride.NoApprove) {
            return try {
                store.validateBoundary()
                WorkspaceTrustOutcome.Override(WorkspaceTrustDecision.Untrusted, override)
            } catch (error: Exception) {
                errorOutcome(error, mayContinue = false)
            }
        }
        return when (val snapshot = store.read()) {
            is WorkspaceTrustSnapshot.Error -> WorkspaceTrustOutcome.Error(snapshot, mayContinueUntrustedForRun = false)
            is WorkspaceTrustSnapshot.Valid -> when {
                override == WorkspaceTrustOverride.Approve ->
                    WorkspaceTrustOutcome.Override(WorkspaceTrustDecision.Trusted, override)
                else -> snapshot.decisionFor(store.workspaceRoot)?.let(WorkspaceTrustOutcome::Saved)
                    ?: WorkspaceTrustOutcome.SessionOnly(WorkspaceTrustDecision.Untrusted)
            }
        }
    }

    /** Compatibility overload. The presence fact is intentionally ignored because ACP never prompts. */
    @Suppress("UNUSED_PARAMETER")
    fun resolveNonInteractive(
        override: WorkspaceTrustOverride = WorkspaceTrustOverride.None,
        gatedProjectSourcePresent: Boolean,
    ): WorkspaceTrustOutcome = resolveNonInteractive(override)

    /** Apply one of the four normal choices. Session-only choices perform no store/lock/candidate write. */
    fun choose(
        required: WorkspaceTrustOutcome.ChoiceRequired,
        choice: WorkspaceTrustChoice,
    ): WorkspaceTrustOutcome {
        require(required.workspaceRoot == store.workspaceRoot) {
            "Trust choice belongs to ${required.workspaceRoot}, not ${store.workspaceRoot}."
        }
        if (!choice.persistent) return WorkspaceTrustOutcome.SessionOnly(choice.decision)

        return try {
            val saved = store.persist(required.accepted, choice.decision)
            WorkspaceTrustOutcome.Saved(
                saved.decisionFor(store.workspaceRoot)
                    ?: throw WorkspaceTrustStoreException("Persisted trust decision was absent after publication."),
            )
        } catch (error: Exception) {
            if (choice.decision == WorkspaceTrustDecision.Untrusted) {
                WorkspaceTrustOutcome.SessionOnly(
                    WorkspaceTrustDecision.Untrusted,
                    error.message ?: error.javaClass.simpleName,
                )
            } else {
                errorOutcome(error, mayContinue = true)
            }
        }
    }

    /** Dedicated corrupt-store repair-screen continuation. It never reads, repairs, or writes the store. */
    fun continueUntrustedForRun(error: WorkspaceTrustOutcome.Error): WorkspaceTrustOutcome.SessionOnly {
        require(error.mayContinueUntrustedForRun) { "This trust error cannot be bypassed without --no-approve." }
        return WorkspaceTrustOutcome.SessionOnly(WorkspaceTrustDecision.Untrusted)
    }

    private fun gatedProjectSourcePresent(cwd: Path): Boolean {
        val canonicalCwd = cwd.toRealPath()
        require(canonicalCwd == store.workspaceRoot || canonicalCwd.startsWith(store.workspaceRoot)) {
            "Project cwd $canonicalCwd is outside workspace ${store.workspaceRoot}."
        }
        if (literalEntryPresent(canonicalCwd.resolve(".env"))) return true
        val projectConfigEntry = canonicalCwd.resolve(".konductor")
        val configAttributes = attributesIfPresent(projectConfigEntry) ?: return false
        // A present invalid/symlinked project config entry still requires a decision; contents are not followed here.
        if (!configAttributes.isDirectory || configAttributes.isSymbolicLink) return true
        return literalEntryPresent(projectConfigEntry.resolve("settings.json"))
    }

    private fun literalEntryPresent(path: Path): Boolean = attributesIfPresent(path) != null

    private fun attributesIfPresent(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun errorOutcome(error: Exception, mayContinue: Boolean): WorkspaceTrustOutcome.Error =
        WorkspaceTrustOutcome.Error(
            WorkspaceTrustSnapshot.Error(store.storePath, error.message ?: error.javaClass.simpleName, error),
            mayContinueUntrustedForRun = mayContinue,
        )
}

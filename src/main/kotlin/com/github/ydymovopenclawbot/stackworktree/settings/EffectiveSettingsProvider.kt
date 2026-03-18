package com.github.ydymovopenclawbot.stackworktree.settings

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import java.nio.file.Path

/**
 * Resolves the *effective* [StackTreeSettings] for a project by merging the
 * IDE-level settings from [StackTreeSettingsService] with per-repo overrides
 * loaded from `.stacktree.yml` by [RepoSettingsLoader].
 *
 * ## Precedence (highest → lowest)
 * 1. `.stacktree.yml` key (repo-level override)
 * 2. IDE-level setting from [StackTreeSettingsService]
 *
 * ## Usage — production
 * ```kotlin
 * val settings = EffectiveSettingsProvider.get(project, repository)
 * ```
 *
 * ## Usage — tests
 * Pass pre-built objects to the pure [merge] function directly:
 * ```kotlin
 * val merged = EffectiveSettingsProvider.merge(ideSettings, override)
 * ```
 */
object EffectiveSettingsProvider {

    /**
     * Resolves effective settings for [project], optionally scoped to [repository].
     *
     * When [repository] is `null` (e.g. no VCS root detected), the IDE-level
     * settings are returned unchanged.
     */
    fun get(project: Project, repository: GitRepository? = null): StackTreeSettings {
        val ide = StackTreeSettingsService.getInstance(project).asSettings()
        val repoRoot = repository?.root?.toNioPath() ?: return ide
        return get(ide, repoRoot)
    }

    /**
     * Resolves effective settings given pre-built [ideSettings] and a [repoRoot] path.
     *
     * Useful when callers already have the IDE snapshot and want to apply a repo
     * override without triggering a service lookup.
     */
    fun get(ideSettings: StackTreeSettings, repoRoot: Path): StackTreeSettings {
        val override = RepoSettingsLoader.load(repoRoot) ?: return ideSettings
        return merge(ideSettings, override)
    }

    /**
     * Merges [ide] settings with [override], returning a new [StackTreeSettings]
     * where each field is the override value if non-null, or the IDE value otherwise.
     *
     * This function is pure (no I/O, no IntelliJ Platform dependency) and is the
     * primary unit-testable entry point for the merge logic.
     */
    fun merge(ide: StackTreeSettings, override: StackTreeSettingsOverride): StackTreeSettings =
        StackTreeSettings(
            worktreeBaseDir            = override.worktreeBaseDir            ?: ide.worktreeBaseDir,
            autoPruneOnMerge           = override.autoPruneOnMerge           ?: ide.autoPruneOnMerge,
            prNavigationComment        = override.prNavigationComment        ?: ide.prNavigationComment,
            aheadBehindRefreshInterval = override.aheadBehindRefreshInterval ?: ide.aheadBehindRefreshInterval,
            prPollInterval             = override.prPollInterval             ?: ide.prPollInterval,
            autoRestackOnSync          = override.autoRestackOnSync          ?: ide.autoRestackOnSync,
            branchNamingTemplate       = override.branchNamingTemplate       ?: ide.branchNamingTemplate,
        )
}

package com.github.ydymovopenclawbot.stackworktree.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-level service that persists user-configurable StackTree settings to
 * `.idea/stacktree-settings.xml`.
 *
 * ## Design
 * The inner [State] class is a mutable Java-bean so IntelliJ's XmlSerializer can
 * round-trip it without annotation magic.  A single [_state] instance is kept in
 * memory; [loadState] copies field values **in-place** rather than replacing the
 * object reference.  This ensures that Kotlin property references bound in
 * [StackTreeConfigurable] (via `_state::field`) remain valid after the IDE
 * deserialises new values on project open.
 *
 * ## Usage
 * ```kotlin
 * val svc = StackTreeSettingsService.getInstance(project)
 * val settings = svc.asSettings()   // read immutable snapshot
 * svc.state.prPollInterval = 120    // mutate (from Configurable.apply())
 * ```
 */
@Service(Service.Level.PROJECT)
@State(
    name     = "StackTreeSettings",
    storages = [Storage("stacktree-settings.xml")],
)
class StackTreeSettingsService : PersistentStateComponent<StackTreeSettingsService.State> {

    /**
     * Mutable bean serialised by IntelliJ's XmlSerializer.
     *
     * All properties must have defaults so that first-time users get a usable
     * out-of-the-box configuration.  Field names must match those in
     * [StackTreeSettings] so [asSettings] stays readable.
     */
    class State {
        var worktreeBaseDir: String = ""
        var autoPruneOnMerge: Boolean = true
        var prNavigationComment: Boolean = true
        var aheadBehindRefreshInterval: Int = 30
        var prPollInterval: Int = 60
        var autoRestackOnSync: Boolean = false
        var branchNamingTemplate: String = "{stack}/{index}-{description}"
    }

    /**
     * The single live [State] instance; never replaced after construction.
     *
     * Named `settingsState` (not `state`) to avoid a JVM signature clash with
     * the [getState] method required by [PersistentStateComponent]: Kotlin
     * auto-generates `getState()` for a `val state` property, which collides
     * with the interface method on the JVM.
     */
    val settingsState: State = State()

    // ── PersistentStateComponent ──────────────────────────────────────────────

    /**
     * Returns [settingsState] directly so XmlSerializer reads the current values.
     *
     * Note: unlike [com.github.ydymovopenclawbot.stackworktree.state.StackStateService],
     * we do **not** return a defensive copy here because [settingsState] is not
     * mutated concurrently — all mutations happen on the EDT inside [StackTreeConfigurable].
     */
    override fun getState(): State = settingsState

    /**
     * Copies deserialised field values into [settingsState] in-place.
     *
     * Replacing the reference would break Kotlin property references already
     * bound in [StackTreeConfigurable]'s panel.
     */
    override fun loadState(loaded: State) {
        settingsState.worktreeBaseDir             = loaded.worktreeBaseDir
        settingsState.autoPruneOnMerge            = loaded.autoPruneOnMerge
        settingsState.prNavigationComment         = loaded.prNavigationComment
        settingsState.aheadBehindRefreshInterval  = loaded.aheadBehindRefreshInterval
        settingsState.prPollInterval              = loaded.prPollInterval
        settingsState.autoRestackOnSync           = loaded.autoRestackOnSync
        settingsState.branchNamingTemplate        = loaded.branchNamingTemplate
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns an immutable [StackTreeSettings] snapshot of the current [settingsState].
     *
     * Prefer calling [EffectiveSettingsProvider.get] to account for per-repo
     * `.stacktree.yml` overrides before consuming settings.
     */
    fun asSettings(): StackTreeSettings = StackTreeSettings(
        worktreeBaseDir            = settingsState.worktreeBaseDir,
        autoPruneOnMerge           = settingsState.autoPruneOnMerge,
        prNavigationComment        = settingsState.prNavigationComment,
        aheadBehindRefreshInterval = settingsState.aheadBehindRefreshInterval,
        prPollInterval             = settingsState.prPollInterval,
        autoRestackOnSync          = settingsState.autoRestackOnSync,
        branchNamingTemplate       = settingsState.branchNamingTemplate,
    )

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        /** Retrieves the service instance for [project]. */
        fun getInstance(project: Project): StackTreeSettingsService =
            project.service()
    }
}

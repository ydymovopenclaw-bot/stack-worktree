package com.github.ydymovopenclawbot.stackworktree.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [EffectiveSettingsProvider.merge].
 *
 * Pure JVM — no IntelliJ Platform or filesystem required.
 * Tests cover the merge logic in isolation; [RepoSettingsLoaderTest] covers the
 * YAML parsing that produces the [StackTreeSettingsOverride] inputs.
 */
class EffectiveSettingsProviderTest {

    private val defaults = StackTreeSettings()  // all fields at their defaults

    // ── no override ───────────────────────────────────────────────────────────

    @Test
    fun `all-null override returns IDE settings unchanged`() {
        val override = StackTreeSettingsOverride()   // every field null
        assertEquals(defaults, EffectiveSettingsProvider.merge(defaults, override))
    }

    // ── full override ─────────────────────────────────────────────────────────

    @Test
    fun `all-set override replaces every IDE field`() {
        val override = StackTreeSettingsOverride(
            worktreeBaseDir            = "/custom/wt",
            autoPruneOnMerge           = false,
            prNavigationComment        = false,
            aheadBehindRefreshInterval = 15,
            prPollInterval             = 300,
            autoRestackOnSync          = true,
            branchNamingTemplate       = "feat/{index}",
        )
        val merged = EffectiveSettingsProvider.merge(defaults, override)

        assertEquals("/custom/wt",   merged.worktreeBaseDir)
        assertEquals(false,          merged.autoPruneOnMerge)
        assertEquals(false,          merged.prNavigationComment)
        assertEquals(15,             merged.aheadBehindRefreshInterval)
        assertEquals(300,            merged.prPollInterval)
        assertEquals(true,           merged.autoRestackOnSync)
        assertEquals("feat/{index}", merged.branchNamingTemplate)
    }

    // ── partial override ──────────────────────────────────────────────────────

    @Test
    fun `partial override replaces only the fields that are non-null`() {
        val override = StackTreeSettingsOverride(
            prPollInterval    = 300,
            autoRestackOnSync = true,
        )
        val merged = EffectiveSettingsProvider.merge(defaults, override)

        // Overridden fields
        assertEquals(300,  merged.prPollInterval)
        assertEquals(true, merged.autoRestackOnSync)

        // Non-overridden fields fall through to IDE defaults
        assertEquals(defaults.worktreeBaseDir,            merged.worktreeBaseDir)
        assertEquals(defaults.autoPruneOnMerge,           merged.autoPruneOnMerge)
        assertEquals(defaults.prNavigationComment,        merged.prNavigationComment)
        assertEquals(defaults.aheadBehindRefreshInterval, merged.aheadBehindRefreshInterval)
        assertEquals(defaults.branchNamingTemplate,       merged.branchNamingTemplate)
    }

    @Test
    fun `override of single boolean field leaves all others as IDE defaults`() {
        val override = StackTreeSettingsOverride(autoPruneOnMerge = false)
        val merged   = EffectiveSettingsProvider.merge(defaults, override)

        assertEquals(false,                          merged.autoPruneOnMerge)
        assertEquals(defaults.worktreeBaseDir,       merged.worktreeBaseDir)
        assertEquals(defaults.prNavigationComment,   merged.prNavigationComment)
        assertEquals(defaults.prPollInterval,        merged.prPollInterval)
        assertEquals(defaults.autoRestackOnSync,     merged.autoRestackOnSync)
        assertEquals(defaults.branchNamingTemplate,  merged.branchNamingTemplate)
    }

    // ── idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `merging same values as IDE defaults produces equal result`() {
        val sameAsDefaults = StackTreeSettingsOverride(
            worktreeBaseDir            = defaults.worktreeBaseDir,
            autoPruneOnMerge           = defaults.autoPruneOnMerge,
            prNavigationComment        = defaults.prNavigationComment,
            aheadBehindRefreshInterval = defaults.aheadBehindRefreshInterval,
            prPollInterval             = defaults.prPollInterval,
            autoRestackOnSync          = defaults.autoRestackOnSync,
            branchNamingTemplate       = defaults.branchNamingTemplate,
        )
        assertEquals(defaults, EffectiveSettingsProvider.merge(defaults, sameAsDefaults))
    }

    // ── custom IDE baseline ───────────────────────────────────────────────────

    @Test
    fun `override wins over non-default IDE value`() {
        val customIde = StackTreeSettings(prPollInterval = 45)
        val override  = StackTreeSettingsOverride(prPollInterval = 90)
        val merged    = EffectiveSettingsProvider.merge(customIde, override)
        assertEquals(90, merged.prPollInterval)
    }

    @Test
    fun `null override preserves non-default IDE value`() {
        val customIde = StackTreeSettings(prPollInterval = 45)
        val override  = StackTreeSettingsOverride()      // prPollInterval is null
        val merged    = EffectiveSettingsProvider.merge(customIde, override)
        assertEquals(45, merged.prPollInterval)
    }
}

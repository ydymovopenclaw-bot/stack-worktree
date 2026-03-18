package com.github.ydymovopenclawbot.stackworktree.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Unit tests for [RepoSettingsLoader].
 *
 * Pure JVM — no IntelliJ Platform runtime required.
 * Uses [@TempDir][TempDir] to create real filesystem fixtures so SnakeYAML
 * reads from actual files (the same path it would follow in production).
 */
class RepoSettingsLoaderTest {

    @TempDir
    lateinit var repoRoot: Path

    // ── absent file ───────────────────────────────────────────────────────────

    @Test
    fun `returns null when stacktree yml is absent`() {
        assertNull(RepoSettingsLoader.load(repoRoot))
    }

    // ── full file ─────────────────────────────────────────────────────────────

    @Test
    fun `parses all fields from a complete yml file`() {
        repoRoot.resolve(".stacktree.yml").writeText(
            """
            worktreeBaseDir: /workspace/worktrees
            autoPruneOnMerge: false
            prNavigationComment: false
            aheadBehindRefreshInterval: 15
            prPollInterval: 120
            autoRestackOnSync: true
            branchNamingTemplate: "feat/{index}/{description}"
            """.trimIndent()
        )

        val override = RepoSettingsLoader.load(repoRoot)
        assertNotNull(override)
        assertEquals("/workspace/worktrees", override!!.worktreeBaseDir)
        assertEquals(false,                  override.autoPruneOnMerge)
        assertEquals(false,                  override.prNavigationComment)
        assertEquals(15,                     override.aheadBehindRefreshInterval)
        assertEquals(120,                    override.prPollInterval)
        assertEquals(true,                   override.autoRestackOnSync)
        assertEquals("feat/{index}/{description}", override.branchNamingTemplate)
    }

    // ── partial file ──────────────────────────────────────────────────────────

    @Test
    fun `partial yml leaves absent fields as null`() {
        repoRoot.resolve(".stacktree.yml").writeText(
            """
            prPollInterval: 300
            autoRestackOnSync: true
            """.trimIndent()
        )

        val override = RepoSettingsLoader.load(repoRoot)
        assertNotNull(override)
        assertNull(override!!.worktreeBaseDir)
        assertNull(override.autoPruneOnMerge)
        assertNull(override.prNavigationComment)
        assertNull(override.aheadBehindRefreshInterval)
        assertEquals(300,  override.prPollInterval)
        assertEquals(true, override.autoRestackOnSync)
        assertNull(override.branchNamingTemplate)
    }

    // ── empty file ────────────────────────────────────────────────────────────

    @Test
    fun `empty yml file returns null (no mappings)`() {
        repoRoot.resolve(".stacktree.yml").writeText("")
        assertNull(RepoSettingsLoader.load(repoRoot))
    }

    // ── malformed yaml ────────────────────────────────────────────────────────

    @Test
    fun `malformed yml returns null without throwing`() {
        repoRoot.resolve(".stacktree.yml").writeText(
            """
            this: is: invalid: yaml: [
            """.trimIndent()
        )
        // Must not propagate; loader swallows and logs the exception.
        assertNull(RepoSettingsLoader.load(repoRoot))
    }

    // ── numeric type coercion ─────────────────────────────────────────────────

    @Test
    fun `integer fields accept whole-number values`() {
        repoRoot.resolve(".stacktree.yml").writeText(
            """
            aheadBehindRefreshInterval: 45
            prPollInterval: 180
            """.trimIndent()
        )

        val override = RepoSettingsLoader.load(repoRoot)
        assertNotNull(override)
        assertEquals(45,  override!!.aheadBehindRefreshInterval)
        assertEquals(180, override.prPollInterval)
    }
}

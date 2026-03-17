package com.github.ydymovopenclawbot.stackworktree.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-unit tests for [GitLayerImpl.parsePorcelain].
 *
 * No IntelliJ platform or git process is required — [GitLayerImpl.parsePorcelain]
 * is a stateless companion-object function so it can be exercised in any JVM process.
 */
class PorcelainParserTest {

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    fun `empty input returns empty list`() {
        val result = GitLayerImpl.parsePorcelain(emptyList())
        assertTrue(result.isEmpty(), "empty input should yield no worktrees")
    }

    @Test
    fun `single blank line returns empty list`() {
        val result = GitLayerImpl.parsePorcelain(listOf(""))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parses main worktree with branch`() {
        val lines = listOf(
            "worktree /home/user/repo",
            "HEAD abc1234567890abcdef1234567890abcdef12345678",
            "branch refs/heads/main",
            "",
        )
        val result = GitLayerImpl.parsePorcelain(lines)
        assertEquals(1, result.size)
        val wt = result[0]
        assertEquals("/home/user/repo", wt.path)
        assertEquals("main", wt.branch)
        assertEquals("abc1234567890abcdef1234567890abcdef12345678", wt.head)
        assertFalse(wt.isLocked)
        assertTrue(wt.isMain, "First block should be marked isMain=true")
    }

    @Test
    fun `parses two worktrees with correct isMain flags`() {
        val lines = listOf(
            "worktree /home/user/repo",
            "HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "branch refs/heads/main",
            "",
            "worktree /home/user/repo-feature",
            "HEAD bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "branch refs/heads/feature-x",
            "",
        )
        val result = GitLayerImpl.parsePorcelain(lines)
        assertEquals(2, result.size)
        assertTrue(result[0].isMain, "First block must be isMain=true")
        assertFalse(result[1].isMain, "Second block must be isMain=false")
        assertEquals("main", result[0].branch)
        assertEquals("feature-x", result[1].branch)
    }

    // -------------------------------------------------------------------------
    // Detached HEAD
    // -------------------------------------------------------------------------

    @Test
    fun `detached HEAD produces empty branch string`() {
        val lines = listOf(
            "worktree /home/user/repo-detached",
            "HEAD cccccccccccccccccccccccccccccccccccccccc",
            "detached",
            "",
        )
        val result = GitLayerImpl.parsePorcelain(lines)
        assertEquals(1, result.size)
        assertEquals("", result[0].branch, "Detached HEAD should yield empty branch")
    }

    // -------------------------------------------------------------------------
    // Bare worktree
    // -------------------------------------------------------------------------

    @Test
    fun `bare worktree entry is handled explicitly and yields empty branch`() {
        val lines = listOf(
            "worktree /home/user/repo.git",
            "HEAD dddddddddddddddddddddddddddddddddddddddd",
            "bare",
            "",
        )
        val result = GitLayerImpl.parsePorcelain(lines)
        assertEquals(1, result.size, "Bare worktree block should produce exactly one Worktree")
        assertEquals("", result[0].branch, "Bare worktree should yield empty branch string")
        assertEquals("/home/user/repo.git", result[0].path)
        // Bare worktrees still populate the HEAD hash.
        assertEquals("dddddddddddddddddddddddddddddddddddddddd", result[0].head)
    }

    @Test
    fun `bare worktree mixed with regular worktree`() {
        val lines = listOf(
            "worktree /home/user/repo",
            "HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "branch refs/heads/main",
            "",
            "worktree /home/user/repo.git",
            "HEAD bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "bare",
            "",
        )
        val result = GitLayerImpl.parsePorcelain(lines)
        assertEquals(2, result.size)
        assertEquals("main", result[0].branch)
        assertEquals("", result[1].branch)
        assertTrue(result[0].isMain)
        assertFalse(result[1].isMain)
    }

    // -------------------------------------------------------------------------
    // Locked worktree
    // -------------------------------------------------------------------------

    @Test
    fun `locked worktree sets isLocked flag`() {
        val lines = listOf(
            "worktree /home/user/repo",
            "HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "branch refs/heads/main",
            "",
            "worktree /home/user/repo-locked",
            "HEAD bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "branch refs/heads/feature",
            "locked reason text",
            "",
        )
        val result = GitLayerImpl.parsePorcelain(lines)
        assertEquals(2, result.size)
        assertFalse(result[0].isLocked)
        assertTrue(result[1].isLocked)
    }

    // -------------------------------------------------------------------------
    // Non-heads ref (e.g. detached via refs/tags/)
    // -------------------------------------------------------------------------

    @Test
    fun `branch ref outside refs-heads is preserved verbatim`() {
        val lines = listOf(
            "worktree /home/user/repo-tag",
            "HEAD cccccccccccccccccccccccccccccccccccccccc",
            "branch refs/tags/v1.0",
            "",
        )
        val result = GitLayerImpl.parsePorcelain(lines)
        assertEquals(1, result.size)
        // refs/heads/ prefix is stripped; refs/tags/ is NOT a known prefix so the
        // full tag ref is retained after stripping "refs/heads/" (which is a no-op here).
        assertEquals("refs/tags/v1.0", result[0].branch)
    }
}

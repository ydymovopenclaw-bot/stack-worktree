package com.github.ydymovopenclawbot.stackworktree.git

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.io.path.createTempDirectory

/**
 * Integration tests for [GitLayerImpl] against real git repos created in temp
 * directories. Each test gets a fresh repository with one empty commit so that
 * `git worktree add` has a valid HEAD to check out.
 *
 * Extends [BasePlatformTestCase] because [git4idea.commands.GitLineHandler]
 * requires a live [com.intellij.openapi.project.Project] instance.
 */
class GitLayerImplTest : BasePlatformTestCase() {

    private lateinit var repoDir: java.io.File
    private lateinit var gitLayer: GitLayerImpl

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun setUp() {
        super.setUp()
        repoDir = createTempDirectory("worktree-test-").toFile()
        exec("git", "init", repoDir.absolutePath)
        exec("git", "-C", repoDir.absolutePath, "config", "user.email", "test@test.com")
        exec("git", "-C", repoDir.absolutePath, "config", "user.name", "Test")
        // An initial commit is required before `git worktree add` can work.
        exec("git", "-C", repoDir.absolutePath, "commit", "--allow-empty", "-m", "init")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)
            ?: error("VirtualFile not found for $repoDir")
        gitLayer = GitLayerImpl(project, vf)
    }

    override fun tearDown() {
        repoDir.deleteRecursively()
        super.tearDown()
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    fun testWorktreeListContainsMain() {
        val list = gitLayer.worktreeList()
        assertTrue("Expected at least the main worktree", list.isNotEmpty())
        val main = list.first()
        assertEquals(repoDir.canonicalPath, java.io.File(main.path).canonicalPath)
        assertFalse("Main worktree HEAD must not be empty", main.head.isEmpty())
    }

    fun testWorktreeAddAndList() {
        val wtDir = createTempDirectory("worktree-linked-").toFile()
        try {
            val wt = gitLayer.worktreeAdd(wtDir.absolutePath, "feature-test")
            assertEquals(wtDir.canonicalPath, java.io.File(wt.path).canonicalPath)
            assertEquals("feature-test", wt.branch)
            assertFalse("HEAD SHA must not be empty", wt.head.isEmpty())
            assertFalse(wt.isLocked)

            val listed = gitLayer.worktreeList()
            assertTrue(listed.any { java.io.File(it.path).canonicalPath == wtDir.canonicalPath })
        } finally {
            wtDir.deleteRecursively()
        }
    }

    fun testWorktreeRemove() {
        val wtDir = createTempDirectory("worktree-remove-").toFile()
        try {
            gitLayer.worktreeAdd(wtDir.absolutePath, "branch-to-remove")
            gitLayer.worktreeRemove(wtDir.absolutePath)
            val listed = gitLayer.worktreeList()
            assertFalse(listed.any { java.io.File(it.path).canonicalPath == wtDir.canonicalPath })
        } finally {
            wtDir.deleteRecursively()
        }
    }

    fun testWorktreePrune() {
        // Prune on a clean repo must succeed without throwing.
        gitLayer.worktreePrune()
    }

    fun testWorktreeAddDuplicateThrows() {
        val wtDir = createTempDirectory("worktree-dup-").toFile()
        try {
            gitLayer.worktreeAdd(wtDir.absolutePath, "branch-dup")
            assertThrows(WorktreeAlreadyExistsException::class.java) {
                // Same path → git rejects it as already existing.
                gitLayer.worktreeAdd(wtDir.absolutePath, "branch-dup-2")
            }
        } finally {
            wtDir.deleteRecursively()
        }
    }

    fun testWorktreeRemoveNonExistentThrows() {
        assertThrows(WorktreeNotFoundException::class.java) {
            gitLayer.worktreeRemove("/tmp/nonexistent-worktree-path-that-does-not-exist")
        }
    }

    fun testWorktreeRemoveLockedThrows() {
        val wtDir = createTempDirectory("worktree-locked-").toFile()
        try {
            gitLayer.worktreeAdd(wtDir.absolutePath, "branch-locked")
            // Lock the worktree via plain git so we don't depend on our own API.
            exec("git", "-C", repoDir.absolutePath, "worktree", "lock", wtDir.absolutePath)
            assertThrows(WorktreeIsLockedException::class.java) {
                gitLayer.worktreeRemove(wtDir.absolutePath)
            }
        } finally {
            // Unlock so tearDown can clean up cleanly.
            exec("git", "-C", repoDir.absolutePath, "worktree", "unlock", wtDir.absolutePath)
            wtDir.deleteRecursively()
        }
    }

    fun testPorcelainParserDetachedHead() {
        val wtDir = createTempDirectory("worktree-detached-").toFile()
        try {
            // `--detach` checks out HEAD as a detached HEAD — no branch ref.
            exec("git", "-C", repoDir.absolutePath, "worktree", "add", "--detach",
                wtDir.absolutePath)
            val listed = gitLayer.worktreeList()
            val detached = listed.first { java.io.File(it.path).canonicalPath == wtDir.canonicalPath }
            assertEquals("Detached HEAD should produce empty branch string", "", detached.branch)
            assertFalse("HEAD SHA must not be empty for detached worktree", detached.head.isEmpty())
        } finally {
            wtDir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Runs an external command synchronously and asserts a zero exit code.
     * Used only for test fixture setup/teardown, not under test.
     */
    private fun exec(vararg cmd: String) {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        assertEquals(
            "Command ${cmd.toList()} failed (exit $exit):\n$output",
            0, exit
        )
    }
}

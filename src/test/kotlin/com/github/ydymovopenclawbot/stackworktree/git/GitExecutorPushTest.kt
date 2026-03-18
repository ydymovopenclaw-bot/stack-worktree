package com.github.ydymovopenclawbot.stackworktree.git

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for [GitExecutor.push] using a real bare repository as the remote
 * and a full clone as the local working directory.
 *
 * Fixture layout (both under a single [rootDir] TempDir):
 * - `bare/`  — bare repository acting as the remote ("origin")
 * - `clone/` — full clone of `bare/`, where GitExecutor runs
 *
 * This mirrors how [GitExecutorTest] tests the other GitExecutor operations against a
 * real [ProcessGitRunner] without needing the IntelliJ platform runtime.
 */
class GitExecutorPushTest {

    @TempDir
    lateinit var rootDir: Path

    private lateinit var bareDir: Path
    private lateinit var cloneDir: Path
    private lateinit var executor: GitExecutor

    /** Runs a git command in [workDir]; asserts success for fixture setup. */
    private fun git(workDir: Path, vararg args: String) {
        val result = ProcessGitRunner().run(workDir, args.toList())
        check(result.isSuccess) {
            "Test fixture git command failed: git ${args.toList()} — ${result.stderr}"
        }
    }

    @BeforeEach
    fun setup() {
        bareDir  = rootDir.resolve("bare")
        cloneDir = rootDir.resolve("clone")

        // 1. Create a bare repo to act as "origin"
        git(rootDir, "init", "--bare", "-b", "main", bareDir.toString())

        // 2. Clone into a working directory so we have a local repo with a remote
        git(rootDir, "clone", bareDir.toString(), cloneDir.toString())

        // 3. Configure identity inside the clone
        git(cloneDir, "config", "user.email", "test@example.com")
        git(cloneDir, "config", "user.name", "Test User")

        // 4. Commit something so the branch has a tip to push
        git(cloneDir, "commit", "--allow-empty", "-m", "initial")

        executor = GitExecutor(cloneDir, ProcessGitRunner())
    }

    // -------------------------------------------------------------------------
    // push — basic upstream
    // -------------------------------------------------------------------------

    @Test
    fun `push sends branch to remote and succeeds`() = runTest {
        val result = executor.push("main")
        assertTrue(result.isSuccess, "Expected push to succeed but got: ${result.exceptionOrNull()?.message}")
    }

    @Test
    fun `push sets upstream so the remote ref is created`() = runTest {
        executor.push("main").getOrThrow()

        // Verify the remote has the branch by asking the bare repo for its refs
        val bare = GitExecutor(bareDir, ProcessGitRunner())
        val branches = bare.branchList().getOrThrow()
        assertTrue("main" in branches, "Expected 'main' to exist in remote after push, got: $branches")
    }

    @Test
    fun `push of a feature branch creates the remote ref`() = runTest {
        // First push main so bare has at least one commit
        executor.push("main").getOrThrow()

        // Create a feature branch locally with a new commit
        git(cloneDir, "checkout", "-b", "feature/xyz")
        git(cloneDir, "commit", "--allow-empty", "-m", "feature commit")

        val result = executor.push("feature/xyz")
        assertTrue(result.isSuccess, "Expected feature branch push to succeed")

        val bare = GitExecutor(bareDir, ProcessGitRunner())
        val branches = bare.branchList().getOrThrow()
        assertTrue("feature/xyz" in branches, "Expected 'feature/xyz' in remote after push, got: $branches")
    }

    // -------------------------------------------------------------------------
    // push --force-with-lease
    // -------------------------------------------------------------------------

    @Test
    fun `push with forceWithLease succeeds when remote tip matches local fetch`() = runTest {
        // First push to establish the remote tracking ref
        executor.push("main").getOrThrow()

        // Add another commit locally; remote tip still matches what we fetched
        git(cloneDir, "commit", "--allow-empty", "-m", "second commit")

        val result = executor.push("main", forceWithLease = true)
        assertTrue(result.isSuccess, "Expected --force-with-lease push to succeed: ${result.exceptionOrNull()?.message}")
    }

    // -------------------------------------------------------------------------
    // push — failure cases
    // -------------------------------------------------------------------------

    @Test
    fun `push of non-existent branch returns failure`() = runTest {
        val result = executor.push("branch-that-does-not-exist")
        assertFalse(result.isSuccess, "Expected push of unknown branch to fail")
        val ex = result.exceptionOrNull()
        assertTrue(ex is GitException, "Expected GitException but got ${ex?.javaClass}")
        assertTrue(ex.message!!.isNotBlank(), "Expected non-blank error message")
    }

    @Test
    fun `push to non-existent remote returns failure`() = runTest {
        // Push main to an invented remote name — should fail
        val result = executor.push("main", remote = "no-such-remote")
        assertFalse(result.isSuccess, "Expected push to unknown remote to fail")
        val ex = result.exceptionOrNull()
        assertTrue(ex is GitException, "Expected GitException but got ${ex?.javaClass}")
    }

    // -------------------------------------------------------------------------
    // push — remote URL verification helper
    // -------------------------------------------------------------------------

    @Test
    fun `remote origin URL points to the bare repo`() = runTest {
        val url = executor.remoteUrl("origin").getOrThrow()
        assertEquals(bareDir.toString(), url)
    }
}

package com.github.ydymovopenclawbot.stackworktree.git

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitExecutorTest {

    @TempDir
    lateinit var repoDir: Path
    private lateinit var executor: GitExecutor

    /** Runs a git command against [repoDir] for test fixture setup. */
    private fun git(vararg args: String) {
        val result = ProcessGitRunner().run(repoDir, args.toList())
        check(result.isSuccess) {
            "Test fixture git command failed: git ${args.toList()} — ${result.stderr}"
        }
    }

    @BeforeEach
    fun setup() {
        git("init", "-b", "main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test User")
        git("commit", "--allow-empty", "-m", "initial")
        executor = GitExecutor(repoDir, ProcessGitRunner())
    }

    // -------------------------------------------------------------------------
    // revParse
    // -------------------------------------------------------------------------

    @Test
    fun `revParse resolves HEAD to 40-char SHA`() = runTest {
        val result = executor.revParse("HEAD")
        assertTrue(result.isSuccess, "Expected success but got: ${result.exceptionOrNull()?.message}")
        assertEquals(40, result.getOrThrow().length)
    }

    @Test
    fun `revParse returns failure with message for nonexistent ref`() = runTest {
        val result = executor.revParse("nonexistent-ref-xyz")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.isNotBlank())
    }

    @Test
    fun `revParse returns failure outside a git repo`() = runTest {
        // Must be a path that is NOT inside repoDir, so git cannot walk up to find a .git dir.
        val notARepo = java.nio.file.Files.createTempDirectory("no-git-repo-test")
        try {
            val outsideExecutor = GitExecutor(notARepo, ProcessGitRunner())
            val result = outsideExecutor.revParse("HEAD")
            assertTrue(result.isFailure)
        } finally {
            notARepo.toFile().deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // revList
    // -------------------------------------------------------------------------

    @Test
    fun `revList returns empty list for identical refs`() = runTest {
        val list = executor.revList("HEAD", "HEAD").getOrThrow()
        assertTrue(list.isEmpty())
    }

    @Test
    fun `revList returns commits between two refs`() = runTest {
        git("commit", "--allow-empty", "-m", "second")
        // HEAD~1..HEAD should contain exactly the second commit
        val list = executor.revList("HEAD~1", "HEAD").getOrThrow()
        assertEquals(1, list.size)
        assertEquals(40, list[0].length)
    }

    // -------------------------------------------------------------------------
    // log
    // -------------------------------------------------------------------------

    @Test
    fun `log returns one entry for single-commit repo`() = runTest {
        val entries = executor.log("main", 10).getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("initial", entries[0].subject)
        assertEquals(40, entries[0].hash.length)
        assertTrue(entries[0].authorDate.isNotBlank())
    }

    @Test
    fun `log respects n limit`() = runTest {
        repeat(5) { i -> git("commit", "--allow-empty", "-m", "commit-$i") }
        val entries = executor.log("main", 3).getOrThrow()
        assertEquals(3, entries.size)
    }

    @Test
    fun `log subject survives special characters`() = runTest {
        git("commit", "--allow-empty", "-m", "fix: handle a/b, c|d and e\u001f separators")
        val entries = executor.log("main", 1).getOrThrow()
        assertTrue(entries[0].subject.startsWith("fix:"))
    }

    // -------------------------------------------------------------------------
    // branchList
    // -------------------------------------------------------------------------

    @Test
    fun `branchList contains main`() = runTest {
        val branches = executor.branchList().getOrThrow()
        assertTrue(branches.contains("main"), "Expected 'main' in $branches")
    }

    @Test
    fun `branchList includes newly created branch`() = runTest {
        git("branch", "feature-x")
        val branches = executor.branchList().getOrThrow()
        assertTrue(branches.containsAll(listOf("main", "feature-x")))
    }

    // -------------------------------------------------------------------------
    // currentBranch
    // -------------------------------------------------------------------------

    @Test
    fun `currentBranch returns main`() = runTest {
        assertEquals("main", executor.currentBranch().getOrThrow())
    }

    @Test
    fun `currentBranch returns empty string in detached HEAD`() = runTest {
        val sha = executor.revParse("HEAD").getOrThrow()
        git("checkout", "--detach", sha)
        val branch = executor.currentBranch().getOrThrow()
        assertEquals("", branch)
    }

    // -------------------------------------------------------------------------
    // remoteUrl
    // -------------------------------------------------------------------------

    @Test
    fun `remoteUrl returns failure when no remote configured`() = runTest {
        val result = executor.remoteUrl()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.isNotBlank())
    }

    @Test
    fun `remoteUrl returns configured origin URL`() = runTest {
        git("remote", "add", "origin", "https://github.com/example/repo.git")
        assertEquals("https://github.com/example/repo.git", executor.remoteUrl().getOrThrow())
    }

    @Test
    fun `remoteUrl accepts custom remote name`() = runTest {
        git("remote", "add", "upstream", "https://github.com/upstream/repo.git")
        assertEquals(
            "https://github.com/upstream/repo.git",
            executor.remoteUrl("upstream").getOrThrow(),
        )
    }
}

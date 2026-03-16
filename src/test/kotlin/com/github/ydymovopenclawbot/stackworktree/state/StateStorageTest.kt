package com.github.ydymovopenclawbot.stackworktree.state

import com.github.ydymovopenclawbot.stackworktree.git.ProcessGitRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateStorageTest {

    @TempDir
    lateinit var repoDir: Path

    private lateinit var storage: StateStorage

    private val defaultConfig = RepoConfig(trunk = "main", remote = "origin")

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
        // An initial commit is required so the repo is not empty; hash-object/mktree/
        // commit-tree work on any non-bare repo with or without commits, but having an
        // initial commit makes rev-parse behave predictably in all test scenarios.
        git("commit", "--allow-empty", "-m", "initial")
        storage = StateStorage(repoDir, ProcessGitRunner())
    }

    // -------------------------------------------------------------------------
    // exists()
    // -------------------------------------------------------------------------

    @Test
    fun `exists returns false on fresh repo with no stacktree ref`() {
        assertFalse(storage.exists())
    }

    @Test
    fun `exists returns true after first write`() {
        storage.write(StackState(repoConfig = defaultConfig))
        assertTrue(storage.exists())
    }

    // -------------------------------------------------------------------------
    // read()
    // -------------------------------------------------------------------------

    @Test
    fun `read returns null when ref is missing`() {
        assertNull(storage.read())
    }

    @Test
    fun `write then read returns identical StackState`() {
        val original = StackState(
            repoConfig = defaultConfig,
            branches = mapOf(
                "main" to BranchNode(name = "main", parent = null),
                "feat/base" to BranchNode(
                    name = "feat/base",
                    parent = "main",
                    children = listOf("feat/top"),
                    worktreePath = "/tmp/feat-base",
                    health = BranchHealth.CLEAN,
                ),
                "feat/top" to BranchNode(
                    name = "feat/top",
                    parent = "feat/base",
                    worktreePath = "/tmp/feat-top",
                    health = BranchHealth.NEEDS_REBASE,
                ),
            ),
        )

        storage.write(original)
        val loaded = storage.read()

        assertNotNull(loaded)
        assertEquals(original.repoConfig, loaded.repoConfig)
        assertEquals(original.branches.size, loaded.branches.size)
        assertEquals(original.branches["feat/base"]?.worktreePath, loaded.branches["feat/base"]?.worktreePath)
        assertEquals(original.branches["feat/top"]?.health, loaded.branches["feat/top"]?.health)
    }

    @Test
    fun `write then read round-trips empty StackState`() {
        val empty = StackState(repoConfig = defaultConfig)
        storage.write(empty)
        val loaded = storage.read()
        assertNotNull(loaded)
        assertEquals(0, loaded.branches.size)
        assertEquals(defaultConfig, loaded.repoConfig)
    }

    @Test
    fun `second write overwrites state and read returns latest`() {
        storage.write(StackState(repoConfig = RepoConfig(trunk = "v1", remote = "origin")))
        storage.write(StackState(repoConfig = RepoConfig(trunk = "v2", remote = "origin")))
        assertEquals("v2", storage.read()!!.repoConfig.trunk)
    }

    // -------------------------------------------------------------------------
    // commit chain
    // -------------------------------------------------------------------------

    @Test
    fun `multiple writes create a commit chain in the ref`() {
        storage.write(StackState(repoConfig = RepoConfig(trunk = "first", remote = "origin")))
        storage.write(StackState(repoConfig = RepoConfig(trunk = "second", remote = "origin")))

        // The commit pointed to by the ref must have a "parent" line, proving
        // the second commit chains the first as its parent.
        val commitText = ProcessGitRunner()
            .run(repoDir, listOf("cat-file", "-p", "refs/stacktree/state"))
        assertTrue(commitText.isSuccess)
        assertTrue(
            commitText.stdout.lines().any { it.startsWith("parent ") },
            "Expected a 'parent' line in commit object but got:\n${commitText.stdout}",
        )
    }

    @Test
    fun `ref log grows by one entry per write`() {
        storage.write(StackState(repoConfig = RepoConfig(trunk = "a", remote = "origin")))
        storage.write(StackState(repoConfig = RepoConfig(trunk = "b", remote = "origin")))
        storage.write(StackState(repoConfig = RepoConfig(trunk = "c", remote = "origin")))

        // git log on the ref should show exactly 3 commits (the chain length)
        val log = ProcessGitRunner()
            .run(repoDir, listOf("log", "--oneline", "refs/stacktree/state"))
        assertTrue(log.isSuccess)
        val commitCount = log.stdout.lines().count(String::isNotBlank)
        assertEquals(3, commitCount)
    }
}

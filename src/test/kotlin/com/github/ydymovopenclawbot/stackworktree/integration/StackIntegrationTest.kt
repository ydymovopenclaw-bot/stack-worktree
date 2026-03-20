package com.github.ydymovopenclawbot.stackworktree.integration

import com.github.ydymovopenclawbot.stackworktree.git.ProcessGitRunner
import com.github.ydymovopenclawbot.stackworktree.state.BranchHealth
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StateCorruptedException
import com.github.ydymovopenclawbot.stackworktree.state.StateStorage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the critical stack-management path:
 *   create stack → add branch → record state → verify persistence → lifecycle operations
 *
 * All tests use [ProcessGitRunner] against a real git repository created in a [TempDir],
 * so they run without the IntelliJ Platform and can be executed in plain JUnit 5.
 */
class StackIntegrationTest {

    @TempDir
    lateinit var repoDir: Path

    private lateinit var storage: StateStorage
    private val runner = ProcessGitRunner()
    private val defaultConfig = RepoConfig(trunk = "main", remote = "origin")

    /** Runs a git command against [repoDir] and asserts success. */
    private fun git(vararg args: String) {
        val result = runner.run(repoDir, args.toList())
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
        storage = StateStorage(repoDir, runner)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Create stack and persist via StateStorage
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `create stack with one branch and read back yields identical state`() {
        val state = StackState(
            repoConfig = defaultConfig,
            branches = mapOf(
                "main" to BranchNode(name = "main", parent = null),
                "feature/login" to BranchNode(
                    name = "feature/login",
                    parent = "main",
                    health = BranchHealth.CLEAN,
                ),
            ),
        )

        storage.write(state)
        val loaded = storage.read()

        assertNotNull(loaded)
        assertEquals(defaultConfig, loaded.repoConfig)
        assertEquals(2, loaded.branches.size)
        assertEquals("main", loaded.branches["feature/login"]?.parent)
        assertEquals(BranchHealth.CLEAN, loaded.branches["feature/login"]?.health)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Add branch records worktree path in state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `add branch with worktree path persists and is readable`() {
        val worktreePath = "/tmp/feature-auth-worktree"

        val state = StackState(
            repoConfig = defaultConfig,
            branches = mapOf(
                "main" to BranchNode(name = "main", parent = null),
                "feature/auth" to BranchNode(
                    name = "feature/auth",
                    parent = "main",
                    worktreePath = worktreePath,
                    health = BranchHealth.CLEAN,
                ),
            ),
        )

        storage.write(state)
        val loaded = storage.read()

        assertNotNull(loaded)
        val authNode = loaded.branches["feature/auth"]
        assertNotNull(authNode)
        assertEquals(worktreePath, authNode.worktreePath)
        assertEquals("main", authNode.parent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Multi-branch stack round-trips correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `multi-branch stack with four levels round-trips through state storage`() {
        val state = StackState(
            repoConfig = RepoConfig(trunk = "main", remote = "upstream"),
            branches = mapOf(
                "main" to BranchNode(
                    name = "main",
                    parent = null,
                    children = listOf("feat/a"),
                ),
                "feat/a" to BranchNode(
                    name = "feat/a",
                    parent = "main",
                    children = listOf("feat/b"),
                    worktreePath = "/tmp/wt-a",
                    health = BranchHealth.CLEAN,
                ),
                "feat/b" to BranchNode(
                    name = "feat/b",
                    parent = "feat/a",
                    children = listOf("feat/c"),
                    worktreePath = "/tmp/wt-b",
                    health = BranchHealth.NEEDS_REBASE,
                ),
                "feat/c" to BranchNode(
                    name = "feat/c",
                    parent = "feat/b",
                    worktreePath = "/tmp/wt-c",
                    health = BranchHealth.NEEDS_REBASE,
                ),
            ),
        )

        storage.write(state)
        val loaded = storage.read()

        assertNotNull(loaded)
        assertEquals("upstream", loaded.repoConfig.remote)
        assertEquals(4, loaded.branches.size)
        assertEquals(listOf("feat/b"), loaded.branches["feat/a"]?.children)
        assertEquals("/tmp/wt-b", loaded.branches["feat/b"]?.worktreePath)
        assertEquals(BranchHealth.NEEDS_REBASE, loaded.branches["feat/c"]?.health)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: State updates form an auditable commit chain
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `three sequential writes create a three-commit chain in refs stacktree state`() {
        storage.write(StackState(repoConfig = RepoConfig(trunk = "v1", remote = "origin")))
        storage.write(StackState(repoConfig = RepoConfig(trunk = "v2", remote = "origin")))
        storage.write(StackState(repoConfig = RepoConfig(trunk = "v3", remote = "origin")))

        // Verify the ref points to exactly 3 commits in history
        val log = runner.run(repoDir, listOf("log", "--oneline", "refs/stacktree/state"))
        assertTrue(log.isSuccess)
        val commitCount = log.stdout.lines().count(String::isNotBlank)
        assertEquals(3, commitCount, "Expected 3 commits in the chain but got $commitCount")

        // The tip should reflect the most recent write
        assertEquals("v3", storage.read()!!.repoConfig.trunk)

        // The second commit must have a 'parent' line (chains)
        val commitObj = runner.run(repoDir, listOf("cat-file", "-p", "refs/stacktree/state"))
        assertTrue(commitObj.stdout.lines().any { it.startsWith("parent ") })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: Corrupted JSON triggers StateCorruptedException
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `corrupted JSON blob in state ref causes StateCorruptedException on read`() {
        // Write a valid state first so the ref exists.
        storage.write(StackState(repoConfig = defaultConfig))

        // Overwrite the blob with invalid JSON using git plumbing directly.
        val badJson = "{ this is not valid JSON !!!"
        val blobSha = runner.run(repoDir, listOf("hash-object", "-w", "--stdin")).let { r ->
            // Use ProcessBuilder to pipe stdin
            val pb = ProcessBuilder(listOf("git", "hash-object", "-w", "--stdin"))
                .directory(repoDir.toFile())
            val proc = pb.start()
            proc.outputStream.bufferedWriter().use { it.write(badJson) }
            val sha = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            sha
        }

        // Build a tree pointing to the corrupt blob
        val treeInput = "100644 blob $blobSha\tstate.json\n"
        val pb2 = ProcessBuilder(listOf("git", "mktree"))
            .directory(repoDir.toFile())
        val proc2 = pb2.start()
        proc2.outputStream.bufferedWriter().use { it.write(treeInput) }
        val treeSha = proc2.inputStream.bufferedReader().readText().trim()
        proc2.waitFor()

        // Create a commit pointing to the corrupt tree
        val env = mapOf(
            "GIT_AUTHOR_NAME" to "test", "GIT_AUTHOR_EMAIL" to "t@t.com",
            "GIT_AUTHOR_DATE" to "1970-01-01T00:00:00+0000",
            "GIT_COMMITTER_NAME" to "test", "GIT_COMMITTER_EMAIL" to "t@t.com",
            "GIT_COMMITTER_DATE" to "1970-01-01T00:00:00+0000",
        )
        val pb3 = ProcessBuilder(listOf("git", "commit-tree", treeSha, "-m", "corrupt"))
            .directory(repoDir.toFile())
        pb3.environment().putAll(env)
        val proc3 = pb3.start()
        val commitSha = proc3.inputStream.bufferedReader().readText().trim()
        proc3.waitFor()

        // Point the ref at the corrupt commit
        runner.run(repoDir, listOf("update-ref", "refs/stacktree/state", commitSha))

        // Now read() must throw StateCorruptedException
        assertThrows<StateCorruptedException> {
            storage.read()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6: Write + delete lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `write then delete clears the ref and subsequent read returns null`() {
        val state = StackState(
            repoConfig = defaultConfig,
            branches = mapOf("main" to BranchNode(name = "main", parent = null)),
        )

        storage.write(state)
        assertTrue(storage.exists(), "Ref should exist after write")

        storage.delete()
        assertNull(storage.read(), "read() should return null after delete()")
        assertTrue(!storage.exists(), "Ref should not exist after delete()")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7: Concurrent writes via ReentrantLock produce valid final state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ten concurrent writes all complete and final state is valid`() {
        val pool = Executors.newFixedThreadPool(10)
        val errors = mutableListOf<Throwable>()

        repeat(10) { i ->
            pool.submit {
                try {
                    storage.write(
                        StackState(
                            repoConfig = RepoConfig(trunk = "write-$i", remote = "origin"),
                        )
                    )
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add(t) }
                }
            }
        }

        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "Thread pool did not finish in time")
        assertTrue(errors.isEmpty(), "Concurrent writes produced errors: $errors")

        // Whatever write won the race, the state must be deserializable.
        val final = storage.read()
        assertNotNull(final, "Final state should be readable after concurrent writes")
        assertTrue(
            final.repoConfig.trunk.startsWith("write-"),
            "Expected trunk to be one of the concurrent writes but was '${final.repoConfig.trunk}'"
        )
    }
}

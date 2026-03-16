package com.github.ydymovopenclawbot.stackworktree.state

import com.github.ydymovopenclawbot.stackworktree.git.GitException
import com.github.ydymovopenclawbot.stackworktree.git.GitRunResult
import com.github.ydymovopenclawbot.stackworktree.git.GitRunner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Persists [StackState] as a single JSON blob inside the git object store, pointed to by
 * [REF].  Each call to [write] creates a new commit object whose parent is the previous
 * commit, building an auditable history chain in `refs/stacktree/state`.
 *
 * Layout inside the git object database:
 * ```
 * refs/stacktree/state  →  commit
 *                               └─ tree
 *                                     └─ 100644 blob  state.json   (JSON-encoded StackState)
 * ```
 *
 * @param root   Absolute path to the root of the git repository (the directory that
 *               contains `.git`).
 * @param runner Strategy for executing raw git commands; default is [ProcessGitRunner][com.github.ydymovopenclawbot.stackworktree.git.ProcessGitRunner].
 */
class StateStorage(
    private val root: Path,
    private val runner: GitRunner,
) {
    // ----------------------------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------------------------

    /** Returns `true` if [REF] already exists in the repository. */
    fun exists(): Boolean = runner.run(root, listOf("rev-parse", "--verify", REF)).isSuccess

    /**
     * Reads and deserializes the [StackState] from [REF], or returns `null` if the ref
     * does not exist yet (i.e. StackTree has never written state to this repository).
     */
    fun read(): StackState? {
        if (!exists()) return null

        // commit → tree SHA
        val commitText = exec("cat-file", "-p", REF).stdout
        val treeSha = commitText.lineSequence()
            .first { it.startsWith("tree ") }
            .removePrefix("tree ")
            .trim()

        // tree → blob SHA for state.json
        val treeText = exec("cat-file", "-p", treeSha).stdout
        val blobSha = treeText.lineSequence()
            .first { it.contains(BLOB_FILENAME) }
            .trimStart()
            // line format: "100644 blob <sha>  state.json" (two spaces before name)
            .split("\\s+".toRegex())[2]

        val jsonStr = exec("cat-file", "blob", blobSha).stdout
        return JSON.decodeFromString<StackState>(jsonStr)
    }

    /**
     * Serializes [state] to JSON and persists it as a new commit on [REF].
     *
     * Steps:
     * 1. Write JSON to a loose blob via `git hash-object -w --stdin`.
     * 2. Wrap the blob in a tree via `git mktree`.
     * 3. Create a commit object via `git commit-tree`, chaining the previous commit as
     *    parent when the ref already exists.
     * 4. Advance [REF] to the new commit via `git update-ref`.
     */
    fun write(state: StackState) {
        val jsonStr = JSON.encodeToString(state)

        // Step 1 — blob
        val blobSha = execWithStdin(jsonStr, "hash-object", "-w", "--stdin").trim()

        // Step 2 — tree  (mktree reads one entry per line from stdin; tab-separated)
        val treeInput = "100644 blob $blobSha\t$BLOB_FILENAME\n"
        val treeSha = execWithStdin(treeInput, "mktree").trim()

        // Step 3 — commit (optionally chain parent)
        val parentSha = runner.run(root, listOf("rev-parse", "--verify", REF))
            .takeIf { it.isSuccess }?.stdout?.trim()

        val commitArgs = buildList {
            add("commit-tree"); add(treeSha)
            if (!parentSha.isNullOrBlank()) { add("-p"); add(parentSha) }
            add("-m"); add("stacktree state")
        }
        val commitSha = execWithEnv(AUTHOR_ENV, *commitArgs.toTypedArray()).trim()

        // Step 4 — advance ref
        exec("update-ref", REF, commitSha)
    }

    // ----------------------------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------------------------

    /** Runs a git command via [runner], throwing [GitException] on non-zero exit. */
    private fun exec(vararg args: String): GitRunResult {
        val result = runner.run(root, args.toList())
        if (!result.isSuccess) {
            val msg = result.stderr.ifBlank { "git ${args[0]} failed (exit ${result.exitCode})" }
            throw GitException(msg)
        }
        return result
    }

    /**
     * Runs a git command that needs **stdin** input, returning trimmed stdout.
     *
     * Uses [ProcessBuilder] directly because [GitRunner] does not expose a stdin channel.
     * Drains stdout and stderr concurrently via [CompletableFuture] to avoid OS pipe
     * deadlocks, and writes stdin on a third concurrent thread for the same reason.
     */
    private fun execWithStdin(stdin: String, vararg args: String): String =
        runProcess(emptyMap(), stdin, *args)

    /** Like [exec] but injects additional environment variables into the process. */
    private fun execWithEnv(env: Map<String, String>, vararg args: String): String =
        runProcess(env, null, *args)

    private fun runProcess(
        extraEnv: Map<String, String>,
        stdin: String?,
        vararg args: String,
    ): String {
        val pb = ProcessBuilder(listOf("git") + args.toList())
            .directory(root.toFile())
            .redirectErrorStream(false)
        pb.environment().putAll(extraEnv)

        val process = pb.start()

        // Write stdin concurrently — if we block on writing while the child's stdout
        // pipe buffer fills, both sides deadlock.
        val stdinFuture: CompletableFuture<*> = if (stdin != null) {
            CompletableFuture.runAsync {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
            }
        } else {
            process.outputStream.close()
            CompletableFuture.completedFuture<Void?>(null)
        }

        val stdoutFuture = CompletableFuture
            .supplyAsync { process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim() }
        val stderrFuture = CompletableFuture
            .supplyAsync { process.errorStream.bufferedReader(Charsets.UTF_8).readText().trim() }

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw GitException("git ${args[0]} timed out after 30s")
        }

        stdinFuture.get()
        val stdout = stdoutFuture.get()
        val stderr = stderrFuture.get()

        if (process.exitValue() != 0) {
            val msg = stderr.ifBlank { "git ${args[0]} failed (exit ${process.exitValue()})" }
            throw GitException(msg)
        }
        return stdout
    }

    // ----------------------------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------------------------

    private companion object {
        const val REF = "refs/stacktree/state"
        const val BLOB_FILENAME = "state.json"

        val JSON = Json

        /** Stable author/committer identity used for every stacktree commit. */
        val AUTHOR_ENV: Map<String, String> = mapOf(
            "GIT_AUTHOR_NAME"     to "stacktree",
            "GIT_AUTHOR_EMAIL"    to "stacktree@localhost",
            "GIT_AUTHOR_DATE"     to "1970-01-01T00:00:00+0000",
            "GIT_COMMITTER_NAME"  to "stacktree",
            "GIT_COMMITTER_EMAIL" to "stacktree@localhost",
            "GIT_COMMITTER_DATE"  to "1970-01-01T00:00:00+0000",
        )
    }
}

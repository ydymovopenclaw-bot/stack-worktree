package com.github.ydymovopenclawbot.stackworktree.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Path

/** A single commit entry returned by [GitExecutor.log]. */
data class LogEntry(val hash: String, val subject: String, val authorDate: String)

/**
 * Low-level, suspend-friendly git operations wrapper.
 *
 * All functions:
 * - Run on [Dispatchers.IO]
 * - Respect a [timeoutMs] deadline (default 30 s) enforced via [withTimeout]
 * - Return [Result.success] with a typed value, or [Result.failure] with a [GitException]
 *   carrying a human-readable message from stderr (never a blank error)
 *
 * The [runner] dependency is injectable so production code can supply [IntelliJGitRunner]
 * while unit tests use [ProcessGitRunner] against a real `@TempDir` git repo without
 * requiring the IntelliJ platform runtime.
 *
 * @param root     Absolute path to the git repository root.
 * @param runner   Strategy for executing raw git commands.
 * @param timeoutMs Per-command timeout in milliseconds.
 */
class GitExecutor(
    private val root: Path,
    private val runner: GitRunner,
    private val timeoutMs: Long = 30_000L,
) {
    // -------------------------------------------------------------------------
    // Internal execution helper
    // -------------------------------------------------------------------------

    private suspend fun exec(vararg args: String): Result<GitRunResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(timeoutMs) {
                    val result = runner.run(root, args.toList())
                    if (!result.isSuccess) {
                        val msg = result.stderr.ifBlank {
                            "git ${args[0]} failed with exit code ${result.exitCode}"
                        }
                        throw GitException(msg)
                    }
                    result
                }
            }
        }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Resolves [ref] to the full 40-character commit SHA.
     *
     * Fails with [GitException] when [ref] does not exist or the working directory
     * is not inside a git repository.
     */
    suspend fun revParse(ref: String): Result<String> =
        exec("rev-parse", "--verify", ref).map { it.stdout }

    /**
     * Returns the list of commit SHAs reachable from [to] but not from [from]
     * (i.e. the commits in the range `from..to`), newest first.
     *
     * Returns an empty list when the two refs are identical.
     */
    suspend fun revList(from: String, to: String): Result<List<String>> =
        exec("rev-list", "$from..$to")
            .map { it.stdout.lines().filter(String::isNotBlank) }

    /**
     * Returns the last [n] commits on [branch] as [LogEntry] objects.
     *
     * Uses a unit-separator (`\u001f`) as the pretty-format delimiter to survive
     * subjects that contain commas, pipes, or other common delimiters.
     */
    suspend fun log(branch: String, n: Int = 10): Result<List<LogEntry>> =
        exec("log", branch, "-$n", "--pretty=format:%H\u001f%s\u001f%ad", "--date=short")
            .map { res ->
                res.stdout.lines()
                    .filter(String::isNotBlank)
                    .map { line ->
                        // Use index-based parsing instead of split() so a unit-separator
                        // character inside the commit subject does not corrupt the fields.
                        val first = line.indexOf('\u001f')
                        val last = line.lastIndexOf('\u001f')
                        LogEntry(
                            hash = if (first >= 0) line.substring(0, first) else line,
                            subject = if (first >= 0 && last > first) line.substring(first + 1, last) else "",
                            authorDate = if (last >= 0) line.substring(last + 1) else "",
                        )
                    }
            }

    /**
     * Returns all local branch names (short form, e.g. `"main"`).
     */
    suspend fun branchList(): Result<List<String>> =
        exec("branch", "--format=%(refname:short)")
            .map { res ->
                res.stdout.lines().map(String::trim).filter(String::isNotBlank)
            }

    /**
     * Returns the name of the currently checked-out branch (e.g. `"main"`).
     *
     * Uses `rev-parse --abbrev-ref HEAD` which never fails: it returns the branch name
     * on a normal checkout and the literal string `"HEAD"` in detached HEAD state.
     * This implementation normalises detached HEAD to an empty string.
     */
    suspend fun currentBranch(): Result<String> =
        exec("rev-parse", "--abbrev-ref", "HEAD")
            .map { result ->
                val name = result.stdout
                if (name == "HEAD") "" else name   // detached HEAD → empty string
            }

    /**
     * Returns the fetch URL configured for [remote] (default `"origin"`).
     *
     * Fails with [GitException] when no such remote is configured.
     */
    suspend fun remoteUrl(remote: String = "origin"): Result<String> =
        exec("remote", "get-url", remote).map { it.stdout }

    /**
     * Pushes [branch] to [remote] (default `"origin"`), setting the upstream tracking
     * reference with `--set-upstream` so subsequent push/pull work without explicit targets.
     *
     * When [forceWithLease] is `true`, `--force-with-lease` is appended, which refuses
     * the push if the remote tip has advanced beyond what was last fetched — preventing
     * accidental overwrites of concurrent commits.
     *
     * Fails with [GitException] if the push is rejected or a network error occurs.
     */
    suspend fun push(
        branch: String,
        remote: String = "origin",
        forceWithLease: Boolean = false,
    ): Result<Unit> {
        val args = buildList {
            add("push")
            add("--set-upstream")
            if (forceWithLease) add("--force-with-lease")
            add(remote)
            add(branch)
        }
        return exec(*args.toTypedArray()).map { }
    }
}

/** Thrown when a git command exits with a non-zero status. */
class GitException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

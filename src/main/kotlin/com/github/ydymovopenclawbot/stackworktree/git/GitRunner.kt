package com.github.ydymovopenclawbot.stackworktree.git

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Result of running a raw git process. */
data class GitRunResult(val stdout: String, val stderr: String, val exitCode: Int) {
    val isSuccess: Boolean get() = exitCode == 0
}

/** Abstraction over git process execution, allowing test injection. */
fun interface GitRunner {
    fun run(workDir: Path, args: List<String>): GitRunResult
}

/**
 * Shell-based [GitRunner] implemented with [ProcessBuilder].
 * Does not require the IntelliJ platform — suitable for unit tests.
 */
class ProcessGitRunner : GitRunner {
    override fun run(workDir: Path, args: List<String>): GitRunResult {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workDir.toFile())
            .redirectErrorStream(false)
            .start()

        // Drain stdout and stderr on separate threads BEFORE calling waitFor.
        // If streams are read after waitFor the OS pipe buffer can fill up, causing
        // the child process to block on write and waitFor to never return.
        val stdoutFuture = java.util.concurrent.CompletableFuture
            .supplyAsync { process.inputStream.bufferedReader().readText().trim() }
        val stderrFuture = java.util.concurrent.CompletableFuture
            .supplyAsync { process.errorStream.bufferedReader().readText().trim() }

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return GitRunResult("", "git process timed out after 30s", -1)
        }
        return GitRunResult(
            stdout = stdoutFuture.get(),
            stderr = stderrFuture.get(),
            exitCode = process.exitValue(),
        )
    }
}

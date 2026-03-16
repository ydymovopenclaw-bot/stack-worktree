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
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return GitRunResult("", "git process timed out after 30s", -1)
        }
        return GitRunResult(
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim(),
            exitCode = process.exitValue(),
        )
    }
}

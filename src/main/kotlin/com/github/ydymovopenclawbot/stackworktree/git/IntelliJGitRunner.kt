package com.github.ydymovopenclawbot.stackworktree.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.nio.file.Path

/**
 * Production [GitRunner] that delegates to IntelliJ's [git4idea.commands.Git] service.
 *
 * Uses [GitLineHandler] to construct commands and [Git.getInstance] to execute them,
 * giving IntelliJ full visibility into git operations (credential helpers, SSH agents, etc.).
 *
 * [GitCommand] entries used:
 *  - REV_PARSE    → git rev-parse
 *  - REV_LIST     → git rev-list
 *  - LOG          → git log
 *  - BRANCH       → git branch
 *  - REMOTE       → git remote
 *  - PUSH         → git push
 *  - CAT_FILE     → git cat-file
 *  - UPDATE_REF   → git update-ref
 *  - HASH_OBJECT  → git hash-object
 *  - write("commit-tree") → git commit-tree (ad-hoc write command)
 */
class IntelliJGitRunner(private val project: Project) : GitRunner {

    private val commandMap: Map<String, GitCommand> = mapOf(
        "rev-parse" to GitCommand.REV_PARSE,
        "rev-list" to GitCommand.REV_LIST,
        "log" to GitCommand.LOG,
        "branch" to GitCommand.BRANCH,
        "remote" to GitCommand.REMOTE,
        "push" to GitCommand.PUSH,
        "cat-file" to GitCommand.CAT_FILE,
        "update-ref" to GitCommand.UPDATE_REF,
        "hash-object" to GitCommand.HASH_OBJECT,
    )

    /**
     * Commands not present in [GitCommand] are executed via [ProcessBuilder] as a fallback.
     * `commit-tree` is used by [com.github.ydymovopenclawbot.stackworktree.state.StateStorage]
     * but has no corresponding [GitCommand] constant.
     */
    private val fallbackCommands = setOf("commit-tree")

    override fun run(workDir: Path, args: List<String>): GitRunResult {
        val commandName = args.firstOrNull()
            ?: return GitRunResult("", "No git command provided", -1)

        if (commandName in fallbackCommands) {
            return runViaProcess(workDir, args)
        }

        val gitCommand = commandMap[commandName]
            ?: return GitRunResult("", "Unsupported git command for IntelliJGitRunner: $commandName", -1)

        val vf = VfsUtil.findFile(workDir, true)
            ?: return GitRunResult("", "Cannot resolve VirtualFile for path: $workDir", -1)

        val handler = GitLineHandler(project, vf, gitCommand)
        args.drop(1).forEach { handler.addParameters(it) }

        val result = Git.getInstance().runCommand(handler)
        return GitRunResult(
            stdout = result.output.joinToString("\n"),
            stderr = result.errorOutput.joinToString("\n"),
            exitCode = if (result.success()) 0 else 1,
        )
    }

    private fun runViaProcess(workDir: Path, args: List<String>): GitRunResult {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workDir.toFile())
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        return GitRunResult(stdout, stderr, exitCode)
    }
}

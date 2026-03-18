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
 *  - REV_PARSE  → git rev-parse  (also handles --abbrev-ref for currentBranch)
 *  - REV_LIST   → git rev-list
 *  - LOG        → git log
 *  - BRANCH     → git branch
 *  - REMOTE     → git remote
 *  - PUSH       → git push
 */
class IntelliJGitRunner(private val project: Project) : GitRunner {

    private val commandMap: Map<String, GitCommand> = mapOf(
        "rev-parse" to GitCommand.REV_PARSE,
        "rev-list" to GitCommand.REV_LIST,
        "log" to GitCommand.LOG,
        "branch" to GitCommand.BRANCH,
        "remote" to GitCommand.REMOTE,
        "push" to GitCommand.PUSH,
    )

    override fun run(workDir: Path, args: List<String>): GitRunResult {
        val commandName = args.firstOrNull()
            ?: return GitRunResult("", "No git command provided", -1)

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
}

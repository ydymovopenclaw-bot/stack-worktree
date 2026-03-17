package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.MyBundle
import com.github.ydymovopenclawbot.stackworktree.git.IntelliJGitRunner
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.StateStorage
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import git4idea.GitUtil
import java.nio.file.Paths

private val LOG = logger<NewStackAction>()

/**
 * Creates a new stack in the current repository.
 *
 * Behaviour:
 * - **No existing state** – initialises a fresh [StackState] with the trunk node and,
 *   when the current branch differs from trunk, adds the current branch as the first node.
 * - **Existing state** – adds the current branch as a new root node (parent = trunk)
 *   if it is not already tracked; otherwise the existing state is left unchanged.
 *
 * The state is persisted to `refs/stacktree/state` via [StateStorage] on a pooled
 * background thread.  After a successful write, [StackTreeStateListener.TOPIC] is
 * published so any open UI (e.g. the Stacks tab) refreshes automatically.
 */
class NewStackAction : AnAction("New Stack", "Create a new branch stack", AllIcons.General.Add) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo    = GitUtil.getRepositoryManager(project).repositories.firstOrNull() ?: return
        val currentBranch = repo.currentBranch?.name ?: return

        val dialog = NewStackDialog(project)
        if (!dialog.showAndGet()) return

        val trunk = dialog.trunkBranch
        val root  = Paths.get(repo.root.path)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val storage  = StateStorage(root, IntelliJGitRunner(project))
                val existing = storage.read()
                val newState = buildState(existing, currentBranch, trunk)

                if (newState !== existing) {          // reference equality: only write when changed
                    storage.write(newState)
                    project.messageBus
                        .syncPublisher(StackTreeStateListener.TOPIC)
                        .stateChanged()
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("StackTree")
                        .createNotification(
                            MyBundle.message("stacktree.newStack.created", trunk),
                            NotificationType.INFORMATION,
                        )
                        .notify(project)
                }
            } catch (ex: Exception) {
                LOG.warn("Failed to persist stack state", ex)
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("StackTree")
                    .createNotification(
                        MyBundle.message("stacktree.newStack.error", ex.message ?: "unknown"),
                        NotificationType.ERROR,
                    )
                    .notify(project)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null &&
            GitUtil.getRepositoryManager(project).repositories.isNotEmpty()
    }

    // -------------------------------------------------------------------------
    // State construction — pure logic with no platform dependencies; unit-testable.
    // -------------------------------------------------------------------------

    /**
     * Computes the next [StackState] given the [existing] state (if any), the
     * [currentBranch] that is checked out, and the desired [trunk] branch.
     *
     * Returns the same [existing] reference (not a copy) when no change is needed,
     * so callers can use reference equality (`!==`) to skip a redundant write.
     */
    internal fun buildState(
        existing: StackState?,
        currentBranch: String,
        trunk: String,
    ): StackState {
        if (existing == null) {
            // Fresh repository — create state from scratch.
            val branches = mutableMapOf(
                trunk to BranchNode(name = trunk, parent = null),
            )
            if (currentBranch != trunk) {
                branches[currentBranch] = BranchNode(name = currentBranch, parent = trunk)
            }
            return StackState(
                repoConfig = RepoConfig(trunk = trunk, remote = "origin"),
                branches   = branches,
            )
        }

        // State already exists — add current branch as a new root if not yet tracked.
        if (currentBranch == existing.repoConfig.trunk ||
            existing.branches.containsKey(currentBranch)
        ) {
            return existing   // nothing to change; return same reference
        }

        return existing.copy(
            branches = existing.branches + (
                currentBranch to BranchNode(
                    name   = currentBranch,
                    parent = existing.repoConfig.trunk,
                )
            ),
        )
    }
}

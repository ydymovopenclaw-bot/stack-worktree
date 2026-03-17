package com.github.ydymovopenclawbot.stackworktree.startup

import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Bridges [GitRepositoryChangeListener] to a plain Kotlin lambda so callers do not need
 * to implement the interface directly.
 *
 * Fires on commit, checkout, rebase, fetch, and pull — all events that can change
 * ahead/behind counts or the currently active branch.
 *
 * @param onChange Callback invoked with the changed [GitRepository] on every repository event.
 */
class StackGitChangeListener(
    private val onChange: (GitRepository) -> Unit,
) : GitRepositoryChangeListener {
    override fun repositoryChanged(repository: GitRepository) {
        onChange(repository)
    }
}

package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import java.util.function.Predicate

class StacksTabVisibilityPredicate : Predicate<Project> {

    override fun test(project: Project): Boolean =
        GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
}

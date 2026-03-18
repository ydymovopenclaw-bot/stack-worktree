package com.github.ydymovopenclawbot.stackworktree.pr.github

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Resolved coordinates for a GitHub (or GitHub Enterprise) repository, derived from the
 * project's git remotes.
 *
 * @param owner       The repository owner (user or organisation) as it appears in the URL.
 * @param repo        The repository name without the `.git` suffix.
 * @param apiBaseUrl  Base URL for the REST API, e.g. `https://api.github.com` for
 *                    github.com or `https://<hostname>/api/v3` for GHE.
 */
data class GitHubRepoInfo(
    val owner: String,
    val repo: String,
    val apiBaseUrl: String,
) {
    companion object {
        /**
         * Attempts to resolve a [GitHubRepoInfo] from the first GitHub-hosted remote found in
         * the project.  Returns `null` if no GitHub remote is configured.
         *
         * Both HTTPS (`https://github.com/owner/repo.git`) and SSH
         * (`git@github.com:owner/repo.git`) remote formats are supported, as well as
         * GitHub Enterprise Server instances.
         */
        fun fromProject(project: Project): GitHubRepoInfo? {
            val repositories = GitRepositoryManager.getInstance(project).repositories
            for (gitRepo in repositories) {
                for (remote in gitRepo.remotes) {
                    for (url in remote.urls) {
                        val info = parseRemoteUrl(url)
                        if (info != null) return info
                    }
                }
            }
            return null
        }

        /**
         * Parses [url] into a [GitHubRepoInfo].  Returns `null` if the URL does not match a
         * known GitHub remote pattern.
         *
         * Supported formats:
         * - `https://github.com/owner/repo.git`
         * - `https://github.com/owner/repo`
         * - `git@github.com:owner/repo.git`
         * - `ssh://git@github.com/owner/repo.git`
         * - Same patterns for GitHub Enterprise (any hostname)
         */
        fun parseRemoteUrl(url: String): GitHubRepoInfo? {
            // HTTPS: https://<host>/<owner>/<repo>[.git]
            val httpsMatch = HTTPS_REGEX.find(url)
            if (httpsMatch != null) {
                val (host, owner, repo) = httpsMatch.destructured
                if (!looksLikeGitHub(host)) return null
                val apiBase = apiBaseUrl(host)
                return GitHubRepoInfo(owner, repo.removeSuffix(".git"), apiBase)
            }

            // SCP-style SSH: git@<host>:<owner>/<repo>[.git]
            val scpMatch = SCP_REGEX.find(url)
            if (scpMatch != null) {
                val (host, owner, repo) = scpMatch.destructured
                if (!looksLikeGitHub(host)) return null
                val apiBase = apiBaseUrl(host)
                return GitHubRepoInfo(owner, repo.removeSuffix(".git"), apiBase)
            }

            // ssh:// URL: ssh://git@<host>/<owner>/<repo>[.git]
            val sshMatch = SSH_URL_REGEX.find(url)
            if (sshMatch != null) {
                val (host, owner, repo) = sshMatch.destructured
                if (!looksLikeGitHub(host)) return null
                val apiBase = apiBaseUrl(host)
                return GitHubRepoInfo(owner, repo.removeSuffix(".git"), apiBase)
            }

            return null
        }

        /** Returns `https://api.github.com` for `github.com`, or the GHE `/api/v3` base. */
        private fun apiBaseUrl(host: String): String =
            if (host == "github.com") "https://api.github.com"
            else "https://$host/api/v3"

        /**
         * Heuristic: a host is "GitHub-like" if it contains "github" or looks like a
         * GitHub Enterprise hostname.  This avoids misidentifying GitLab/Bitbucket remotes.
         */
        private fun looksLikeGitHub(host: String): Boolean =
            host.contains("github", ignoreCase = true)

        // https://<host>/<owner>/<repo>[.git]
        private val HTTPS_REGEX = Regex(
            """^https?://([^/]+)/([^/]+)/([^/]+?)(?:\.git)?/?$"""
        )

        // git@<host>:<owner>/<repo>[.git]
        private val SCP_REGEX = Regex(
            """^git@([^:]+):([^/]+)/([^/]+?)(?:\.git)?$"""
        )

        // ssh://git@<host>/<owner>/<repo>[.git]
        private val SSH_URL_REGEX = Regex(
            """^ssh://(?:git@)?([^/]+)/([^/]+)/([^/]+?)(?:\.git)?/?$"""
        )
    }
}

package com.github.ydymovopenclawbot.stackworktree.pr.gitlab

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Resolved coordinates for a GitLab (or self-hosted GitLab) repository, derived from
 * the project's git remotes.
 *
 * @param host       The GitLab hostname, e.g. `"gitlab.com"` or `"gitlab.mycompany.com"`.
 * @param namespace  The project path including groups/sub-groups, e.g. `"myorg/myrepo"` or
 *                   `"group/sub/repo"`.
 * @param apiBaseUrl The HTTPS base URL for the GitLab instance, e.g. `"https://gitlab.com"`.
 */
data class GitLabRepoInfo(
    val host: String,
    val namespace: String,
    val apiBaseUrl: String,
) {
    /**
     * Namespace URL-encoded for use in REST API paths.
     *
     * GitLab's API requires the project path to be URL-encoded when embedded in the URL,
     * replacing `/` with `%2F` (e.g. `"myorg/myrepo"` → `"myorg%2Fmyrepo"`).
     */
    val encodedNamespace: String get() = namespace.replace("/", "%2F")

    companion object {
        /**
         * Scans all remotes across all git repositories in [project] and returns the first
         * [GitLabRepoInfo] found, or `null` if no GitLab remote is configured.
         *
         * @param gitLabHostOverride When non-null, any remote whose hostname exactly matches
         *   this value is treated as GitLab regardless of whether the hostname contains
         *   the word "gitlab".  Use this for self-hosted instances with custom domain names.
         */
        fun fromProject(project: Project, gitLabHostOverride: String? = null): GitLabRepoInfo? {
            val repos = GitRepositoryManager.getInstance(project).repositories
            for (gitRepo in repos) {
                for (remote in gitRepo.remotes) {
                    for (url in remote.urls) {
                        val info = parseRemoteUrl(url, gitLabHostOverride)
                        if (info != null) return info
                    }
                }
            }
            return null
        }

        /**
         * Parses [url] into a [GitLabRepoInfo], or returns `null` if the URL does not match a
         * known GitLab remote pattern.
         *
         * Supported formats:
         * - `https://gitlab.com/owner/repo.git`
         * - `https://gitlab.com/group/sub/repo` (nested namespaces)
         * - `git@gitlab.com:owner/repo.git`
         * - `ssh://git@gitlab.com/owner/repo.git`
         * - Same patterns for self-hosted GitLab instances
         *
         * @param gitLabHostOverride  When non-null, overrides hostname-based auto-detection.
         */
        fun parseRemoteUrl(url: String, gitLabHostOverride: String? = null): GitLabRepoInfo? {
            // HTTPS: https://<host>/<namespace/...>[.git][/]
            val httpsMatch = HTTPS_REGEX.find(url)
            if (httpsMatch != null) {
                val (host, path) = httpsMatch.destructured
                if (!looksLikeGitLab(host, gitLabHostOverride)) return null
                val namespace = path.removeSuffix(".git").trimEnd('/')
                return GitLabRepoInfo(host, namespace, "https://$host")
            }

            // SCP-style SSH: git@<host>:<namespace/...>[.git]
            val scpMatch = SCP_REGEX.find(url)
            if (scpMatch != null) {
                val (host, path) = scpMatch.destructured
                if (!looksLikeGitLab(host, gitLabHostOverride)) return null
                val namespace = path.removeSuffix(".git")
                return GitLabRepoInfo(host, namespace, "https://$host")
            }

            // ssh:// URL: ssh://[git@]<host>/<namespace/...>[.git][/]
            val sshMatch = SSH_URL_REGEX.find(url)
            if (sshMatch != null) {
                val (host, path) = sshMatch.destructured
                if (!looksLikeGitLab(host, gitLabHostOverride)) return null
                val namespace = path.removeSuffix(".git").trimEnd('/')
                return GitLabRepoInfo(host, namespace, "https://$host")
            }

            return null
        }

        /**
         * Returns `true` when [host] is recognisable as a GitLab instance.
         *
         * When [override] is non-null, an exact case-insensitive match is required.
         * Otherwise the heuristic checks whether the hostname contains the word "gitlab".
         */
        private fun looksLikeGitLab(host: String, override: String?): Boolean =
            override?.equals(host, ignoreCase = true) ?: host.contains("gitlab", ignoreCase = true)

        // https://<host>/<path>[.git][/]
        private val HTTPS_REGEX = Regex("""^https?://([^/]+)/(.+?)(?:\.git)?/?$""")

        // git@<host>:<path>[.git]
        private val SCP_REGEX = Regex("""^git@([^:]+):(.+?)(?:\.git)?$""")

        // ssh://[git@]<host>/<path>[.git][/]
        private val SSH_URL_REGEX = Regex("""^ssh://(?:git@)?([^/]+)/(.+?)(?:\.git)?/?$""")
    }
}

package com.github.ydymovopenclawbot.stackworktree.pr.gitlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitLabRepoInfoTest {

    // ── gitlab.com HTTPS ──────────────────────────────────────────────────────

    @Test
    fun `parses https gitlab dot com with git suffix`() {
        val info = parse("https://gitlab.com/myorg/myrepo.git")
        assertEquals("gitlab.com", info?.host)
        assertEquals("myorg/myrepo", info?.namespace)
        assertEquals("https://gitlab.com", info?.apiBaseUrl)
    }

    @Test
    fun `parses https gitlab dot com without git suffix`() {
        val info = parse("https://gitlab.com/myorg/myrepo")
        assertEquals("myorg/myrepo", info?.namespace)
        assertEquals("https://gitlab.com", info?.apiBaseUrl)
    }

    @Test
    fun `parses https gitlab dot com with trailing slash`() {
        val info = parse("https://gitlab.com/myorg/myrepo/")
        assertEquals("myorg/myrepo", info?.namespace)
    }

    @Test
    fun `parses nested namespace three levels deep`() {
        val info = parse("https://gitlab.com/group/subgroup/repo.git")
        assertEquals("group/subgroup/repo", info?.namespace)
    }

    @Test
    fun `parses nested namespace four levels deep`() {
        val info = parse("https://gitlab.com/a/b/c/repo")
        assertEquals("a/b/c/repo", info?.namespace)
    }

    // ── gitlab.com SSH ────────────────────────────────────────────────────────

    @Test
    fun `parses SCP-style SSH with git suffix`() {
        val info = parse("git@gitlab.com:myorg/myrepo.git")
        assertEquals("gitlab.com", info?.host)
        assertEquals("myorg/myrepo", info?.namespace)
        assertEquals("https://gitlab.com", info?.apiBaseUrl)
    }

    @Test
    fun `parses SCP-style SSH without git suffix`() {
        val info = parse("git@gitlab.com:myorg/myrepo")
        assertEquals("myorg/myrepo", info?.namespace)
    }

    @Test
    fun `parses SCP-style SSH with nested namespace`() {
        val info = parse("git@gitlab.com:group/subgroup/repo.git")
        assertEquals("group/subgroup/repo", info?.namespace)
    }

    @Test
    fun `parses ssh-url scheme`() {
        val info = parse("ssh://git@gitlab.com/myorg/myrepo.git")
        assertEquals("gitlab.com", info?.host)
        assertEquals("myorg/myrepo", info?.namespace)
        assertEquals("https://gitlab.com", info?.apiBaseUrl)
    }

    @Test
    fun `parses ssh-url scheme without git at prefix`() {
        val info = parse("ssh://gitlab.com/myorg/myrepo.git")
        assertEquals("myorg/myrepo", info?.namespace)
    }

    // ── Self-hosted GitLab ────────────────────────────────────────────────────

    @Test
    fun `parses self-hosted HTTPS URL containing gitlab`() {
        val info = parse("https://gitlab.mycompany.com/team/project.git")
        assertEquals("gitlab.mycompany.com", info?.host)
        assertEquals("team/project", info?.namespace)
        assertEquals("https://gitlab.mycompany.com", info?.apiBaseUrl)
    }

    @Test
    fun `parses self-hosted SSH URL containing gitlab`() {
        val info = parse("git@gitlab.mycompany.com:team/project.git")
        assertEquals("gitlab.mycompany.com", info?.host)
        assertEquals("team/project", info?.namespace)
    }

    // ── Host override for custom domains ─────────────────────────────────────

    @Test
    fun `override allows custom domain not containing gitlab`() {
        val info = parseWithOverride("https://git.mycompany.com/team/project.git", "git.mycompany.com")
        assertEquals("git.mycompany.com", info?.host)
        assertEquals("team/project", info?.namespace)
    }

    @Test
    fun `override is case-insensitive`() {
        val info = parseWithOverride("https://GIT.MyCompany.com/team/project.git", "git.mycompany.com")
        assertEquals("GIT.MyCompany.com", info?.host)
        assertEquals("team/project", info?.namespace)
    }

    @Test
    fun `override does not affect non-matching host`() {
        // Override specifies a different host — should not match
        assertNull(parseWithOverride("https://git.other.com/team/project.git", "git.mycompany.com"))
    }

    // ── encodedNamespace ─────────────────────────────────────────────────────

    @Test
    fun `encodedNamespace replaces slashes with percent-2F`() {
        val info = parse("https://gitlab.com/group/subgroup/repo.git")!!
        assertEquals("group%2Fsubgroup%2Frepo", info.encodedNamespace)
    }

    @Test
    fun `encodedNamespace is unchanged for single-level namespace`() {
        val info = parse("https://gitlab.com/myorg/myrepo.git")!!
        assertEquals("myorg%2Fmyrepo", info.encodedNamespace)
    }

    // ── Non-GitLab URLs → null ────────────────────────────────────────────────

    @Test
    fun `returns null for github SSH URL`() {
        assertNull(parse("git@github.com:octocat/Hello-World.git"))
    }

    @Test
    fun `returns null for github HTTPS URL`() {
        assertNull(parse("https://github.com/octocat/Hello-World.git"))
    }

    @Test
    fun `returns null for bitbucket URL`() {
        assertNull(parse("https://bitbucket.org/myorg/myrepo.git"))
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(parse(""))
    }

    @Test
    fun `returns null for malformed URL`() {
        assertNull(parse("not-a-url"))
    }

    @Test
    fun `returns null for bare hostname without path`() {
        assertNull(parse("https://gitlab.com"))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun parse(url: String): GitLabRepoInfo? = GitLabRepoInfo.parseRemoteUrl(url)

    private fun parseWithOverride(url: String, override: String): GitLabRepoInfo? =
        GitLabRepoInfo.parseRemoteUrl(url, gitLabHostOverride = override)
}

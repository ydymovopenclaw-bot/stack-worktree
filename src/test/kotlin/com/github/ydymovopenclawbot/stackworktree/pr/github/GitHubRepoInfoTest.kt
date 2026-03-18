package com.github.ydymovopenclawbot.stackworktree.pr.github

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitHubRepoInfoTest {

    // ── github.com HTTPS ──────────────────────────────────────────────────────

    @Test
    fun `parses https github dot com with git suffix`() {
        val info = parse("https://github.com/octocat/Hello-World.git")
        assertEquals("octocat", info?.owner)
        assertEquals("Hello-World", info?.repo)
        assertEquals("https://api.github.com", info?.apiBaseUrl)
    }

    @Test
    fun `parses https github dot com without git suffix`() {
        val info = parse("https://github.com/octocat/Hello-World")
        assertEquals("octocat", info?.owner)
        assertEquals("Hello-World", info?.repo)
        assertEquals("https://api.github.com", info?.apiBaseUrl)
    }

    @Test
    fun `parses https github dot com with trailing slash`() {
        val info = parse("https://github.com/octocat/Hello-World/")
        assertEquals("octocat", info?.owner)
        assertEquals("Hello-World", info?.repo)
    }

    // ── github.com SSH ────────────────────────────────────────────────────────

    @Test
    fun `parses SCP-style SSH with git suffix`() {
        val info = parse("git@github.com:octocat/Hello-World.git")
        assertEquals("octocat", info?.owner)
        assertEquals("Hello-World", info?.repo)
        assertEquals("https://api.github.com", info?.apiBaseUrl)
    }

    @Test
    fun `parses SCP-style SSH without git suffix`() {
        val info = parse("git@github.com:octocat/Hello-World")
        assertEquals("octocat", info?.owner)
        assertEquals("Hello-World", info?.repo)
    }

    @Test
    fun `parses ssh-url scheme`() {
        val info = parse("ssh://git@github.com/octocat/Hello-World.git")
        assertEquals("octocat", info?.owner)
        assertEquals("Hello-World", info?.repo)
        assertEquals("https://api.github.com", info?.apiBaseUrl)
    }

    // ── GitHub Enterprise ─────────────────────────────────────────────────────

    @Test
    fun `parses GHE HTTPS URL`() {
        val info = parse("https://github.example.com/myorg/myrepo.git")
        assertEquals("myorg", info?.owner)
        assertEquals("myrepo", info?.repo)
        assertEquals("https://github.example.com/api/v3", info?.apiBaseUrl)
    }

    @Test
    fun `parses GHE SSH URL`() {
        val info = parse("git@github.example.com:myorg/myrepo.git")
        assertEquals("myorg", info?.owner)
        assertEquals("myrepo", info?.repo)
        assertEquals("https://github.example.com/api/v3", info?.apiBaseUrl)
    }

    // ── Non-GitHub URLs → null ────────────────────────────────────────────────

    @Test
    fun `returns null for gitlab SSH URL`() {
        assertNull(parse("git@gitlab.com:octocat/Hello-World.git"))
    }

    @Test
    fun `returns null for bitbucket HTTPS URL`() {
        assertNull(parse("https://bitbucket.org/octocat/Hello-World.git"))
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(parse(""))
    }

    @Test
    fun `returns null for malformed URL`() {
        assertNull(parse("not-a-url"))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun parse(url: String) = GitHubRepoInfo.parseRemoteUrl(url)
}

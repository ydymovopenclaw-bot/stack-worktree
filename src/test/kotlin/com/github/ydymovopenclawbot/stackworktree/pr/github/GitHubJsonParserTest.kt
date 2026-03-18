package com.github.ydymovopenclawbot.stackworktree.pr.github

import com.github.ydymovopenclawbot.stackworktree.pr.ChecksState
import com.github.ydymovopenclawbot.stackworktree.pr.PrState
import com.github.ydymovopenclawbot.stackworktree.pr.ReviewState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitHubJsonParserTest {

    // ── parsePrInfo ───────────────────────────────────────────────────────────

    @Test
    fun `parsePrInfo maps open PR`() {
        val json = prJson(state = "open", merged = false)
        val info = GitHubJsonParser.parsePrInfo(json)
        assertEquals(42, info.number)
        assertEquals("My PR", info.title)
        assertEquals("https://github.com/owner/repo/pull/42", info.url)
        assertEquals(PrState.OPEN, info.state)
    }

    @Test
    fun `parsePrInfo maps closed non-merged PR`() {
        val info = GitHubJsonParser.parsePrInfo(prJson(state = "closed", merged = false))
        assertEquals(PrState.CLOSED, info.state)
    }

    @Test
    fun `parsePrInfo maps merged PR`() {
        val info = GitHubJsonParser.parsePrInfo(prJson(state = "closed", merged = true))
        assertEquals(PrState.MERGED, info.state)
    }

    @Test
    fun `parsePrList returns all items`() {
        val json = """[${prJson(number = 1)}, ${prJson(number = 2)}, ${prJson(number = 3)}]"""
        val list = GitHubJsonParser.parsePrList(json)
        assertEquals(3, list.size)
        assertEquals(listOf(1, 2, 3), list.map { it.number })
    }

    @Test
    fun `parsePrList returns empty list for empty array`() {
        assertEquals(emptyList<Any>(), GitHubJsonParser.parsePrList("[]"))
    }

    // ── parseCheckRuns ────────────────────────────────────────────────────────

    @Test
    fun `parseCheckRuns returns NONE when array is empty`() {
        assertEquals(ChecksState.NONE, GitHubJsonParser.parseCheckRuns("""{"check_runs":[]}"""))
    }

    @Test
    fun `parseCheckRuns returns NONE when check_runs key is absent`() {
        assertEquals(ChecksState.NONE, GitHubJsonParser.parseCheckRuns("""{}"""))
    }

    @Test
    fun `parseCheckRuns returns PENDING when any run is in_progress`() {
        val json = checkRunsJson(
            run("completed", "success"),
            run("in_progress", ""),
        )
        assertEquals(ChecksState.PENDING, GitHubJsonParser.parseCheckRuns(json))
    }

    @Test
    fun `parseCheckRuns returns PENDING when any run is queued`() {
        val json = checkRunsJson(run("queued", ""))
        assertEquals(ChecksState.PENDING, GitHubJsonParser.parseCheckRuns(json))
    }

    @Test
    fun `parseCheckRuns returns FAILING when any conclusion is failure`() {
        val json = checkRunsJson(
            run("completed", "success"),
            run("completed", "failure"),
        )
        assertEquals(ChecksState.FAILING, GitHubJsonParser.parseCheckRuns(json))
    }

    @Test
    fun `parseCheckRuns returns FAILING for timed_out`() {
        assertEquals(
            ChecksState.FAILING,
            GitHubJsonParser.parseCheckRuns(checkRunsJson(run("completed", "timed_out"))),
        )
    }

    @Test
    fun `parseCheckRuns returns PASSING when all runs succeeded`() {
        val json = checkRunsJson(
            run("completed", "success"),
            run("completed", "skipped"),
        )
        assertEquals(ChecksState.PASSING, GitHubJsonParser.parseCheckRuns(json))
    }

    // ── parseReviews ──────────────────────────────────────────────────────────

    @Test
    fun `parseReviews returns REVIEW_REQUIRED for empty array`() {
        assertEquals(ReviewState.REVIEW_REQUIRED, GitHubJsonParser.parseReviews("[]"))
    }

    @Test
    fun `parseReviews returns APPROVED when all approve`() {
        val json = reviewsJson(
            review("alice", "APPROVED"),
            review("bob", "APPROVED"),
        )
        assertEquals(ReviewState.APPROVED, GitHubJsonParser.parseReviews(json))
    }

    @Test
    fun `parseReviews returns CHANGES_REQUESTED when any requests changes`() {
        val json = reviewsJson(
            review("alice", "APPROVED"),
            review("bob", "CHANGES_REQUESTED"),
        )
        assertEquals(ReviewState.CHANGES_REQUESTED, GitHubJsonParser.parseReviews(json))
    }

    @Test
    fun `parseReviews uses latest review per reviewer`() {
        // bob first requested changes but then approved
        val json = reviewsJson(
            review("bob", "CHANGES_REQUESTED"),
            review("bob", "APPROVED"),
        )
        assertEquals(ReviewState.APPROVED, GitHubJsonParser.parseReviews(json))
    }

    @Test
    fun `parseReviews ignores COMMENTED state`() {
        // Only COMMENTED reviews — not actionable
        val json = reviewsJson(review("alice", "COMMENTED"))
        assertEquals(ReviewState.REVIEW_REQUIRED, GitHubJsonParser.parseReviews(json))
    }

    // ── parseHeadSha ──────────────────────────────────────────────────────────

    @Test
    fun `parseHeadSha extracts sha from PR json`() {
        val prJson = """{"number":1,"title":"t","html_url":"u","state":"open","merged":false,"head":{"sha":"cafebabe"}}"""
        assertEquals("cafebabe", GitHubJsonParser.parseHeadSha(prJson))
    }

    @Test
    fun `parseHeadSha throws for missing head field`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            GitHubJsonParser.parseHeadSha("""{"number":1}""")
        }
    }

    @Test
    fun `parseHeadSha throws for malformed json`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            GitHubJsonParser.parseHeadSha("not-json")
        }
    }

    // ── JSON builders ─────────────────────────────────────────────────────────

    private fun prJson(
        number: Int = 42,
        state: String = "open",
        merged: Boolean = false,
    ) = """
        {
          "number": $number,
          "title": "My PR",
          "html_url": "https://github.com/owner/repo/pull/$number",
          "state": "$state",
          "merged": $merged,
          "head": { "sha": "abc123" }
        }
    """.trimIndent()

    private fun run(status: String, conclusion: String) =
        """{"status":"$status","conclusion":"$conclusion"}"""

    private fun checkRunsJson(vararg runs: String) =
        """{"check_runs":[${runs.joinToString(",")}]}"""

    private fun review(login: String, state: String) =
        """{"user":{"login":"$login"},"state":"$state"}"""

    private fun reviewsJson(vararg reviews: String) =
        """[${reviews.joinToString(",")}]"""
}

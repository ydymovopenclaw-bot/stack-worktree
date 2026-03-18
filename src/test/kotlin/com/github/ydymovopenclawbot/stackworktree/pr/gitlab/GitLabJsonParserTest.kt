package com.github.ydymovopenclawbot.stackworktree.pr.gitlab

import com.github.ydymovopenclawbot.stackworktree.pr.ChecksState
import com.github.ydymovopenclawbot.stackworktree.pr.PrProviderException
import com.github.ydymovopenclawbot.stackworktree.pr.PrState
import com.github.ydymovopenclawbot.stackworktree.pr.ReviewState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GitLabJsonParserTest {

    // ── parseMrInfo ───────────────────────────────────────────────────────────

    @Test
    fun `parseMrInfo maps opened MR`() {
        val info = GitLabJsonParser.parseMrInfo(mrJson(state = "opened"))
        assertEquals(42, info.number)
        assertEquals("My MR", info.title)
        assertEquals("https://gitlab.com/owner/repo/-/merge_requests/42", info.url)
        assertEquals(PrState.OPEN, info.state)
    }

    @Test
    fun `parseMrInfo maps closed MR`() {
        val info = GitLabJsonParser.parseMrInfo(mrJson(state = "closed"))
        assertEquals(PrState.CLOSED, info.state)
    }

    @Test
    fun `parseMrInfo maps merged MR`() {
        val info = GitLabJsonParser.parseMrInfo(mrJson(state = "merged"))
        assertEquals(PrState.MERGED, info.state)
    }

    @Test
    fun `parseMrInfo treats locked as open`() {
        val info = GitLabJsonParser.parseMrInfo(mrJson(state = "locked"))
        assertEquals(PrState.OPEN, info.state)
    }

    @Test
    fun `parseMrInfo throws on missing required field`() {
        assertThrows<PrProviderException> {
            GitLabJsonParser.parseMrInfo("""{"iid": 1}""")
        }
    }

    // ── parseMrList ───────────────────────────────────────────────────────────

    @Test
    fun `parseMrList returns all items`() {
        val json = """[${mrJson(iid = 1)}, ${mrJson(iid = 2)}, ${mrJson(iid = 3)}]"""
        val list = GitLabJsonParser.parseMrList(json)
        assertEquals(3, list.size)
        assertEquals(listOf(1, 2, 3), list.map { it.number })
    }

    @Test
    fun `parseMrList returns empty for empty array`() {
        assertEquals(emptyList<Any>(), GitLabJsonParser.parseMrList("[]"))
    }

    // ── parsePipelines ────────────────────────────────────────────────────────

    @Test
    fun `parsePipelines returns NONE for empty array`() {
        assertEquals(ChecksState.NONE, GitLabJsonParser.parsePipelines("[]"))
    }

    @Test
    fun `parsePipelines returns PENDING for running pipeline`() {
        val json = """[{"status":"running"}]"""
        assertEquals(ChecksState.PENDING, GitLabJsonParser.parsePipelines(json))
    }

    @Test
    fun `parsePipelines returns PENDING for pending pipeline`() {
        val json = """[{"status":"pending"}]"""
        assertEquals(ChecksState.PENDING, GitLabJsonParser.parsePipelines(json))
    }

    @Test
    fun `parsePipelines returns PENDING for created pipeline`() {
        val json = """[{"status":"created"}]"""
        assertEquals(ChecksState.PENDING, GitLabJsonParser.parsePipelines(json))
    }

    @Test
    fun `parsePipelines returns FAILING for failed pipeline`() {
        val json = """[{"status":"success"}, {"status":"failed"}]"""
        assertEquals(ChecksState.FAILING, GitLabJsonParser.parsePipelines(json))
    }

    @Test
    fun `parsePipelines returns FAILING for cancelled pipeline`() {
        val json = """[{"status":"cancelled"}]"""
        assertEquals(ChecksState.FAILING, GitLabJsonParser.parsePipelines(json))
    }

    @Test
    fun `parsePipelines returns PASSING when all succeed`() {
        val json = """[{"status":"success"}, {"status":"success"}]"""
        assertEquals(ChecksState.PASSING, GitLabJsonParser.parsePipelines(json))
    }

    @Test
    fun `parsePipelines PENDING takes precedence over FAILING`() {
        val json = """[{"status":"failed"}, {"status":"running"}]"""
        assertEquals(ChecksState.PENDING, GitLabJsonParser.parsePipelines(json))
    }

    // ── parseApprovals ────────────────────────────────────────────────────────

    @Test
    fun `parseApprovals returns APPROVED when approved is true`() {
        val json = """{"approved": true, "approvals_required": 1}"""
        assertEquals(ReviewState.APPROVED, GitLabJsonParser.parseApprovals(json))
    }

    @Test
    fun `parseApprovals returns REVIEW_REQUIRED when not approved and approvals required`() {
        val json = """{"approved": false, "approvals_required": 2}"""
        assertEquals(ReviewState.REVIEW_REQUIRED, GitLabJsonParser.parseApprovals(json))
    }

    @Test
    fun `parseApprovals returns NONE when not approved and no approvals required`() {
        val json = """{"approved": false, "approvals_required": 0}"""
        assertEquals(ReviewState.NONE, GitLabJsonParser.parseApprovals(json))
    }

    @Test
    fun `parseApprovals defaults to not approved when fields missing`() {
        val json = """{}"""
        assertEquals(ReviewState.NONE, GitLabJsonParser.parseApprovals(json))
    }

    // ── JSON builders ─────────────────────────────────────────────────────────

    private fun mrJson(
        iid: Int = 42,
        state: String = "opened",
    ) = """
        {
          "iid": $iid,
          "title": "My MR",
          "web_url": "https://gitlab.com/owner/repo/-/merge_requests/$iid",
          "state": "$state"
        }
    """.trimIndent()
}

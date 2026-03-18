package com.github.ydymovopenclawbot.stackworktree.pr.github

import com.github.ydymovopenclawbot.stackworktree.pr.ChecksState
import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrState
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import com.github.ydymovopenclawbot.stackworktree.pr.ReviewState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stateless parser for GitHub REST API responses.
 *
 * Uses [kotlinx.serialization.json] (already on the classpath) to navigate raw JSON strings
 * without requiring annotated data-class models for every endpoint shape.
 */
object GitHubJsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    // ── PR object ─────────────────────────────────────────────────────────────

    /**
     * Parses a single PR object (response from `GET /repos/{owner}/{repo}/pulls/{id}`
     * or `POST /repos/{owner}/{repo}/pulls`) into a [PrInfo].
     */
    fun parsePrInfo(rawJson: String): PrInfo = parsePrObject(json.parseToJsonElement(rawJson).jsonObject)

    /**
     * Parses an array of PR objects (response from `GET /repos/{owner}/{repo}/pulls`)
     * into a list of [PrInfo].
     */
    fun parsePrList(rawJson: String): List<PrInfo> =
        json.parseToJsonElement(rawJson).jsonArray.map { parsePrObject(it.jsonObject) }

    private fun parsePrObject(obj: JsonObject): PrInfo {
        val number = obj["number"]!!.jsonPrimitive.int
        val title = obj["title"]!!.jsonPrimitive.content
        val url = obj["html_url"]!!.jsonPrimitive.content
        val apiState = obj["state"]!!.jsonPrimitive.content       // "open" | "closed"
        val merged = obj["merged"]?.jsonPrimitive?.boolean ?: false

        val prState = when {
            merged -> PrState.MERGED
            apiState == "open" -> PrState.OPEN
            else -> PrState.CLOSED
        }
        return PrInfo(number = number, title = title, url = url, state = prState)
    }

    // ── Check runs ────────────────────────────────────────────────────────────

    /**
     * Parses the response from `GET /repos/{owner}/{repo}/commits/{sha}/check-runs`
     * into a [ChecksState].
     *
     * Mapping rules (evaluated in order):
     * - No check runs → [ChecksState.NONE]
     * - Any run with status `"in_progress"` or `"queued"` → [ChecksState.PENDING]
     * - Any run with conclusion `"failure"` or `"timed_out"` or `"cancelled"` → [ChecksState.FAILING]
     * - All runs completed successfully → [ChecksState.PASSING]
     */
    fun parseCheckRuns(rawJson: String): ChecksState {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val runs = root["check_runs"]?.jsonArray ?: return ChecksState.NONE
        if (runs.isEmpty()) return ChecksState.NONE

        var anyPending = false
        var anyFailing = false

        for (run in runs) {
            val obj = run.jsonObject
            val status = obj["status"]?.jsonPrimitive?.content ?: ""
            val conclusion = obj["conclusion"]?.jsonPrimitive?.content ?: ""

            if (status == "in_progress" || status == "queued") anyPending = true
            if (conclusion in FAILING_CONCLUSIONS) anyFailing = true
        }

        return when {
            anyPending -> ChecksState.PENDING
            anyFailing -> ChecksState.FAILING
            else -> ChecksState.PASSING
        }
    }

    // ── Reviews ───────────────────────────────────────────────────────────────

    /**
     * Parses the response from `GET /repos/{owner}/{repo}/pulls/{id}/reviews`
     * into a [ReviewState].
     *
     * Strategy: take the *latest* review per reviewer (by review `id` order), then:
     * - Any `"CHANGES_REQUESTED"` (even if another reviewer approved) → [ReviewState.CHANGES_REQUESTED]
     * - All actionable reviews are `"APPROVED"` → [ReviewState.APPROVED]
     * - No reviews → [ReviewState.REVIEW_REQUIRED]
     */
    fun parseReviews(rawJson: String): ReviewState {
        val reviews = json.parseToJsonElement(rawJson).jsonArray
        if (reviews.isEmpty()) return ReviewState.REVIEW_REQUIRED

        // Keyed by reviewer login → latest state (reviews are returned oldest-first)
        val latestByReviewer = mutableMapOf<String, String>()
        for (review in reviews) {
            val obj = review.jsonObject
            val login = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: continue
            val state = obj["state"]?.jsonPrimitive?.content ?: continue
            // Only track actionable states; skip COMMENTED / PENDING / DISMISSED
            if (state in ACTIONABLE_REVIEW_STATES) {
                latestByReviewer[login] = state
            }
        }

        if (latestByReviewer.isEmpty()) return ReviewState.REVIEW_REQUIRED

        return when {
            latestByReviewer.values.any { it == "CHANGES_REQUESTED" } -> ReviewState.CHANGES_REQUESTED
            latestByReviewer.values.all { it == "APPROVED" } -> ReviewState.APPROVED
            else -> ReviewState.REVIEW_REQUIRED
        }
    }

    // ── Full status ───────────────────────────────────────────────────────────

    /**
     * Convenience method that composes [parsePrInfo], [parseCheckRuns], and [parseReviews]
     * into a [PrStatus].
     */
    fun parsePrStatus(
        prJson: String,
        checkRunsJson: String,
        reviewsJson: String,
    ): PrStatus = PrStatus(
        prInfo = parsePrInfo(prJson),
        checksState = parseCheckRuns(checkRunsJson),
        reviewState = parseReviews(reviewsJson),
    )

    // ── constants ─────────────────────────────────────────────────────────────

    private val FAILING_CONCLUSIONS = setOf("failure", "timed_out", "cancelled", "action_required")
    private val ACTIONABLE_REVIEW_STATES = setOf("APPROVED", "CHANGES_REQUESTED")
}

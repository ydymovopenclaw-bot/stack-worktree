package com.github.ydymovopenclawbot.stackworktree.pr.gitlab

import com.github.ydymovopenclawbot.stackworktree.pr.ChecksState
import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrState
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import com.github.ydymovopenclawbot.stackworktree.pr.ReviewState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stateless parser for GitLab REST API v4 responses.
 *
 * Uses [kotlinx.serialization.json] to navigate raw JSON strings without requiring
 * annotated data-class models for every endpoint shape.
 */
object GitLabJsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    // ── MR object ─────────────────────────────────────────────────────────────

    /**
     * Parses a single MR object (response from `POST /api/v4/projects/:id/merge_requests`
     * or `PUT /api/v4/projects/:id/merge_requests/:iid`) into a [PrInfo].
     */
    fun parseMrInfo(rawJson: String): PrInfo =
        parseMrObject(json.parseToJsonElement(rawJson).jsonObject)

    /**
     * Parses an array of MR objects (response from `GET /api/v4/projects/:id/merge_requests`)
     * into a list of [PrInfo].
     */
    fun parseMrList(rawJson: String): List<PrInfo> =
        json.parseToJsonElement(rawJson).jsonArray.map { parseMrObject(it.jsonObject) }

    private fun parseMrObject(obj: kotlinx.serialization.json.JsonObject): PrInfo {
        val iid = obj["iid"]!!.jsonPrimitive.int
        val title = obj["title"]!!.jsonPrimitive.content
        val url = obj["web_url"]!!.jsonPrimitive.content
        // GitLab states: "opened", "closed", "merged", "locked"
        val apiState = obj["state"]!!.jsonPrimitive.content

        val prState = when (apiState) {
            "merged" -> PrState.MERGED
            "closed" -> PrState.CLOSED
            else -> PrState.OPEN   // "opened", "locked", or unknown → treat as open
        }
        return PrInfo(number = iid, title = title, url = url, state = prState)
    }

    // ── Pipelines (CI checks) ─────────────────────────────────────────────────

    /**
     * Parses the pipeline list from
     * `GET /api/v4/projects/:id/merge_requests/:iid/pipelines` into a [ChecksState].
     *
     * Mapping rules (evaluated in order):
     * - Empty list → [ChecksState.NONE]
     * - Any pipeline with a running/pending/scheduled status → [ChecksState.PENDING]
     * - Any pipeline with status `"failed"` or `"canceled"` → [ChecksState.FAILING]
     * - All pipelines succeeded → [ChecksState.PASSING]
     */
    fun parsePipelines(rawJson: String): ChecksState {
        val pipelines = json.parseToJsonElement(rawJson).jsonArray
        if (pipelines.isEmpty()) return ChecksState.NONE

        var anyPending = false
        var anyFailing = false

        for (pipeline in pipelines) {
            when (pipeline.jsonObject["status"]?.jsonPrimitive?.content ?: "") {
                "running", "pending", "created",
                "waiting_for_resource", "preparing", "scheduled" -> anyPending = true

                "failed", "canceled" -> anyFailing = true
            }
        }

        return when {
            anyPending -> ChecksState.PENDING
            anyFailing -> ChecksState.FAILING
            else -> ChecksState.PASSING
        }
    }

    // ── Approvals (review decision) ───────────────────────────────────────────

    /**
     * Parses the approvals response from
     * `GET /api/v4/projects/:id/merge_requests/:iid/approvals` into a [ReviewState].
     *
     * Mapping rules:
     * - `approved == true` → [ReviewState.APPROVED]
     * - `approvals_required > 0` and not yet approved → [ReviewState.REVIEW_REQUIRED]
     * - No approvals required and not approved → [ReviewState.NONE]
     */
    fun parseApprovals(rawJson: String): ReviewState {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        val approved = obj["approved"]?.jsonPrimitive?.boolean ?: false
        val approvalsRequired = obj["approvals_required"]?.jsonPrimitive?.int ?: 0

        return when {
            approved -> ReviewState.APPROVED
            approvalsRequired > 0 -> ReviewState.REVIEW_REQUIRED
            else -> ReviewState.NONE
        }
    }

    // ── Full status ───────────────────────────────────────────────────────────

    /**
     * Convenience method that composes [parseMrInfo], [parsePipelines], and [parseApprovals]
     * into a [PrStatus].
     */
    fun parsePrStatus(mrJson: String, pipelinesJson: String, approvalsJson: String): PrStatus =
        PrStatus(
            prInfo = parseMrInfo(mrJson),
            checksState = parsePipelines(pipelinesJson),
            reviewState = parseApprovals(approvalsJson),
        )
}

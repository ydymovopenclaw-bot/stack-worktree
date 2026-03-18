package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.state.BranchHealth
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [HealthCalculator].
 *
 * No IntelliJ Platform runtime is required — [HealthCalculator] is a pure-logic
 * object with zero IDE dependencies.
 */
class HealthCalculatorTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun node(
        name: String,
        parent: String? = null,
        health: BranchHealth = BranchHealth.CLEAN,
    ) = BranchNode(name = name, parent = parent, health = health)

    private fun ab(ahead: Int = 0, behind: Int = 0) = AheadBehind(ahead = ahead, behind = behind)

    // ------------------------------------------------------------------
    // computeSingle — CLEAN baseline
    // ------------------------------------------------------------------

    @Test
    fun `CLEAN when ahead=0 behind=0 and no conflicts or merges`() {
        val result = HealthCalculator.computeSingle(
            branch = "feature",
            node   = node("feature"),
            ab     = ab(ahead = 0, behind = 0),
        )
        assertEquals(HealthStatus.CLEAN, result)
    }

    @Test
    fun `CLEAN when ab is null and no other signals`() {
        val result = HealthCalculator.computeSingle(
            branch = "feature",
            node   = node("feature"),
            ab     = null,
        )
        assertEquals(HealthStatus.CLEAN, result)
    }

    @Test
    fun `CLEAN when branch is ahead of parent but not behind`() {
        val result = HealthCalculator.computeSingle(
            branch = "feature",
            node   = node("feature"),
            ab     = ab(ahead = 5, behind = 0),
        )
        assertEquals(HealthStatus.CLEAN, result)
    }

    // ------------------------------------------------------------------
    // computeSingle — STALE (amber, behind > 0)
    // ------------------------------------------------------------------

    @Test
    fun `STALE when behind is 1`() {
        val result = HealthCalculator.computeSingle(
            branch = "feature",
            node   = node("feature"),
            ab     = ab(ahead = 0, behind = 1),
        )
        assertEquals(HealthStatus.STALE, result)
    }

    @Test
    fun `STALE when significantly behind parent`() {
        val result = HealthCalculator.computeSingle(
            branch = "feature",
            node   = node("feature"),
            ab     = ab(ahead = 3, behind = 20),
        )
        assertEquals(HealthStatus.STALE, result)
    }

    // ------------------------------------------------------------------
    // computeSingle — CONFLICT (red, stored HAS_CONFLICTS)
    // ------------------------------------------------------------------

    @Test
    fun `CONFLICT when stored health is HAS_CONFLICTS`() {
        val result = HealthCalculator.computeSingle(
            branch = "feature",
            node   = node("feature", health = BranchHealth.HAS_CONFLICTS),
            ab     = ab(ahead = 0, behind = 0),
        )
        assertEquals(HealthStatus.CONFLICT, result)
    }

    @Test
    fun `CONFLICT even when behind is zero`() {
        val result = HealthCalculator.computeSingle(
            branch = "feature",
            node   = node("feature", health = BranchHealth.HAS_CONFLICTS),
            ab     = ab(ahead = 2, behind = 0),
        )
        assertEquals(HealthStatus.CONFLICT, result)
    }

    // ------------------------------------------------------------------
    // computeSingle — MERGED (purple)
    // ------------------------------------------------------------------

    @Test
    fun `MERGED when branch is in mergedBranches set`() {
        val result = HealthCalculator.computeSingle(
            branch         = "feature",
            node           = node("feature"),
            ab             = ab(),
            mergedBranches = setOf("feature"),
        )
        assertEquals(HealthStatus.MERGED, result)
    }

    @Test
    fun `MERGED even when branch also has HAS_CONFLICTS stored`() {
        // MERGED takes the highest priority.
        val result = HealthCalculator.computeSingle(
            branch         = "feature",
            node           = node("feature", health = BranchHealth.HAS_CONFLICTS),
            ab             = ab(behind = 5),
            mergedBranches = setOf("feature"),
        )
        assertEquals(HealthStatus.MERGED, result)
    }

    @Test
    fun `MERGED even when behind count is positive`() {
        val result = HealthCalculator.computeSingle(
            branch         = "feature",
            node           = node("feature"),
            ab             = ab(ahead = 0, behind = 10),
            mergedBranches = setOf("feature"),
        )
        assertEquals(HealthStatus.MERGED, result)
    }

    @Test
    fun `not MERGED when branch name does not appear in mergedBranches set`() {
        val result = HealthCalculator.computeSingle(
            branch         = "feature",
            node           = node("feature"),
            ab             = ab(),
            mergedBranches = setOf("other-branch"),
        )
        assertEquals(HealthStatus.CLEAN, result)
    }

    // ------------------------------------------------------------------
    // Priority ordering: MERGED > CONFLICT > STALE > CLEAN
    // ------------------------------------------------------------------

    @Test
    fun `CONFLICT takes priority over STALE when both signals present`() {
        val result = HealthCalculator.computeSingle(
            branch = "feature",
            node   = node("feature", health = BranchHealth.HAS_CONFLICTS),
            ab     = ab(behind = 3),  // would be STALE without the conflict flag
        )
        assertEquals(HealthStatus.CONFLICT, result)
    }

    @Test
    fun `MERGED takes priority over CONFLICT and STALE together`() {
        val result = HealthCalculator.computeSingle(
            branch         = "feature",
            node           = node("feature", health = BranchHealth.HAS_CONFLICTS),
            ab             = ab(behind = 7),
            mergedBranches = setOf("feature"),
        )
        assertEquals(HealthStatus.MERGED, result)
    }

    // ------------------------------------------------------------------
    // compute (batch) — delegates to computeSingle per branch
    // ------------------------------------------------------------------

    @Test
    fun `compute returns correct health for every branch in the map`() {
        val branches = mapOf(
            "main"    to node("main",    parent = null),
            "feat-a"  to node("feat-a",  parent = "main"),
            "feat-b"  to node("feat-b",  parent = "main", health = BranchHealth.HAS_CONFLICTS),
            "feat-c"  to node("feat-c",  parent = "main"),
        )
        val aheadBehind = mapOf(
            "feat-a" to ab(ahead = 2, behind = 1),   // → STALE
            "feat-c" to ab(ahead = 0, behind = 0),   // → CLEAN
            // feat-b: ab absent, but conflict flag → CONFLICT
        )
        val merged = setOf("feat-b")  // wait, feat-b has HAS_CONFLICTS AND is in merged →
        // MERGED should win

        val result = HealthCalculator.compute(branches, aheadBehind, merged)

        assertEquals(HealthStatus.CLEAN,   result["main"])
        assertEquals(HealthStatus.STALE,   result["feat-a"])
        assertEquals(HealthStatus.MERGED,  result["feat-b"])  // MERGED beats CONFLICT
        assertEquals(HealthStatus.CLEAN,   result["feat-c"])
    }

    @Test
    fun `compute with empty branches returns empty map`() {
        val result = HealthCalculator.compute(emptyMap())
        assertEquals(emptyMap<String, HealthStatus>(), result)
    }

    @Test
    fun `compute with empty mergedBranches and no ahead-behind produces all CLEAN`() {
        val branches = mapOf(
            "main" to node("main"),
            "feat" to node("feat", parent = "main"),
        )
        val result = HealthCalculator.compute(branches)
        assertEquals(HealthStatus.CLEAN, result["main"])
        assertEquals(HealthStatus.CLEAN, result["feat"])
    }

    // ------------------------------------------------------------------
    // Trunk node always resolves to CLEAN (no parent, ab always null)
    // ------------------------------------------------------------------

    @Test
    fun `trunk branch with no ab data and no signals resolves to CLEAN`() {
        val result = HealthCalculator.computeSingle(
            branch = "main",
            node   = node("main", parent = null),
            ab     = null,
        )
        assertEquals(HealthStatus.CLEAN, result)
    }
}

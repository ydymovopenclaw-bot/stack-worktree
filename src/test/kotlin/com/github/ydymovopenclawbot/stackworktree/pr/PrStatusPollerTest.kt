package com.github.ydymovopenclawbot.stackworktree.pr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrStatusPollerTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    /** Map-backed [PrLayer] that returns configurable responses per branch. */
    private class FakePrLayer : PrLayer {
        val statusByBranch = mutableMapOf<String, PrStatus>()

        /** When non-null, [getPrStatus] throws this exception for every call. */
        var throwOnGet: Exception? = null

        override fun findPr(branch: String): PrInfo? = statusByBranch[branch]?.prInfo

        override fun getPrStatus(branch: String): PrStatus? {
            throwOnGet?.let { throw it }
            return statusByBranch[branch]
        }

        override fun openPr(branch: String) { /* no-op */ }
    }

    /** Fixed-list [BranchProvider]. */
    private class FakeBranchProvider(private val branches: List<String>) : BranchProvider {
        override fun trackedBranches(): List<String> = branches
    }

    /** Builds a minimal [PrStatus] for testing. */
    private fun prStatus(
        branch: String,
        state: PrState = PrState.OPEN,
        isDraft: Boolean = false,
        checks: ChecksState = ChecksState.PASSING,
        review: ReviewState = ReviewState.APPROVED,
    ) = PrStatus(
        prInfo = PrInfo(number = 1, title = "PR for $branch", url = "https://example.com/1",
            state = state, isDraft = isDraft),
        checksState = checks,
        reviewState = review,
    )

    /**
     * Helper that constructs a [PrStatusPoller] scoped to [scope].
     *
     * Passes [UnconfinedTestDispatcher] as the IO dispatcher so that
     * [PrStatusPoller.pollOnce]'s `withContext(ioDispatcher)` completes
     * eagerly within the test's virtual-time clock rather than running on a
     * real `Dispatchers.IO` thread that the test scheduler cannot control.
     */
    private fun createPoller(
        scope: TestScope,
        prLayer: PrLayer = FakePrLayer(),
        branches: List<String> = emptyList(),
        pollIntervalMs: Long = 60_000L,
    ) = PrStatusPoller(
        scope = scope,
        prLayer = prLayer,
        branchProvider = FakeBranchProvider(branches),
        pollIntervalMs = pollIntervalMs,
        ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `initial state has empty badges`() = runTest {
        val poller = createPoller(this)
        assertTrue(poller.badges.value.statuses.isEmpty())
        assertEquals(0L, poller.badges.value.lastUpdated)
    }

    @Test
    fun `pollOnce populates badges from PrLayer`() = runTest {
        val prLayer = FakePrLayer()
        prLayer.statusByBranch["feature-a"] = prStatus("feature-a",
            checks = ChecksState.PASSING, review = ReviewState.APPROVED)
        val poller = createPoller(this, prLayer = prLayer, branches = listOf("feature-a"))

        poller.pollOnce()

        val status = poller.badges.value.statuses["feature-a"]
        assertNotNull(status)
        assertEquals(PrState.OPEN, status!!.prInfo.state)
        assertEquals(ChecksState.PASSING, status.checksState)
        assertEquals(ReviewState.APPROVED, status.reviewState)
    }

    @Test
    fun `branch with no PR gets null status`() = runTest {
        val poller = createPoller(this, branches = listOf("no-pr-branch"))
        poller.pollOnce()

        // Key must be present (branch is tracked), value must be null (no PR)
        assertTrue(poller.badges.value.statuses.containsKey("no-pr-branch"))
        assertNull(poller.badges.value.statuses["no-pr-branch"])
    }

    @Test
    fun `pollOnce removes branches that are no longer tracked`() = runTest {
        val prLayer = FakePrLayer()
        prLayer.statusByBranch["old-branch"] = prStatus("old-branch")

        // First poll with "old-branch"
        val poller = createPoller(this, prLayer = prLayer, branches = listOf("old-branch"))
        poller.pollOnce()
        assertTrue(poller.badges.value.statuses.containsKey("old-branch"))

        // Second poll with an empty branch list (old-branch no longer tracked)
        val pollerEmpty = createPoller(this, prLayer = prLayer, branches = emptyList())
        pollerEmpty.pollOnce()
        assertTrue(pollerEmpty.badges.value.statuses.isEmpty())
    }

    @Test
    fun `polling loop only polls when tab is visible`() = runTest {
        val prLayer = FakePrLayer()
        prLayer.statusByBranch["main"] = prStatus("main")
        val poller = createPoller(this, prLayer = prLayer, branches = listOf("main"),
            pollIntervalMs = 10_000L)
        poller.start()

        // Tab not visible — advance 30 s (3 intervals), badges should stay empty
        advanceTimeBy(30_001L)
        assertTrue(poller.badges.value.statuses.isEmpty())

        poller.dispose()
    }

    @Test
    fun `polling loop polls when tab is visible`() = runTest {
        val prLayer = FakePrLayer()
        prLayer.statusByBranch["main"] = prStatus("main")
        val poller = createPoller(this, prLayer = prLayer, branches = listOf("main"),
            pollIntervalMs = 10_000L)
        poller.start()
        poller.setTabVisible(true)

        // Immediate poll from setTabVisible fires first; allow it to complete
        advanceTimeBy(1L)
        assertTrue(poller.badges.value.statuses.containsKey("main"),
            "Badges should be populated after immediate poll on visibility change")

        poller.dispose()
    }

    @Test
    fun `setTabVisible triggers immediate poll on hidden to visible transition`() = runTest {
        val prLayer = FakePrLayer()
        prLayer.statusByBranch["main"] = prStatus("main")
        val poller = createPoller(this, prLayer = prLayer, branches = listOf("main"),
            pollIntervalMs = 60_000L)
        poller.start()

        // Transition hidden → visible; immediate poll should fire
        poller.setTabVisible(true)
        advanceTimeBy(1L)

        assertTrue(poller.badges.value.statuses.containsKey("main"))
        poller.dispose()
    }

    @Test
    fun `setTabVisible does not trigger extra poll when already visible`() = runTest {
        var pollCount = 0
        val countingLayer = object : PrLayer {
            override fun findPr(branch: String): PrInfo? = null
            override fun getPrStatus(branch: String): PrStatus? { pollCount++; return null }
            override fun openPr(branch: String) {}
        }
        val poller = PrStatusPoller(
            scope = this,
            prLayer = countingLayer,
            branchProvider = BranchProvider { listOf("main") },
            pollIntervalMs = 60_000L,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        poller.start()

        // First setTabVisible(true) → 1 immediate poll
        poller.setTabVisible(true)
        advanceTimeBy(1L)
        assertEquals(1, pollCount)

        // Calling setTabVisible(true) again while already visible → no extra poll
        poller.setTabVisible(true)
        advanceTimeBy(1L)
        assertEquals(1, pollCount)

        poller.dispose()
    }

    @Test
    fun `refreshNow triggers immediate poll`() = runTest {
        val prLayer = FakePrLayer()
        prLayer.statusByBranch["main"] = prStatus("main")
        val poller = createPoller(this, prLayer = prLayer, branches = listOf("main"),
            pollIntervalMs = 60_000L)

        poller.refreshNow()
        advanceTimeBy(1L)

        assertTrue(poller.badges.value.statuses.containsKey("main"))
    }

    @Test
    fun `PrLayer exception per-branch does not crash poller and retains old value`() = runTest {
        val prLayer = FakePrLayer()
        val oldStatus = prStatus("main")
        prLayer.statusByBranch["main"] = oldStatus
        val poller = createPoller(this, prLayer = prLayer, branches = listOf("main"))

        // First poll succeeds
        poller.pollOnce()
        assertNotNull(poller.badges.value.statuses["main"])

        // Second poll throws — old value should be retained
        prLayer.throwOnGet = RuntimeException("API unavailable")
        poller.pollOnce()

        assertEquals(oldStatus, poller.badges.value.statuses["main"],
            "Stale value should be retained when PrLayer throws")
    }

    @Test
    fun `dispose cancels polling job cleanly`() = runTest {
        val poller = createPoller(this, pollIntervalMs = 1_000L)
        poller.start()
        poller.setTabVisible(true)
        poller.dispose()

        // Advancing time after dispose should not cause errors
        advanceTimeBy(5_000L)
    }

    @Test
    fun `lastUpdated is set after a poll`() = runTest {
        val poller = createPoller(this, branches = listOf("main"))
        assertEquals(0L, poller.badges.value.lastUpdated)

        poller.pollOnce()

        assertTrue(poller.badges.value.lastUpdated > 0L)
    }

    @Test
    fun `isDraft badge state is preserved in snapshot`() = runTest {
        val prLayer = FakePrLayer()
        prLayer.statusByBranch["wip"] = prStatus("wip", isDraft = true)
        val poller = createPoller(this, prLayer = prLayer, branches = listOf("wip"))

        poller.pollOnce()

        assertTrue(poller.badges.value.statuses["wip"]!!.prInfo.isDraft)
    }
}

package com.github.ydymovopenclawbot.stackworktree.pr

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returns the list of branch names that the poller should query for PR status.
 *
 * Extracted as a `fun interface` to decouple [PrStatusPoller] from any particular
 * git or state implementation.  Production code passes a lambda that delegates to
 * the active [com.github.ydymovopenclawbot.stackworktree.state.StateLayer]; unit
 * tests pass a simple list lambda.
 */
fun interface BranchProvider {
    /** Returns the current set of tracked branch names. Must be safe to call on any thread. */
    fun trackedBranches(): List<String>
}

/**
 * Background poller that periodically fetches PR and CI status for all tracked
 * branches and publishes the results as a [StateFlow] of [PrBadgeSnapshot].
 *
 * ## Design
 * - Constructor-injected [CoroutineScope]: the caller controls the lifecycle.
 *   In production a project-scoped coroutine scope is passed; in tests a [TestScope].
 * - Constructor-injected [PrLayer]: enables fake implementations in unit tests.
 * - Constructor-injected [BranchProvider]: decouples branch discovery from git.
 * - Implements [Disposable]: calling [dispose] cancels the polling coroutine.
 *
 * ## Polling logic
 * The loop runs at [pollIntervalMs] (default 60 s).  Each cycle is skipped when
 * the Stacks tab is hidden — signalled via [setTabVisible].  When the tab becomes
 * visible again an immediate [pollOnce] is triggered so badges are never stale when
 * the user switches back to the tab.
 *
 * [refreshNow] triggers an out-of-band [pollOnce] for the manual-refresh button.
 *
 * ## Error handling
 * [PrLayer.getPrStatus] errors are caught per-branch; a single failing branch does
 * not prevent the other branches from updating.  The [StateFlow] is always updated
 * at the end of [pollOnce], even when all branches failed — in that case the old
 * values are preserved individually per-branch via the accumulated [statuses] map.
 *
 * @param scope          Coroutine scope that governs the lifetime of the polling loop.
 * @param prLayer        Source of PR and CI data.
 * @param branchProvider Supplies the list of branches to poll each cycle.
 * @param pollIntervalMs Interval between automatic poll cycles, in milliseconds.
 * @param ioDispatcher   Dispatcher used for blocking [PrLayer] calls.  Defaults to
 *                       [Dispatchers.IO]; override in tests with [kotlinx.coroutines.test.UnconfinedTestDispatcher].
 */
class PrStatusPoller(
    private val scope: CoroutineScope,
    private val prLayer: PrLayer,
    private val branchProvider: BranchProvider,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Disposable {

    // ── Public API ────────────────────────────────────────────────────────────

    /** Latest snapshot of PR/CI status per branch. Starts empty; updated after each poll. */
    val badges: StateFlow<PrBadgeSnapshot> get() = _badges.asStateFlow()

    // ── State ─────────────────────────────────────────────────────────────────

    private val _badges = MutableStateFlow(PrBadgeSnapshot())

    @Volatile
    private var tabVisible: Boolean = false

    private var pollingJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the background polling loop.  Must be called exactly once after construction.
     *
     * The loop waits [pollIntervalMs] between cycles and skips a cycle when
     * [tabVisible] is `false`.
     */
    fun start() {
        pollingJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                if (tabVisible) {
                    pollOnce()
                }
            }
        }
    }

    /**
     * Notifies the poller whether the Stacks tab is currently visible.
     *
     * - When [visible] is `false` the next scheduled cycle is skipped.
     * - When [visible] transitions from `false` to `true`, an immediate
     *   [pollOnce] is triggered so badges are fresh as soon as the tab opens.
     */
    fun setTabVisible(visible: Boolean) {
        val wasHidden = !tabVisible
        tabVisible = visible
        if (visible && wasHidden) {
            scope.launch { pollOnce() }
        }
    }

    /**
     * Immediately triggers a single poll cycle, independent of the periodic timer.
     * Used by the manual-refresh action to keep badges in sync on demand.
     */
    fun refreshNow() {
        scope.launch { pollOnce() }
    }

    override fun dispose() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ── Poll logic ────────────────────────────────────────────────────────────

    /**
     * Fetches PR status for every branch returned by [branchProvider] and updates
     * [badges].  All [PrLayer.getPrStatus] calls are dispatched on [Dispatchers.IO]
     * since they perform blocking network I/O.
     *
     * Existing entries for branches that are no longer tracked are removed;
     * per-branch failures preserve the previous entry for that branch.
     */
    internal suspend fun pollOnce() {
        val branches = branchProvider.trackedBranches()
        if (branches.isEmpty()) {
            _badges.value = PrBadgeSnapshot(emptyMap(), System.currentTimeMillis())
            return
        }

        // Preserve old statuses so a single failing branch doesn't lose its previous value
        val previous = _badges.value.statuses
        val updated = mutableMapOf<String, PrStatus?>()

        for (branch in branches) {
            updated[branch] = try {
                withContext(ioDispatcher) { prLayer.getPrStatus(branch) }
            } catch (e: Exception) {
                LOG.warn("PrStatusPoller: failed to fetch status for '$branch'", e)
                previous[branch]   // retain stale value on error
            }
        }

        _badges.value = PrBadgeSnapshot(
            statuses = updated,
            lastUpdated = System.currentTimeMillis(),
        )
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        /** Default polling interval: 60 seconds. */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 60_000L

        private val LOG = Logger.getInstance(PrStatusPoller::class.java)
    }
}

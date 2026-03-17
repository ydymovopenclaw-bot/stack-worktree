package com.github.ydymovopenclawbot.stackworktree.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AheadBehindCalculatorTest {

    // ---------------------------------------------------------------------------
    // Fake collaborator
    // ---------------------------------------------------------------------------

    private class FakeGitLayer : GitLayer {
        /** Per-pair responses keyed by "branch:parent". */
        val responses = mutableMapOf<String, AheadBehind>()

        /** Total number of aheadBehind() calls made. */
        var callCount = 0

        override fun aheadBehind(branch: String, parent: String): AheadBehind {
            callCount++
            return responses["$branch:$parent"] ?: AheadBehind(0, 0)
        }

        // Unused in these tests
        override fun worktreeAdd(path: String, branch: String): Worktree =
            Worktree(path, branch, head = "", isLocked = false)
        override fun worktreeRemove(path: String) = Unit
        override fun worktreeList(): List<Worktree> = emptyList()
        override fun worktreePrune() = Unit
        override fun createBranch(branchName: String, baseBranch: String) = Unit
        override fun deleteBranch(branchName: String) = Unit
        override fun rebaseOnto(branch: String, newBase: String, upstream: String): RebaseResult =
            RebaseResult.Success
    }

    private lateinit var fake: FakeGitLayer
    private var fakeNow = 1_000L
    private lateinit var calc: AheadBehindCalculator

    @BeforeEach
    fun setUp() {
        fake = FakeGitLayer()
        fakeNow = 1_000L
        calc = AheadBehindCalculator(gitLayer = fake, ttlMs = 30_000L, clock = { fakeNow })
    }

    // ---------------------------------------------------------------------------
    // Basic correctness
    // ---------------------------------------------------------------------------

    @Test
    fun `same commit reports ahead=0 behind=0`() {
        fake.responses["feature:main"] = AheadBehind(0, 0)
        val result = calc.calculate(mapOf("feature" to "main"))
        assertEquals(AheadBehind(0, 0), result["feature"])
    }

    @Test
    fun `branch ahead only`() {
        fake.responses["feature:main"] = AheadBehind(3, 0)
        val result = calc.calculate(mapOf("feature" to "main"))
        assertEquals(AheadBehind(3, 0), result["feature"])
    }

    @Test
    fun `branch behind only`() {
        fake.responses["feature:main"] = AheadBehind(0, 2)
        val result = calc.calculate(mapOf("feature" to "main"))
        assertEquals(AheadBehind(0, 2), result["feature"])
    }

    @Test
    fun `branch both ahead and behind`() {
        fake.responses["feature:main"] = AheadBehind(3, 2)
        val result = calc.calculate(mapOf("feature" to "main"))
        assertEquals(AheadBehind(3, 2), result["feature"])
    }

    // ---------------------------------------------------------------------------
    // Caching
    // ---------------------------------------------------------------------------

    @Test
    fun `cache hit — gitLayer called only once within TTL`() {
        fake.responses["feature:main"] = AheadBehind(1, 0)
        calc.calculate(mapOf("feature" to "main"))
        calc.calculate(mapOf("feature" to "main"))
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `cache expiry — gitLayer called again after TTL`() {
        fake.responses["feature:main"] = AheadBehind(1, 0)
        calc.calculate(mapOf("feature" to "main"))

        fakeNow += 30_001L  // advance past TTL

        calc.calculate(mapOf("feature" to "main"))
        assertEquals(2, fake.callCount)
    }

    @Test
    fun `cache still valid at TTL boundary (one ms before expiry)`() {
        fake.responses["feature:main"] = AheadBehind(1, 0)
        calc.calculate(mapOf("feature" to "main"))

        fakeNow += 29_999L  // still within TTL

        calc.calculate(mapOf("feature" to "main"))
        assertEquals(1, fake.callCount)
    }

    // ---------------------------------------------------------------------------
    // Invalidation
    // ---------------------------------------------------------------------------

    @Test
    fun `invalidate all — next calculate re-fetches every branch`() {
        fake.responses["a:main"] = AheadBehind(1, 0)
        fake.responses["b:main"] = AheadBehind(0, 1)
        val branches = mapOf("a" to "main", "b" to "main")

        calc.calculate(branches)
        assertEquals(2, fake.callCount)

        calc.invalidate()
        calc.calculate(branches)
        assertEquals(4, fake.callCount)
    }

    @Test
    fun `invalidate single branch — only that branch re-fetched`() {
        fake.responses["a:main"] = AheadBehind(1, 0)
        fake.responses["b:main"] = AheadBehind(0, 1)
        val branches = mapOf("a" to "main", "b" to "main")

        calc.calculate(branches)   // both cached
        assertEquals(2, fake.callCount)

        calc.invalidate("a")       // invalidate only "a"
        calc.calculate(branches)   // "a" re-fetched, "b" from cache
        assertEquals(3, fake.callCount)
    }

    // ---------------------------------------------------------------------------
    // Batch efficiency
    // ---------------------------------------------------------------------------

    @Test
    fun `batch — only uncached branches call gitLayer`() {
        fake.responses["a:main"] = AheadBehind(1, 0)
        fake.responses["b:main"] = AheadBehind(0, 1)
        fake.responses["c:main"] = AheadBehind(2, 2)

        // Warm up cache for "a" and "b"
        calc.calculate(mapOf("a" to "main", "b" to "main"))
        assertEquals(2, fake.callCount)

        // Now calculate all three — only "c" should be fetched
        val result = calc.calculate(mapOf("a" to "main", "b" to "main", "c" to "main"))
        assertEquals(3, fake.callCount)
        assertEquals(AheadBehind(1, 0), result["a"])
        assertEquals(AheadBehind(0, 1), result["b"])
        assertEquals(AheadBehind(2, 2), result["c"])
    }

}

package com.github.ydymovopenclawbot.stackworktree.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StackStateService].
 *
 * Pure JVM — no IntelliJ Platform runtime required.
 */
class StackStateServiceTest {

    private lateinit var service: StackStateService

    @BeforeEach
    fun setUp() {
        // Instantiate directly; @Service annotation is ignored outside the platform.
        service = StackStateService()
    }

    // ── getParent / recordBranch ──────────────────────────────────────────────

    @Test
    fun `getParent returns null for unknown branch`() {
        assertNull(service.getParent("unknown"))
    }

    @Test
    fun `recordBranch stores parent relationship`() {
        service.recordBranch("feature", "main", null)
        assertEquals("main", service.getParent("feature"))
    }

    @Test
    fun `recordBranch overwrites existing parent`() {
        service.recordBranch("feature", "main",    null)
        service.recordBranch("feature", "develop", null)
        assertEquals("develop", service.getParent("feature"))
    }

    // ── getWorktreePath ───────────────────────────────────────────────────────

    @Test
    fun `getWorktreePath returns null when no path recorded`() {
        service.recordBranch("feature", "main", null)
        assertNull(service.getWorktreePath("feature"))
    }

    @Test
    fun `getWorktreePath returns recorded path`() {
        service.recordBranch("feature", "main", "/tmp/worktrees/feature")
        assertEquals("/tmp/worktrees/feature", service.getWorktreePath("feature"))
    }

    // ── getAllParents ─────────────────────────────────────────────────────────

    @Test
    fun `getAllParents returns empty map initially`() {
        assertEquals(emptyMap<String, String>(), service.getAllParents())
    }

    @Test
    fun `getAllParents includes all recorded branches`() {
        service.recordBranch("feat-a", "main",    null)
        service.recordBranch("feat-b", "main",    null)
        service.recordBranch("fix-1",  "feat-a",  null)

        val all = service.getAllParents()
        assertEquals(3, all.size)
        assertEquals("main",   all["feat-a"])
        assertEquals("main",   all["feat-b"])
        assertEquals("feat-a", all["fix-1"])
    }

    @Test
    fun `getAllParents returns defensive copy — mutations do not affect service`() {
        service.recordBranch("feat", "main", null)

        val snapshot = service.getAllParents().toMutableMap()
        snapshot["extra"] = "injected"

        // The service itself must not see the mutation.
        assertNull(service.getParent("extra"))
        assertEquals(1, service.getAllParents().size)
    }

    // ── Thread safety (smoke test) ────────────────────────────────────────────

    @Test
    fun `concurrent recordBranch calls do not throw`() {
        val threads = (1..20).map { i ->
            Thread { service.recordBranch("branch-$i", "main", null) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2_000) }

        assertEquals(20, service.getAllParents().size)
    }
}

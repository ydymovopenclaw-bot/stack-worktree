package com.github.ydymovopenclawbot.stackworktree.state

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class StackStateSerializationTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun `round-trip full StackState`() {
        val original = StackState(
            repoConfig = RepoConfig(trunk = "main", remote = "origin"),
            branches = mapOf(
                "main" to BranchNode(name = "main", parent = null),
                "feat/foo" to BranchNode(
                    name = "feat/foo",
                    parent = "main",
                    children = listOf("feat/foo-bar"),
                    worktreePath = "/tmp/worktrees/feat-foo",
                    prInfo = PrInfo(
                        provider = "github",
                        id = "42",
                        url = "https://github.com/org/repo/pull/42",
                        status = "open",
                        ciStatus = "passing",
                    ),
                    baseCommit = "deadbeef",
                    health = BranchHealth.NEEDS_REBASE,
                ),
            ),
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<StackState>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip BranchNode with all nullable fields null`() {
        val node = BranchNode(name = "main", parent = null)
        val encoded = json.encodeToString(node)
        val decoded = json.decodeFromString<BranchNode>(encoded)
        assertEquals(node, decoded)
    }

    @Test
    fun `all BranchHealth enum values survive round-trip`() {
        BranchHealth.entries.forEach { health ->
            val node = BranchNode(name = "branch", parent = "main", health = health)
            val decoded = json.decodeFromString<BranchNode>(json.encodeToString(node))
            assertEquals(health, decoded.health)
        }
    }
}

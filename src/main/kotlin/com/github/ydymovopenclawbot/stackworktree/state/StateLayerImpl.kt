package com.github.ydymovopenclawbot.stackworktree.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persists [PluginState] to `stackworktree.xml` in the project `.idea` directory.
 *
 * IntelliJ's XML serializer requires JavaBean-style inner state classes (mutable fields,
 * no-arg constructors), so [XmlState] and [XmlNode] act as the serialisation DTOs while
 * the public API works with the immutable [PluginState] / [TrackedBranchNode] types.
 */
@State(
    name = "StackWorktreeState",
    storages = [Storage("stackworktree.xml")],
)
@Service(Service.Level.PROJECT)
class StateLayerImpl : StateLayer, PersistentStateComponent<StateLayerImpl.XmlState> {

    // ── XML-serialisable DTOs ─────────────────────────────────────────────────

    class XmlNode {
        var name: String = ""
        var parentName: String? = null
        var children: MutableList<String> = mutableListOf()
    }

    class XmlState {
        var trunkBranch: String? = null
        var trackedBranches: MutableMap<String, XmlNode> = mutableMapOf()
        var activeWorktrees: MutableList<String> = mutableListOf()
        var lastUsedBranch: String? = null
    }

    // ── PersistentStateComponent ──────────────────────────────────────────────

    private var xmlState = XmlState()

    override fun getState(): XmlState = xmlState

    override fun loadState(state: XmlState) {
        xmlState = state
    }

    // ── StateLayer ────────────────────────────────────────────────────────────

    override fun load(): PluginState = PluginState(
        trunkBranch = xmlState.trunkBranch,
        trackedBranches = xmlState.trackedBranches.mapValues { (_, xml) ->
            TrackedBranchNode(
                name = xml.name,
                parentName = xml.parentName,
                children = xml.children.toList(),
            )
        },
        activeWorktrees = xmlState.activeWorktrees.toList(),
        lastUsedBranch  = xmlState.lastUsedBranch,
    )

    override fun save(state: PluginState) {
        xmlState.trunkBranch = state.trunkBranch
        xmlState.trackedBranches = state.trackedBranches
            .mapValues { (_, node) ->
                XmlNode().also {
                    it.name = node.name
                    it.parentName = node.parentName
                    it.children = node.children.toMutableList()
                }
            }
            .toMutableMap()
        xmlState.activeWorktrees = state.activeWorktrees.toMutableList()
        xmlState.lastUsedBranch  = state.lastUsedBranch
    }
}

package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehindCalculator
import com.github.ydymovopenclawbot.stackworktree.git.BranchDetailService
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.IntelliJGitRunner
import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.startup.StackGitChangeListener
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.PluginState
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.github.ydymovopenclawbot.stackworktree.state.StateStorage
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.HealthStatus
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphData
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphPanel
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.github.ydymovopenclawbot.stackworktree.actions.StackDataKeys
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBusConnection
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.Point
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

private val LOG = logger<StacksTabFactory>()

/**
 * Content provider for the "Stacks" tab in the VCS Changes view.
 *
 * Renders a [JBSplitter] with a [StackTreeToolbar] above it:
 * - **NORTH** – toolbar (New Stack / Add Branch / Restack* / Submit* / Sync* / Refresh).
 * - **Left**  – [StackGraphPanel]: the stack graph that emits node-selection events.
 * - **Right** – [BranchDetailPanel]: shows metadata for the selected branch.
 *
 * Selecting a node triggers [BranchDetailService.loadNode] on a pooled thread, then
 * updates the detail panel on the EDT.
 *
 * On [initContent] the panel subscribes to:
 * - [GitRepository.GIT_REPO_CHANGE] so the graph auto-refreshes on every commit, checkout,
 *   rebase, fetch, or pull.
 * - [StackTreeStateListener.TOPIC] so the graph refreshes immediately after
 *   [com.github.ydymovopenclawbot.stackworktree.actions.NewStackAction] (or any other writer)
 *   persists new state.
 *
 * Right-clicking a node shows a context menu:
 * - **Insert Branch Above / Below** — registered actions via `StackWorktree.StackBranchPopup`
 *   group (installed via [PopupHandler]).
 * - **Track Branch…** — always available; opens [TrackBranchDialog] to add an untracked
 *   local branch to the [StateLayer] tree.
 * - **Untrack Branch** — only for nodes present in [StateLayer.load].trackedBranches;
 *   removes the node via [OpsLayer.untrackBranch] without deleting the git branch.
 *
 * The graph auto-refreshes on [GitRepository.GIT_REPO_CHANGE] (commit / checkout /
 * rebase / fetch / pull) and on [STACK_STATE_TOPIC] (track / untrack operations).
 *
 * @param project Injected by the platform via
 *   [com.intellij.openapi.vcs.changes.ui.ChangesViewContentEP].
 */
class StacksTabFactory(private val project: Project) : ChangesViewContentProvider {

    private var graphPanel: StackGraphPanel? = null
    private var detailPanel: BranchDetailPanel? = null
    private var connection: MessageBusConnection? = null

    /**
     * Per-repository helper objects whose lifetime matches the open Stacks tab.
     *
     * Hoisted here so that [AheadBehindCalculator]'s TTL cache survives across
     * consecutive [performRefresh] calls. Stored keyed to the repository root path;
     * replaced if the root changes (rare, but possible when the project re-opens
     * against a different clone).
     *
     * Volatile so pooled-thread readers always see the latest reference; construction
     * is guarded by [synchronized] in [getOrCreateHelpers] to prevent duplicates.
     */
    @Volatile
    private var helpers: RefreshHelpers? = null

    private data class RefreshHelpers(
        val repoRootPath: String,
        val gitLayer: GitLayer,
        val calculator: AheadBehindCalculator,
        val storage: StateStorage,
        val service: BranchDetailService,
    )

    /**
     * Returns the cached [RefreshHelpers] for [root], creating them on first access or
     * when the repository root changes. Double-checked locking keeps the fast path lock-free.
     */
    private fun getOrCreateHelpers(root: VirtualFile): RefreshHelpers {
        // Fast path: already initialised for this root (no lock needed).
        helpers?.takeIf { it.repoRootPath == root.path }?.let { return it }
        // Slow path: first call, or repo root changed.
        return synchronized(this) {
            helpers?.takeIf { it.repoRootPath == root.path } ?: run {
                val gitLayer = project.service<GitLayer>()
                val fresh = RefreshHelpers(
                    repoRootPath = root.path,
                    gitLayer     = gitLayer,
                    calculator   = AheadBehindCalculator(gitLayer),
                    storage      = StateStorage(Paths.get(root.path), IntelliJGitRunner(project)),
                    service      = BranchDetailService(project),
                )
                helpers = fresh
                fresh
            }
        }
    }

    // ── ChangesViewContentProvider ────────────────────────────────────────────

    override fun initContent(): JComponent {
        val graph  = StackGraphPanel()
        graphPanel = graph
        val detail  = BranchDetailPanel()
        detailPanel = detail

        // ── Node selection → load detail panel ───────────────────────────────
        graph.onNodeSelected = { node ->
            LOG.debug("Selected node: ${node.branchName}")
            ApplicationManager.getApplication().executeOnPooledThread {
                val service      = helpers?.service ?: return@executeOnPooledThread
                val worktreePath = service.worktreesByBranch()[node.branchName]
                val stackNode    = service.loadNode(node.branchName, worktreePath)
                SwingUtilities.invokeLater {
                    if (stackNode != null) detail.showNode(stackNode)
                    else detail.clearSelection()
                }
            }
        }
        graph.onNodeNavigated = { node -> LOG.info("Navigate requested for: ${node.branchName}") }

        // Attach the stack branch context-menu for registered actions (Insert Branch Above/Below).
        PopupHandler.installPopupMenu(graph, "StackWorktree.StackBranchPopup", ActionPlaces.POPUP)

        // ── Right-click → context menu (track / untrack) ─────────────────────
        val stateLayer = project.service<StateLayer>()
        val opsLayer   = project.service<OpsLayer>()
        val gitLayer   = project.service<GitLayer>()
        graph.onContextMenu = { node, location ->
            val popup = JPopupMenu()

            // "Track Branch…" — always available.
            // State is loaded inside the listener so it reflects any mutations that
            // occurred between popup-open time and the moment the user clicks the item.
            val trackItem = JMenuItem("Track Branch\u2026")
            trackItem.addActionListener {
                val currentState  = stateLayer.load()
                val allBranches   = gitLayer.listLocalBranches()
                val tracked       = currentState.trackedBranches.keys
                val untracked     = allBranches.filter { it !in tracked && it != currentState.trunkBranch }
                if (untracked.isEmpty()) return@addActionListener
                val parentChoices = listOfNotNull(currentState.trunkBranch) + tracked.sorted()
                val dialog = TrackBranchDialog(project, untracked, parentChoices)
                if (dialog.showAndGet()) {
                    opsLayer.trackBranch(dialog.selectedBranch, dialog.selectedParent)
                }
            }
            popup.add(trackItem)

            // "Untrack Branch" — only for tracked (non-trunk) nodes.
            // Visibility is checked at popup-open time (acceptable: the popup was just
            // opened, and OpsLayerImpl will reject an invalid untrack gracefully).
            if (node != null) {
                val openState = stateLayer.load()
                if (node.branchName in openState.trackedBranches) {
                    popup.add(JSeparator())
                    val untrackItem = JMenuItem("Untrack Branch")
                    untrackItem.addActionListener { opsLayer.untrackBranch(node.branchName) }
                    popup.add(untrackItem)
                }
            }

            popup.show(graph, location.x, location.y)
        }

        val toolbar = StackTreeToolbar.create("StacksTab") { performRefresh() }
        toolbar.targetComponent = graph

        // Subscribe to git-repo changes, NewStack writes, AND track/untrack mutations
        // so the graph stays in sync regardless of what triggers a state change.
        connection = project.messageBus.connect().also { conn ->
            conn.subscribe(GitRepository.GIT_REPO_CHANGE, StackGitChangeListener { performRefresh() })
            conn.subscribe(StackTreeStateListener.TOPIC, StackTreeStateListener { performRefresh() })
            conn.subscribe(STACK_STATE_TOPIC, object : StackStateListener {
                override fun stateChanged() { performRefresh() }
            })
        }

        // Show current state immediately without waiting for the first git event.
        performRefresh()

        val splitter = JBSplitter(/* vertical = */ false, /* proportion = */ 0.35f).apply {
            firstComponent = JBScrollPane(
                graph,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
            )
            secondComponent = detail
            dividerWidth    = 3
        }

        return object : JBPanel<JBPanel<*>>(BorderLayout()), DataProvider {
            init {
                add(toolbar.component, BorderLayout.NORTH)
                add(splitter, BorderLayout.CENTER)
            }

            override fun getData(dataId: String): Any? = when {
                StackDataKeys.SELECTED_BRANCH_NAME.`is`(dataId) -> graph.selectedNodeId
                else -> null
            }
        }
    }

    override fun disposeContent() {
        connection?.disconnect()
        connection  = null
        graphPanel  = null
        detailPanel = null
        helpers     = null
    }

    // ── Refresh pipeline ──────────────────────────────────────────────────────

    /**
     * Full refresh: resolves the effective [StackState] (preferring [StateLayer] data
     * written by track/untrack operations over the git-refs-based [StateStorage]),
     * recalculates ahead/behind counts via [AheadBehindCalculator], converts to
     * [StackGraphData] and [StackViewModel], then re-renders on the EDT.
     *
     * **Source precedence:**
     * 1. [StateLayer.load].trackedBranches — written by [com.github.ydymovopenclawbot.stackworktree.ops.OpsLayerImpl]
     *    on every track/untrack mutation; always up-to-date after a [STACK_STATE_TOPIC] event.
     * 2. [StateStorage.read] — git-refs-based store used by earlier plugin versions or
     *    richer metadata (PR info, health). Used only when trackedBranches is empty.
     *
     * Runs git I/O on a pooled thread; switches back to the EDT for all UI updates.
     */
    private fun performRefresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull() ?: run {
                    renderEmpty(); return@executeOnPooledThread
                }

                val h             = getOrCreateHelpers(repo.root)
                val currentBranch = repo.currentBranchName

                // Prefer StateLayer (kept in sync by OpsLayerImpl) over StateStorage so
                // that tracked branches are immediately visible after track/untrack.
                val pluginState = project.service<StateLayer>().load()
                val state: StackState? = when {
                    pluginState.trackedBranches.isNotEmpty() -> synthesizeStackState(pluginState)
                    else                                     -> h.storage.read()
                }

                // Build branch → parent map from the resolved graph.
                val branchToParent: Map<String, String> = state?.branches?.values
                    ?.mapNotNull { node -> node.parent?.let { node.name to it } }
                    ?.toMap()
                    ?: emptyMap()

                val aheadBehind = if (branchToParent.isNotEmpty()) {
                    runCatching { h.calculator.calculate(branchToParent) }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }

                // Build StackGraphData for the visual panel.
                val nodes: List<StackNodeData> = state?.branches?.values?.map { branchNode ->
                    val ab     = aheadBehind[branchNode.name]
                    val health = when {
                        ab != null && ab.behind > 0 -> HealthStatus.STALE
                        else                        -> HealthStatus.CLEAN
                    }
                    StackNodeData(
                        id              = branchNode.name,
                        branchName      = branchNode.name,
                        parentId        = branchNode.parent,
                        ahead           = ab?.ahead ?: 0,
                        behind          = ab?.behind ?: 0,
                        healthStatus    = health,
                        isCurrentBranch = branchNode.name == currentBranch,
                    )
                } ?: emptyList()

                // Build StackViewModel for the status bar (ordered BFS from trunk).
                val orderedBranches: List<String> = if (state == null) {
                    emptyList()
                } else {
                    val childrenOf: Map<String?, List<String>> = state.branches.values
                        .groupBy({ it.parent }) { it.name }
                    buildList {
                        val queue = ArrayDeque<String?>()
                        queue.add(state.repoConfig.trunk)
                        while (queue.isNotEmpty()) {
                            val cur = queue.removeFirst()
                            if (cur != null && cur in state.branches) add(cur)
                            childrenOf[cur]?.forEach { queue.add(it) }
                        }
                        // Append orphaned branches not reachable from trunk.
                        state.branches.keys.filterNot { it in this }.forEach { add(it) }
                    }
                }

                val viewModel = StackViewModel(
                    stacks = if (orderedBranches.isEmpty()) emptyList() else listOf(
                        StackViewEntry(
                            name     = state!!.repoConfig.trunk,
                            branches = orderedBranches.map { branch ->
                                BranchView(
                                    name            = branch,
                                    isCurrentBranch = branch == currentBranch,
                                    aheadBehind     = aheadBehind[branch],
                                )
                            },
                        )
                    ),
                    activeStack   = null,
                    currentBranch = currentBranch,
                )

                ApplicationManager.getApplication().invokeLater {
                    graphPanel?.updateGraph(StackGraphData(nodes))
                    updateStatusBar(viewModel)
                }
            } catch (e: Exception) {
                LOG.warn("StackTree: refresh failed", e)
                renderEmpty()
            }
        }
    }

    /**
     * Converts [PluginState.trackedBranches] into a [StackState] suitable for the graph
     * renderer.  Fields not tracked by [StateLayer] (PR info, health, worktree path) are
     * left at their defaults; ahead/behind is computed separately by [AheadBehindCalculator].
     */
    private fun synthesizeStackState(pluginState: PluginState): StackState {
        val trunk = pluginState.trunkBranch ?: "main"
        val branches = pluginState.trackedBranches.mapValues { (_, node) ->
            BranchNode(name = node.name, parent = node.parentName, children = node.children)
        }
        return StackState(
            repoConfig = RepoConfig(trunk = trunk, remote = "origin"),
            branches   = branches,
        )
    }

    private fun renderEmpty() {
        ApplicationManager.getApplication().invokeLater {
            graphPanel?.updateGraph(StackGraphData(emptyList()))
        }
    }

    private fun updateStatusBar(model: StackViewModel) {
        val bar = WindowManager.getInstance().getStatusBar(project) ?: return
        (bar.getWidget(StackStatusBarWidget.ID) as? StackStatusBarWidget)?.updateState(model)
    }
}

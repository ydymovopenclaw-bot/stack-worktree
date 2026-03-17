package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehindCalculator
import com.github.ydymovopenclawbot.stackworktree.git.BranchDetailService
import com.github.ydymovopenclawbot.stackworktree.git.GitLayerImpl
import com.github.ydymovopenclawbot.stackworktree.git.IntelliJGitRunner
import com.github.ydymovopenclawbot.stackworktree.startup.StackGitChangeListener
import com.github.ydymovopenclawbot.stackworktree.state.StateStorage
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.HealthStatus
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphData
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphPanel
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBusConnection
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.nio.file.Paths
import javax.swing.JComponent
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
 * Selecting a node in the graph triggers [BranchDetailService.loadNode] on a pooled thread,
 * then updates the detail panel on the EDT.
 *
 * On [initContent] the panel subscribes to [GitRepository.GIT_REPO_CHANGE] so the graph
 * auto-refreshes on every commit, checkout, rebase, fetch, or pull.
 *
 * @param project Injected by the platform via [com.intellij.openapi.vcs.changes.ui.ChangesViewContentEP].
 */
class StacksTabFactory(private val project: Project) : ChangesViewContentProvider {

    private var graphPanel: StackGraphPanel? = null
    private var detailPanel: BranchDetailPanel? = null
    private var connection: MessageBusConnection? = null

    /**
     * Per-repository helper objects whose lifetime matches the open Stacks tab.
     *
     * Hoisted here so that [AheadBehindCalculator]'s TTL cache survives across
     * consecutive [performRefresh] calls.  Stored as a [RefreshHelpers] record keyed
     * to the repository root path; the record is replaced if the root changes (rare,
     * but possible when the project re-opens against a different clone).
     *
     * Volatile so pooled-thread readers always see the latest reference written by
     * any thread; the actual construction is guarded by [synchronized] in
     * [getOrCreateHelpers] to prevent duplicate initialisation.
     */
    @Volatile
    private var helpers: RefreshHelpers? = null

    private data class RefreshHelpers(
        val repoRootPath: String,
        val gitLayer: GitLayerImpl,
        val calculator: AheadBehindCalculator,
        val storage: StateStorage,
        val service: BranchDetailService,
    )

    /**
     * Returns the cached [RefreshHelpers] for [root], creating them on first access or when
     * the repository root changes.  Double-checked locking keeps the fast path lock-free.
     */
    private fun getOrCreateHelpers(root: VirtualFile): RefreshHelpers {
        // Fast path: already initialised for this root (no lock needed).
        helpers?.takeIf { it.repoRootPath == root.path }?.let { return it }
        // Slow path: first call, or repo root changed.
        return synchronized(this) {
            helpers?.takeIf { it.repoRootPath == root.path } ?: run {
                val gitLayer = GitLayerImpl(project, root)
                val fresh = RefreshHelpers(
                    repoRootPath = root.path,
                    gitLayer = gitLayer,
                    calculator = AheadBehindCalculator(gitLayer),
                    storage = StateStorage(Paths.get(root.path), IntelliJGitRunner(project)),
                    service = BranchDetailService(project),
                )
                helpers = fresh
                fresh
            }
        }
    }

    // -------------------------------------------------------------------------
    // ChangesViewContentProvider
    // -------------------------------------------------------------------------

    override fun initContent(): JComponent {
        val graph = StackGraphPanel()
        graphPanel = graph
        val detail = BranchDetailPanel()
        detailPanel = detail

        // Wire node selection: load branch details on a pooled thread, update panel on EDT.
        graph.onNodeSelected = { node ->
            LOG.debug("Selected node: ${node.branchName}")
            ApplicationManager.getApplication().executeOnPooledThread {
                val service = helpers?.service ?: return@executeOnPooledThread
                val worktreePath = service.worktreesByBranch()[node.branchName]
                val stackNode = service.loadNode(node.branchName, worktreePath)
                SwingUtilities.invokeLater {
                    if (stackNode != null) detail.showNode(stackNode)
                    else detail.clearSelection()
                }
            }
        }
        graph.onNodeNavigated = { node -> LOG.info("Navigate requested for: ${node.branchName}") }

        val toolbar = StackTreeToolbar.create("StacksTab") { performRefresh() }
        toolbar.targetComponent = graph

        // Auto-refresh whenever the git repository changes (commit / checkout / rebase / …).
        connection = project.messageBus.connect().also { conn ->
            conn.subscribe(GitRepository.GIT_REPO_CHANGE, StackGitChangeListener { performRefresh() })
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
            dividerWidth = 3
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    override fun disposeContent() {
        connection?.disconnect()
        connection = null
        graphPanel = null
        detailPanel = null
        helpers = null
    }

    // -------------------------------------------------------------------------
    // Refresh pipeline
    // -------------------------------------------------------------------------

    /**
     * Full refresh: reads [com.github.ydymovopenclawbot.stackworktree.state.StackState] from
     * `refs/stacktree/state`, recalculates ahead/behind counts via [AheadBehindCalculator],
     * converts to [StackGraphData] and [StackViewModel], then re-renders on the EDT.
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

                val h = getOrCreateHelpers(repo.root)
                val state = h.storage.read()
                val currentBranch = repo.currentBranchName

                // Build branch → parent map from the persisted graph.
                // BranchNode.parent is null only for the trunk node.
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
                    val ab = aheadBehind[branchNode.name]
                    val health = when {
                        ab != null && ab.behind > 0 -> HealthStatus.STALE
                        else -> HealthStatus.CLEAN
                    }
                    StackNodeData(
                        id = branchNode.name,
                        branchName = branchNode.name,
                        parentId = branchNode.parent,
                        ahead = ab?.ahead ?: 0,
                        behind = ab?.behind ?: 0,
                        healthStatus = health,
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
                            name = state!!.repoConfig.trunk,
                            branches = orderedBranches.map { branch ->
                                BranchView(
                                    name = branch,
                                    isCurrentBranch = branch == currentBranch,
                                    aheadBehind = aheadBehind[branch],
                                )
                            },
                        )
                    ),
                    activeStack = null,
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

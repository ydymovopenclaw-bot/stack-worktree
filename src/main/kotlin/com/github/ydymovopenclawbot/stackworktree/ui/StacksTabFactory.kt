package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehindCalculator
import com.github.ydymovopenclawbot.stackworktree.git.BranchDetailService
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.IntelliJGitRunner
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.ops.WorktreeOps
import com.github.ydymovopenclawbot.stackworktree.startup.StackGitChangeListener
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.PluginState
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.github.ydymovopenclawbot.stackworktree.state.StateStorage
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.HealthStatus
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphData
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphPanel
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.github.ydymovopenclawbot.stackworktree.actions.OpenInNewWindowAction
import com.github.ydymovopenclawbot.stackworktree.actions.OpenInTerminalAction
import com.github.ydymovopenclawbot.stackworktree.actions.StackDataKeys
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
import java.awt.Point
import java.io.File
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
 * Renders a [JBSplitter] with a [StackTreeToolbar] above it and a [WorktreeListPanel] below:
 * - **NORTH**  – toolbar (New Stack / Add Branch / Restack* / Submit* / Sync* / Refresh).
 * - **CENTER** – horizontal [JBSplitter]:
 *     - **Left**  – [StackGraphPanel]: the stack graph that emits node-selection events.
 *     - **Right** – [BranchDetailPanel]: shows metadata for the selected branch.
 * - **SOUTH**  – [WorktreeListPanel]: collapsible list of all git worktrees (both
 *     StackTree-managed and externally created); clicking a tracked row selects its
 *     corresponding node in the graph.
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
 * Right-clicking a node shows a **single** context menu containing:
 * - **Insert Branch Above / Below** — via registered actions `StackWorktree.InsertBranchAbove`
 *   / `StackWorktree.InsertBranchBelow`, triggered through [ActionManager.tryToExecute].
 * - **Create Worktree / Remove Worktree** — mutually exclusive based on whether the branch
 *   already has a linked worktree; delegates to [launchCreateWorktree] / [launchRemoveWorktree].
 * - **Track Branch…** — always available; opens [TrackBranchDialog] to add an untracked
 *   local branch to the [StateLayer] tree.
 * - **Untrack Branch** — only for nodes present in [StateLayer.load].trackedBranches;
 *   removes the node via [OpsLayer.untrackBranch] without deleting the git branch.
 *
 * A single custom [JPopupMenu] is used for all items (no [com.intellij.ui.PopupHandler]);
 * this avoids the double-popup issue that would arise if both a registered action group and
 * a programmatic popup were wired to the same right-click trigger.
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
    private var worktreeListPanel: WorktreeListPanel? = null
    private var connection: MessageBusConnection? = null

    /**
     * Last-known list of worktrees, updated on the EDT in [performRefresh].
     * Read by [buildContextMenu] to look up the path for a graph node that has a worktree.
     * Volatile so the context-menu handler (EDT) sees writes from the refresh pipeline
     * without synchronisation.
     */
    @Volatile
    private var cachedWorktrees: List<Worktree> = emptyList()

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

        // Wire Create / Remove worktree buttons in the detail panel.
        detail.onCreateWorktree = { branchName -> launchCreateWorktree(branchName) }
        detail.onRemoveWorktree = { branchName -> launchRemoveWorktree(branchName) }

        // Wire Open in New Window / Open in Terminal buttons in the detail panel.
        // The path comes directly from the StackNode already held in the detail panel.
        detail.onOpenInNewWindow = { worktreePath ->
            val wt = cachedWorktrees.find { it.path == worktreePath }
                ?: Worktree(path = worktreePath, branch = "", head = "", isLocked = false)
            OpenInNewWindowAction.perform(wt)
        }
        detail.onOpenInTerminal = { worktreePath, branchName ->
            val wt = cachedWorktrees.find { it.path == worktreePath }
                ?: Worktree(path = worktreePath, branch = branchName, head = "", isLocked = false)
            openInTerminalIfAvailable(wt)
        }

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

        // ── Right-click → single unified context menu ─────────────────────────
        //
        // All context-menu items are placed in one JPopupMenu to avoid the double-popup
        // that would occur if both a PopupHandler (action group) and a programmatic popup
        // were wired to the same isPopupTrigger event.
        //
        // Insert Above / Below are triggered via ActionManager.tryToExecute so they reuse
        // the existing action logic (input dialog, validation, background task) without
        // duplicating it here.
        val stateLayer = project.service<StateLayer>()
        val opsLayer   = project.service<OpsLayer>()
        val gitLayer   = project.service<GitLayer>()
        graph.onContextMenu = { node, location ->
            buildContextMenu(
                graph      = graph,
                node       = node,
                location   = location,
                stateLayer = stateLayer,
                opsLayer   = opsLayer,
                gitLayer   = gitLayer,
            )
        }

        val toolbar = StackTreeToolbar.create("StacksTab") { performRefresh() }
        toolbar.targetComponent = graph

        // Worktree list — clicking a tracked row selects its node in the graph;
        // right-clicking shows Open in New Window / Terminal actions.
        val wtPanel = WorktreeListPanel(
            project            = project,
            onWorktreeSelected = { wt -> if (wt.branch.isNotEmpty()) graph.selectNode(wt.branch) },
            onOpenInNewWindow  = { wt -> OpenInNewWindowAction.perform(wt) },
            onOpenInTerminal   = { wt -> openInTerminalIfAvailable(wt) },
        )
        worktreeListPanel = wtPanel

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
                add(wtPanel, BorderLayout.SOUTH)
            }

            override fun getData(dataId: String): Any? = when {
                StackDataKeys.SELECTED_BRANCH_NAME.`is`(dataId) -> graph.selectedNodeId
                StackDataKeys.SELECTED_WORKTREE.`is`(dataId) -> {
                    val branch = graph.selectedNodeId ?: return@getData null
                    cachedWorktrees.find { it.branch == branch && it.path.isNotEmpty() }
                }
                else -> null
            }
        }
    }

    override fun disposeContent() {
        connection?.disconnect()
        connection       = null
        graphPanel       = null
        detailPanel      = null
        worktreeListPanel = null
        helpers          = null
        cachedWorktrees  = emptyList()
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
                        hasWorktree     = branchNode.worktreePath != null,
                    )
                } ?: emptyList()

                // Fetch all worktrees and identify which branches are tracked in the stack graph.
                val worktrees: List<Worktree> = runCatching { h.gitLayer.worktreeList() }
                    .getOrDefault(emptyList())
                // Derive trackedBranches from the same resolved state so that the trunk branch
                // (which has no parent and is absent from getAllParents().keys) is included.
                val trackedBranches: Set<String> = state?.branches?.keys.orEmpty()

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
                    cachedWorktrees = worktrees
                    graphPanel?.updateGraph(StackGraphData(nodes))
                    worktreeListPanel?.refresh(worktrees, trackedBranches)
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

    // ── Context menu ──────────────────────────────────────────────────────────

    /**
     * Builds and shows the single right-click context menu for the graph panel.
     *
     * All actions are placed in one [JPopupMenu] so that right-clicking never triggers
     * two separate popups. Insert Above / Below are fired via [ActionManager.tryToExecute]
     * so their existing input-dialog / validation / background-task logic is reused.
     * Worktree and Track/Untrack items are wired directly to [launchCreateWorktree],
     * [launchRemoveWorktree], and [OpsLayer].
     *
     * Must be called on the EDT (from [StackGraphPanel.onContextMenu]).
     */
    private fun buildContextMenu(
        graph: StackGraphPanel,
        node: StackNodeData?,
        location: Point,
        stateLayer: StateLayer,
        opsLayer: OpsLayer,
        gitLayer: GitLayer,
    ) {
        val popup = JPopupMenu()
        val am    = ActionManager.getInstance()

        // Insert Above / Below — only meaningful when a node is targeted.
        if (node != null) {
            val insertAboveItem = JMenuItem("Insert Branch Above")
            insertAboveItem.addActionListener {
                am.getAction("StackWorktree.InsertBranchAbove")?.let { action ->
                    am.tryToExecute(action, null, graph, ActionPlaces.POPUP, true)
                }
            }
            popup.add(insertAboveItem)

            val insertBelowItem = JMenuItem("Insert Branch Below")
            insertBelowItem.addActionListener {
                am.getAction("StackWorktree.InsertBranchBelow")?.let { action ->
                    am.tryToExecute(action, null, graph, ActionPlaces.POPUP, true)
                }
            }
            popup.add(insertBelowItem)

            popup.add(JSeparator())

            // Create Worktree — only when no worktree is bound yet.
            if (!node.hasWorktree) {
                val createItem = JMenuItem("Create Worktree")
                createItem.addActionListener { launchCreateWorktree(node.branchName) }
                popup.add(createItem)
            } else {
                // Remove Worktree — only when a worktree is already bound.
                val removeItem = JMenuItem("Remove Worktree")
                removeItem.addActionListener { launchRemoveWorktree(node.branchName) }
                popup.add(removeItem)

                // Open actions — only meaningful when the branch has a linked worktree.
                val wt = cachedWorktrees.find { it.branch == node.branchName }
                if (wt != null) {
                    popup.add(JSeparator())
                    val openWindowItem = JMenuItem("Open in New Window")
                    openWindowItem.addActionListener { OpenInNewWindowAction.perform(wt) }
                    popup.add(openWindowItem)

                    val openTerminalItem = JMenuItem("Open in Terminal")
                    openTerminalItem.addActionListener { openInTerminalIfAvailable(wt) }
                    popup.add(openTerminalItem)
                }
            }

            popup.add(JSeparator())
        }

        // "Track Branch…" — always available.
        // State is loaded inside the listener so it reflects mutations that occurred
        // between popup-open time and the moment the user clicks the item.
        val trackItem = JMenuItem("Track Branch\u2026")
        trackItem.addActionListener {
            val currentState  = stateLayer.load()
            val allBranches   = gitLayer.listLocalBranches()
            val tracked       = currentState.trackedBranches.keys
            val untracked     = allBranches.filter { it !in tracked && it != currentState.trunkBranch }
            if (untracked.isEmpty()) return@addActionListener
            val parentChoices = listOfNotNull(currentState.trunkBranch) + tracked.sorted()
            val dialog        = TrackBranchDialog(project, untracked, parentChoices)
            if (dialog.showAndGet()) {
                opsLayer.trackBranch(dialog.selectedBranch, dialog.selectedParent)
            }
        }
        popup.add(trackItem)

        // "Untrack Branch" — only for tracked (non-trunk) nodes.
        // Visibility checked at popup-open time; OpsLayerImpl rejects invalid untracks.
        if (node != null) {
            val openState = stateLayer.load()
            if (node.branchName in openState.trackedBranches) {
                val untrackItem = JMenuItem("Untrack Branch")
                untrackItem.addActionListener { opsLayer.untrackBranch(node.branchName) }
                popup.add(untrackItem)
            }
        }

        popup.show(graph, location.x, location.y)
    }

    // ── Worktree helpers ──────────────────────────────────────────────────────

    /**
     * Shows [WorktreePathDialog] and — on confirmation — creates a linked worktree for
     * [branchName] on a background thread.  Fires [StackTreeStateListener] on success.
     *
     * Must be called on the EDT.
     */
    private fun launchCreateWorktree(branchName: String) {
        val ops         = WorktreeOps.forProject(project)
        val defaultPath = ops.defaultWorktreePath(branchName)
        val dialog      = WorktreePathDialog(project, branchName, defaultPath)
        if (!dialog.showAndGet()) return

        val chosenPath = dialog.getChosenPath()
        if (dialog.isRememberDefault()) {
            val parentDir = File(chosenPath).parent
            if (parentDir != null) project.stackStateService().setWorktreeBasePath(parentDir)
        }

        object : Task.Backgroundable(project, "Creating worktree for '$branchName'…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    ops.createWorktreeForBranch(branchName, chosenPath)
                    project.messageBus
                        .syncPublisher(StackTreeStateListener.TOPIC)
                        .stateChanged()
                    notify("Worktree for '$branchName' created at '$chosenPath'.", NotificationType.INFORMATION)
                } catch (ex: WorktreeException) {
                    LOG.warn("StacksTabFactory: createWorktree failed", ex)
                    notify("Failed to create worktree: ${ex.message}", NotificationType.ERROR)
                } catch (ex: IllegalStateException) {
                    LOG.warn("StacksTabFactory: branch already has worktree", ex)
                    notify(ex.message ?: "Branch already has a worktree.", NotificationType.WARNING)
                } catch (ex: Exception) {
                    LOG.error("StacksTabFactory: createWorktree unexpected error", ex)
                    notify("Unexpected error: ${ex.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    /**
     * Shows a confirmation dialog and — on confirmation — removes the linked worktree for
     * [branchName] on a background thread.  Fires [StackTreeStateListener] on success.
     *
     * Must be called on the EDT.
     */
    private fun launchRemoveWorktree(branchName: String) {
        val ops  = WorktreeOps.forProject(project)
        // Use "(path unknown)" rather than the branch name when no path is recorded — the
        // branch name in a "Directory:" field would be misleading to the user.
        val path = project.stackStateService().getWorktreePath(branchName) ?: "(path unknown)"

        val confirmed = Messages.showYesNoDialog(
            project,
            "Remove the worktree for '$branchName'?\n\nDirectory: $path\n\nThe directory will be deleted.",
            "Remove Worktree",
            "Remove",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return

        object : Task.Backgroundable(project, "Removing worktree for '$branchName'…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    ops.removeWorktreeForBranch(branchName)
                    project.messageBus
                        .syncPublisher(StackTreeStateListener.TOPIC)
                        .stateChanged()
                    notify("Worktree for '$branchName' removed.", NotificationType.INFORMATION)
                } catch (ex: WorktreeException) {
                    LOG.warn("StacksTabFactory: removeWorktree failed", ex)
                    notify("Failed to remove worktree: ${ex.message}", NotificationType.ERROR)
                } catch (ex: Exception) {
                    LOG.error("StacksTabFactory: removeWorktree unexpected error", ex)
                    notify("Unexpected error: ${ex.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    /**
     * Opens [wt] in a terminal tab via [OpenInTerminalAction.perform].
     *
     * Wrapped in a try/catch so that if the Terminal plugin is absent at runtime
     * (unlikely for IDEA but possible in stripped builds) the call is silently
     * ignored rather than crashing the whole refresh pipeline.
     */
    private fun openInTerminalIfAvailable(wt: Worktree) {
        try {
            OpenInTerminalAction.perform(project, wt)
        } catch (_: NoClassDefFoundError) {
            LOG.warn("Terminal plugin not available; cannot open worktree in terminal")
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StackWorktree")
            .createNotification(message, type)
            .notify(project)
    }
}

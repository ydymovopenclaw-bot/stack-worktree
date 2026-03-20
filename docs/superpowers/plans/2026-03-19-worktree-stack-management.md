# Worktree & Stack Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four features: Remove Stack, Worktrees Tab, Git Branch Popup integration, and Enhanced Worktree Creation Dialog.

**Architecture:** Each feature is independent but shares the enhanced dialog (Task 1). Implementation follows TDD with FakeGitLayer test doubles. State mutations clear both StateLayer (XML) and StateStorage (git-refs). UI refresh via `STACK_STATE_TOPIC` message bus.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (252+), JUnit 5, kotlinx.serialization

**Spec:** `docs/superpowers/specs/2026-03-19-worktree-stack-management-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `src/main/kotlin/.../ui/CreateWorktreeDialog.kt` | Enhanced worktree creation dialog (replaces WorktreePathDialog) |
| `src/main/kotlin/.../ops/RemoveStackResult.kt` | Result type for removeStack operation |
| `src/main/kotlin/.../actions/RemoveStackAction.kt` | Action + confirmation dialog for stack removal |
| `src/main/kotlin/.../ui/WorktreesTabFactory.kt` | Dedicated Worktrees tab in Git tool window |
| `src/main/kotlin/.../actions/CreateWorktreeFromBranchAction.kt` | Action in Git branch popup |
| `src/test/kotlin/.../ui/CreateWorktreeDialogTest.kt` | Dialog validation tests |
| `src/test/kotlin/.../ops/RemoveStackTest.kt` | Remove stack algorithm tests |
| `src/test/kotlin/.../actions/CreateWorktreeFromBranchActionTest.kt` | Branch popup action tests |

### Modified Files
| File | Changes |
|------|---------|
| `src/main/kotlin/.../ops/OpsLayer.kt` | Add `removeStack()` method |
| `src/main/kotlin/.../ops/OpsLayerImpl.kt` | Implement `removeStack()`, add `Algorithms.applyRemoveStack()` |
| `src/main/kotlin/.../state/StackStateService.kt` | Add `clearAll()` method |
| `src/main/kotlin/.../state/StateStorage.kt` | Add `delete()` method |
| `src/main/kotlin/.../ui/StacksTabFactory.kt` | Rewire `launchCreateWorktree` to `CreateWorktreeDialog`, add "Remove Stack" to context menu |
| `src/main/kotlin/.../ui/BranchDetailPanel.kt` | Rewire `onCreateWorktree` callback to use new dialog |
| `src/main/resources/META-INF/plugin.xml` | Register Worktrees tab, RemoveStackAction, CreateWorktreeFromBranchAction |
| `src/main/kotlin/.../testutil/FakeGitLayer.kt` | Add `deleteBranchCalls` tracking list |

### Retired Files
| File | Replaced By |
|------|-------------|
| `src/main/kotlin/.../ui/WorktreePathDialog.kt` | `CreateWorktreeDialog.kt` |

---

## Abbreviations

All file paths below use this base:
- **main:** `src/main/kotlin/com/github/ydymovopenclawbot/stackworktree`
- **test:** `src/test/kotlin/com/github/ydymovopenclawbot/stackworktree`
- **res:** `src/main/resources`

---

## Task 1: Enhanced Worktree Creation Dialog (Feature 4)

Shared infrastructure used by Tasks 3 and 4.

**Files:**
- Create: `main/ui/CreateWorktreeDialog.kt`
- Create: `test/ui/CreateWorktreeDialogTest.kt`
- Modify: `main/ui/StacksTabFactory.kt` (rewire launchCreateWorktree)
- Modify: `main/ui/BranchDetailPanel.kt` (rewire onCreateWorktree)
- Delete: `main/ui/WorktreePathDialog.kt`

### Step 1.1: Write validation tests

- [ ] Create `test/ui/CreateWorktreeDialogTest.kt` with tests for the dialog's validation logic. These test the pure validation functions without needing a real dialog:

```kotlin
package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.actions.isValidBranchName
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreateWorktreeDialogTest {

    @Test
    fun `rejects blank worktree path`() {
        val error = CreateWorktreeDialog.validatePath("")
        assertNotNull(error)
        assertTrue(error!!.contains("empty"))
    }

    @Test
    fun `accepts valid worktree path`() {
        assertNull(CreateWorktreeDialog.validatePath("/tmp/my-worktree"))
    }

    @Test
    fun `rejects invalid new branch name`() {
        val error = CreateWorktreeDialog.validateNewBranch("feature..bar", existingBranches = emptySet())
        assertNotNull(error)
    }

    @Test
    fun `rejects duplicate branch name`() {
        val error = CreateWorktreeDialog.validateNewBranch("feature/foo", existingBranches = setOf("feature/foo"))
        assertNotNull(error)
        assertTrue(error!!.contains("exists"))
    }

    @Test
    fun `accepts valid new branch name`() {
        assertNull(CreateWorktreeDialog.validateNewBranch("feature/bar", existingBranches = setOf("main")))
    }

    @Test
    fun `rejects branch that already has worktree`() {
        val error = CreateWorktreeDialog.validateExistingBranch("feat", worktreePaths = mapOf("feat" to "/tmp/wt"))
        assertNotNull(error)
        assertTrue(error!!.contains("worktree"))
    }

    @Test
    fun `accepts branch without worktree`() {
        assertNull(CreateWorktreeDialog.validateExistingBranch("feat", worktreePaths = emptyMap()))
    }
}
```

- [ ] Run test to verify it fails:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew test --tests "*.CreateWorktreeDialogTest" -x instrumentCode
```

Expected: FAIL — `CreateWorktreeDialog` class not found.

### Step 1.2: Create CreateWorktreeDialog

- [ ] Create `main/ui/CreateWorktreeDialog.kt`:

```kotlin
package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.actions.isValidBranchName
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog for creating a git worktree, optionally with a new branch.
 *
 * Supports two modes:
 * - **Existing branch**: user selects from [branches] combo box
 * - **New branch**: user enters a name and selects a base branch
 *
 * When [preselectedBranch] is non-null (e.g. from the Git branch popup),
 * the branch combo box is pre-selected and disabled.
 */
class CreateWorktreeDialog(
    private val project: Project,
    private val branches: List<String>,
    private val defaultPath: String,
    private val preselectedBranch: String? = null,
    private val existingWorktreePaths: Map<String, String> = emptyMap(),
) : DialogWrapper(project) {

    private val branchCombo = JComboBox(DefaultComboBoxModel(branches.toTypedArray()))
    private val createNewBranchBox = JBCheckBox("Create new branch", false)
    private val newBranchNameField = JBTextField()
    private val baseBranchCombo = JComboBox(DefaultComboBoxModel(branches.toTypedArray()))
    private val newBranchLabel = JBLabel("Branch name:")
    private val baseBranchLabel = JBLabel("Base branch:")

    val pathField = TextFieldWithBrowseButton().apply {
        text = defaultPath
        addBrowseFolderListener(
            "Choose Worktree Directory",
            "Select the directory where the worktree will be created",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )
    }

    val rememberDefaultBox = JBCheckBox("Remember as default base path", false)
    val openAfterCreationBox = JBCheckBox("Open worktree after creation", true)

    init {
        title = "Create Worktree"
        setOKButtonText("Create")

        if (preselectedBranch != null) {
            branchCombo.selectedItem = preselectedBranch
            branchCombo.isEnabled = false
            createNewBranchBox.isEnabled = false
        }

        // Toggle new-branch fields visibility
        createNewBranchBox.addChangeListener { updateNewBranchFieldsVisibility() }
        updateNewBranchFieldsVisibility()

        // Update path when branch selection changes
        branchCombo.addActionListener { updatePathFromBranch() }
        newBranchNameField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updatePathFromBranch()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updatePathFromBranch()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updatePathFromBranch()
        })

        init()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getChosenPath(): String = pathField.text.trim()
    fun isRememberDefault(): Boolean = rememberDefaultBox.isSelected
    fun isOpenAfterCreation(): Boolean = openAfterCreationBox.isSelected
    fun isCreateNewBranch(): Boolean = createNewBranchBox.isSelected
    fun getNewBranchName(): String = newBranchNameField.text.trim()
    fun getBaseBranch(): String = baseBranchCombo.selectedItem as? String ?: "main"
    fun getSelectedBranch(): String {
        return if (isCreateNewBranch()) getNewBranchName()
        else branchCombo.selectedItem as? String ?: ""
    }

    // ── Validation (static for testability) ───────────────────────────────────

    companion object {
        fun validatePath(path: String): String? {
            if (path.isBlank()) return "Worktree path must not be empty."
            return null
        }

        fun validateNewBranch(name: String, existingBranches: Set<String>): String? {
            if (name.isBlank()) return "Branch name must not be empty."
            if (!isValidBranchName(name)) return "Invalid branch name: '$name'."
            if (name in existingBranches) return "Branch '$name' already exists."
            return null
        }

        fun validateExistingBranch(branch: String, worktreePaths: Map<String, String>): String? {
            if (branch in worktreePaths) return "Branch '$branch' already has a worktree."
            return null
        }
    }

    // ── DialogWrapper overrides ───────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val lc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 0, 4, 8)
        }
        val fc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insets(4, 0)
            gridwidth = GridBagConstraints.REMAINDER
        }

        // Branch selection
        panel.add(JBLabel("Branch:"), lc.clone() as GridBagConstraints)
        panel.add(branchCombo, fc.clone() as GridBagConstraints)

        // Create new branch checkbox
        panel.add(createNewBranchBox, (fc.clone() as GridBagConstraints).apply {
            insets = JBUI.insets(4, 0, 2, 0)
        })

        // New branch fields (toggled)
        panel.add(newBranchLabel, lc.clone() as GridBagConstraints)
        panel.add(newBranchNameField, fc.clone() as GridBagConstraints)
        panel.add(baseBranchLabel, lc.clone() as GridBagConstraints)
        panel.add(baseBranchCombo, fc.clone() as GridBagConstraints)

        // Worktree path
        panel.add(JBLabel("Worktree path:"), lc.clone() as GridBagConstraints)
        panel.add(pathField, fc.clone() as GridBagConstraints)

        // Checkboxes
        val cbConstraints = (fc.clone() as GridBagConstraints).apply {
            insets = JBUI.insets(4, 0, 2, 0)
        }
        panel.add(rememberDefaultBox, cbConstraints.clone() as GridBagConstraints)
        panel.add(openAfterCreationBox, cbConstraints)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (isCreateNewBranch()) {
            val branchError = validateNewBranch(getNewBranchName(), branches.toSet())
            if (branchError != null) return ValidationInfo(branchError, newBranchNameField)
        } else {
            val selected = branchCombo.selectedItem as? String ?: return ValidationInfo("Select a branch.")
            val existingError = validateExistingBranch(selected, existingWorktreePaths)
            if (existingError != null) return ValidationInfo(existingError, branchCombo)
        }

        val pathError = validatePath(getChosenPath())
        if (pathError != null) return ValidationInfo(pathError, pathField.textField)

        return null
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (preselectedBranch != null) pathField.textField else branchCombo

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun updateNewBranchFieldsVisibility() {
        val show = createNewBranchBox.isSelected
        newBranchLabel.isVisible = show
        newBranchNameField.isVisible = show
        baseBranchLabel.isVisible = show
        baseBranchCombo.isVisible = show
        branchCombo.isEnabled = !show && preselectedBranch == null
    }

    private fun updatePathFromBranch() {
        val branch = if (isCreateNewBranch()) getNewBranchName() else (branchCombo.selectedItem as? String ?: "")
        if (branch.isNotBlank()) {
            val sanitised = branch.replace('/', '-').replace('\\', '-')
            val basePath = pathField.text.substringBeforeLast('/').ifBlank { defaultPath.substringBeforeLast('/') }
            pathField.text = "$basePath/$sanitised"
        }
    }
}
```

- [ ] Run tests to verify they pass:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew test --tests "*.CreateWorktreeDialogTest" -x instrumentCode
```

Expected: All 7 tests PASS.

### Step 1.3: Rewire StacksTabFactory to use CreateWorktreeDialog

- [ ] In `main/ui/StacksTabFactory.kt`, replace the `launchCreateWorktree` method body. Change:
  - Import `CreateWorktreeDialog` instead of `WorktreePathDialog`
  - Replace the dialog instantiation inside `launchCreateWorktree`:

```kotlin
// Replace the dialog creation in launchCreateWorktree():
private fun launchCreateWorktree(branchName: String) {
    val ops = WorktreeOps.forProject(project)
    val defaultPath = ops.defaultWorktreePath(branchName)
    val gitLayer = project.service<GitLayer>()
    val svc = project.stackStateService()

    val dialog = CreateWorktreeDialog(
        project = project,
        branches = gitLayer.listLocalBranches(),
        defaultPath = defaultPath,
        preselectedBranch = branchName,
        existingWorktreePaths = buildMap {
            gitLayer.worktreeList().filter { it.branch.isNotEmpty() }.forEach {
                put(it.branch, it.path)
            }
        },
    )
    if (!dialog.showAndGet()) return

    val chosenPath = dialog.getChosenPath()
    if (dialog.isRememberDefault()) {
        val parentDir = java.io.File(chosenPath).parent
        if (parentDir != null) svc.setWorktreeBasePath(parentDir)
    }

    object : Task.Backgroundable(project, "Creating worktree for '$branchName'…", false) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            try {
                val branch = if (dialog.isCreateNewBranch()) {
                    gitLayer.createBranch(dialog.getNewBranchName(), dialog.getBaseBranch())
                    dialog.getNewBranchName()
                } else branchName

                val wt = ops.createWorktreeForBranch(branch, chosenPath)
                project.messageBus
                    .syncPublisher(StackTreeStateListener.TOPIC)
                    .stateChanged()
                notify("Worktree for '$branch' created at '$chosenPath'.", NotificationType.INFORMATION)

                if (dialog.isOpenAfterCreation()) {
                    SwingUtilities.invokeLater { OpenInNewWindowAction.perform(wt) }
                }
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
```

- [ ] Update the import in `StacksTabFactory.kt`: remove `WorktreePathDialog`, add `CreateWorktreeDialog`.

### Step 1.4: Delete WorktreePathDialog

- [ ] Delete `main/ui/WorktreePathDialog.kt`.

- [ ] Verify build compiles:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL. If `BranchDetailPanel` references `WorktreePathDialog`, fix that too (it should only reference the `onCreateWorktree` callback which is wired in `StacksTabFactory`).

### Step 1.5: Run full test suite and commit

- [ ] Run all tests:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew test
```

Expected: All tests PASS.

- [ ] Commit:

```bash
git add -A && git commit -m "feat: enhanced CreateWorktreeDialog with new-branch and open-after options

Replaces WorktreePathDialog with CreateWorktreeDialog that supports:
- Create new branch + worktree simultaneously
- Open worktree after creation (checked by default)
- Pre-selected branch mode (for context menu usage)
- Static validation methods for testability"
```

---

## Task 2: Remove Stack (Feature 1)

**Files:**
- Create: `main/ops/RemoveStackResult.kt`
- Create: `main/actions/RemoveStackAction.kt`
- Create: `test/ops/RemoveStackTest.kt`
- Modify: `main/ops/OpsLayer.kt` (add removeStack)
- Modify: `main/ops/OpsLayerImpl.kt` (implement removeStack + Algorithms.applyRemoveStack)
- Modify: `main/state/StackStateService.kt` (add clearAll)
- Modify: `main/state/StateStorage.kt` (add delete)
- Modify: `main/ui/StacksTabFactory.kt` (add Remove Stack to context menu)
- Modify: `main/testutil/FakeGitLayer.kt` (track deleteBranch calls)
- Modify: `res/META-INF/plugin.xml` (register action)

### Step 2.1: Write RemoveStackResult data class

- [ ] Create `main/ops/RemoveStackResult.kt`:

```kotlin
package com.github.ydymovopenclawbot.stackworktree.ops

/**
 * Result of an [OpsLayer.removeStack] operation.
 *
 * @param removedBranches   Branch names that were untracked.
 * @param deletedBranches   Branch names whose git branches were deleted (subset of [removedBranches]).
 * @param removedWorktrees  Worktree paths that were successfully pruned.
 * @param failedWorktrees   Worktree paths that failed to remove (with error messages).
 */
data class RemoveStackResult(
    val removedBranches: List<String>,
    val deletedBranches: List<String> = emptyList(),
    val removedWorktrees: List<String> = emptyList(),
    val failedWorktrees: Map<String, String> = emptyMap(),
) {
    fun summary(): String = buildString {
        append("Removed ${removedBranches.size} branch(es) from stack")
        if (deletedBranches.isNotEmpty()) append(", deleted ${deletedBranches.size} git branch(es)")
        if (removedWorktrees.isNotEmpty()) append(", pruned ${removedWorktrees.size} worktree(s)")
        if (failedWorktrees.isNotEmpty()) append(", ${failedWorktrees.size} worktree(s) failed to remove")
        append(".")
    }
}
```

### Step 2.2: Add clearAll to StackStateService

- [ ] Add to `main/state/StackStateService.kt` after the `clearWorktreePath` method:

```kotlin
/** Removes all tracked branches and worktree paths. Preserves [worktreeBasePath] (user preference). */
fun clearAll(): Unit = synchronized(lock) {
    state.branchParents.clear()
    state.worktreePaths.clear()
    // worktreeBasePath is intentionally preserved — it's a user preference, not stack data.
}
```

### Step 2.3: Add delete to StateStorage

- [ ] Add a `delete()` method to `main/state/StateStorage.kt`. The class uses field `root: Path`, constant `REF = "refs/stacktree/state"`, and helper `exec()` for git commands:

```kotlin
/**
 * Deletes the persisted [StackState] by removing the git ref.
 * No-op if no state is stored.
 */
fun delete() {
    if (!exists()) return
    val result = runner.run(root, listOf("update-ref", "-d", REF))
    if (result.exitCode != 0) {
        throw GitException("Failed to delete $REF: ${result.stderr}")
    }
}
```

- [ ] Also add `fun delete()` to the `StackStateStore` interface in `main/state/StackStateStore.kt` so the `OpsLayerImpl.stateStore()` call compiles:

```kotlin
/** Deletes all persisted state. No-op if nothing is stored. */
fun delete()
```

- [ ] Update `test/testutil/FakeGitLayer.kt` — if a `FakeStackStateStore` exists, add `delete()` to it. If not, the test double for `StackStateStore` needs a no-op `delete()` override.

### Step 2.4: Update FakeGitLayer for deleteBranch tracking

- [ ] In `test/testutil/FakeGitLayer.kt`, **replace** the existing `deleteBranch` override (line 48: `override fun deleteBranch(branchName: String) = Unit`) with a tracking version:

```kotlin
val deleteBranchCalls = mutableListOf<String>()

override fun deleteBranch(branchName: String) { deleteBranchCalls += branchName }
```

### Step 2.5: Write remove-stack algorithm tests

- [ ] Create `test/ops/RemoveStackTest.kt`:

```kotlin
package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.state.PluginState
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.github.ydymovopenclawbot.stackworktree.state.TrackedBranchNode
import com.github.ydymovopenclawbot.stackworktree.testutil.FakeGitLayer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemoveStackTest {

    @Test
    fun `applyRemoveStack removes all branches from state`() {
        val state = PluginState(
            trunkBranch = "main",
            trackedBranches = mapOf(
                "feat-a" to TrackedBranchNode("feat-a", "main", listOf("feat-b")),
                "feat-b" to TrackedBranchNode("feat-b", "feat-a"),
            ),
        )
        val (newState, leafFirst) = OpsLayerImpl.Algorithms.applyRemoveStack(state, "main")
        assertTrue(newState.trackedBranches.isEmpty())
        assertNull(newState.trunkBranch)
        assertEquals(listOf("feat-b", "feat-a"), leafFirst)
    }

    @Test
    fun `applyRemoveStack returns leaf-first order`() {
        val state = PluginState(
            trunkBranch = "main",
            trackedBranches = mapOf(
                "a" to TrackedBranchNode("a", "main", listOf("b", "c")),
                "b" to TrackedBranchNode("b", "a", listOf("d")),
                "c" to TrackedBranchNode("c", "a"),
                "d" to TrackedBranchNode("d", "b"),
            ),
        )
        val (_, leafFirst) = OpsLayerImpl.Algorithms.applyRemoveStack(state, "main")
        // d before b, c and d before a
        val dIdx = leafFirst.indexOf("d")
        val bIdx = leafFirst.indexOf("b")
        val aIdx = leafFirst.indexOf("a")
        assertTrue(dIdx < bIdx, "d should come before b")
        assertTrue(bIdx < aIdx, "b should come before a")
    }

    @Test
    fun `applyRemoveStack with empty stack returns empty result`() {
        val state = PluginState(trunkBranch = "main", trackedBranches = emptyMap())
        val (newState, leafFirst) = OpsLayerImpl.Algorithms.applyRemoveStack(state, "main")
        assertTrue(leafFirst.isEmpty())
        assertNull(newState.trunkBranch)
    }

    @Test
    fun `removeStack skips failed worktree removal and reports in result`() {
        // This test requires a FakeGitLayer whose worktreeRemove throws,
        // a FakeStateLayer, and a FakeStackStateStore. Wire via OpsLayerImpl.forTest().
        val fakeGit = FakeGitLayer().apply {
            // Make worktreeRemove throw for one specific path
        }
        // Override worktreeRemove to throw:
        val throwingGit = object : FakeGitLayer() {
            override fun worktreeRemove(path: String) {
                if (path == "/tmp/wt-a") throw WorktreeException("dirty files")
                super.worktreeRemove(path)
            }
        }

        val stateLayer = object : StateLayer {
            var current = PluginState(
                trunkBranch = "main",
                trackedBranches = mapOf(
                    "feat-a" to TrackedBranchNode("feat-a", "main"),
                ),
            )
            override fun load() = current
            override fun save(state: PluginState) { current = state }
        }

        val fakeStore = object : StackStateStore {
            override fun read(): StackState? = null
            override fun write(state: StackState) {}
            override fun delete() {}
        }

        // Note: OpsLayerImpl.forTest needs a Project stub — use the same
        // Proxy pattern from WorktreeOpsTest. StackStateService.clearAll()
        // also needs to be callable — wire via stateServiceOverride or
        // ensure the project stub returns a test StackStateService.
        // Full wiring left to implementer; the key assertion is:
        //
        // val result = ops.removeStack("main", removeWorktrees = true)
        // assertEquals(1, result.failedWorktrees.size)
        // assertTrue(result.failedWorktrees.containsKey("/tmp/wt-a"))
        // assertEquals(listOf("feat-a"), result.removedBranches)
    }
}
```

- [ ] Run tests to verify they fail:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew test --tests "*.RemoveStackTest" -x instrumentCode
```

Expected: FAIL — `applyRemoveStack` not found.

### Step 2.6: Implement removeStack in OpsLayer and OpsLayerImpl

- [ ] Add to `main/ops/OpsLayer.kt` interface (after `restackAll`):

```kotlin
/**
 * Removes the entire stack: untracks all branches, optionally deletes git branches
 * and removes linked worktrees. Runs on the calling thread — callers must dispatch
 * to a background thread.
 *
 * Worktree removal failures are skipped (not thrown) and reported in the result.
 * Both StateLayer and StateStorage are cleared.
 */
fun removeStack(
    stackRoot: String,
    deleteBranches: Boolean = false,
    removeWorktrees: Boolean = false,
): RemoveStackResult
```

- [ ] Add the algorithm to `OpsLayerImpl.Algorithms`:

```kotlin
/**
 * Returns a new [PluginState] with all tracked branches removed and trunk cleared,
 * plus the list of branch names in leaf-first order (safe for deletion).
 */
fun applyRemoveStack(current: PluginState, stackRoot: String): Pair<PluginState, List<String>> {
    val branches = current.trackedBranches
    if (branches.isEmpty()) return current.copy(trunkBranch = null, trackedBranches = emptyMap()) to emptyList()

    // BFS from root to get all branches, then reverse for leaf-first
    val ordered = mutableListOf<String>()
    val queue = ArrayDeque<String>()
    // Start with direct children of trunk
    branches.values.filter { it.parentName == stackRoot || it.parentName == current.trunkBranch }
        .forEach { queue.add(it.name) }
    while (queue.isNotEmpty()) {
        val name = queue.removeFirst()
        ordered.add(name)
        branches[name]?.children?.forEach { queue.add(it) }
    }
    // Include any orphans not reachable from trunk
    branches.keys.filter { it !in ordered }.forEach { ordered.add(it) }

    val leafFirst = ordered.reversed()
    val cleared = current.copy(trunkBranch = null, trackedBranches = emptyMap())
    return cleared to leafFirst
}
```

- [ ] Implement the `removeStack` method in `OpsLayerImpl`:

```kotlin
override fun removeStack(
    stackRoot: String,
    deleteBranches: Boolean,
    removeWorktrees: Boolean,
): RemoveStackResult {
    val sl = stateLayer()
    val store = stateStore()
    val git = gitLayer()
    val svc = project.stackStateService()

    val (clearedState, leafFirstBranches) = Algorithms.applyRemoveStack(sl.load(), stackRoot)

    val deletedBranches = mutableListOf<String>()
    val removedWorktrees = mutableListOf<String>()
    val failedWorktrees = mutableMapOf<String, String>()

    for (branch in leafFirstBranches) {
        // Remove worktree if requested
        if (removeWorktrees) {
            val wtPath = svc.getWorktreePath(branch)
            if (wtPath != null) {
                try {
                    git.worktreeRemove(wtPath)
                    removedWorktrees += wtPath
                } catch (e: Exception) {
                    LOG.warn("removeStack: failed to remove worktree for '$branch' at '$wtPath': ${e.message}")
                    failedWorktrees[wtPath] = e.message ?: "unknown error"
                }
            }
        }

        // Delete git branch if requested
        if (deleteBranches) {
            try {
                git.deleteBranch(branch)
                deletedBranches += branch
            } catch (e: Exception) {
                LOG.warn("removeStack: failed to delete branch '$branch': ${e.message}")
            }
        }
    }

    // Clear both state stores
    sl.save(clearedState)
    svc.clearAll()
    store.delete()

    notifyStateChanged()
    LOG.info("removeStack: removed ${leafFirstBranches.size} branches, deleted ${deletedBranches.size}, pruned ${removedWorktrees.size} worktrees")

    return RemoveStackResult(
        removedBranches = leafFirstBranches,
        deletedBranches = deletedBranches,
        removedWorktrees = removedWorktrees,
        failedWorktrees = failedWorktrees,
    )
}
```

- [ ] Run algorithm tests:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew test --tests "*.RemoveStackTest" -x instrumentCode
```

Expected: All 3 tests PASS.

### Step 2.7: Create RemoveStackAction with confirmation dialog

- [ ] Create `main/actions/RemoveStackAction.kt`:

```kotlin
package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Action that removes the entire stack after showing a confirmation dialog
 * with options to delete git branches and remove worktrees.
 */
class RemoveStackAction : AnAction("Remove Stack", "Remove all branches from the stack", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateLayer = project.service<StateLayer>()
        val state = stateLayer.load()
        val trunk = state.trunkBranch ?: return
        val branchCount = state.trackedBranches.size

        val dialog = RemoveStackDialog(branchCount)
        if (!dialog.showAndGet()) return

        val deleteBranches = dialog.isDeleteBranches()
        val removeWorktrees = dialog.isRemoveWorktrees()

        object : Task.Backgroundable(project, "Removing stack…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = project.service<OpsLayer>().removeStack(
                    stackRoot = trunk,
                    deleteBranches = deleteBranches,
                    removeWorktrees = removeWorktrees,
                )
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("StackWorktree")
                    .createNotification(result.summary(), NotificationType.INFORMATION)
                    .notify(project)
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            project.service<StateLayer>().load().trackedBranches.isNotEmpty()
    }
}

private class RemoveStackDialog(private val branchCount: Int) : DialogWrapper(true) {

    private val deleteBranchesBox = JBCheckBox("Delete git branches", false)
    private val removeWorktreesBox = JBCheckBox("Remove linked worktrees", false)

    init {
        title = "Remove Stack"
        setOKButtonText("Remove")
        init()
    }

    fun isDeleteBranches(): Boolean = deleteBranchesBox.isSelected
    fun isRemoveWorktrees(): Boolean = removeWorktreesBox.isSelected

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val fc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insets(4, 0)
            gridwidth = GridBagConstraints.REMAINDER
        }

        panel.add(JBLabel("Remove stack with $branchCount tracked branch(es)?"), fc.clone() as GridBagConstraints)
        panel.add(deleteBranchesBox, fc.clone() as GridBagConstraints)
        panel.add(removeWorktreesBox, fc)

        return panel
    }
}
```

### Step 2.8: Add Remove Stack to context menu and plugin.xml

- [ ] In `main/ui/StacksTabFactory.kt`, in the `buildContextMenu` method, add before the "Track Branch…" item:

```kotlin
// "Remove Stack" — only when a stack exists.
if (node != null) {
    val currentState = stateLayer.load()
    if (currentState.trackedBranches.isNotEmpty()) {
        val removeStackItem = JMenuItem("Remove Stack")
        removeStackItem.addActionListener {
            am.getAction("StackWorktree.RemoveStack")?.let { action ->
                am.tryToExecute(action, null, graph, ActionPlaces.POPUP, true)
            }
        }
        popup.add(removeStackItem)
    }
}
```

- [ ] Register the action in `res/META-INF/plugin.xml` inside the `<actions>` block:

```xml
<!-- Remove Stack action -->
<action id="StackWorktree.RemoveStack"
        class="com.github.ydymovopenclawbot.stackworktree.actions.RemoveStackAction"
        text="Remove Stack"
        description="Remove all branches from the stack, optionally deleting git branches and worktrees"/>
```

### Step 2.9: Build, test, commit

- [ ] Run full build:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] Commit:

```bash
git add -A && git commit -m "feat: add Remove Stack with confirmation dialog

Adds OpsLayer.removeStack() that walks all branches leaf-first,
optionally deletes git branches and prunes worktrees (skip on failure).
Clears both StateLayer and StateStorage. Confirmation dialog offers
checkboxes for delete branches and remove worktrees."
```

---

## Task 3: Worktrees Tab (Feature 2)

**Files:**
- Create: `main/ui/WorktreesTabFactory.kt`
- Modify: `res/META-INF/plugin.xml` (register new tab)

### Step 3.1: Create WorktreesTabFactory

- [ ] Create `main/ui/WorktreesTabFactory.kt`:

```kotlin
package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.actions.OpenInNewWindowAction
import com.github.ydymovopenclawbot.stackworktree.actions.OpenInTerminalAction
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.ops.WorktreeOps
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.github.ydymovopenclawbot.stackworktree.startup.StackGitChangeListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBusConnection
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

private val LOG = logger<WorktreesTabFactory>()

/**
 * Content provider for the dedicated "Worktrees" tab in the VCS Changes view.
 *
 * Lists all git worktrees for the repository with context-menu actions for
 * Open in New Window, Open in Terminal, Remove, and Copy Path.
 */
class WorktreesTabFactory(private val project: Project) : ChangesViewContentProvider {

    private var listModel: DefaultListModel<Worktree>? = null
    private var worktreeList: JBList<Worktree>? = null
    private var connection: MessageBusConnection? = null

    override fun initContent(): JComponent {
        val model = DefaultListModel<Worktree>()
        listModel = model
        val list = JBList(model).apply {
            cellRenderer = WorktreeListCellRenderer()
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) = maybePopup(e)
                override fun mouseReleased(e: java.awt.event.MouseEvent) = maybePopup(e)
                private fun maybePopup(e: java.awt.event.MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val idx = locationToIndex(e.point) ?: return
                    selectedIndex = idx
                    val wt = model.getElementAt(idx) ?: return
                    buildContextMenu(wt).show(this@apply, e.x, e.y)
                }
            })
        }
        worktreeList = list

        // Toolbar
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "WorktreesTab",
            DefaultActionGroup().apply {
                add(object : DumbAwareAction("Create Worktree", "Create a new worktree", AllIcons.General.Add) {
                    override fun actionPerformed(e: AnActionEvent) = launchCreateWorktree()
                })
                add(object : DumbAwareAction("Remove", "Remove selected worktree", AllIcons.General.Remove) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val wt = list.selectedValue ?: return
                        launchRemoveWorktree(wt)
                    }
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = list.selectedValue != null && list.selectedValue?.isMain == false
                    }
                })
                add(object : DumbAwareAction("Refresh", "Refresh worktree list", AllIcons.Actions.Refresh) {
                    override fun actionPerformed(e: AnActionEvent) = performRefresh()
                })
            },
            true,
        ).apply { targetComponent = list }

        connection = project.messageBus.connect().also { conn ->
            conn.subscribe(GitRepository.GIT_REPO_CHANGE, StackGitChangeListener { performRefresh() })
            conn.subscribe(StackTreeStateListener.TOPIC, StackTreeStateListener { performRefresh() })
            conn.subscribe(STACK_STATE_TOPIC, object : StackStateListener {
                override fun stateChanged() { performRefresh() }
            })
        }

        performRefresh()

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
    }

    override fun disposeContent() {
        connection?.disconnect()
        connection = null
        listModel = null
        worktreeList = null
    }

    private fun performRefresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val gitLayer = project.service<GitLayer>()
                val worktrees = gitLayer.worktreeList()
                val tracked = project.service<StateLayer>().load().trackedBranches.keys
                SwingUtilities.invokeLater {
                    listModel?.let { model ->
                        model.clear()
                        worktrees.forEach { model.addElement(it) }
                    }
                    (worktreeList?.cellRenderer as? WorktreeListCellRenderer)?.trackedBranches = tracked
                    worktreeList?.repaint()
                }
            } catch (e: Exception) {
                LOG.warn("WorktreesTab: refresh failed", e)
            }
        }
    }

    private fun buildContextMenu(wt: Worktree): JPopupMenu {
        val popup = JPopupMenu()

        if (!wt.isMain) {
            val openWindowItem = JMenuItem("Open in New Window")
            openWindowItem.addActionListener { OpenInNewWindowAction.perform(wt) }
            popup.add(openWindowItem)

            val openTerminalItem = JMenuItem("Open in Terminal")
            openTerminalItem.addActionListener {
                try { OpenInTerminalAction.perform(project, wt) }
                catch (_: NoClassDefFoundError) { LOG.warn("Terminal plugin not available") }
            }
            popup.add(openTerminalItem)

            popup.addSeparator()

            val removeItem = JMenuItem("Remove Worktree")
            removeItem.addActionListener { launchRemoveWorktree(wt) }
            popup.add(removeItem)

            popup.addSeparator()
        }

        val copyPathItem = JMenuItem("Copy Path")
        copyPathItem.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(wt.path), null)
        }
        popup.add(copyPathItem)

        return popup
    }

    private fun launchCreateWorktree() {
        val gitLayer = project.service<GitLayer>()
        val ops = WorktreeOps.forProject(project)
        val branches = gitLayer.listLocalBranches()
        if (branches.isEmpty()) return

        val defaultPath = ops.defaultWorktreePath(branches.first())
        val dialog = CreateWorktreeDialog(
            project = project,
            branches = branches,
            defaultPath = defaultPath,
            existingWorktreePaths = buildMap {
                gitLayer.worktreeList().filter { it.branch.isNotEmpty() }.forEach { put(it.branch, it.path) }
            },
        )
        if (!dialog.showAndGet()) return

        val chosenPath = dialog.getChosenPath()
        val branch = dialog.getSelectedBranch()
        if (dialog.isRememberDefault()) {
            val parentDir = java.io.File(chosenPath).parent
            if (parentDir != null) project.stackStateService().setWorktreeBasePath(parentDir)
        }

        object : Task.Backgroundable(project, "Creating worktree…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    if (dialog.isCreateNewBranch()) {
                        gitLayer.createBranch(dialog.getNewBranchName(), dialog.getBaseBranch())
                    }
                    val wt = ops.createWorktreeForBranch(branch, chosenPath)
                    notify("Worktree created at '$chosenPath'.", NotificationType.INFORMATION)
                    if (dialog.isOpenAfterCreation()) {
                        SwingUtilities.invokeLater { OpenInNewWindowAction.perform(wt) }
                    }
                } catch (ex: Exception) {
                    LOG.warn("WorktreesTab: createWorktree failed", ex)
                    notify("Failed to create worktree: ${ex.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun launchRemoveWorktree(wt: Worktree) {
        val confirmed = Messages.showYesNoDialog(
            project,
            "Remove worktree at '${wt.path}'?\n\nBranch: ${wt.branch}\nThe directory will be deleted.",
            "Remove Worktree",
            "Remove",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return

        object : Task.Backgroundable(project, "Removing worktree…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val ops = WorktreeOps.forProject(project)
                    ops.removeWorktreeForBranch(wt.branch)
                    project.messageBus.syncPublisher(StackTreeStateListener.TOPIC).stateChanged()
                    notify("Worktree removed.", NotificationType.INFORMATION)
                } catch (ex: Exception) {
                    LOG.warn("WorktreesTab: removeWorktree failed", ex)
                    notify("Failed to remove worktree: ${ex.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StackWorktree")
            .createNotification(message, type)
            .notify(project)
    }
}

/**
 * List cell renderer for worktrees.
 * Shows: branch name (bold), path, HEAD hash, "Stack" badge if tracked, lock icon if locked.
 *
 * [trackedBranches] is updated on each refresh so the badge stays current.
 */
private class WorktreeListCellRenderer(
    var trackedBranches: Set<String> = emptySet(),
) : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): java.awt.Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val wt = value as? Worktree ?: return this
        val branchDisplay = wt.branch.ifBlank { "(detached)" }
        val shortHead = wt.head.take(7)
        val stackBadge = if (wt.branch in trackedBranches) " <font color='#6a9fb5'>[Stack]</font>" else ""
        val lockBadge = if (wt.isLocked) " \uD83D\uDD12" else ""
        val mainTag = if (wt.isMain) " (main)" else ""
        text = "<html><b>$branchDisplay</b>$mainTag$stackBadge$lockBadge — ${wt.path} — $shortHead</html>"
        return this
    }
}
```

### Step 3.2: Register Worktrees tab in plugin.xml

- [ ] Add to `res/META-INF/plugin.xml` inside `<extensions defaultExtensionNs="com.intellij">`, after the existing `changesViewContent` for Stacks:

```xml
<changesViewContent
    id="Worktrees"
    tabName="Worktrees"
    className="com.github.ydymovopenclawbot.stackworktree.ui.WorktreesTabFactory"
    predicateClassName="com.github.ydymovopenclawbot.stackworktree.ui.StacksTabVisibilityPredicate"/>
```

Note: Reuses `StacksTabVisibilityPredicate` since both tabs need a git repo to be visible.

### Step 3.3: Build, test, commit

- [ ] Run full build:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] Commit:

```bash
git add -A && git commit -m "feat: dedicated Worktrees tab in Git tool window

New ChangesViewContentProvider showing all git worktrees with toolbar
actions (Create, Remove, Refresh) and context menu (Open in New Window,
Open in Terminal, Remove, Copy Path). Uses CreateWorktreeDialog for
creation. Auto-refreshes on git and state change events."
```

---

## Task 4: Git Branch Popup Integration (Feature 3)

**Files:**
- Create: `main/actions/CreateWorktreeFromBranchAction.kt`
- Create: `test/actions/CreateWorktreeFromBranchActionTest.kt`
- Modify: `res/META-INF/plugin.xml` (register action in Git.Branches.List)

### Step 4.1: Spike — verify Git.Branches.List data context

- [ ] Before writing code, verify the `Git.Branches.List` action group exists in the target SDK. Run the sandboxed IDE:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew runIde
```

In the running IDE: open Settings → Keymap → search for "Git.Branches" to confirm the group exists. Also try: open a git project → click the branch widget → verify custom actions could appear there.

If `Git.Branches.List` doesn't exist, check alternatives: `Git.Experimental.Branch.Popup.Actions`, `Git.Branch.Popup.Actions`, or `Vcs.Branch.Popup.Actions`.

**Document the actual group ID** before proceeding. The rest of this task assumes `Git.Branches.List` works.

### Step 4.2: Write action test

- [ ] Create `test/actions/CreateWorktreeFromBranchActionTest.kt`:

```kotlin
package com.github.ydymovopenclawbot.stackworktree.actions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreateWorktreeFromBranchActionTest {

    @Test
    fun `action is disabled when branch has worktree`() {
        val worktreePaths = mapOf("feature/foo" to "/tmp/wt-foo")
        assertTrue(CreateWorktreeFromBranchAction.shouldDisable("feature/foo", worktreePaths))
    }

    @Test
    fun `action is enabled when branch has no worktree`() {
        assertFalse(CreateWorktreeFromBranchAction.shouldDisable("feature/bar", emptyMap()))
    }
}
```

- [ ] Run to verify failure:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew test --tests "*.CreateWorktreeFromBranchActionTest" -x instrumentCode
```

Expected: FAIL — class not found.

### Step 4.3: Create CreateWorktreeFromBranchAction

- [ ] Create `main/actions/CreateWorktreeFromBranchAction.kt`:

```kotlin
package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.ops.WorktreeOps
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.github.ydymovopenclawbot.stackworktree.ui.CreateWorktreeDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.SwingUtilities

private val LOG = logger<CreateWorktreeFromBranchAction>()

/**
 * Action registered in `Git.Branches.List` to create a worktree from
 * IntelliJ's native git branch popup.
 */
class CreateWorktreeFromBranchAction : DumbAwareAction(
    "Create Worktree",
    "Create a git worktree for this branch",
    null,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Try to read the selected branch from the data context.
        // The key name may vary by SDK version — adapt after spike (Step 4.1).
        val branchName = e.getData(StackDataKeys.SELECTED_BRANCH_NAME)
            ?: return

        val gitLayer = project.service<GitLayer>()
        val ops = WorktreeOps.forProject(project)
        val defaultPath = ops.defaultWorktreePath(branchName)

        val dialog = CreateWorktreeDialog(
            project = project,
            branches = gitLayer.listLocalBranches(),
            defaultPath = defaultPath,
            preselectedBranch = branchName,
            existingWorktreePaths = buildMap {
                gitLayer.worktreeList().filter { it.branch.isNotEmpty() }.forEach { put(it.branch, it.path) }
            },
        )
        if (!dialog.showAndGet()) return

        val chosenPath = dialog.getChosenPath()
        if (dialog.isRememberDefault()) {
            val parentDir = java.io.File(chosenPath).parent
            if (parentDir != null) project.stackStateService().setWorktreeBasePath(parentDir)
        }

        object : Task.Backgroundable(project, "Creating worktree for '$branchName'…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    if (dialog.isCreateNewBranch()) {
                        gitLayer.createBranch(dialog.getNewBranchName(), dialog.getBaseBranch())
                    }
                    val wt = ops.createWorktreeForBranch(dialog.getSelectedBranch(), chosenPath)
                    project.messageBus.syncPublisher(StackTreeStateListener.TOPIC).stateChanged()
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("StackWorktree")
                        .createNotification("Worktree created at '$chosenPath'.", NotificationType.INFORMATION)
                        .notify(project)
                    if (dialog.isOpenAfterCreation()) {
                        SwingUtilities.invokeLater { OpenInNewWindowAction.perform(wt) }
                    }
                } catch (ex: Exception) {
                    LOG.warn("CreateWorktreeFromBranch: failed", ex)
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("StackWorktree")
                        .createNotification("Failed: ${ex.message}", NotificationType.ERROR)
                        .notify(project)
                }
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val branch = e.getData(StackDataKeys.SELECTED_BRANCH_NAME)
        if (branch == null) {
            // In the branch popup, the branch might not be available via our custom key.
            // Keep the action visible but enabled — the dialog handles validation.
            e.presentation.isEnabledAndVisible = true
            return
        }
        val worktreePaths = project.stackStateService().let { svc ->
            buildMap { svc.getAllParents().keys.forEach { b -> svc.getWorktreePath(b)?.let { put(b, it) } } }
        }
        e.presentation.isEnabled = !shouldDisable(branch, worktreePaths)
    }

    companion object {
        /** Pure function for testability: returns true if the branch already has a worktree. */
        fun shouldDisable(branch: String, worktreePaths: Map<String, String>): Boolean =
            branch in worktreePaths
    }
}
```

- [ ] Run tests:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew test --tests "*.CreateWorktreeFromBranchActionTest" -x instrumentCode
```

Expected: Both tests PASS.

### Step 4.4: Register in plugin.xml

- [ ] Add to `res/META-INF/plugin.xml` inside `<actions>`:

```xml
<!-- Create Worktree from Git branch popup -->
<action id="StackWorktree.CreateWorktreeFromBranch"
        class="com.github.ydymovopenclawbot.stackworktree.actions.CreateWorktreeFromBranchAction"
        text="Create Worktree"
        description="Create a git worktree for this branch"
        icon="AllIcons.Nodes.Folder">
    <add-to-group group-id="Git.Branches.List" anchor="last"/>
</action>
```

**Note:** If the spike in Step 4.1 reveals a different group ID, update accordingly.

### Step 4.5: Build, test, commit

- [ ] Run full build:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] Commit:

```bash
git add -A && git commit -m "feat: Create Worktree action in Git branch popup

Registers CreateWorktreeFromBranchAction in Git.Branches.List so users
can create worktrees directly from IntelliJ's native branch widget.
Uses the shared CreateWorktreeDialog with pre-selected branch."
```

---

## Task 5: Final Integration Test

- [ ] Run full test suite:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew build
```

- [ ] Launch sandbox IDE and manually verify all four features:

```bash
JAVA_HOME=/home/yevgeniy/.jdks/jbr-25.0.2 ./gradlew runIde
```

Verify:
1. Stacks tab → right-click root node → "Remove Stack" shows dialog with checkboxes
2. "Worktrees" tab appears in Git tool window with Create/Remove/Refresh toolbar
3. Branch widget → right-click branch → "Create Worktree" appears
4. Create Worktree dialog has: branch dropdown, "Create new branch" toggle, "Open after creation" checkbox

- [ ] Final commit if any fixes needed.

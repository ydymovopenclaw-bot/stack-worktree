# Worktree & Stack Management Enhancements

**Date:** 2026-03-19
**Status:** Approved
**Author:** Yevgeniy + Claude

---

## Overview

Four independent features that improve worktree and stack lifecycle management in the StackTree plugin. Each can be implemented and shipped independently.

---

## Feature 1: Remove Stack

### Problem
Users can create stacks but cannot remove them. The only option is to untrack branches one by one.

### Design

**Entry point:** Right-click on a stack's root node in the Stacks tab → "Remove Stack" context menu item. Also available as a toolbar button (enabled when a stack exists).

**Confirmation dialog** with two optional checkboxes:
- "Delete git branches" (unchecked by default)
- "Remove linked worktrees" (unchecked by default)

The dialog shows the stack name and a count of affected branches/worktrees.

**Operation sequence (runs on a background thread via `Task.Backgroundable`):**
1. Walk all nodes in the stack (BFS from root to collect, then reverse for leaf-first).
2. For each node (leaf-first to avoid parent-before-child issues):
   - If "Remove linked worktrees" is checked and node has a worktree: `git worktree remove <path>`. On failure (e.g. dirty files): skip, log warning, add to summary.
   - If "Delete git branches" is checked: `git branch -D <name>`.
   - Untrack the branch from both `StateLayer` (XML-backed `PluginState.trackedBranches`) and `StateStorage` (git-refs-backed `StackState.branches`).
3. Clear the `StackState` entirely from `StateStorage` (delete the `refs/stacktree/state` ref) and reset `PluginState.trunkBranch` to null in `StateLayer`.
4. Fire `StackTreeStateListener` to refresh the graph.
5. Show notification balloon summarizing what was removed (including skipped worktrees on failure).

**Threading:** Callers dispatch to a background thread (same pattern as `syncAll()`). The method itself runs on the calling thread.

**New API:**
```kotlin
// OpsLayer.kt
fun removeStack(
    stackRoot: String,
    deleteBranches: Boolean = false,
    removeWorktrees: Boolean = false,
): RemoveStackResult
```

**New classes:**
- `RemoveStackAction` — `AnAction` for context menu and toolbar
- `RemoveStackDialog` — confirmation dialog with checkboxes

**Testing:**
- Unit test: `OpsLayerImpl.removeStack()` with FakeGitLayer — verify all branches untracked from both StateLayer and StateStorage, branches deleted only when flag set, worktrees removed only when flag set.
- Unit test: leaf-first ordering (children removed before parents).
- Unit test: worktree removal failure is skipped and reported in result (not thrown).

---

## Feature 2: Worktrees Tab

### Problem
Worktree management is buried as a collapsible panel at the bottom of the Stacks tab. Users want a dedicated view for managing all worktrees.

### Design

**New "Worktrees" tab** in the Git tool window (via `ChangesViewContentProvider`), alongside the existing Stacks tab.

**Layout:**
- **Toolbar** — "Create Worktree" button, "Remove" button (enabled when a row is selected), "Refresh" button.
- **List view** — table of all worktrees with columns:
  - Directory path (relative to repo parent)
  - Branch name
  - HEAD (short hash, 7 chars)
  - "Stack" badge if the branch is tracked in a stack
  - Lock icon if worktree is locked
- **Context menu per row:**
  - Open in New Window
  - Open in Terminal
  - Remove Worktree (with confirmation)
  - Copy Path

**Data source:** `GitLayer.worktreeList()` — same as the existing `WorktreeListPanel`.

**Auto-refresh:** Subscribes to `GitRepository.GIT_REPO_CHANGE` and `StackTreeStateListener.TOPIC`.

**The existing collapsible `WorktreeListPanel`** at the bottom of the Stacks tab is kept as a quick-glance summary.

**New classes:**
- `WorktreesTabFactory` — `ChangesViewContentProvider`
- `WorktreesTabVisibilityPredicate` — shows tab only when git repo is open (same logic as `StacksTabVisibilityPredicate`)

**Reused:**
- `WorktreeOps` for create/remove operations
- `OpenInNewWindowAction`, `OpenInTerminalAction` for context menu actions
- The enhanced `CreateWorktreeDialog` (Feature 4) for the "Create" button

**plugin.xml registration:**
```xml
<changesViewContent
    id="Worktrees"
    tabName="Worktrees"
    className="...ui.WorktreesTabFactory"
    predicateClassName="...ui.WorktreesTabVisibilityPredicate"/>
```

**Testing:**
- Unit test: `WorktreesTabFactory` renders worktree list from FakeGitLayer test doubles.
- Unit test: context menu actions dispatch correctly.

---

## Feature 3: Git Branch Popup Integration

### Problem
Users want to create worktrees from IntelliJ's native branch popup (the menu with Checkout, New Branch, etc.) without navigating to the Stacks or Worktrees tab.

### Design

**New action** registered in `Git.Branches.List` action group — appears as "Create Worktree" in the native branch popup.

**Behavior:**
- Reads the selected branch from the action's data context.
- Opens the enhanced `CreateWorktreeDialog` (Feature 4) with the branch pre-selected.
- Grayed out (`update()` returns `isEnabled = false`) if the branch already has a linked worktree. The check uses `StackStateService.getWorktreePath()` (XML-backed, EDT-safe) — never `git worktree list`.

**New class:**
- `CreateWorktreeFromBranchAction` extending `DumbAwareAction`

**plugin.xml registration:**
```xml
<action id="StackWorktree.CreateWorktreeFromBranch"
        class="...actions.CreateWorktreeFromBranchAction"
        text="Create Worktree"
        description="Create a git worktree for this branch"
        icon="AllIcons.Nodes.Folder">
    <add-to-group group-id="Git.Branches.List" anchor="last"/>
</action>
```

**Data context keys:** The exact data keys available in `Git.Branches.List` actions depend on the SDK version. A spike task is required during implementation to verify:
- How the selected branch name is exposed (likely via internal `GitBranchesPopupKeys` or the popup's own data provider)
- Which repository references are available

**Risk:** `Git.Branches.List` data context keys may be internal API. If the spike reveals instability, fallback: prompt the user to select a branch in the dialog instead of pre-filling from context.

**Testing:**
- Unit test: action disabled when branch already has worktree (via `StackStateService`).
- Spike: verify `Git.Branches.List` data context in target SDK (2025.2.5+).
- Manual test: verify action appears in branch popup and creates worktree correctly.

---

## Feature 4: Enhanced Worktree Creation Dialog

### Problem
The current `WorktreePathDialog` only accepts a path. Users want to create a new branch and worktree simultaneously, and optionally open the worktree after creation.

### Design

**New `CreateWorktreeDialog`** replacing/extending `WorktreePathDialog`. Used by:
- Worktrees tab "Create" button (Feature 2)
- Branch popup "Create Worktree" action (Feature 3)
- Stacks tab "Create Worktree" context menu (existing, rewired)

**Dialog fields:**

1. **Branch selection** — `ComboBox` of existing local branches.
   - **"Create new branch" checkbox** — when checked, reveals:
     - "Branch name" text field
     - "Base branch" dropdown (defaults to currently selected branch or current HEAD)
   - When unchecked: the combo box selects an existing branch.
   - When the dialog is opened from the branch popup (Feature 3), the branch is pre-selected and the combo box is disabled.

2. **Worktree path** — text field pre-filled with `<worktreeBasePath>/<branch-name>`, editable. Updates dynamically when branch name changes.

3. **"Remember as default base path"** checkbox (carried over from existing `WorktreePathDialog`).

4. **"Open worktree after creation"** checkbox — **checked by default**.

**Validation:**
- Branch name: valid git branch name (reuse `BranchNameValidator`), must not already exist (when creating new).
- Worktree path: non-empty, directory must not already exist or be in use by another worktree.
- Existing branch: must not already have a linked worktree.

**On OK (runs on background thread via `Task.Backgroundable`):**
1. If "Create new branch" checked: `GitLayer.createBranch(name, baseBranch)`.
2. `WorktreeOps.createWorktreeForBranch(branch, path)` — returns the created `Worktree` object.
3. If "Remember" checked: persist base path via `StackStateService.setWorktreeBasePath()`.
4. If "Open after creation" checked: construct `Worktree` from the return value of step 2, call `OpenInNewWindowAction.perform(worktree)` on the EDT.
5. Fire `StackTreeStateListener` to refresh all panels.

**New class:**
- `CreateWorktreeDialog` — `DialogWrapper` with Kotlin UI DSL

**Retired:**
- `WorktreePathDialog` — replaced by `CreateWorktreeDialog`. Known callers to migrate:
  - `StacksTabFactory.launchCreateWorktree()`
  - `BranchDetailPanel.onCreateWorktree` (callback wired in `StacksTabFactory.initContent()`)

**Testing:**
- Unit test: dialog validation logic (invalid branch name, duplicate branch, existing path).
- Unit test: create-new-branch flow calls `createBranch` then `createWorktreeForBranch`.
- Unit test: open-after-creation flag triggers `OpenInNewWindowAction`.

---

## Implementation Order

Features are independent but share the dialog (Feature 4). Recommended order:

1. **Feature 4** (Enhanced dialog) — shared infrastructure, needed by Features 2 and 3.
2. **Feature 1** (Remove Stack) — self-contained, no dependencies on other features.
3. **Feature 2** (Worktrees tab) — uses the new dialog.
4. **Feature 3** (Branch popup integration) — uses the new dialog, depends on verifying `Git.Branches.List` works with the target SDK.

---

## Files Changed (Estimated)

| Feature | New Files | Modified Files |
|---------|-----------|----------------|
| 1. Remove Stack | `RemoveStackAction.kt`, `RemoveStackDialog.kt`, `RemoveStackResult.kt` | `OpsLayer.kt`, `OpsLayerImpl.kt`, `StacksTabFactory.kt`, `StateStorage.kt`, `plugin.xml` |
| 2. Worktrees Tab | `WorktreesTabFactory.kt` | `plugin.xml` (reuses `WorktreesTabVisibilityPredicate` or adds new) |
| 3. Branch Popup | `CreateWorktreeFromBranchAction.kt` | `plugin.xml` |
| 4. Enhanced Dialog | `CreateWorktreeDialog.kt` | `StacksTabFactory.kt` (rewire `launchCreateWorktree`), `BranchDetailPanel.kt` (rewire `onCreateWorktree`), retire `WorktreePathDialog.kt` |

# StackTree

![Build](https://github.com/ydymovopenclaw-bot/stack-worktree/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
**StackTree** brings stacked-branch workflows to IntelliJ IDEA.

Work on multiple dependent pull requests simultaneously — each branch lives in its own
[git worktree](https://git-scm.com/docs/git-worktree) so you can switch contexts instantly
without stashing or committing incomplete work.

### Features

- **Visual stack graph** — see your entire branch stack at a glance in the *Stacks* tab of
  the VCS Changes view; branches are colour-coded by health (clean / needs-rebase / conflict).
- **One-click worktree management** — create and remove linked worktrees directly from the
  graph context menu or the dedicated *Worktrees* tab.
- **Restack** — rebase every branch in the stack onto its parent in one operation
  (`Ctrl+Alt+Shift+R`); IntelliJ's three-pane merge dialog opens automatically on conflicts.
- **Submit stack** — push all branches and create or update GitHub / GitLab pull requests in
  a single action (`Ctrl+Alt+Shift+S`); PR descriptions include a navigation table so
  reviewers always know where they are in the stack.
- **Sync** — fetch the remote, detect merged branches, and refresh ahead/behind counts for
  every tracked branch (`Ctrl+Shift+Y`).
- **State persistence** — branch relationships and worktree paths are stored in
  `.idea/stack-worktree.xml` and survive IDE restarts.
- **Status-bar widget** — the current branch is always visible in the bottom status bar.

### Requirements

- IntelliJ IDEA 2025.2 or newer (build 252+)
- Git 2.20+ (for `git worktree` support)
<!-- Plugin description end -->

---

## Installation

**From the IDE:**

<kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search for **StackTree** → <kbd>Install</kbd>

**From JetBrains Marketplace:**

Visit [plugins.jetbrains.com/plugin/MARKETPLACE_ID](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
and click **Install to …**

**Manual:**

Download the [latest release](https://github.com/ydymovopenclaw-bot/stack-worktree/releases/latest)
and install via <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙</kbd> → <kbd>Install plugin from disk…</kbd>

---

## Quick Start

1. Open a project that is a git repository.
2. Switch to the **Commit** tool window and click the **Stacks** tab.
3. Click **New Stack** (or press the <kbd>+</kbd> toolbar button) and enter a trunk branch
   name (e.g. `main`).
4. Right-click any branch node → **Add Branch** to start building your stack.
5. Use **Restack** to keep all branches up to date after the trunk advances.
6. Use **Submit Stack** to push and open PRs for all branches at once.

---

## Keyboard Shortcuts

| Action | Default shortcut |
|--------|-----------------|
| Restack all | `Ctrl+Alt+Shift+R` |
| Submit stack | `Ctrl+Alt+Shift+S` |
| Sync with remote | `Ctrl+Shift+Y` |
| Refresh graph | `F5` |

All shortcuts are customisable under <kbd>Settings</kbd> → <kbd>Keymap</kbd> → **StackTree**.

---

## Development

```bash
./gradlew build          # compile + test + checks
./gradlew test           # run all tests (JUnit 5)
./gradlew runIde         # launch sandboxed IDE with plugin loaded
./gradlew buildPlugin    # produce distributable ZIP
./gradlew verifyPlugin   # run IntelliJ Plugin Verifier
```

Run a single test class:

```bash
./gradlew test --tests "com.github.ydymovopenclawbot.stackworktree.state.StateStorageTest"
```

---

Plugin built on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

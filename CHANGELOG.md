<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# stack-worktree Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial scaffold from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Layered architecture: `git`, `state`, `ops`, `pr`, `ui` packages with clean interface boundaries
- `.editorconfig` for consistent coding style
- GitHub Actions CI: build, test (Kover coverage → CodeCov), Qodana static analysis, Plugin Verifier

#### Core features (S3–S5)
- **Stack graph panel** in the VCS Changes *Stacks* tab: visual DAG of stacked branches colour-coded by health
- **Branch tracking** — track/untrack local branches via right-click context menu; state persisted to `.idea/stack-worktree.xml`
- **Worktrees tab** — dedicated panel listing all git worktrees with open-in-new-window and open-in-terminal actions
- **Create / remove linked worktrees** from the graph context menu or branch list
- **Restack** (`Ctrl+Alt+Shift+R`) — rebase entire stack bottom-to-top via IntelliJ's three-pane merge dialog
- **Sync** (`Ctrl+Shift+Y`) — fetch remote, detect merged branches, refresh ahead/behind counts
- **Insert branch above / below** — structural stack mutations that rebase descendants automatically
- **Ahead/behind indicator** on each graph node with TTL-cached calculation

#### PR integration (S6)
- **Submit stack** (`Ctrl+Alt+Shift+S`) — push all branches and create or update GitHub / GitLab PRs; PR descriptions include a stack-navigation table
- GitHub and GitLab provider implementations; auto-detection based on remote URL
- PR/CI badge polling in the stack graph (status icon on each node)

#### Settings & UX (S7)
- **Settings page** under VCS → StackTree: trunk branch, remote name, worktree base path, auto-prune
- **Status-bar widget** showing the current branch; updates on every checkout event
- Keyboard shortcut group in Keymap settings (all six actions customisable)
- Accessibility: ARIA labels and keyboard navigation in the stack graph panel

#### Error handling & logging (S7.3)
- `StackTreeNotifier` — centralized balloon notifications with **"Show Details"** action for errors
- `StateCorruptedException` — thrown when the persisted JSON cannot be deserialized; callers recover gracefully
- `ReentrantLock` in `StateStorage.write()` for safe concurrent writes within a single JVM
- Structured `DEBUG`/`WARN`/`ERROR` logging throughout the git, state, and UI layers (`idea.log` under `#StackTree`)
- `StacksTabFactory.initContent()` wrapped in try/catch — returns a readable error panel instead of a blank tab on failure
- Plugin icons (light + dark) for JetBrains Marketplace listing

### Changed

### Deprecated

### Removed

### Fixed

### Security

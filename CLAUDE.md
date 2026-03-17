# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IntelliJ Platform plugin (Kotlin) for stack-based git worktree management. Enables multi-PR workflows with graph visualization of branch stacks, real-time ahead/behind tracking, and state persistence via git refs.

- **Plugin ID**: `com.github.ydymovopenclawbot.stackworktree`
- **Target IDE**: IntelliJ IDEA 2025.2.5+ (build 252+)
- **JVM**: Java 21, Kotlin with kotlinx.serialization and coroutines
- **Bundled plugin dependency**: Git4Idea

## Build Commands

```bash
./gradlew build          # Full build (compile + test + checks)
./gradlew test           # Run all tests (JUnit 5)
./gradlew check          # Tests + Kover coverage + verifications
./gradlew runIde         # Launch sandboxed IDE with plugin loaded
```

Run a single test class:
```bash
./gradlew test --tests "com.github.ydymovopenclawbot.stackworktree.git.GitExecutorTest"
```

Gradle configuration cache and build cache are enabled.

## Architecture

The plugin follows a layered architecture with four main layers:

### State Layer (`state/`)
Serializable data model for branch stacks. `StackState` holds a forest of `StackNode` trees. `StateStorage` persists state as git objects under `refs/stacktree/state`. Uses kotlinx.serialization for JSON encoding.

### Git Layer (`git/`)
Wraps git CLI operations. `GitRunner` is the interface (with `IntelliJGitRunner` for IDE, injectable for tests). `GitExecutor` handles worktree CRUD. `AheadBehindCalculator` tracks commit divergence with TTL-cached `StateFlow`. `GitLayer`/`GitLayerImpl` provide the high-level API.

### UI Layer (`ui/`)
`StackGraphModel` is the view-model that transforms `StackState` into `GraphNode` trees with layout coordinates and `NodeHealth` status. `StacksTabFactory` and `StacksTabVisibilityPredicate` integrate into the IDE's Changes view.

### Integration (`pr/`, `ops/`)
`PrLayer` and `OpsLayer` are placeholder interfaces for pull request management and stack operations (rebase, sync).

### Key patterns
- **Reactive state**: Kotlin `StateFlow` for observable state propagation
- **Interface-driven testability**: `GitRunner` interface allows test doubles without mocking frameworks
- **IDE lifecycle**: `MyProjectActivity` (startup), `MyProjectService` (project-scoped service), `MyToolWindowFactory` (tool window)

## Testing

Tests use JUnit 5 (Jupiter). Test classes mirror the main source structure under `src/test/kotlin/`. Git-layer tests use a `FakeGitRunner` rather than mocking frameworks. State tests verify JSON round-trip serialization. UI tests validate graph layout computation.

## CI/CD

GitHub Actions workflow (`.github/workflows/build.yml`) runs on push to main and all PRs:
- Build, test with Kover coverage (uploaded to CodeCov)
- Qodana static analysis
- IntelliJ Plugin Verifier
- Automated draft release creation

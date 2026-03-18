package com.github.ydymovopenclawbot.stackworktree.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Settings page registered under **Settings → Version Control → StackTree**.
 *
 * ## Implementation notes
 * - Extends [BoundConfigurable]: [isModified], [apply], and [reset] are handled
 *   automatically by the Kotlin UI DSL bindings — no boilerplate overrides needed.
 * - Binds directly to [StackTreeSettingsService.state] fields.  Because
 *   [StackTreeSettingsService.loadState] copies values *in-place* (rather than
 *   replacing the state reference), the property references captured at panel
 *   creation remain valid for the lifetime of the project.
 * - Changes take effect immediately on **Apply** / **OK** — no IDE restart required.
 *
 * ## Per-repo overrides
 * The settings shown here are the IDE-level defaults.  A `.stacktree.yml` file in
 * the repository root can override individual keys; see [RepoSettingsLoader].
 */
class StackTreeConfigurable(private val project: Project) : BoundConfigurable("StackTree") {

    private val svc get() = StackTreeSettingsService.getInstance(project)

    override fun createPanel() = panel {

        // ── Worktree ──────────────────────────────────────────────────────────
        group("Worktree") {
            row("Base directory:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select Worktree Base Directory"),
                    project,
                ).bindText(svc.settingsState::worktreeBaseDir)
                    .comment(
                        "Directory where new linked worktrees are created. " +
                        "Leave empty to use a sibling folder next to the project root."
                    )
            }
            row("Branch naming template:") {
                textField()
                    .bindText(svc.settingsState::branchNamingTemplate)
                    .comment("Supported placeholders: <tt>{stack}</tt>, <tt>{index}</tt>, <tt>{description}</tt>")
            }
        }

        // ── Automation ────────────────────────────────────────────────────────
        group("Automation") {
            row {
                checkBox("Auto-prune worktrees on merge")
                    .bindSelected(svc.settingsState::autoPruneOnMerge)
                    .comment("Remove linked worktrees for branches detected as merged during sync")
            }
            row {
                checkBox("Auto-restack on sync")
                    .bindSelected(svc.settingsState::autoRestackOnSync)
                    .comment("Rebase all stack branches onto their parents after a successful sync (can be slow on large stacks)")
            }
            row {
                checkBox("Add PR navigation comment")
                    .bindSelected(svc.settingsState::prNavigationComment)
                    .comment("Append a Markdown stack-navigation table to each PR description when submitting the stack")
            }
        }

        // ── Polling intervals ─────────────────────────────────────────────────
        group("Polling Intervals") {
            row("Ahead/behind refresh:") {
                spinner(5..600, step = 5)
                    .bindIntValue(svc.settingsState::aheadBehindRefreshInterval)
                label("seconds")
                comment("How often the ahead/behind count cache is refreshed in the stack graph")
            }
            row("PR status poll:") {
                spinner(10..3600, step = 10)
                    .bindIntValue(svc.settingsState::prPollInterval)
                label("seconds")
                comment("How often the plugin queries the hosting service for PR and CI status")
            }
        }

        // ── Per-repo override hint ────────────────────────────────────────────
        row {
            comment(
                "Individual settings can be overridden per repository by placing a " +
                "<tt>.stacktree.yml</tt> file in the repository root. " +
                "Repository-level values take precedence over the IDE defaults above."
            )
        }
    }
}

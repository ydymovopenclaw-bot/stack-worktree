package com.github.ydymovopenclawbot.stackworktree.settings

import com.intellij.openapi.diagnostic.thisLogger
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.reader

/**
 * Loads per-repository StackTree settings from `.stacktree.yml` in the repo root.
 *
 * ## Format
 * All keys are optional.  Only keys that are present in the file override the
 * corresponding IDE-level setting; absent keys fall through to the IDE default.
 *
 * ```yaml
 * worktreeBaseDir: /workspace/worktrees
 * autoPruneOnMerge: true
 * prNavigationComment: false
 * aheadBehindRefreshInterval: 15   # seconds
 * prPollInterval: 120              # seconds
 * autoRestackOnSync: true
 * branchNamingTemplate: "{stack}/{index}-{description}"
 * ```
 *
 * ## Error handling
 * Malformed YAML returns `null` (a warning is logged).  Code that consumes the
 * result should treat `null` as "no override" and use IDE settings unchanged.
 *
 * ## Dependency note
 * SnakeYAML is bundled with IntelliJ Platform — no extra Gradle dependency is
 * required.
 */
object RepoSettingsLoader {

    private const val FILE_NAME = ".stacktree.yml"

    /**
     * Reads and parses [FILE_NAME] from [repoRoot].
     *
     * @return [StackTreeSettingsOverride] with only the keys that were present in
     *   the file set to non-null, or `null` when the file is absent or unparseable.
     */
    fun load(repoRoot: Path): StackTreeSettingsOverride? {
        val file = repoRoot.resolve(FILE_NAME)
        if (!file.exists()) return null

        return try {
            val yaml = Yaml()
            @Suppress("UNCHECKED_CAST")
            val map: Map<String, Any> = yaml.load(file.reader()) ?: return null

            StackTreeSettingsOverride(
                worktreeBaseDir            = map["worktreeBaseDir"] as? String,
                autoPruneOnMerge           = map["autoPruneOnMerge"] as? Boolean,
                prNavigationComment        = map["prNavigationComment"] as? Boolean,
                aheadBehindRefreshInterval = (map["aheadBehindRefreshInterval"] as? Number)?.toInt(),
                prPollInterval             = (map["prPollInterval"] as? Number)?.toInt(),
                autoRestackOnSync          = map["autoRestackOnSync"] as? Boolean,
                branchNamingTemplate       = map["branchNamingTemplate"] as? String,
            )
        } catch (e: Exception) {
            thisLogger().warn("StackTree: failed to parse $FILE_NAME at $repoRoot — using IDE settings", e)
            null
        }
    }
}

/**
 * Partial settings overlay loaded from `.stacktree.yml`.
 *
 * All fields are nullable: a non-null value overrides the corresponding IDE-level
 * setting; a null value means "not specified — use IDE default".
 *
 * @see EffectiveSettingsProvider
 */
data class StackTreeSettingsOverride(
    val worktreeBaseDir: String? = null,
    val autoPruneOnMerge: Boolean? = null,
    val prNavigationComment: Boolean? = null,
    val aheadBehindRefreshInterval: Int? = null,
    val prPollInterval: Int? = null,
    val autoRestackOnSync: Boolean? = null,
    val branchNamingTemplate: String? = null,
)

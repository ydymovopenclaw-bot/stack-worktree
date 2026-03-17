package com.github.ydymovopenclawbot.stackworktree.actions

/**
 * Returns `true` when [name] is a plausible git branch name.
 *
 * Rejects the most common invalid forms without reimplementing the full git
 * `check-ref-format` rules:
 * - blank or whitespace-only strings
 * - names containing ASCII control characters or the characters ` `, `~`, `^`, `:`,
 *   `?`, `*`, `[`, `\`
 * - names starting or ending with `/` or `.`
 * - names containing `..` or `@{`
 * - the literal strings `@` and `HEAD`
 *
 * For the definitive ruleset see `git help check-ref-format`.
 */
internal fun isValidBranchName(name: String): Boolean {
    if (name.isBlank()) return false
    if (name == "@" || name.equals("HEAD", ignoreCase = true)) return false
    if (name.startsWith("/") || name.endsWith("/")) return false
    if (name.startsWith(".") || name.endsWith(".")) return false
    if (name.endsWith(".lock")) return false
    if (name.contains("..") || name.contains("@{")) return false
    val forbidden = charArrayOf(' ', '~', '^', ':', '?', '*', '[', '\\')
    if (name.any { it in forbidden || it.code < 32 }) return false
    return true
}

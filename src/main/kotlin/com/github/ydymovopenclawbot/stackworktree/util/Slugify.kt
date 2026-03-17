package com.github.ydymovopenclawbot.stackworktree.util

/**
 * Converts freeform text into a git-safe branch-name slug.
 */
object Slugify {

    /**
     * Produces a git-compatible branch-name segment from [input]:
     *  1. Lowercases the string.
     *  2. Strips characters that are not alphanumeric, spaces, or hyphens.
     *  3. Trims leading/trailing whitespace.
     *  4. Collapses runs of whitespace to a single hyphen.
     *  5. Collapses consecutive hyphens to one.
     *  6. Truncates to [maxLength] characters.
     *  7. Strips any trailing hyphen left by truncation.
     *
     * Returns an empty string when [input] contains no usable characters.
     */
    fun slugify(input: String, maxLength: Int = 50): String =
        input
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .take(maxLength)
            .trimEnd('-')
}

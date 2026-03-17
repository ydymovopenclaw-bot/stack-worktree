package com.github.ydymovopenclawbot.stackworktree.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SlugifyTest {

    @Test
    fun `plain text becomes lowercase hyphenated slug`() {
        assertEquals("fix-the-bug", Slugify.slugify("Fix the bug"))
    }

    @Test
    fun `special characters are stripped`() {
        assertEquals("fix-the-bug", Slugify.slugify("Fix the bug!"))
    }

    @Test
    fun `slashes and colons are removed`() {
        assertEquals("featfoobar", Slugify.slugify("feat/foo:bar"))
    }

    @Test
    fun `multiple spaces collapse to single hyphen`() {
        assertEquals("a-b", Slugify.slugify("a   b"))
    }

    @Test
    fun `consecutive hyphens collapse to one`() {
        assertEquals("a-b", Slugify.slugify("a--b"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("hello", Slugify.slugify("  hello  "))
    }

    @Test
    fun `trailing hyphen from truncation is stripped`() {
        // "ab-cd" truncated to 4 chars is "ab-c", not "ab-"
        assertEquals("ab-c", Slugify.slugify("ab-cd", maxLength = 4))
    }

    @Test
    fun `truncation at hyphen boundary strips trailing hyphen`() {
        // "abc-def" truncated to 4 gives "abc-" → trimmed to "abc"
        assertEquals("abc", Slugify.slugify("abc-def", maxLength = 4))
    }

    @Test
    fun `empty string returns empty string`() {
        assertEquals("", Slugify.slugify(""))
    }

    @Test
    fun `all-special-chars string returns empty string`() {
        assertEquals("", Slugify.slugify("!!!???"))
    }

    @Test
    fun `already valid slug is unchanged`() {
        assertEquals("my-branch", Slugify.slugify("my-branch"))
    }

    @Test
    fun `unicode letters are stripped leaving only ascii`() {
        assertEquals("caf", Slugify.slugify("café"))
    }

    @Test
    fun `string within maxLength is not truncated`() {
        val input = "short"
        assertEquals(input, Slugify.slugify(input, maxLength = 50))
    }

    @Test
    fun `result never exceeds maxLength`() {
        val result = Slugify.slugify("a very long commit message that goes on and on", maxLength = 20)
        assert(result.length <= 20) { "Expected length ≤ 20 but got ${result.length}: $result" }
    }
}

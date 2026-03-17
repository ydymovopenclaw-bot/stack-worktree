package com.github.ydymovopenclawbot.stackworktree.actions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [isValidBranchName].
 */
class BranchNameValidatorTest {

    // -------------------------------------------------------------------------
    // Valid names
    // -------------------------------------------------------------------------

    @Test fun `simple alphanumeric name is valid`()      { assertTrue(isValidBranchName("feature")) }
    @Test fun `slash-separated path is valid`()          { assertTrue(isValidBranchName("feature/foo")) }
    @Test fun `hyphens are valid`()                      { assertTrue(isValidBranchName("fix-my-bug")) }
    @Test fun `underscores are valid`()                  { assertTrue(isValidBranchName("fix_my_bug")) }
    @Test fun `numbers in name are valid`()              { assertTrue(isValidBranchName("release/2.0.0")) }
    @Test fun `deep path is valid`()                     { assertTrue(isValidBranchName("user/feat/JIRA-123")) }

    // -------------------------------------------------------------------------
    // Invalid names
    // -------------------------------------------------------------------------

    @Test fun `blank string is invalid`()                { assertFalse(isValidBranchName("")) }
    @Test fun `whitespace-only is invalid`()             { assertFalse(isValidBranchName("   ")) }
    @Test fun `name with space is invalid`()             { assertFalse(isValidBranchName("my branch")) }
    @Test fun `leading slash is invalid`()               { assertFalse(isValidBranchName("/feature")) }
    @Test fun `trailing slash is invalid`()              { assertFalse(isValidBranchName("feature/")) }
    @Test fun `leading dot is invalid`()                 { assertFalse(isValidBranchName(".hidden")) }
    @Test fun `trailing dot is invalid`()                { assertFalse(isValidBranchName("feature.")) }
    @Test fun `double dot is invalid`()                  { assertFalse(isValidBranchName("feat..fix")) }
    @Test fun `at-open-brace is invalid`()               { assertFalse(isValidBranchName("feat@{1}")) }
    @Test fun `bare @ is invalid`()                      { assertFalse(isValidBranchName("@")) }
    @Test fun `HEAD is invalid`()                        { assertFalse(isValidBranchName("HEAD")) }
    @Test fun `tilde is invalid`()                       { assertFalse(isValidBranchName("feat~1")) }
    @Test fun `caret is invalid`()                       { assertFalse(isValidBranchName("feat^2")) }
    @Test fun `colon is invalid`()                       { assertFalse(isValidBranchName("feat:fix")) }
    @Test fun `asterisk is invalid`()                    { assertFalse(isValidBranchName("feat*")) }
    @Test fun `question mark is invalid`()               { assertFalse(isValidBranchName("feat?")) }
    @Test fun `backslash is invalid`()                   { assertFalse(isValidBranchName("feat\\fix")) }
    @Test fun `dot-lock suffix is invalid`()             { assertFalse(isValidBranchName("feature.lock")) }
    @Test fun `control character is invalid`()           { assertFalse(isValidBranchName("feat\u0001name")) }
}

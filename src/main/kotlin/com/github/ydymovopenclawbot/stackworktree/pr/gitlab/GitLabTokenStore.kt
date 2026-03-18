package com.github.ydymovopenclawbot.stackworktree.pr.gitlab

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service

/**
 * Application-level store for GitLab Personal Access Tokens, backed by IntelliJ's
 * [PasswordSafe].
 *
 * Tokens are keyed by GitLab hostname so that multiple GitLab instances (e.g. gitlab.com
 * plus a self-hosted instance) can each have their own token stored independently.
 *
 * This is an application-level service (not project-level) so that a token entered once
 * is reused across all projects that share the same GitLab host.
 */
@Service(Service.Level.APP)
class GitLabTokenStore {

    /**
     * Returns the stored token for [host], or `null` if none has been saved yet.
     */
    fun getToken(host: String): String? =
        PasswordSafe.instance.getPassword(attributes(host))

    /**
     * Persists [token] for [host] in the IDE's secure credential store.
     */
    fun storeToken(host: String, token: String) {
        PasswordSafe.instance.set(attributes(host), Credentials(host, token))
    }

    /**
     * Removes the stored token for [host], if any.
     */
    fun clearToken(host: String) {
        PasswordSafe.instance.set(attributes(host), null)
    }

    private fun attributes(host: String) =
        CredentialAttributes("StackWorkTree GitLab: $host", host)
}

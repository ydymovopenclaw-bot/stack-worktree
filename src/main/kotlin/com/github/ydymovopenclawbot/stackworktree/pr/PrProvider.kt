package com.github.ydymovopenclawbot.stackworktree.pr

/**
 * Low-level provider interface for pull-request CRUD against a remote hosting service.
 *
 * All methods perform blocking network I/O and **must** be called from a background thread
 * (never from the EDT).  Implementations may throw [PrProviderException] for any
 * host-level error (auth failure, not found, network error, etc.).
 *
 * Each hosting platform (GitHub, GitLab, Bitbucket, …) supplies its own implementation.
 * The plugin wires the active provider via the service registry in plugin.xml.
 */
interface PrProvider {
    /**
     * Creates a new pull request from [branch] targeting [base] with the given [title] and
     * [body].  Returns the newly created [PrInfo].
     *
     * @throws PrProviderException if the PR cannot be created (e.g. branch does not exist on
     *   the remote, a PR for this branch already exists, or authentication failed).
     */
    fun createPr(branch: String, base: String, title: String, body: String): PrInfo

    /**
     * Updates an existing pull request identified by [prId].  Only the non-null fields are
     * sent to the remote — passing `null` leaves that field unchanged.
     *
     * @throws PrProviderException if the PR does not exist or the update is rejected.
     */
    fun updatePr(prId: Int, title: String? = null, body: String? = null, base: String? = null): PrInfo

    /**
     * Fetches the current status of pull request [prId], including CI check results and the
     * review decision.
     *
     * @throws PrProviderException if the PR does not exist or the request fails.
     */
    fun getPrStatus(prId: Int): PrStatus

    /**
     * Closes (without merging) the pull request identified by [prId].
     *
     * @throws PrProviderException if the PR does not exist or cannot be closed.
     */
    fun closePr(prId: Int)

    /**
     * Returns the open pull request whose head branch equals [branch], or `null` if none
     * exists.  Only open PRs are considered; merged/closed ones are ignored.
     */
    fun findPrByBranch(branch: String): PrInfo?
}

/** Thrown by [PrProvider] implementations when a host-level operation fails. */
class PrProviderException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

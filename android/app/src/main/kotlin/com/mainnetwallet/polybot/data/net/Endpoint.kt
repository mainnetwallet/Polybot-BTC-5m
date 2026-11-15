package com.mainnetwallet.polybot.data.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

sealed interface Reresolve {
    data class Resolved(val baseUrl: String) : Reresolve
    data object Throttled : Reresolve
    data object NotGist : Reresolve
    data class Failed(val message: String) : Reresolve
}

/**
 * Where the PC currently is. For a direct URL that is fixed. For `gist:<id>` the tunnel URL is read once
 * from the Gist and cached; it is only read again after a failure, and never more often than every
 * [MIN_INTERVAL_MS] (GitHub allows 60 unauthenticated calls per hour per IP). While failures continue the
 * interval doubles up to [MAX_INTERVAL_MS], which keeps an offline phone inside that budget.
 */
class Endpoint(
    private val transport: HttpTransport,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    @Volatile
    private var target: PcTarget? = null

    @Volatile
    private var cached: String? = null
    private var lastResolveAt: Long = NEVER
    private var backoffLevel: Int = 0

    val isGist: Boolean get() = target is PcTarget.Gist

    /** The base URL currently in use for a Gist target (null before the first resolve). */
    val cachedBase: String? get() = cached

    fun configure(newTarget: PcTarget?) {
        target = newTarget
        cached = null
        lastResolveAt = NEVER
        backoffLevel = 0
    }

    /** A request or socket worked: the next failure may re-resolve after the shortest interval again. */
    fun noteSuccess() {
        backoffLevel = 0
    }

    suspend fun currentBase(): String {
        val current = target ?: throw ApiException(ApiException.Kind.NotConfigured, "No PC address is set.")
        return when (current) {
            is PcTarget.Direct -> current.baseUrl
            is PcTarget.Gist -> {
                cached?.let { return it }
                mutex.withLock {
                    cached?.let { return it }
                    if (lastResolveAt != NEVER && nowMs() - lastResolveAt < interval()) {
                        throw ApiException(
                            ApiException.Kind.Gist,
                            "Waiting a moment before reading the Gist again.",
                        )
                    }
                    resolveLocked(current.id)
                }
            }
        }
    }

    /** Called after a failed request or socket. Reads the Gist again unless that happened too recently. */
    suspend fun reresolveAfterFailure(): Reresolve {
        val current = target as? PcTarget.Gist ?: return Reresolve.NotGist
        return mutex.withLock {
            if (lastResolveAt != NEVER && nowMs() - lastResolveAt < interval()) return@withLock Reresolve.Throttled
            try {
                val url = resolveLocked(current.id)
                backoffLevel = minOf(backoffLevel + 1, MAX_LEVEL)
                Reresolve.Resolved(url)
            } catch (e: ApiException) {
                backoffLevel = minOf(backoffLevel + 1, MAX_LEVEL)
                Reresolve.Failed(e.message ?: "Could not read the Gist.")
            }
        }
    }

    /** Reads the Gist now, no throttle. Used by "Test connection", which owns a fresh [Endpoint]. */
    suspend fun resolveNow(id: String): String = mutex.withLock { resolveLocked(id) }

    private fun interval(): Long = minOf(MIN_INTERVAL_MS shl backoffLevel, MAX_INTERVAL_MS)

    private suspend fun resolveLocked(id: String): String {
        lastResolveAt = nowMs()
        val response = try {
            transport.execute(
                HttpRequestSpec(
                    method = "GET",
                    url = "https://api.github.com/gists/$id",
                    headers = mapOf("Accept" to "application/vnd.github+json"),
                ),
            )
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.Gist, "Could not reach GitHub to read the Gist (${e.message ?: "network error"}).")
        }
        when {
            response.code == 404 -> throw ApiException(
                ApiException.Kind.Gist, "Gist not found. Check the id after gist:.", response.code,
            )
            response.code == 403 || response.code == 429 -> throw ApiException(
                ApiException.Kind.Gist, "GitHub rate limit reached. Try again in a few minutes.", response.code,
            )
            response.code !in 200..299 -> throw ApiException(
                ApiException.Kind.Gist, "Could not read the Gist (HTTP ${response.code}).", response.code,
            )
        }
        return when (val parsed = parseGistUrl(response.body)) {
            is GistParse.Ok -> {
                cached = parsed.baseUrl
                parsed.baseUrl
            }
            is GistParse.Error -> throw ApiException(ApiException.Kind.Gist, parsed.message)
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 15_000L
        const val MAX_INTERVAL_MS = 60_000L
        private const val MAX_LEVEL = 2
        private const val NEVER = Long.MIN_VALUE / 2
    }
}

package com.mainnetwallet.polybot.data.net

import com.mainnetwallet.polybot.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Sends authenticated REST calls to the PC and recovers from a changed tunnel URL by itself.
 *
 * Failure handling: a connect error, timeout, 5xx (incl. Cloudflare 530) or a reply that is not JSON
 * is a "transport failure". For a Gist target that triggers one throttled re-read of the Gist, and the
 * call is repeated once after a successful re-read. Calls that change state (POST/PATCH) are only repeated
 * when the failure proves the request never reached the PC (connect error, 502/503/530), so a sell or a
 * start can never be applied twice.
 */
class ApiClient(
    private val transport: HttpTransport,
    private val endpoint: Endpoint,
    private val token: () -> String,
) {
    private class Failure(val exception: ApiException, val transport: Boolean, val safeToRepeat: Boolean)

    suspend fun send(
        method: String,
        path: String,
        body: String? = null,
        authenticated: Boolean = true,
    ): String {
        var repeated = false
        while (true) {
            val base = endpoint.currentBase()
            val headers = HashMap<String, String>()
            headers["Accept"] = "application/json"
            val key = token().trim()
            if (authenticated && key.isNotEmpty()) headers["X-API-Key"] = key

            var failure: Failure? = null
            var success: String? = null
            try {
                val response = transport.execute(HttpRequestSpec(method, base + path, headers, body))
                failure = classify(response)
                if (failure == null) success = response.body
            } catch (e: IOException) {
                failure = Failure(
                    ApiException(ApiException.Kind.Network, "Cannot reach the PC (${e.message ?: "network error"})."),
                    transport = true,
                    safeToRepeat = neverSent(e),
                )
            }
            if (failure == null) {
                endpoint.noteSuccess()
                return success.orEmpty()
            }
            if (failure.transport && !repeated && endpoint.isGist) {
                val outcome = endpoint.reresolveAfterFailure()
                if (outcome is Reresolve.Resolved && (method.equals("GET", true) || failure.safeToRepeat)) {
                    repeated = true
                    continue
                }
            }
            throw failure.exception
        }
    }

    private fun neverSent(e: IOException): Boolean =
        e is ConnectException || e is UnknownHostException || e is NoRouteToHostException || e is SSLException ||
            (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)

    private fun classify(response: HttpResponseSpec): Failure? {
        val code = response.code
        val text = response.body
        return when {
            code in 200..299 -> {
                val trimmed = text.trimStart()
                if (code == 204 || trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    null
                } else {
                    Failure(
                        ApiException(
                            ApiException.Kind.BadResponse,
                            "The PC answered with something that is not JSON. Is the tunnel up?",
                            code,
                        ),
                        transport = true,
                        safeToRepeat = false,
                    )
                }
            }
            code == 401 -> Failure(
                ApiException(ApiException.Kind.Unauthorized, "Wrong or missing API token (HTTP 401).", code),
                transport = false,
                safeToRepeat = false,
            )
            code >= 500 -> {
                val detail = extractDetail(text)
                val message = detail ?: when (code) {
                    530, 1033 -> "The tunnel is down (HTTP $code). Is the PC server running?"
                    502, 503, 504 -> "The PC is not answering (HTTP $code)."
                    else -> "Server error (HTTP $code)."
                }
                Failure(
                    ApiException(ApiException.Kind.Server, message, code),
                    transport = true,
                    safeToRepeat = code == 502 || code == 503 || code == 530,
                )
            }
            code in 400..499 -> Failure(
                ApiException(ApiException.Kind.Client, extractDetail(text) ?: "Request rejected (HTTP $code).", code),
                transport = false,
                safeToRepeat = false,
            )
            else -> Failure(
                ApiException(ApiException.Kind.BadResponse, "Unexpected reply (HTTP $code).", code),
                transport = false,
                safeToRepeat = false,
            )
        }
    }

    companion object {
        /** FastAPI errors are `{"detail": "text"}` or, for validation, `{"detail": [{"loc": [...], "msg": "..."}]}`. */
        fun extractDetail(body: String): String? {
            val root = try {
                AppJson.parseToJsonElement(body)
            } catch (_: Exception) {
                return null
            }
            val detail = (root as? JsonObject)?.get("detail") ?: return null
            return when (detail) {
                is JsonPrimitive -> detail.contentOrNull
                is JsonArray -> detail.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val msg = (obj["msg"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val field = (obj["loc"] as? JsonArray)?.lastOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (field != null) "$field: $msg" else msg
                }.joinToString("; ").ifBlank { null }
                else -> detail.toString()
            }
        }
    }
}

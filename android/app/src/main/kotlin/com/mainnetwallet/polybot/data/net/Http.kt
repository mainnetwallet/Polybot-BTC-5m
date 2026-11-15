package com.mainnetwallet.polybot.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponseSpec(val code: Int, val body: String)

/**
 * The only seam between the app logic and the network. Tests plug in a fake.
 * Implementations throw [IOException] when the request could not be completed.
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequestSpec): HttpResponseSpec
}

class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {
    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        val builder = try {
            Request.Builder().url(request.url)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid URL", e)
        }
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val jsonType = "application/json; charset=utf-8".toMediaType()
        when (request.method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post((request.body ?: "{}").toRequestBody(jsonType))
            "PATCH" -> builder.patch((request.body ?: "{}").toRequestBody(jsonType))
            "PUT" -> builder.put((request.body ?: "{}").toRequestBody(jsonType))
            else -> builder.method(request.method.uppercase(), request.body?.toRequestBody(jsonType))
        }
        val response = client.newCall(builder.build()).await()
        return response.use { HttpResponseSpec(it.code, it.body?.string().orEmpty()) }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

/** An error the UI can show as is. [message] is the server's `detail` text whenever there is one. */
class ApiException(
    val kind: Kind,
    message: String,
    val httpCode: Int? = null,
) : Exception(message) {
    enum class Kind { NotConfigured, Gist, Network, Unauthorized, Client, Server, BadResponse }
}

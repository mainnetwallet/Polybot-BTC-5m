package com.mainnetwallet.polybot.data.net

import com.mainnetwallet.polybot.core.AppJson
import com.mainnetwallet.polybot.data.model.LogRow
import com.mainnetwallet.polybot.data.model.Snapshot
import com.mainnetwallet.polybot.data.model.Tick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface FeedEvent {
    data class StateEvent(val snapshot: Snapshot) : FeedEvent
    data class TickEvent(val tick: Tick) : FeedEvent
    data class LogEvent(val row: LogRow) : FeedEvent
}

enum class FeedState { Idle, Connecting, Live, Reconnecting, Offline, AuthFailed }

/**
 * The live WebSocket (`/api/ws?token=...`). Runs only while the app is in the foreground (the app calls
 * [start] / [stop]). Reconnects with a 0.5 s .. 10 s backoff, re-reads the Gist after a failure (throttled by
 * [Endpoint]) and can be told to reconnect immediately when the Android network changes ([kick]).
 */
class LiveFeed(
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val endpoint: Endpoint,
    private val token: () -> String,
    private val onConnected: () -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(FeedState.Idle)
    val state: StateFlow<FeedState> = _state

    /** Lossless, ordered stream of parsed frames. */
    val events = Channel<FeedEvent>(Channel.UNLIMITED)

    private val kickSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var activeSocket: WebSocket? = null

    @Volatile
    private var wanted = false

    @Volatile
    private var manualKick = false

    private class Outcome(val opened: Boolean, val authFailed: Boolean)

    fun start() {
        wanted = true
        if (job?.isActive == true) return
        _state.value = FeedState.Connecting
        job = scope.launch { run() }
    }

    fun stop() {
        wanted = false
        job?.cancel()
        job = null
        activeSocket?.cancel()
        activeSocket = null
        _state.value = FeedState.Idle
    }

    /** Settings changed (new address or token): start over. */
    fun restart() {
        stop()
        start()
    }

    /** The Android network changed: do not wait for the backoff. */
    fun kick() {
        if (!wanted) return
        if (job?.isActive != true) {
            start()
            return
        }
        manualKick = true
        kickSignal.trySend(Unit)
        activeSocket?.cancel()
    }

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val key = token().trim()
            if (key.isEmpty()) {
                _state.value = FeedState.Idle
                return
            }
            var outcome: Outcome? = null
            try {
                val base = endpoint.currentBase()
                outcome = connectOnce(base, key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiException) {
                // no address yet, or the Gist could not be read: wait and try again
            }
            if (outcome != null && outcome.opened) attempt = 0
            if (outcome != null && outcome.authFailed) {
                _state.value = FeedState.AuthFailed
                return
            }
            val kicked = manualKick
            manualKick = false
            if (!kicked && endpoint.isGist) endpoint.reresolveAfterFailure()
            _state.value = if (attempt < OFFLINE_AFTER_ATTEMPTS) FeedState.Reconnecting else FeedState.Offline
            val signalled = withTimeoutOrNull(backoffMs(attempt)) { kickSignal.receive() } != null
            if (signalled) attempt = 0 else attempt++
        }
    }

    private suspend fun connectOnce(base: String, key: String): Outcome {
        val request = Request.Builder().url(UrlUtils.wsUrl(base, key)).build()
        val done = CompletableDeferred<Outcome>()
        val opened = AtomicBoolean(false)
        val lastMessage = AtomicLong(nowMs())

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                lastMessage.set(nowMs())
                endpoint.noteSuccess()
                _state.value = FeedState.Live
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessage.set(nowMs())
                handleFrame(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Outcome(opened.get(), code == 1008))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                done.complete(Outcome(opened.get(), code == 401 || code == 403))
            }
        }

        val socket = client.newWebSocket(request, listener)
        activeSocket = socket
        // A mobile network can drop silently. The server pushes state twice a second, so silence means "dead".
        val watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (opened.get() && nowMs() - lastMessage.get() > STALE_AFTER_MS) {
                    socket.cancel()
                    break
                }
            }
        }
        try {
            return done.await()
        } finally {
            watchdog.cancel()
            socket.cancel()
            if (activeSocket === socket) activeSocket = null
        }
    }

    private fun handleFrame(text: String) {
        try {
            val root = AppJson.parseToJsonElement(text).jsonObject
            val type = root["type"]?.jsonPrimitive?.content ?: return
            val data = root["data"] ?: return
            when (type) {
                "state" -> events.trySend(FeedEvent.StateEvent(AppJson.decodeFromJsonElement(Snapshot.serializer(), data)))
                "tick" -> events.trySend(FeedEvent.TickEvent(AppJson.decodeFromJsonElement(Tick.serializer(), data)))
                "log" -> {
                    val row = AppJson.decodeFromJsonElement(LogRow.serializer(), data)
                    val stamped = if (row.createdAt.isBlank()) row.copy(createdAt = Instant.ofEpochMilli(nowMs()).toString()) else row
                    events.trySend(FeedEvent.LogEvent(stamped))
                }
            }
        } catch (_: Exception) {
            // a malformed frame must never kill the feed
        }
    }

    companion object {
        private const val OFFLINE_AFTER_ATTEMPTS = 4
        private const val WATCHDOG_INTERVAL_MS = 3_000L
        private const val STALE_AFTER_MS = 12_000L

        /** 0.5 s, 1 s, 2 s, 4 s, 8 s, then 10 s. */
        fun backoffMs(attempt: Int): Long = minOf(500L shl minOf(attempt, 5), 10_000L)
    }
}

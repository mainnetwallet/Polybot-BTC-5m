package com.mainnetwallet.polybot

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.mainnetwallet.polybot.data.BotRepository
import com.mainnetwallet.polybot.data.net.ApiClient
import com.mainnetwallet.polybot.data.net.BotApi
import com.mainnetwallet.polybot.data.net.ConnectionTester
import com.mainnetwallet.polybot.data.net.Endpoint
import com.mainnetwallet.polybot.data.net.LiveFeed
import com.mainnetwallet.polybot.data.net.NetworkMonitor
import com.mainnetwallet.polybot.data.net.OkHttpTransport
import com.mainnetwallet.polybot.data.net.TargetParse
import com.mainnetwallet.polybot.data.net.UrlUtils
import com.mainnetwallet.polybot.data.store.AppPrefs
import com.mainnetwallet.polybot.data.store.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired dependency graph; one instance lives as long as the process. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefsStore = PrefsStore(app)

    @Volatile
    private var token: String = ""

    @Volatile
    private var foreground = false

    @Volatile
    private var configured = false

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val wsClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val transport = OkHttpTransport(httpClient)
    private val endpoint = Endpoint(transport)
    val api = BotApi(ApiClient(transport, endpoint) { token })
    val connectionTester = ConnectionTester(transport)

    private val feed: LiveFeed = LiveFeed(
        scope = scope,
        client = wsClient,
        endpoint = endpoint,
        token = { token },
        onConnected = { repository.onFeedConnected() },
    )

    val repository: BotRepository = BotRepository(scope, api, feed)

    val prefs: StateFlow<AppPrefs?> = prefsStore.prefs.stateIn(scope, SharingStarted.Eagerly, null)

    private val networkMonitor = NetworkMonitor(app) { feed.kick() }

    fun start() {
        networkMonitor.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                if (configured) {
                    feed.start()
                    scope.launch { repository.refresh() }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
                feed.stop()
            }
        })
        scope.launch {
            var last: Pair<String, String>? = null
            prefsStore.prefs.collect { p ->
                val key = p.pcUrl to p.token
                if (key != last) {
                    last = key
                    applyConnection(p)
                }
            }
        }
    }

    private fun applyConnection(p: AppPrefs) {
        token = p.token
        val parsed = UrlUtils.parseTarget(p.pcUrl)
        endpoint.configure((parsed as? TargetParse.Ok)?.target)
        configured = p.isConfigured && parsed is TargetParse.Ok
        if (configured) {
            if (foreground) feed.restart()
        } else {
            feed.stop()
            repository.clearAll()
        }
    }
}

package com.mainnetwallet.polybot.data.net

import com.mainnetwallet.polybot.core.AppJson
import com.mainnetwallet.polybot.data.model.Analytics
import com.mainnetwallet.polybot.data.model.HealthInfo
import com.mainnetwallet.polybot.data.model.LiveReadiness
import com.mainnetwallet.polybot.data.model.LogRow
import com.mainnetwallet.polybot.data.model.OrderRow
import com.mainnetwallet.polybot.data.model.PositionRow
import com.mainnetwallet.polybot.data.model.Snapshot
import com.mainnetwallet.polybot.data.model.TriggerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Typed view of the backend REST API (backend/app/api/routes.py). Errors are [ApiException]s. */
class BotApi(private val client: ApiClient) {

    private suspend fun <T> call(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        body: JsonObject? = null,
    ): T {
        val text = client.send(method, path, body?.toString())
        return try {
            // a full state or 200 log rows are big: decode them away from the main thread
            withContext(Dispatchers.Default) { AppJson.decodeFromString(serializer, text) }
        } catch (e: IllegalArgumentException) {
            throw ApiException(ApiException.Kind.BadResponse, "The PC sent a reply the app could not read.")
        }
    }

    // ---- open endpoints --------------------------------------------------------------------------------------

    /** `GET /healthz`: no token needed. Used to tell "tunnel reachable" from "token valid". */
    suspend fun healthz(): String = client.send("GET", "/healthz", authenticated = false)

    suspend fun health(): HealthInfo =
        AppJson.decodeFromString(HealthInfo.serializer(), client.send("GET", "/api/health", authenticated = false))

    // ---- state & control -------------------------------------------------------------------------------------

    suspend fun state(): Snapshot = call("GET", "/api/state", Snapshot.serializer())

    suspend fun startBot(confirmLive: Boolean, phrase: String): Snapshot =
        call(
            "POST", "/api/bot/start", Snapshot.serializer(),
            buildJsonObject { put("confirm_live", confirmLive); put("confirmation_phrase", phrase) },
        )

    suspend fun stopBot(): Snapshot = call("POST", "/api/bot/stop", Snapshot.serializer(), JsonObject(emptyMap()))

    suspend fun switchMode(mode: String, confirmLive: Boolean, phrase: String): Snapshot =
        call(
            "POST", "/api/mode", Snapshot.serializer(),
            buildJsonObject { put("mode", mode); put("confirm_live", confirmLive); put("confirmation_phrase", phrase) },
        )

    suspend fun killSwitch(active: Boolean): Snapshot =
        call("POST", "/api/kill-switch", Snapshot.serializer(), buildJsonObject { put("active", active) })

    /** Turn NEW buys on/off for every stage. Exits and settlement are not affected. */
    suspend fun setBuys(enabled: Boolean): Snapshot =
        call("POST", "/api/buys", Snapshot.serializer(), buildJsonObject { put("enabled", enabled) })

    suspend fun sellNow(marketId: String, side: String, stage: Int): Snapshot =
        call(
            "POST", "/api/orders/sell", Snapshot.serializer(),
            buildJsonObject { put("market_id", marketId); put("side", side); put("stage", stage) },
        )

    /** One-tap buy of [amount] USDC. [requestId] makes a request the network repeats buy only once. */
    suspend fun tapBuy(marketId: String, side: String, amount: Double, requestId: String): Snapshot =
        call(
            "POST", "/api/orders/tap-buy", Snapshot.serializer(),
            buildJsonObject {
                put("market_id", marketId)
                put("side", side)
                put("amount", amount)
                put("request_id", requestId)
            },
        )

    suspend fun sellAll(marketId: String): Snapshot =
        call("POST", "/api/orders/sell-all", Snapshot.serializer(), buildJsonObject { put("market_id", marketId) })

    // ---- settings --------------------------------------------------------------------------------------------

    suspend fun settings(): Map<String, JsonElement> =
        parseObject(client.send("GET", "/api/settings"))

    suspend fun patchSettings(changes: Map<String, JsonElement>): Map<String, JsonElement> =
        parseObject(client.send("PATCH", "/api/settings", JsonObject(changes).toString()))

    suspend fun resetSettings(): Map<String, JsonElement> =
        parseObject(client.send("POST", "/api/settings/reset", "{}"))

    // ---- history & analytics ---------------------------------------------------------------------------------

    suspend fun orders(mode: String, limit: Int = 100): List<OrderRow> =
        call("GET", "/api/orders?mode=$mode&limit=$limit", ListSerializer(OrderRow.serializer()))

    suspend fun positions(mode: String, limit: Int = 100): List<PositionRow> =
        call("GET", "/api/positions?mode=$mode&limit=$limit", ListSerializer(PositionRow.serializer()))

    suspend fun analytics(mode: String): Analytics =
        call("GET", "/api/analytics?mode=$mode", Analytics.serializer())

    suspend fun triggers(limit: Int = 100): List<TriggerRow> =
        call("GET", "/api/triggers?limit=$limit", ListSerializer(TriggerRow.serializer()))

    suspend fun logs(limit: Int = 200): List<LogRow> =
        call("GET", "/api/logs?limit=$limit", ListSerializer(LogRow.serializer()))

    // ---- live readiness & maintenance ------------------------------------------------------------------------

    suspend fun liveReadiness(): LiveReadiness = call("GET", "/api/live/readiness", LiveReadiness.serializer())

    /** `POST /api/live/validate`: returns the raw reply (balance, allowance) as text for display. */
    suspend fun validateLive(): String = client.send("POST", "/api/live/validate", "{}")

    suspend fun resetPaper(balance: Double): Snapshot =
        call(
            "POST", "/api/paper/reset", Snapshot.serializer(),
            buildJsonObject { put("balance", balance); put("confirm", true) },
        )

    suspend fun clearOrders(): Snapshot =
        call("POST", "/api/orders/clear", Snapshot.serializer(), buildJsonObject { put("confirm", true) })

    suspend fun resetDaily(): Snapshot =
        call("POST", "/api/daily/reset", Snapshot.serializer(), buildJsonObject { put("confirm", true) })

    suspend fun resetServer(phrase: String): Snapshot =
        call(
            "POST", "/api/server/reset", Snapshot.serializer(),
            buildJsonObject { put("confirm", true); put("confirmation_phrase", phrase) },
        )

    private fun parseObject(text: String): Map<String, JsonElement> = try {
        AppJson.parseToJsonElement(text).jsonObject.toMap()
    } catch (e: IllegalArgumentException) {
        throw ApiException(ApiException.Kind.BadResponse, "The PC sent a reply the app could not read.")
    }
}

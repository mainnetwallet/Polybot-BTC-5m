package com.mainnetwallet.polybot.data.net

import java.io.IOException

enum class StepState { Pending, Running, Ok, Failed, Skipped }

data class TestStep(
    val id: String,
    val title: String,
    val state: StepState = StepState.Pending,
    val detail: String = "",
)

/**
 * "Test connection": checks the address, the Gist (if any), that the tunnel/PC answers and that the token works.
 * It uses its own [Endpoint] so a test never disturbs the live connection or its Gist rate limiting.
 */
class ConnectionTester(private val transport: HttpTransport) {

    suspend fun run(
        input: String,
        token: String,
        onUpdate: (List<TestStep>) -> Unit,
    ): Boolean {
        var steps = listOf(
            TestStep(STEP_ADDRESS, "Address"),
            TestStep(STEP_GIST, "Gist link"),
            TestStep(STEP_TUNNEL, "Tunnel reachable"),
            TestStep(STEP_TOKEN, "Token valid"),
        )

        fun update(id: String, state: StepState, detail: String = "") {
            steps = steps.map { if (it.id == id) it.copy(state = state, detail = detail) else it }
            onUpdate(steps)
        }

        fun failRest(from: String) {
            var seen = false
            steps = steps.map {
                if (it.id == from) seen = true
                if (seen && it.state == StepState.Pending) it.copy(state = StepState.Skipped) else it
            }
            onUpdate(steps)
        }

        onUpdate(steps)

        // 1. address
        update(STEP_ADDRESS, StepState.Running)
        val parsed = UrlUtils.parseTarget(input)
        val target = when (parsed) {
            is TargetParse.Invalid -> {
                update(STEP_ADDRESS, StepState.Failed, parsed.reason)
                failRest(STEP_GIST)
                return false
            }
            is TargetParse.Ok -> parsed.target
        }
        update(STEP_ADDRESS, StepState.Ok, if (target is PcTarget.Gist) "Gist ${target.id.take(8)}…" else (target as PcTarget.Direct).baseUrl)

        val endpoint = Endpoint(transport)
        endpoint.configure(target)
        val client = ApiClient(transport, endpoint) { token }

        // 2. gist
        when (target) {
            is PcTarget.Direct -> update(STEP_GIST, StepState.Skipped, "Not a Gist link")
            is PcTarget.Gist -> {
                update(STEP_GIST, StepState.Running)
                try {
                    val url = endpoint.resolveNow(target.id)
                    update(STEP_GIST, StepState.Ok, hostOf(url))
                } catch (e: ApiException) {
                    update(STEP_GIST, StepState.Failed, e.message ?: "Could not read the Gist.")
                    failRest(STEP_TUNNEL)
                    return false
                }
            }
        }

        // 3. tunnel / PC reachable (no token needed)
        update(STEP_TUNNEL, StepState.Running)
        try {
            val base = endpoint.currentBase()
            val reply = client.send("GET", "/healthz", authenticated = false)
            if (reply.contains("ok")) {
                update(STEP_TUNNEL, StepState.Ok, hostOf(base))
            } else {
                update(STEP_TUNNEL, StepState.Failed, "The address answered, but it is not the Polybot server.")
                failRest(STEP_TOKEN)
                return false
            }
        } catch (e: ApiException) {
            update(STEP_TUNNEL, StepState.Failed, e.message ?: "Cannot reach the PC.")
            failRest(STEP_TOKEN)
            return false
        }

        // 4. token
        update(STEP_TOKEN, StepState.Running)
        if (token.isBlank()) {
            update(STEP_TOKEN, StepState.Failed, "Enter the API token (API_TOKEN in the PC's .env).")
            return false
        }
        return try {
            client.send("GET", "/api/state")
            update(STEP_TOKEN, StepState.Ok, "Accepted")
            true
        } catch (e: ApiException) {
            val hint = if (e.kind == ApiException.Kind.Unauthorized) {
                "The PC rejected the token. Check API_TOKEN in the PC's .env."
            } else {
                e.message ?: "The token check failed."
            }
            update(STEP_TOKEN, StepState.Failed, hint)
            false
        } catch (e: IOException) {
            update(STEP_TOKEN, StepState.Failed, e.message ?: "The token check failed.")
            false
        }
    }

    private fun hostOf(url: String): String = url.substringAfter("://").trimEnd('/')

    companion object {
        const val STEP_ADDRESS = "address"
        const val STEP_GIST = "gist"
        const val STEP_TUNNEL = "tunnel"
        const val STEP_TOKEN = "token"
    }
}

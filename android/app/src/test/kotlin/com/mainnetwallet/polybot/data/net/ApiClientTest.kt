package com.mainnetwallet.polybot.data.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

/** A scripted HTTP layer: tests set [onRequest] and read back [requests]. */
private class FakeTransport : HttpTransport {
    val requests = mutableListOf<HttpRequestSpec>()
    var onRequest: (HttpRequestSpec) -> HttpResponseSpec = { error("unexpected request ${it.url}") }

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        return onRequest(request)
    }

    fun gistCalls() = requests.count { it.url.startsWith("https://api.github.com/gists/") }
    fun pcCalls() = requests.filterNot { it.url.startsWith("https://api.github.com/") }
}

private const val GIST_ID = "0123456789abcdef0123456789abcdef"
private const val OLD = "https://old-tunnel.trycloudflare.com"
private const val NEW = "https://new-tunnel.trycloudflare.com"

private fun gistJson(url: String) = """{"files":{"pc-url.txt":{"content":"$url"}}}"""
private fun ok(body: String = """{"status":"ok"}""") = HttpResponseSpec(200, body)

class ApiClientTest {
    private val transport = FakeTransport()
    private var now = 1_000_000L
    private val endpoint = Endpoint(transport) { now }
    private val client = ApiClient(transport, endpoint) { "secret-token" }

    /** Serves the Gist from [gist]; every other request goes to [pc]. */
    private fun route(gist: () -> String, pc: (HttpRequestSpec) -> HttpResponseSpec) {
        transport.onRequest = { req ->
            if (req.url.startsWith("https://api.github.com/gists/")) HttpResponseSpec(200, gistJson(gist())) else pc(req)
        }
    }

    private fun useGist() = endpoint.configure(PcTarget.Gist(GIST_ID))

    private suspend fun failing(block: suspend () -> Unit): ApiException {
        try {
            block()
        } catch (e: ApiException) {
            return e
        }
        fail("expected an ApiException")
        throw IllegalStateException()
    }

    @Test fun directTargetNeverTouchesGithub() = runTest {
        endpoint.configure(PcTarget.Direct("https://pc.example.com"))
        route({ error("no gist") }) { ok() }
        client.send("GET", "/api/state")
        assertEquals(0, transport.gistCalls())
        assertEquals("https://pc.example.com/api/state", transport.pcCalls().single().url)
    }

    @Test fun sendsTheTokenHeaderOnAuthenticatedCallsOnly() = runTest {
        endpoint.configure(PcTarget.Direct("https://pc.example.com"))
        route({ "" }) { ok() }
        client.send("GET", "/api/state")
        client.send("GET", "/healthz", authenticated = false)
        val calls = transport.pcCalls()
        assertEquals("secret-token", calls[0].headers["X-API-Key"])
        assertNull(calls[1].headers["X-API-Key"])
    }

    @Test fun lastKnownUrlIsUsedWithoutReadingTheGist() = runTest {
        endpoint.configure(PcTarget.Gist(GIST_ID), OLD)
        route({ error("no gist") }) { ok() }
        client.send("GET", "/api/state")
        assertEquals(0, transport.gistCalls())
        assertEquals("$OLD/api/state", transport.pcCalls().single().url)
    }

    @Test fun staleLastKnownUrlFallsBackToTheGist() = runTest {
        endpoint.configure(PcTarget.Gist(GIST_ID), OLD)
        route({ NEW }) { req ->
            if (req.url.startsWith(OLD)) throw ConnectException("down") else ok()
        }
        client.send("GET", "/api/state")
        assertEquals(1, transport.gistCalls())
        assertEquals("$NEW/api/state", transport.pcCalls().last().url)
    }

    @Test fun lastKnownUrlIsIgnoredForDirectTargets() = runTest {
        endpoint.configure(PcTarget.Direct("https://pc.example.com"), OLD)
        route({ error("no gist") }) { ok() }
        client.send("GET", "/api/state")
        assertEquals("https://pc.example.com/api/state", transport.pcCalls().single().url)
    }

    @Test fun gistReadReportsTheUrlSoItCanBeRemembered() = runTest {
        val seen = mutableListOf<String>()
        val ep = Endpoint(transport, { now }) { seen += it }
        ep.configure(PcTarget.Gist(GIST_ID))
        route({ NEW }) { ok() }
        ep.currentBase()
        assertEquals(listOf(NEW), seen)
    }

    @Test fun gistIsResolvedOnceAndCached() = runTest {
        useGist()
        route({ OLD }) { ok() }
        repeat(3) { client.send("GET", "/api/state") }
        assertEquals(1, transport.gistCalls())
        assertTrue(transport.pcCalls().all { it.url.startsWith(OLD) })
    }

    @Test fun failedGetReresolvesTheGistAndRetriesOnce() = runTest {
        useGist()
        var published = OLD
        route({ published }) { req ->
            if (req.url.startsWith(OLD)) {
                published = NEW // the PC publishes the new link while the old one dies
                now += 20_000 // the failure comes well after the first read, so the 15 s throttle is over
                HttpResponseSpec(530, "")
            } else {
                ok()
            }
        }
        client.send("GET", "/api/state")
        assertEquals(
            listOf("https://api.github.com/gists/$GIST_ID", "$OLD/api/state", "https://api.github.com/gists/$GIST_ID", "$NEW/api/state"),
            transport.requests.map { it.url },
        )
        assertEquals(NEW, endpoint.cachedBase)
    }

    @Test fun retryHappensOnlyOnce() = runTest {
        useGist()
        route({ OLD }) {
            now += 20_000
            HttpResponseSpec(530, "")
        }
        val e = failing { client.send("GET", "/api/state") }
        assertEquals(530, e.httpCode)
        assertEquals(2, transport.pcCalls().size) // the original call and exactly one retry
    }

    @Test fun recoversByItselfAfterTheTunnelUrlChanged() = runTest {
        useGist()
        var published = OLD
        route({ published }) { req ->
            if (req.url.startsWith(OLD)) {
                now += 20_000
                HttpResponseSpec(530, "error code: 1033")
            } else {
                ok("""{"bot":{"status":"RUNNING"}}""")
            }
        }
        // The Gist still points at the dead tunnel: the call fails.
        assertEquals(530, failing { client.send("GET", "/api/state") }.httpCode)
        // The PC restarted and published a new URL: the next call finds it by itself.
        published = NEW
        now += 20_000
        assertTrue(client.send("GET", "/api/state").contains("RUNNING"))
        assertEquals(NEW, endpoint.cachedBase)
    }

    @Test fun gistIsNotReadMoreThanOncePer15Seconds() = runTest {
        useGist()
        route({ OLD }) { HttpResponseSpec(530, "") }
        failing { client.send("GET", "/api/state") } // resolve + fail: the read just happened, so no second one
        assertEquals(1, transport.gistCalls())
        now += 5_000
        failing { client.send("GET", "/api/state") }
        assertEquals(1, transport.gistCalls())
        now += 11_000 // 16 s after the read
        failing { client.send("GET", "/api/state") }
        assertEquals(2, transport.gistCalls())
    }

    @Test fun backoffGrowsWhileFailuresContinue() = runTest {
        useGist()
        route({ OLD }) { HttpResponseSpec(530, "") }
        failing { client.send("GET", "/api/state") }
        now += 16_000
        failing { client.send("GET", "/api/state") } // second Gist read, the interval doubles
        assertEquals(2, transport.gistCalls())
        now += 16_000 // enough for 15 s, not for 30 s
        failing { client.send("GET", "/api/state") }
        assertEquals(2, transport.gistCalls())
        now += 15_000
        failing { client.send("GET", "/api/state") }
        assertEquals(3, transport.gistCalls())
    }

    @Test fun unauthorizedDoesNotTriggerAGistRead() = runTest {
        useGist()
        route({ OLD }) { HttpResponseSpec(401, """{"detail":"unauthorized"}""") }
        val e = failing { client.send("GET", "/api/state") }
        assertEquals(ApiException.Kind.Unauthorized, e.kind)
        assertEquals(1, transport.gistCalls())
    }

    @Test fun clientErrorShowsTheServersDetailText() = runTest {
        endpoint.configure(PcTarget.Direct("https://pc.example.com"))
        route({ "" }) { HttpResponseSpec(400, """{"detail":"live trading requires explicit confirmation"}""") }
        val e = failing { client.send("POST", "/api/mode", "{}") }
        assertEquals("live trading requires explicit confirmation", e.message)
        assertEquals(ApiException.Kind.Client, e.kind)
    }

    @Test fun nonJsonSuccessBodyCountsAsATransportFailure() = runTest {
        useGist()
        var published = OLD
        route({ published }) { req ->
            if (req.url.startsWith(OLD)) {
                published = NEW
                now += 20_000
                HttpResponseSpec(200, "<html>Cloudflare error</html>")
            } else {
                ok()
            }
        }
        assertEquals("""{"status":"ok"}""", client.send("GET", "/healthz", authenticated = false))
    }

    @Test fun postIsNotRepeatedAfterAServerError() = runTest {
        useGist()
        var published = OLD
        route({ published }) { req ->
            published = NEW
            now += 20_000
            if (req.url.startsWith(OLD)) HttpResponseSpec(500, """{"detail":"boom"}""") else ok()
        }
        val e = failing { client.send("POST", "/api/orders/sell", "{}") }
        assertEquals("boom", e.message)
        assertEquals(1, transport.pcCalls().size)
    }

    @Test fun postIsRepeatedWhenTheRequestNeverLeftThePhone() = runTest {
        useGist()
        var published = OLD
        route({ published }) { req ->
            if (req.url.startsWith(OLD)) {
                published = NEW
                now += 20_000
                throw ConnectException("Failed to connect")
            }
            ok()
        }
        client.send("POST", "/api/bot/stop", "{}")
        assertEquals(listOf(OLD, NEW), transport.pcCalls().map { it.url.substringBefore("/api") })
    }

    @Test fun postIsNotRepeatedAfterAReadTimeout() = runTest {
        useGist()
        var published = OLD
        route({ published }) { req ->
            published = NEW
            now += 20_000
            if (req.url.startsWith(OLD)) throw IOException("timeout") else ok()
        }
        val e = failing { client.send("POST", "/api/orders/sell-all", "{}") }
        assertEquals(ApiException.Kind.Network, e.kind)
        assertEquals(1, transport.pcCalls().size)
    }

    @Test fun missingGistFileSurfacesAsAGistError() = runTest {
        useGist()
        transport.onRequest = { HttpResponseSpec(200, """{"files":{}}""") }
        assertEquals(ApiException.Kind.Gist, failing { client.send("GET", "/api/state") }.kind)
    }

    @Test fun gistNotFoundAndRateLimitHaveClearMessages() = runTest {
        useGist()
        transport.onRequest = { HttpResponseSpec(404, "{}") }
        assertTrue(failing { client.send("GET", "/api/state") }.message!!.contains("not found", ignoreCase = true))
        useGist() // configure() starts over, so the throttle does not hide the next read
        transport.onRequest = { HttpResponseSpec(403, "{}") }
        assertTrue(failing { client.send("GET", "/api/state") }.message!!.contains("rate limit", ignoreCase = true))
    }

    @Test fun notConfiguredFailsFast() = runTest {
        endpoint.configure(null)
        assertEquals(ApiException.Kind.NotConfigured, failing { client.send("GET", "/api/state") }.kind)
        assertFalse(endpoint.isGist)
    }

    @Test fun extractDetailHandlesStringAndValidationShapes() {
        assertEquals("nope", ApiClient.extractDetail("""{"detail":"nope"}"""))
        assertEquals(
            "trigger_threshold: Input should be greater than or equal to 0.01",
            ApiClient.extractDetail(
                """{"detail":[{"loc":["body","trigger_threshold"],"msg":"Input should be greater than or equal to 0.01","type":"x"}]}""",
            ),
        )
        assertNull(ApiClient.extractDetail("not json"))
        assertNull(ApiClient.extractDetail("""{"other":1}"""))
    }
}

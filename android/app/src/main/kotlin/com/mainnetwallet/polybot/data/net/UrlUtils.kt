package com.mainnetwallet.polybot.data.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/** What the "PC URL" field points at. */
sealed interface PcTarget {
    /** A ready-to-use base URL such as `https://abc.trycloudflare.com` or `http://192.168.1.20:8000`. */
    data class Direct(val baseUrl: String) : PcTarget

    /** A secret Gist that holds the current tunnel URL in `pc-url.txt`. */
    data class Gist(val id: String) : PcTarget
}

sealed interface TargetParse {
    data class Ok(val target: PcTarget) : TargetParse
    data class Invalid(val reason: String) : TargetParse
}

object UrlUtils {
    private val gistPrefix = Regex("""^gist:\s*([0-9a-fA-F]{20,40})$""", RegexOption.IGNORE_CASE)
    private val gistUrl = Regex(
        """^https?://gist\.github\.com/(?:[^/\s]+/)?([0-9a-fA-F]{20,40})(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Turns the text of the PC URL field into a [PcTarget]:
     * `gist:<id>` or a gist.github.com URL, `https://...`, `http://...` (LAN) or a bare host.
     * A bare host gets `https://`, except private hosts, which get `http://`.
     */
    fun parseTarget(input: String): TargetParse {
        val raw = input.trim()
        if (raw.isEmpty()) return TargetParse.Invalid("Enter the PC address: an https:// URL or gist:<id>.")

        gistPrefix.matchEntire(raw)?.let { return TargetParse.Ok(PcTarget.Gist(it.groupValues[1].lowercase())) }
        gistUrl.matchEntire(raw)?.let { return TargetParse.Ok(PcTarget.Gist(it.groupValues[1].lowercase())) }
        if (raw.lowercase().startsWith("gist:")) {
            return TargetParse.Invalid("That Gist id looks wrong. Use gist:<id> with the 32-character id.")
        }

        val withScheme = if ("://" in raw) raw else defaultScheme(raw) + raw
        val lower = withScheme.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return TargetParse.Invalid("Only http:// and https:// addresses are supported.")
        }
        val url = withScheme.toHttpUrlOrNull()
            ?: return TargetParse.Invalid("That does not look like a valid address.")
        return TargetParse.Ok(PcTarget.Direct(baseOf(url)))
    }

    /** Scheme + host + non-default port, without path, query or trailing slash. */
    fun baseOf(url: HttpUrl): String {
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        val defaultPort = if (url.scheme == "https") 443 else 80
        return buildString {
            append(url.scheme).append("://").append(host)
            if (url.port != defaultPort) append(':').append(url.port)
        }
    }

    private fun defaultScheme(raw: String): String = if (isPrivateHost(rawHost(raw))) "http://" else "https://"

    private fun rawHost(raw: String): String {
        var authority = raw.substringBefore('/').substringBefore('?').substringBefore('#')
        authority = authority.substringAfterLast('@')
        return if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
    }

    /** LAN, loopback, link-local, CGNAT (Tailscale) and single-label names are "private". */
    fun isPrivateHost(hostInput: String): Boolean {
        val host = hostInput.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) {
            return true
        }
        val match = ipv4.matchEntire(host)
        if (match != null) {
            val o = match.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return false
            return when {
                o[0] == 10 -> true
                o[0] == 127 -> true
                o[0] == 169 && o[1] == 254 -> true
                o[0] == 172 && o[1] in 16..31 -> true
                o[0] == 192 && o[1] == 168 -> true
                o[0] == 100 && o[1] in 64..127 -> true
                else -> false
            }
        }
        if (host.contains(':')) {
            return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
        }
        // a name without a dot ("my-pc") only resolves on a local network
        return !host.contains('.')
    }

    /** True for plain http to a public host: traffic (and the token) would travel unencrypted. */
    fun isInsecureOutsidePrivate(baseUrl: String): Boolean {
        val url = baseUrl.toHttpUrlOrNull() ?: return false
        return url.scheme == "http" && !isPrivateHost(url.host)
    }

    /**
     * True when the PC address points straight at a device on the local network (LAN IP, `.local`, ...).
     * Android 17+ apps that target API 37 need the ACCESS_LOCAL_NETWORK permission to reach those. A Gist
     * link never counts: it resolves to a public tunnel URL.
     */
    fun isLocalNetworkTarget(input: String): Boolean {
        val target = (parseTarget(input) as? TargetParse.Ok)?.target as? PcTarget.Direct ?: return false
        val host = target.baseUrl.toHttpUrlOrNull()?.host ?: return false
        return isPrivateHost(host)
    }

    /**
     * The address the app is actually talking to, for showing it and opening it in a browser. A direct address is
     * its own base URL. For `gist:<id>` it is the tunnel URL last read from the Gist ([lastTunnelUrl], valid only
     * while [lastTunnelFor] is the saved address), or null before the first read.
     */
    fun serverUrl(pcUrl: String, lastTunnelFor: String, lastTunnelUrl: String): String? =
        when (val target = (parseTarget(pcUrl) as? TargetParse.Ok)?.target) {
            is PcTarget.Direct -> target.baseUrl
            is PcTarget.Gist -> lastTunnelUrl.trim()
                .takeIf { lastTunnelFor == pcUrl && it.startsWith("https://") }
                ?.toHttpUrlOrNull()
                ?.let(::baseOf)
            null -> null
        }

    /** https -> wss, http -> ws; the API token travels as `?token=` because browsers/OkHttp cannot set WS headers reliably. */
    fun wsUrl(baseUrl: String, token: String): String {
        val url = baseUrl.toHttpUrlOrNull() ?: error("Invalid base URL")
        val scheme = if (url.scheme == "https") "wss" else "ws"
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        val defaultPort = if (url.scheme == "https") 443 else 80
        val port = if (url.port != defaultPort) ":${url.port}" else ""
        val query = if (token.isNotEmpty()) "?token=" + URLEncoder.encode(token, "UTF-8") else ""
        return "$scheme://$host$port/api/ws$query"
    }
}

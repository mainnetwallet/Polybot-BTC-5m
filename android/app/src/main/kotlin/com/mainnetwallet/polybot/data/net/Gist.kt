package com.mainnetwallet.polybot.data.net

import com.mainnetwallet.polybot.core.AppJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The file in the secret Gist that `scripts/start.ps1` keeps up to date. */
const val GIST_FILE = "pc-url.txt"

sealed interface GistParse {
    data class Ok(val baseUrl: String) : GistParse
    data class Error(val message: String) : GistParse
}

/** Reads `files["pc-url.txt"].content` out of a `GET /gists/<id>` reply. The URL must be https. */
fun parseGistUrl(body: String): GistParse {
    val root = try {
        AppJson.parseToJsonElement(body).jsonObject
    } catch (_: Exception) {
        return GistParse.Error("The Gist reply was not valid JSON.")
    }
    val file = runCatching { root["files"]?.jsonObject?.get(GIST_FILE)?.jsonObject }.getOrNull()
        ?: return GistParse.Error("The Gist has no $GIST_FILE file.")
    val content = runCatching { file["content"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (!content.startsWith("https://")) {
        return GistParse.Error("The Gist has no PC link yet. Is start.ps1 running with QUICK_TUNNEL=1?")
    }
    return when (val parsed = UrlUtils.parseTarget(content)) {
        is TargetParse.Ok -> (parsed.target as? PcTarget.Direct)?.let { GistParse.Ok(it.baseUrl) }
            ?: GistParse.Error("The Gist link is not a plain https URL.")
        is TargetParse.Invalid -> GistParse.Error("The Gist link is not a valid URL.")
    }
}

package com.mainnetwallet.polybot.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GistTest {
    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun body(content: String) = """{"id":"x","files":{"pc-url.txt":{"filename":"pc-url.txt","content":${quote(content)}}}}"""

    @Test fun readsTheTunnelUrl() {
        assertEquals(
            GistParse.Ok("https://quiet-river-1234.trycloudflare.com"),
            parseGistUrl(body("https://quiet-river-1234.trycloudflare.com\n")),
        )
    }

    @Test fun ignoresBlankLinesAndPaths() {
        assertEquals(
            GistParse.Ok("https://quiet-river-1234.trycloudflare.com"),
            parseGistUrl(body("\n  https://quiet-river-1234.trycloudflare.com/some/path  \nsecond line")),
        )
    }

    @Test fun ignoresOtherFilesAndExtraFields() {
        val json = """{"files":{"notes.md":{"content":"hi"},"pc-url.txt":{"content":"https://a.trycloudflare.com","size":27}},"owner":null}"""
        assertEquals(GistParse.Ok("https://a.trycloudflare.com"), parseGistUrl(json))
    }

    @Test fun missingFileIsAnError() {
        assertTrue(parseGistUrl("""{"files":{"other.txt":{"content":"https://a.trycloudflare.com"}}}""") is GistParse.Error)
    }

    @Test fun emptyOrNonHttpsContentIsAnError() {
        assertTrue(parseGistUrl(body("")) is GistParse.Error)
        assertTrue(parseGistUrl(body("http://192.168.1.2:8000")) is GistParse.Error)
        assertTrue(parseGistUrl(body("not a url")) is GistParse.Error)
    }

    @Test fun invalidJsonIsAnError() {
        assertTrue(parseGistUrl("<html>rate limited</html>") is GistParse.Error)
        assertTrue(parseGistUrl("[]") is GistParse.Error)
    }

    @Test fun nullContentIsAnError() {
        assertTrue(parseGistUrl("""{"files":{"pc-url.txt":{"content":null}}}""") is GistParse.Error)
    }
}

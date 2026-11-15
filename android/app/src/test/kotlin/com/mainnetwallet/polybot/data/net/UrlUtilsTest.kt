package com.mainnetwallet.polybot.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    private val id = "0123456789abcdef0123456789abcdef"

    private fun direct(input: String): String {
        val parsed = UrlUtils.parseTarget(input)
        assertTrue("expected Ok for '$input' but was $parsed", parsed is TargetParse.Ok)
        return ((parsed as TargetParse.Ok).target as PcTarget.Direct).baseUrl
    }

    private fun gist(input: String): String {
        val parsed = UrlUtils.parseTarget(input)
        assertTrue("expected Ok for '$input' but was $parsed", parsed is TargetParse.Ok)
        return ((parsed as TargetParse.Ok).target as PcTarget.Gist).id
    }

    @Test fun httpsUrlKeepsOnlySchemeHostAndPort() {
        assertEquals("https://abc-def.trycloudflare.com", direct("https://abc-def.trycloudflare.com/"))
        assertEquals("https://abc-def.trycloudflare.com", direct("  https://abc-def.trycloudflare.com/api/state?x=1  "))
        assertEquals("https://pc.example.com:8443", direct("https://pc.example.com:8443/"))
    }

    @Test fun plainHostGetsHttpsWhenPublic() {
        assertEquals("https://pc.example.com", direct("pc.example.com"))
        assertEquals("https://pc.example.com:8443", direct("pc.example.com:8443"))
    }

    @Test fun plainHostGetsHttpOnPrivateNetworks() {
        assertEquals("http://192.168.1.20:8000", direct("192.168.1.20:8000"))
        assertEquals("http://10.0.0.5:8000", direct("10.0.0.5:8000"))
        assertEquals("http://localhost:8000", direct("localhost:8000"))
        assertEquals("http://my-pc:8000", direct("my-pc:8000"))
    }

    @Test fun explicitHttpIsKept() {
        assertEquals("http://192.168.1.20:8000", direct("http://192.168.1.20:8000/"))
    }

    @Test fun gistPrefixIsParsed() {
        assertEquals(id, gist("gist:$id"))
        assertEquals(id, gist("  GIST: ${id.uppercase()}  "))
    }

    @Test fun gistUrlsAreParsed() {
        assertEquals(id, gist("https://gist.github.com/$id"))
        assertEquals(id, gist("https://gist.github.com/someuser/$id"))
        assertEquals(id, gist("https://gist.github.com/someuser/$id/raw/pc-url.txt"))
        assertEquals(id, gist("http://gist.github.com/someuser/$id#file-pc-url-txt"))
    }

    @Test fun badInputIsRejected() {
        assertTrue(UrlUtils.parseTarget("") is TargetParse.Invalid)
        assertTrue(UrlUtils.parseTarget("   ") is TargetParse.Invalid)
        assertTrue(UrlUtils.parseTarget("gist:123") is TargetParse.Invalid)
        assertTrue(UrlUtils.parseTarget("ftp://example.com") is TargetParse.Invalid)
        assertTrue(UrlUtils.parseTarget("https://") is TargetParse.Invalid)
    }

    @Test fun privateHostDetection() {
        listOf("192.168.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.1", "127.0.0.1", "169.254.1.1", "100.64.0.1", "localhost", "pc.local", "my-pc")
            .forEach { assertTrue("$it should be private", UrlUtils.isPrivateHost(it)) }
        listOf("172.32.0.1", "8.8.8.8", "100.128.0.1", "example.com", "abc.trycloudflare.com")
            .forEach { assertFalse("$it should be public", UrlUtils.isPrivateHost(it)) }
    }

    @Test fun insecureWarningOnlyForPlainHttpOutsidePrivateRanges() {
        assertTrue(UrlUtils.isInsecureOutsidePrivate("http://pc.example.com"))
        assertTrue(UrlUtils.isInsecureOutsidePrivate("http://8.8.8.8:8000"))
        assertFalse(UrlUtils.isInsecureOutsidePrivate("http://192.168.1.20:8000"))
        assertFalse(UrlUtils.isInsecureOutsidePrivate("https://pc.example.com"))
    }

    @Test fun wsUrlFollowsTheScheme() {
        assertEquals("wss://abc.trycloudflare.com/api/ws?token=tok", UrlUtils.wsUrl("https://abc.trycloudflare.com", "tok"))
        assertEquals("ws://192.168.1.20:8000/api/ws?token=tok", UrlUtils.wsUrl("http://192.168.1.20:8000", "tok"))
    }

    @Test fun wsUrlEncodesTheToken() {
        assertEquals("wss://pc.example.com/api/ws?token=a%26b%3Dc+d", UrlUtils.wsUrl("https://pc.example.com", "a&b=c d"))
    }

    @Test fun wsUrlWithoutTokenHasNoQuery() {
        assertEquals("wss://pc.example.com/api/ws", UrlUtils.wsUrl("https://pc.example.com", ""))
    }

    @Test fun localNetworkTargetsAreDetected() {
        assertTrue(UrlUtils.isLocalNetworkTarget("192.168.1.20:8000"))
        assertTrue(UrlUtils.isLocalNetworkTarget("http://10.0.0.5:8000"))
        assertTrue(UrlUtils.isLocalNetworkTarget("my-pc.local"))
        assertFalse(UrlUtils.isLocalNetworkTarget("https://abc-def.trycloudflare.com"))
        assertFalse(UrlUtils.isLocalNetworkTarget("gist:$id"))
        assertFalse(UrlUtils.isLocalNetworkTarget("https://gist.github.com/user/$id"))
        assertFalse(UrlUtils.isLocalNetworkTarget(""))
    }
}

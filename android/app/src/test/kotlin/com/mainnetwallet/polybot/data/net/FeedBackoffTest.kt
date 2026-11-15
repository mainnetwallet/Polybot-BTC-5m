package com.mainnetwallet.polybot.data.net

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedBackoffTest {
    @Test fun reconnectBackoffGrowsFromHalfASecondToTenSeconds() {
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L), (0..6).map { LiveFeed.backoffMs(it) })
    }
}

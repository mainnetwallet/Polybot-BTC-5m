package com.mainnetwallet.polybot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsTest {
    @Test fun statusTones() {
        assertEquals(Tone.Up, statusTone("FILLED"))
        assertEquals(Tone.Warn, statusTone("PARTIALLY_FILLED"))
        assertEquals(Tone.Down, statusTone("REJECTED"))
        assertEquals(Tone.Down, statusTone("blocked_by_risk"))
        assertEquals(Tone.Warn, statusTone("skipped_duplicate"))
        assertEquals(Tone.Neutral, statusTone("whatever"))
    }

    @Test fun orderKinds() {
        assertEquals("BUY", orderKind("BUY", null))
        assertEquals("STOP LOSS", orderKind("SELL", "stage1_stop_loss"))
        assertEquals("MANUAL SELL", orderKind("SELL", "stage2_manual"))
        assertEquals("TAKE PROFIT", orderKind("SELL", "stage1_take_profit"))
        assertEquals("SELL", orderKind("SELL", "other"))
        assertNull(orderKind(null, null))
    }

    @Test fun resultLabelHidesTheResultOfFilledBuys() {
        assertEquals("BUY", resultLabel("BUY", 5.0, "OPEN"))
        assertEquals("WIN", resultLabel("SELL", 5.0, "WIN"))
        assertNull(resultLabel("SELL", 5.0, ""))
        assertEquals("OPEN", resultLabel("BUY", 0.0, "OPEN"))
    }

    @Test fun levelFilterUnderstandsTheBackendLevelNames() {
        assertTrue(LevelFilter.Warn.matches("WARNING"))
        assertTrue(LevelFilter.Warn.matches("warn"))
        assertTrue(LevelFilter.Error.matches("CRITICAL"))
        assertTrue(LevelFilter.Error.matches("ERROR"))
        assertFalse(LevelFilter.Info.matches("WARNING"))
        assertTrue(LevelFilter.All.matches("anything"))
    }
}

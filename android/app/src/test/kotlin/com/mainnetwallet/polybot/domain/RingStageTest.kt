package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.StrategyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingStageTest {
    private val s = StrategyState(
        stage0Enabled = true, stage0StartSeconds = 160.0, stage0EndSeconds = 100.0,
        stage1Enabled = true, stage1Seconds = 90.0,
        stage2Enabled = true, stage2Seconds = 50.0,
        stage3Enabled = true, stage3Seconds = 30.0,
    )

    @Test fun waitingOutsideEveryWindow() {
        assertNull(ringStage(false, 250.0, s))
        assertNull(ringStage(false, 95.0, s))
    }

    @Test fun stage0BetweenItsEndAndStart() {
        assertEquals(0, ringStage(false, 160.0, s))
        assertEquals(0, ringStage(false, 120.0, s))
        assertNull(ringStage(false, 100.0, s))
    }

    @Test fun stage1WhileInWindow() {
        assertEquals(1, ringStage(true, 85.0, s))
        assertEquals(1, ringStage(true, 51.0, s))
    }

    @Test fun inWindowIsTrueAllRoundSoItDoesNotMeanStage1() {
        // the server reports in_window for the whole live round: outside every stage window it is still "waiting"
        assertNull(ringStage(true, 250.0, s))
        assertNull(ringStage(true, 95.0, s))
        assertEquals(0, ringStage(true, 148.0, s))
        assertEquals(0, ringStage(true, 120.0, s))
    }

    @Test fun stage0KeepsItsColourWhenItOverlapsStage1() {
        val overlap = s.copy(stage1Seconds = 150.0)
        assertEquals(0, ringStage(true, 148.0, overlap))
        assertEquals(1, ringStage(true, 100.0, overlap))
    }

    @Test fun stage2And3TakeOverLater() {
        assertEquals(2, ringStage(true, 50.0, s))
        assertEquals(2, ringStage(true, 31.0, s))
        assertEquals(3, ringStage(true, 30.0, s))
        assertEquals(3, ringStage(true, 2.0, s))
    }

    @Test fun switchedOffStagesAreSkipped() {
        val noStage3 = s.copy(stage3Enabled = false)
        assertEquals(2, ringStage(true, 10.0, noStage3))
        val onlyStage1 = s.copy(stage2Enabled = false, stage3Enabled = false)
        assertEquals(1, ringStage(true, 10.0, onlyStage1))
        assertNull(ringStage(false, 120.0, s.copy(stage0Enabled = false)))
    }

    @Test fun noClockFallsBackToInWindow() {
        assertEquals(1, ringStage(true, null, s))
        assertNull(ringStage(false, null, s))
    }
}

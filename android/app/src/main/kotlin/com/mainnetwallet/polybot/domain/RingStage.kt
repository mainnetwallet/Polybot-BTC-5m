package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.StrategyState

/** Seconds left at which `stage` starts buying (stage 0: stage0_start_seconds, 1 to 3: stageN_seconds). */
fun stageStartSeconds(stage: Int, s: StrategyState): Double = when (stage) {
    0 -> s.stage0StartSeconds
    1 -> s.stage1Seconds
    2 -> s.stage2Seconds
    else -> s.stage3Seconds
}

/**
 * Seconds left at which `stage` stops buying. Mirrors the backend (RuntimeSettings.stage_end_seconds): stage 0
 * has its own end, stages 1 and 2 end where the next switched-on stage starts, stage 3 runs to the end of the
 * market (0.0).
 */
fun stageEndSeconds(stage: Int, s: StrategyState): Double {
    if (stage == 0) return s.stage0EndSeconds
    for (next in stage + 1..3) {
        if (stageEnabled(next, s)) return stageStartSeconds(next, s)
    }
    return 0.0
}

fun stageEnabled(stage: Int, s: StrategyState): Boolean = when (stage) {
    0 -> s.stage0Enabled
    1 -> s.stage1Enabled
    2 -> s.stage2Enabled
    else -> s.stage3Enabled
}

/** True while `stage` is switched on and may buy: more than its end and at most its start seconds left. */
fun stageOpen(stage: Int, remainingSeconds: Double?, s: StrategyState): Boolean {
    val left = remainingSeconds ?: return false
    return stageEnabled(stage, s) && left > stageEndSeconds(stage, s) && left <= stageStartSeconds(stage, s)
}

/**
 * Which entry stage the market is in right now, for the hero ring colour and the stage pill: 0 to 3, or null
 * while waiting. It is decided by the stage windows (seconds left on the market clock), the same ones the
 * backend trades on (see [stageStartSeconds] and [stageEndSeconds]). Later stages take over from earlier ones
 * (3 over 2 over 1). Stage 0 is checked before stage 1, so when the two windows overlap the early buy window
 * keeps its own colour.
 *
 * `inWindow` is NOT a stage 1 flag: the server sets it for the whole live round. It is only the fallback
 * for a missing clock.
 */
fun ringStage(inWindow: Boolean, remainingSeconds: Double?, s: StrategyState): Int? {
    if (remainingSeconds == null) return if (inWindow) 1 else null
    return listOf(3, 2, 0, 1).firstOrNull { stageOpen(it, remainingSeconds, s) }
}

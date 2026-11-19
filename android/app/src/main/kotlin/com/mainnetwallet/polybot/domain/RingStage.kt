package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.StrategyState

/**
 * Which entry stage the market is in right now, for the hero ring colour and the stage pill: 0 to 3, or null
 * while waiting. It is decided by the stage windows (seconds left on the market clock), the same ones the
 * backend trades on:
 *  - stage 3: the last `stage3_seconds`, stage 2: the last `stage2_seconds` (only when switched on),
 *  - stage 0: more than `stage0_end_seconds` and at most `stage0_start_seconds` left,
 *  - stage 1: at most `stage1_seconds` left.
 * Later stages take over from earlier ones (3 over 2 over 1). Stage 0 is checked before stage 1, so when
 * the two windows overlap the early buy window keeps its own colour.
 *
 * `inWindow` is NOT a stage 1 flag: the server sets it for the whole live round. It is only the fallback
 * for a missing clock.
 */
fun ringStage(inWindow: Boolean, remainingSeconds: Double?, s: StrategyState): Int? {
    val left = remainingSeconds ?: return if (inWindow) 1 else null
    if (s.stage3Enabled && left <= s.stage3Seconds) return 3
    if (s.stage2Enabled && left <= s.stage2Seconds) return 2
    if (s.stage0Enabled && left > s.stage0EndSeconds && left <= s.stage0StartSeconds) return 0
    if (s.stage1Enabled && left <= s.stage1Seconds) return 1
    return null
}

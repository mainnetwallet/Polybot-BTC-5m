package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.data.model.StrategyState

/**
 * Which entry stage the market is in right now, for the hero ring colour: 0 to 3, or null while waiting.
 * Mirrors the backend windows: stage 0 sits between its end and start seconds, stage 1 is the server's
 * `in_window`, stage 2 and 3 are the last `stage2_seconds` / `stage3_seconds` and only count when switched on.
 * The latest stage whose window is open wins, so stage 3 takes over from stage 2, and stage 2 from stage 1.
 */
fun ringStage(inWindow: Boolean, remainingSeconds: Double?, s: StrategyState): Int? {
    val left = remainingSeconds
    if (left != null) {
        if (s.stage3Enabled && left <= s.stage3Seconds) return 3
        if (s.stage2Enabled && left <= s.stage2Seconds) return 2
    }
    if (inWindow) return 1
    if (left != null && s.stage0Enabled && left > s.stage0EndSeconds && left <= s.stage0StartSeconds) return 0
    return null
}

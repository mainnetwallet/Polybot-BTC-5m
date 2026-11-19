package com.mainnetwallet.polybot.domain

import com.mainnetwallet.polybot.Fixtures
import com.mainnetwallet.polybot.core.AppJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    private val saved: Map<String, JsonElement> = AppJson.parseToJsonElement(Fixtures.text("settings.json")).jsonObject.toMap()
    private fun fresh() = SettingsLogic.draftFrom(saved)

    @Test fun everyEditableServerSettingHasAField() {
        val spec = SettingsSpec.fields.map { it.key }.toSet()
        val notEditable = setOf("geo_verified", "live_trading_enabled", "paper_fee_exponent", "paper_fill_ratio", "paper_starting_balance")
        assertEquals(emptySet<String>(), saved.keys - spec - notEditable)
        assertEquals(emptySet<String>(), spec - saved.keys)
    }

    @Test fun everyFieldIsPlacedInExactlyOneGroup() {
        val placed = SettingsSpec.sections.flatMap { s -> s.groups.flatMap { it.keys } }
        assertEquals(placed.size, placed.toSet().size)
        assertEquals(SettingsSpec.fields.map { it.key }.toSet(), placed.toSet())
    }

    @Test fun stageSectionsKnowTheirOnOffSwitch() {
        val switches = SettingsSpec.sections.associate { it.id to SettingsSpec.enabledKey(it) }
        assertEquals("stage0_enabled", switches["stage0"])
        assertEquals("stage1_enabled", switches["stage1"])
        assertEquals("stage2_enabled", switches["stage2"])
        assertEquals("stage3_enabled", switches["stage3"])
        assertNull(switches["risk"])
        assertNull(switches["paper"])
    }

    @Test fun editedFieldsAreCountedPerSection() {
        val stage0 = SettingsSpec.sections.first { it.id == "stage0" }
        val risk = SettingsSpec.sections.first { it.id == "risk" }
        val edited = listOf("stage0_enabled", "stage0_min_ask", "trade_amount_usdc")
        assertEquals(2, SettingsSpec.dirtyIn(stage0, edited))
        assertEquals(1, SettingsSpec.dirtyIn(risk, edited))
        assertEquals(0, SettingsSpec.dirtyIn(stage0, emptyList()))
        assertEquals("risk", SettingsSpec.sectionOfKey("trade_amount_usdc")?.id)
        assertNull(SettingsSpec.sectionOfKey("not_a_setting"))
    }

    @Test fun untouchedDraftHasNoChanges() {
        val draft = fresh()
        assertTrue(SettingsLogic.dirtyKeys(draft, saved).isEmpty())
        assertTrue(SettingsLogic.validate(draft, saved).isEmpty())
        assertTrue(SettingsLogic.diff(draft, saved).isEmpty())
    }

    @Test fun wholeNumbersShowWithoutADecimalPoint() {
        val draft = fresh()
        assertEquals("90", draft["stage1_seconds"])
        assertEquals("0.85", draft["stage1_threshold"])
        assertEquals("true", draft["stage1_enabled"])
        assertEquals("one_per_market", draft["duplicate_policy"])
    }

    @Test fun onlyChangedKeysAreSentAndTyped() {
        val draft = fresh() + mapOf("stage1_threshold" to "0.9", "stage1_seconds" to "75", "stage1_enabled" to "false", "conflict_policy" to "down_first")
        val diff = SettingsLogic.diff(draft, saved)
        assertEquals(setOf("stage1_threshold", "stage1_seconds", "stage1_enabled", "conflict_policy"), diff.keys)
        assertEquals(JsonPrimitive(0.9), diff["stage1_threshold"])
        assertEquals(JsonPrimitive(75L), diff["stage1_seconds"]) // an integer field is sent as an integer
        assertEquals(JsonPrimitive(false), diff["stage1_enabled"])
        assertEquals(JsonPrimitive("down_first"), diff["conflict_policy"])
    }

    @Test fun sameNumberWithDifferentFormattingIsNotAChange() {
        val draft = fresh() + mapOf("stage1_threshold" to "0.850", "stage1_seconds" to "90.0")
        assertTrue(SettingsLogic.dirtyKeys(draft, saved).isEmpty())
    }

    @Test fun rangesMirrorTheServerSchema() {
        fun error(key: String, value: String) = SettingsLogic.validate(fresh() + (key to value), saved)[key]
        assertEquals("Must be at least 0.01.", error("stage1_threshold", "0"))
        assertEquals("Must be at most 0.999.", error("stage1_threshold", "1"))
        assertNull(error("stage1_threshold", "0.95"))
        assertEquals("Must be at most 300.", error("stage1_seconds", "301"))
        assertEquals("Must be at least 1.", error("stage1_seconds", "0"))
        assertEquals("Whole numbers only.", error("stage1_seconds", "45.5"))
        assertEquals("Must be at most 99.", error("stage1_stop_loss_pct", "100"))
        assertNull(error("stage1_stop_loss_pct", "0"))
        assertEquals("Must be at least 100.", error("stale_data_max_age_ms", "99"))
        assertEquals("Must be at most 1.", error("max_execution_price", "1.1"))
        assertNull(error("max_execution_price", "1"))
        assertEquals("Must be at most 5000.", error("paper_slippage_bps", "5001"))
        assertEquals("Must be at least 0.", error("max_daily_loss", "-1"))
        assertNull(error("max_daily_loss", "1000000"))
    }

    @Test fun nonNumbersAreRejected() {
        val errors = SettingsLogic.validate(fresh() + ("trade_amount_usdc" to "abc"), saved)
        assertEquals("Enter a valid number.", errors["trade_amount_usdc"])
        assertEquals("Enter a valid number.", SettingsLogic.validate(fresh() + ("trade_amount_usdc" to ""), saved)["trade_amount_usdc"])
        assertEquals("Enter a valid number.", SettingsLogic.validate(fresh() + ("trade_amount_usdc" to "NaN"), saved)["trade_amount_usdc"])
    }

    @Test fun crossFieldRulesMirrorTheWebDashboard() {
        fun errors(vararg edits: Pair<String, String>) = SettingsLogic.validate(fresh() + edits.toMap(), saved)
        assertEquals("Stage 0 end time must be lower than its start time.", errors("stage0_end_seconds" to "250")["stage0_end_seconds"])
        assertEquals("Min ask must not be higher than the stage 0 max ask.", errors("stage0_min_ask" to "0.97")["stage0_min_ask"])
        assertEquals("Mid tier border must not be higher than the high tier border.", errors("stage0_mid_from_ask" to "0.9")["stage0_mid_from_ask"])
        assertEquals("High sell price must be higher than the stage 1 cheap-buy limit.", errors("stage1_sell_price_high" to "0.5")["stage1_sell_price_high"])
        assertEquals("Sell price must be higher than the stage 2 ask trigger.", errors("stage2_sell_price" to "0.5")["stage2_sell_price"])
        assertEquals("Must be at least the stage 1, 2 and 3 ask triggers.", errors("max_execution_price" to "0.5")["max_execution_price"])
    }

    @Test fun aStoredValueOutsideItsRangeNeverBlocksAnUnrelatedSave() {
        val odd = saved + ("stale_data_max_age_ms" to JsonPrimitive(50))
        val draft = SettingsLogic.draftFrom(odd) + ("stage1_threshold" to "0.9")
        assertTrue(SettingsLogic.validate(draft, odd).isEmpty())
    }

    @Test fun invalidChoiceIsRejected() {
        assertEquals("Pick one of the options.", SettingsLogic.validate(fresh() + ("duplicate_policy" to "nope"), saved)["duplicate_policy"])
    }

    @Test fun settingTextFormatsValues() {
        assertEquals("70", settingText(JsonPrimitive(70.0)))
        assertEquals("0.9", settingText(JsonPrimitive(0.9)))
        assertEquals("true", settingText(JsonPrimitive(true)))
        assertEquals("up_first", settingText(JsonPrimitive("up_first")))
        assertEquals("", settingText(null))
    }

    @Test fun everyStageOwnsItsTimingEntryAndExitSettings() {
        fun keys(id: String) = SettingsSpec.keysOf(SettingsSpec.sections.first { it.id == id }).toSet()
        // The stage 1 trigger, window and block used to sit on a separate "Entry" page.
        assertTrue(keys("stage1").containsAll(listOf("stage1_enabled", "stage1_seconds", "stage1_threshold", "stage1_max_ask", "stage1_block_ask", "min_price_gap_usd")))
        assertTrue(keys("stage2").containsAll(listOf("stage2_seconds", "stage2_threshold", "stage2_max_ask", "stage2_max_spread", "stage2_block_ask", "stage2_sell_price", "stage2_stop_loss_pct")))
        assertTrue(keys("stage3").containsAll(listOf("stage3_seconds", "stage3_threshold", "stage3_max_ask", "stage3_max_spread", "stage3_min_stop_headroom", "stage3_block_ask", "stage3_stop_loss_pct")))
        assertTrue(keys("stage3").containsAll(listOf("stage3_mid_seconds", "stage3_mid_stop_loss_pct", "stage3_last_seconds", "stage3_last_seconds_stop_loss_pct")))
        assertNull(SettingsSpec.sections.firstOrNull { it.id == "entry" })
        for (stage in 0..3) {
            val own = keys("stage$stage")
            // a stage page never holds another stage's settings
            val foreign = (0..3).filter { it != stage }.flatMap { o -> own.filter { it.startsWith("stage${o}_") } }
            assertEquals(emptyList<String>(), foreign)
        }
        assertEquals("risk", SettingsSpec.sectionOfKey("duplicate_policy")?.id)
        assertEquals("risk", SettingsSpec.sectionOfKey("conflict_policy")?.id)
    }

    @Test fun maxAskMustNotBeBelowItsTrigger() {
        fun errors(vararg edits: Pair<String, String>) = SettingsLogic.validate(fresh() + edits.toMap(), saved)
        assertEquals("The stage 1 max ask must not be lower than the stage 1 ask trigger, or stage 1 could never buy.", errors("stage1_max_ask" to "0.5")["stage1_max_ask"])
        assertEquals("The stage 2 max ask must not be lower than the stage 2 ask trigger, or stage 2 could never buy.", errors("stage2_max_ask" to "0.5")["stage2_max_ask"])
        assertEquals("The stage 3 max ask must not be lower than the stage 3 ask trigger, or stage 3 could never buy.", errors("stage3_max_ask" to "0.5")["stage3_max_ask"])
        assertNull(errors("stage3_max_ask" to "0.98")["stage3_max_ask"])
    }

    @Test fun newStageSettingsMirrorTheServerRanges() {
        fun error(key: String, value: String) = SettingsLogic.validate(fresh() + (key to value), saved)[key]
        assertEquals("Must be at most 1.", error("stage2_max_spread", "1.5"))
        assertNull(error("stage2_max_spread", "0"))
        assertEquals("Must be at most 1.", error("stage3_min_stop_headroom", "2"))
        assertEquals("Must be at most 99.", error("stage3_mid_stop_loss_pct", "100"))
        assertEquals("Must be at most 300.", error("stage3_last_seconds", "301"))
        assertEquals("Whole numbers only.", error("stage3_mid_seconds", "12.5"))
        assertNull(error("stage3_mid_seconds", "0"))
        val diff = SettingsLogic.diff(fresh() + mapOf("stage3_mid_seconds" to "25", "stage3_max_spread" to "0.04"), saved)
        assertEquals(JsonPrimitive(25L), diff["stage3_mid_seconds"]) // integer field goes out as an integer
        assertEquals(JsonPrimitive(0.04), diff["stage3_max_spread"])
    }

    @Test fun displayNameCarriesTheSection() {
        assertEquals("Stage 2: Ask trigger", SettingsSpec.displayName("stage2_threshold"))
        assertEquals("Risk and sizing: Order notional", SettingsSpec.displayName("trade_amount_usdc"))
        assertEquals("nope", SettingsSpec.displayName("nope"))
    }

    @Test fun stageWindowsFollowTheNextEnabledStage() {
        // stage 1 ends where stage 2 starts (50s), stage 2 where stage 3 starts (30s), stage 3 runs to expiry
        assertEquals("90s → 50s left", StageFacts.window(1, fresh()))
        assertEquals("50s → 30s left", StageFacts.window(2, fresh()))
        assertEquals("30s → expiry", StageFacts.window(3, fresh()))
        assertEquals("200s → 100s left", StageFacts.window(0, fresh()))
        // with stage 2 switched off, stage 1 runs to stage 3's start
        assertEquals("90s → 30s left", StageFacts.window(1, fresh() + ("stage2_enabled" to "false")))
        // with every later stage off, stage 1 runs to expiry
        assertEquals("90s → expiry", StageFacts.window(1, fresh() + mapOf("stage2_enabled" to "false", "stage3_enabled" to "false")))
    }

    @Test fun stageFactsReadTheDraft() {
        val stage1 = StageFacts.facts(1, fresh()).associate { it.label to it.value }
        assertEquals("≥ 0.85", stage1["Ask"])
        assertEquals("+8%", stage1["Take-profit"])
        assertEquals("30%", stage1["Stop-loss"])
        assertEquals("Off", StageFacts.facts(1, fresh() + ("stage1_sell_enabled" to "false")).first { it.label == "Take-profit" }.value)
        assertEquals("Off", StageFacts.facts(3, fresh() + ("stage3_stop_loss_pct" to "0")).first { it.label == "Stop-loss" }.value)
        assertEquals("None", StageFacts.facts(3, fresh()).first { it.label == "Take-profit" }.value)
        assertEquals("Bid 0.99", StageFacts.facts(2, fresh()).first { it.label == "Take-profit" }.value)
        assertEquals(0, StageFacts.stageOf(SettingsSpec.sections.first { it.id == "stage0" }))
        assertNull(StageFacts.stageOf(SettingsSpec.sections.first { it.id == "risk" }))
    }

    @Test fun longStagePagesAreSplitIntoTabsThatCoverEveryGroupOnce() {
        for (section in SettingsSpec.sections) {
            val pages = SettingsSpec.pagesOf(section)
            assertEquals(section.groups.map { it.title }, pages.flatMap { p -> p.groups.map { it.title } })
        }
        fun labels(id: String) = SettingsSpec.pagesOf(SettingsSpec.sections.first { it.id == id }).map { it.label }
        assertEquals(listOf("Timing", "Entry", "Exits"), labels("stage0"))
        assertEquals(listOf("Entry", "Exits"), labels("stage1"))
        assertEquals(listOf("Entry", "Exits"), labels("stage3"))
        assertEquals(1, labels("risk").size)
        val stage3Exit = SettingsSpec.pagesOf(SettingsSpec.sections.first { it.id == "stage3" }).last()
        assertTrue("stage3_stop_loss_pct" in SettingsSpec.keysOf(stage3Exit))
    }
}

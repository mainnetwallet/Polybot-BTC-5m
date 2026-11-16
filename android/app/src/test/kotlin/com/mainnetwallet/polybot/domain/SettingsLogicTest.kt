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
        assertNull(switches["entry"])
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
        assertEquals("0.85", draft["trigger_threshold"])
        assertEquals("true", draft["stage1_enabled"])
        assertEquals("one_per_market", draft["duplicate_policy"])
    }

    @Test fun onlyChangedKeysAreSentAndTyped() {
        val draft = fresh() + mapOf("trigger_threshold" to "0.9", "stage1_seconds" to "75", "stage1_enabled" to "false", "conflict_policy" to "down_first")
        val diff = SettingsLogic.diff(draft, saved)
        assertEquals(setOf("trigger_threshold", "stage1_seconds", "stage1_enabled", "conflict_policy"), diff.keys)
        assertEquals(JsonPrimitive(0.9), diff["trigger_threshold"])
        assertEquals(JsonPrimitive(75L), diff["stage1_seconds"]) // an integer field is sent as an integer
        assertEquals(JsonPrimitive(false), diff["stage1_enabled"])
        assertEquals(JsonPrimitive("down_first"), diff["conflict_policy"])
    }

    @Test fun sameNumberWithDifferentFormattingIsNotAChange() {
        val draft = fresh() + mapOf("trigger_threshold" to "0.850", "stage1_seconds" to "90.0")
        assertTrue(SettingsLogic.dirtyKeys(draft, saved).isEmpty())
    }

    @Test fun rangesMirrorTheServerSchema() {
        fun error(key: String, value: String) = SettingsLogic.validate(fresh() + (key to value), saved)[key]
        assertEquals("Must be at least 0.01.", error("trigger_threshold", "0"))
        assertEquals("Must be at most 0.999.", error("trigger_threshold", "1"))
        assertNull(error("trigger_threshold", "0.95"))
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
        assertEquals("Must be at least the trigger and the stage 2/3 ask triggers.", errors("max_execution_price" to "0.5")["max_execution_price"])
    }

    @Test fun aStoredValueOutsideItsRangeNeverBlocksAnUnrelatedSave() {
        val odd = saved + ("stale_data_max_age_ms" to JsonPrimitive(50))
        val draft = SettingsLogic.draftFrom(odd) + ("trigger_threshold" to "0.9")
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
}

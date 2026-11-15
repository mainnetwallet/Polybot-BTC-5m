package com.mainnetwallet.polybot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mainnetwallet.polybot.AppGraph
import com.mainnetwallet.polybot.data.ActionResult
import com.mainnetwallet.polybot.data.net.ApiException
import com.mainnetwallet.polybot.domain.SettingsLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** Bot settings form: server values, the person's draft, validation and the PATCH of only the changed keys. */
class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val saved: Map<String, JsonElement> = emptyMap(),
        val draft: Map<String, String> = emptyMap(),
        val errors: Map<String, String> = emptyMap(),
        val dirty: List<String> = emptyList(),
        val saving: Boolean = false,
        val loadError: String? = null,
    ) {
        val canSave: Boolean get() = !saving && dirty.isNotEmpty() && errors.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        try {
            val saved = graph.api.settings()
            _state.value = recompute(State(loading = false, saved = saved, draft = SettingsLogic.draftFrom(saved)))
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, loadError = e.message) }
        }
    }

    private fun recompute(s: State): State = s.copy(
        errors = SettingsLogic.validate(s.draft, s.saved),
        dirty = SettingsLogic.dirtyKeys(s.draft, s.saved),
    )

    fun edit(key: String, value: String) {
        _state.update { recompute(it.copy(draft = it.draft + (key to value))) }
    }

    fun discard() {
        _state.update { recompute(it.copy(draft = SettingsLogic.draftFrom(it.saved))) }
    }

    suspend fun save(): ActionResult {
        val s = _state.value
        if (!s.canSave) return ActionResult(false, "Fix the highlighted fields first.")
        val changes = SettingsLogic.diff(s.draft, s.saved)
        _state.update { it.copy(saving = true) }
        return try {
            val next = graph.api.patchSettings(changes)
            _state.value = recompute(State(loading = false, saved = next, draft = SettingsLogic.draftFrom(next)))
            graph.repository.refresh()
            ActionResult(true, "Saved ${changes.size} setting${if (changes.size == 1) "" else "s"}.")
        } catch (e: ApiException) {
            _state.update { it.copy(saving = false) }
            ActionResult(false, e.message ?: "Could not save the settings")
        }
    }

    suspend fun resetToDefaults(): ActionResult = try {
        val next = graph.api.resetSettings()
        _state.value = recompute(State(loading = false, saved = next, draft = SettingsLogic.draftFrom(next)))
        graph.repository.refresh()
        ActionResult(true, "Settings reset to defaults.")
    } catch (e: ApiException) {
        ActionResult(false, e.message ?: "Could not reset the settings")
    }
}

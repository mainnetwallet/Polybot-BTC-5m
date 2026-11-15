package com.mainnetwallet.polybot.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Everything the app remembers between launches. [token] is decrypted on read. */
data class AppPrefs(
    val pcUrl: String = "",
    val token: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val haptics: Boolean = true,
    val confirmSells: Boolean = true,
    /** The `gist:<id>` address [lastTunnelUrl] was read for; it is ignored when the saved address differs. */
    val lastTunnelFor: String = "",
    /** The tunnel URL the Gist gave last time, tried first on the next launch. */
    val lastTunnelUrl: String = "",
) {
    val isConfigured: Boolean get() = pcUrl.isNotBlank() && token.isNotBlank()
}

private val Context.polyDataStore: DataStore<Preferences> by preferencesDataStore(name = "polybot_prefs")

class PrefsStore(private val context: Context) {
    private object Keys {
        val pcUrl = stringPreferencesKey("pc_url")
        val tokenEnc = stringPreferencesKey("token_enc")
        val theme = stringPreferencesKey("theme")
        val haptics = booleanPreferencesKey("haptics")
        val confirmSells = booleanPreferencesKey("confirm_sells")
        val tunnelFor = stringPreferencesKey("tunnel_for")
        val tunnelUrl = stringPreferencesKey("tunnel_url")
    }

    val prefs: Flow<AppPrefs> = context.polyDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { p ->
            AppPrefs(
                pcUrl = p[Keys.pcUrl].orEmpty(),
                token = p[Keys.tokenEnc]?.let { SecureStore.decrypt(it) }.orEmpty(),
                themeMode = p[Keys.theme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                    ?: ThemeMode.SYSTEM,
                haptics = p[Keys.haptics] ?: true,
                confirmSells = p[Keys.confirmSells] ?: true,
                lastTunnelFor = p[Keys.tunnelFor].orEmpty(),
                lastTunnelUrl = p[Keys.tunnelUrl].orEmpty(),
            )
        }

    /** Remembers the login until [logout]: the URL in plain text, the token encrypted. */
    suspend fun saveConnection(pcUrl: String, token: String) {
        val encrypted = SecureStore.encrypt(token.trim())
        context.polyDataStore.edit {
            it[Keys.pcUrl] = pcUrl.trim()
            it[Keys.tokenEnc] = encrypted
        }
    }

    /** Remembers the tunnel URL read from the Gist for [pcUrl], so the next launch can skip the Gist. */
    suspend fun saveTunnel(pcUrl: String, tunnelUrl: String) {
        context.polyDataStore.edit {
            if (it[Keys.tunnelFor] != pcUrl) it[Keys.tunnelFor] = pcUrl
            if (it[Keys.tunnelUrl] != tunnelUrl) it[Keys.tunnelUrl] = tunnelUrl
        }
    }

    /** Log out: forgets the token, keeps the address so the next login only needs the token. */
    suspend fun logout() {
        context.polyDataStore.edit { it.remove(Keys.tokenEnc) }
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.polyDataStore.edit { it[Keys.theme] = mode.name }
    }

    suspend fun setHaptics(enabled: Boolean) {
        context.polyDataStore.edit { it[Keys.haptics] = enabled }
    }

    suspend fun setConfirmSells(enabled: Boolean) {
        context.polyDataStore.edit { it[Keys.confirmSells] = enabled }
    }
}

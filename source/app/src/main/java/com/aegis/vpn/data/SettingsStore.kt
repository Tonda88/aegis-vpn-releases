package com.aegis.vpn.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("aegis_vpn", Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Stable HWID for subscription panels with a device limit. The default is
     * the device id already registered for the bundled Chorus Connect trial;
     * it can be overridden in preferences if the subscription is replaced.
     */
    val subscriptionHwid: String = prefs.getString(KEY_HWID, null)
        ?: DEFAULT_HWID.also { prefs.edit().putString(KEY_HWID, it).apply() }

    private val _servers = MutableStateFlow<List<ServerProfile>>(emptyList())
    val servers: StateFlow<List<ServerProfile>> = _servers.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val serverType = object : TypeToken<List<ServerProfile>>() {}.type
        val loadedServers = runCatching {
            prefs.getString(KEY_SERVERS, null)?.let { json ->
                gson.fromJson<List<ServerProfile>>(json, serverType)
            }
        }.getOrNull().orEmpty()
        _servers.value = loadedServers

        val storedSelection = prefs.getString(KEY_SELECTED, null)
        _selectedId.value = storedSelection?.takeIf { id -> loadedServers.any { it.id == id } }
            ?: loadedServers.firstOrNull()?.id

        val loadedSettings = runCatching {
            prefs.getString(KEY_SETTINGS, null)?.let { json ->
                gson.fromJson(json, AppSettings::class.java)
            }
        }.getOrNull()
        if (loadedSettings != null) {
            _settings.value = loadedSettings.copy(
                selectedPackages = loadedSettings.selectedPackages.toSet()
            )
        }
    }

    fun addServer(profile: ServerProfile): AddServerResult {
        val current = _servers.value
        if (current.any { it.raw.trim() == profile.raw.trim() }) {
            return AddServerResult.Duplicate
        }
        val updated = current + profile
        _servers.value = updated
        if (_selectedId.value == null) {
            _selectedId.value = profile.id
            prefs.edit().putString(KEY_SELECTED, profile.id).apply()
        }
        persistServers(updated)
        return AddServerResult.Added
    }

    fun deleteServer(id: String) {
        val updated = _servers.value.filterNot { it.id == id }
        _servers.value = updated
        persistServers(updated)
        if (_selectedId.value == id) {
            val next = updated.firstOrNull()?.id
            _selectedId.value = next
            prefs.edit().putString(KEY_SELECTED, next).apply()
        }
    }

    fun deleteAllServers() {
        _servers.value = emptyList()
        _selectedId.value = null
        prefs.edit()
            .putString(KEY_SERVERS, gson.toJson(emptyList<ServerProfile>()))
            .remove(KEY_SELECTED)
            .apply()
    }

    fun selectServer(id: String) {
        if (_servers.value.none { it.id == id }) return
        _selectedId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun renameServer(id: String, name: String) {
        val clean = name.trim().take(64).ifBlank { return }
        val updated = _servers.value.map { if (it.id == id) it.copy(name = clean) else it }
        _servers.value = updated
        persistServers(updated)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit().putString(KEY_SETTINGS, gson.toJson(updated)).apply()
    }

    fun selectedServer(): ServerProfile? =
        _servers.value.firstOrNull { it.id == _selectedId.value }
            ?: _servers.value.firstOrNull()

    private fun persistServers(servers: List<ServerProfile>) {
        prefs.edit().putString(KEY_SERVERS, gson.toJson(servers)).apply()
    }

    enum class AddServerResult { Added, Duplicate }

    private companion object {
        const val KEY_SERVERS = "servers_v1"
        const val KEY_SELECTED = "selected_server_v1"
        const val KEY_SETTINGS = "settings_v1"
        const val KEY_HWID = "subscription_hwid_v1"
        const val DEFAULT_HWID = "fd24239a-7d44-4891-b59f-85d0c15bf2a6"
    }
}

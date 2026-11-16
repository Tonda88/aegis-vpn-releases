package com.aegis.vpn

import android.app.Application
import com.aegis.vpn.data.SettingsStore

object AppGraph {
    @Volatile
    private var settingsStore: SettingsStore? = null

    fun init(application: Application) {
        if (settingsStore == null) {
            synchronized(this) {
                if (settingsStore == null) {
                    settingsStore = SettingsStore(application)
                }
            }
        }
    }

    val store: SettingsStore
        get() = checkNotNull(settingsStore) { "AppGraph не инициализирован" }
}

class AegisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

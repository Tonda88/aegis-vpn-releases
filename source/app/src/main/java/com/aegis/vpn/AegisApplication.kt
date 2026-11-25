package com.aegis.vpn

import android.app.Application
import com.aegis.vpn.data.SettingsStore
import com.aegis.vpn.data.TrafficStore

object AppGraph {
    @Volatile
    private var settingsStore: SettingsStore? = null

    @Volatile
    private var trafficStore: TrafficStore? = null

    fun init(application: Application) {
        if (settingsStore == null) {
            synchronized(this) {
                if (settingsStore == null) {
                    settingsStore = SettingsStore(application)
                }
                if (trafficStore == null) {
                    trafficStore = TrafficStore(application)
                }
            }
        }
    }

    val store: SettingsStore
        get() = checkNotNull(settingsStore) { "AppGraph не инициализирован" }

    val traffic: TrafficStore
        get() = checkNotNull(trafficStore) { "AppGraph не инициализирован" }
}

class AegisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
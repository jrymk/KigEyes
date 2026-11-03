package com.jerrymk.kigeyes

import android.content.Context
import org.json.JSONObject
import java.io.File

/** App-private hotspot preferences, including the original provisioning-file fallback. */
internal class HotspotStore(context: Context) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val legacy = File(context.filesDir, "connection.json")

    fun read(): Pair<String, String> {
        if (preferences.contains("ssid")) {
            return preferences.getString("ssid", "KigEyes")!! to preferences.getString("password", "")!!
        }
        return runCatching {
            JSONObject(legacy.readText()).let { it.getString("ssid") to it.getString("password") }
        }.getOrDefault("KigEyes" to "")
    }

    var peer: String?
        get()=preferences.getString("blePeer",null)
        set(value) { preferences.edit().putString("blePeer",value).apply() }
    var autoConnect: Boolean
        get()=preferences.getBoolean("autoConnect",true)
        set(value) { preferences.edit().putBoolean("autoConnect",value).apply() }

    fun save(ssid: String, password: String) {
        preferences.edit().putString("ssid", ssid).putString("password", password).apply()
    }
}

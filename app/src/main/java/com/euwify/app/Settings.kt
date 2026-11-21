package com.euwify.app

import android.content.Context

/** Non-secret settings: chosen camera, home Wi-Fi names, on/off. */
class Settings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    var stationSerial: String?
        get() = prefs.getString("stationSerial", null)
        set(v) = prefs.edit().putString("stationSerial", v).apply()

    var stationName: String?
        get() = prefs.getString("stationName", null)
        set(v) = prefs.edit().putString("stationName", v).apply()

    var homeSsids: Set<String>
        get() = prefs.getStringSet("homeSsids", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("homeSsids", v.toSet()).apply()

    /** Automatic switching by Wi-Fi. */
    var autoEnabled: Boolean
        get() = prefs.getBoolean("autoEnabled", true)
        set(v) = prefs.edit().putBoolean("autoEnabled", v).apply()

    /** How long away from home Wi-Fi before switching to Away. */
    var awayDelaySeconds: Int
        get() = prefs.getInt("awayDelaySeconds", 75)
        set(v) = prefs.edit().putInt("awayDelaySeconds", v).apply()

    fun clearCamera() {
        prefs.edit().remove("stationSerial").remove("stationName").apply()
    }
}

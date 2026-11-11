package com.arpalaser.iot.data

import android.content.Context
import com.arpalaser.iot.bluetooth.Protocol
import org.json.JSONArray
import org.json.JSONObject

/** Conjuntos guardados en este dispositivo, compartidos entre las cuentas de la aplicación. */
data class CustomPreset(val name: String, val notes: List<Int>)

class CustomPresetRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("custom_presets", Context.MODE_PRIVATE)

    fun load(): List<CustomPreset> {
        val array = try { JSONArray(prefs.getString("presets", "[]")) } catch (_: Exception) { return emptyList() }
        return (0 until array.length()).mapNotNull { index ->
            try {
                val item = array.getJSONObject(index)
                val name = item.getString("name").trim()
                val values = item.getJSONArray("notes")
                val notes = (0 until values.length()).map { values.getInt(it) }
                if (validPreset(name, notes)) CustomPreset(name, notes) else null
            } catch (_: Exception) { null }
        }
    }

    fun save(presets: List<CustomPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            require(validPreset(preset.name, preset.notes))
            val values = JSONArray()
            preset.notes.forEach { values.put(it) }
            array.put(JSONObject().put("name", preset.name).put("notes", values))
        }
        prefs.edit().putString("presets", array.toString()).apply()
    }

    companion object {
        fun validPreset(name: String, notes: List<Int>): Boolean =
            name.trim().length in 1..40 && notes.size == Protocol.STRING_COUNT &&
                notes.all { it in Protocol.MIN_FREQ_HZ..Protocol.MAX_FREQ_HZ }
    }
}

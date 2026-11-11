package com.arpalaser.iot.data

import android.content.ContentValues
import android.content.Context
import com.arpalaser.iot.bluetooth.Protocol
import com.arpalaser.iot.data.DatabaseHelper.Companion.E_DETAIL
import com.arpalaser.iot.data.DatabaseHelper.Companion.E_DEVICE
import com.arpalaser.iot.data.DatabaseHelper.Companion.E_TIMESTAMP
import com.arpalaser.iot.data.DatabaseHelper.Companion.E_TYPE
import com.arpalaser.iot.data.DatabaseHelper.Companion.E_USERNAME
import com.arpalaser.iot.data.DatabaseHelper.Companion.T_EVENTS

/** Tipos de evento registrados. Se agrupan por categoría para filtrar el historial. */
enum class EventType(val category: Category, val label: String) {
    NOTA(Category.TELEMETRIA, "Cuerda activada"),
    NOTA_CONFIG(Category.COMANDO, "Nota asignada"),
    EFECTO(Category.COMANDO, "Efecto de sonido"),
    CALIBRACION(Category.COMANDO, "Calibración láser"),
    CONEXION(Category.COMANDO, "Conexión"),
    DESCONEXION(Category.COMANDO, "Desconexión"),
    LOGIN_OK(Category.SEGURIDAD, "Inicio de sesión"),
    LOGIN_FALLIDO(Category.SEGURIDAD, "Login fallido"),
    LOGOUT(Category.SEGURIDAD, "Cierre de sesión"),
    AUTH_DISPOSITIVO(Category.SEGURIDAD, "Autenticación del arpa"),
    TRAMA_INVALIDA(Category.SEGURIDAD, "Trama rechazada"),
    USUARIO_CREADO(Category.SEGURIDAD, "Usuario creado"),
    HISTORIAL_BORRADO(Category.SEGURIDAD, "Historial borrado");

    enum class Category(val label: String) {
        TELEMETRIA("Telemetría (notas)"), COMANDO("Comandos y conexión"), SEGURIDAD("Seguridad / auditoría")
    }
}

data class Event(
    val timestamp: Long,
    val type: EventType,
    val detail: String?,
    val username: String?,
    val device: String?
)

/** Persistencia y consulta de la telemetría y bitácora de auditoría (SQLite). */
class EventRepository(context: Context) {

    private val db = DatabaseHelper.get(context)

    fun log(type: EventType, detail: String?, username: String?, device: String? = null) {
        val values = ContentValues().apply {
            put(E_TIMESTAMP, System.currentTimeMillis())
            put(E_TYPE, type.name)
            put(E_DETAIL, detail?.take(120))
            put(E_USERNAME, username)
            put(E_DEVICE, device)
        }
        db.writableDatabase.insert(T_EVENTS, null, values)
    }

    /** Últimos eventos, opcionalmente filtrados por categoría. */
    fun recent(category: EventType.Category?, limit: Int = 300): List<Event> {
        val types = EventType.values().filter { category == null || it.category == category }.map { it.name }
        val placeholders = types.joinToString(",") { "?" }
        val list = mutableListOf<Event>()
        db.readableDatabase.query(
            T_EVENTS,
            arrayOf(E_TIMESTAMP, E_TYPE, E_DETAIL, E_USERNAME, E_DEVICE),
            "$E_TYPE IN ($placeholders)", types.toTypedArray(),
            null, null, "$E_TIMESTAMP DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                val type = runCatching { EventType.valueOf(c.getString(1)) }.getOrNull() ?: continue
                list += Event(c.getLong(0), type, c.getString(2), c.getString(3), c.getString(4))
            }
        }
        return list
    }

    /** Cantidad de activaciones por cuerda (1..6) para las estadísticas de ejecución. */
    fun noteCounts(): IntArray {
        val counts = IntArray(Protocol.STRING_COUNT)
        db.readableDatabase.rawQuery(
            "SELECT $E_DETAIL, COUNT(*) FROM $T_EVENTS WHERE $E_TYPE = ? GROUP BY $E_DETAIL",
            arrayOf(EventType.NOTA.name)
        ).use { c ->
            while (c.moveToNext()) {
                val idx = c.getString(0)?.substringBefore(' ')?.toIntOrNull()?.minus(1) ?: continue
                if (idx in counts.indices) counts[idx] += c.getInt(1)
            }
        }
        return counts
    }

    fun clearAll() {
        db.writableDatabase.delete(T_EVENTS, null, null)
    }
}

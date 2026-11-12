package com.arpalaser.iot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.arpalaser.iot.security.Role

/**
 * Base de datos local SQLite.
 *  - users: credenciales (hash PBKDF2), rol y control de intentos fallidos.
 *  - events: telemetría del arpa (notas activadas), comandos enviados y bitácora de seguridad.
 */
class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "arpa_laser.db"
        const val DB_VERSION = 2

        const val T_USERS = "users"
        const val U_ID = "id"
        const val U_USERNAME = "username"
        const val U_PASSWORD = "password_hash"
        const val U_ROLE = "role"
        const val U_FAILED = "failed_attempts"
        const val U_LOCKED_UNTIL = "locked_until"
        const val U_CREATED = "created_at"

        const val T_EVENTS = "events"
        const val E_ID = "id"
        const val E_TIMESTAMP = "timestamp"
        const val E_TYPE = "type"
        const val E_DETAIL = "detail"
        const val E_USERNAME = "username"
        const val E_DEVICE = "device"

        @Volatile private var instance: DatabaseHelper? = null

        fun get(context: Context): DatabaseHelper =
            instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_USERS (
                $U_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $U_USERNAME TEXT NOT NULL UNIQUE COLLATE NOCASE,
                $U_PASSWORD TEXT NOT NULL,
                $U_ROLE TEXT NOT NULL,
                $U_FAILED INTEGER NOT NULL DEFAULT 0,
                $U_LOCKED_UNTIL INTEGER NOT NULL DEFAULT 0,
                $U_CREATED INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $T_EVENTS (
                $E_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $E_TIMESTAMP INTEGER NOT NULL,
                $E_TYPE TEXT NOT NULL,
                $E_DETAIL TEXT,
                $E_USERNAME TEXT,
                $E_DEVICE TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_type_time ON $T_EVENTS($E_TYPE, $E_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "UPDATE $T_USERS SET $U_ROLE = ? WHERE $U_ROLE = ?",
                arrayOf(Role.MUSICO.name, Role.ADMIN.name)
            )
        }
    }
}

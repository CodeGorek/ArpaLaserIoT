package com.arpalaser.iot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import com.arpalaser.iot.data.DatabaseHelper.Companion.T_USERS
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_CREATED
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_FAILED
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_ID
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_LOCKED_UNTIL
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_PASSWORD
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_ROLE
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_USERNAME
import com.arpalaser.iot.security.PasswordHasher
import com.arpalaser.iot.security.Role

data class User(val id: Long, val username: String, val role: Role)

sealed class LoginResult {
    data class Success(val user: User) : LoginResult()
    data class InvalidCredentials(val remainingAttempts: Int) : LoginResult()
    data class Locked(val secondsLeft: Long) : LoginResult()
}

/**
 * Autenticación local con SQLite. Todas las consultas son parametrizadas (previene inyección SQL).
 * Bloqueo temporal de la cuenta tras [MAX_ATTEMPTS] intentos fallidos (protección contra fuerza bruta).
 */
class UserRepository(context: Context) {

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCK_MS = 60_000L
    }

    private val db = DatabaseHelper.get(context)

    fun count(): Int = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_USERS", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /** Retorna el id del usuario creado o -1 si el nombre ya existe. */
    fun register(username: String, password: CharArray, role: Role): Long {
        val values = ContentValues().apply {
            put(U_USERNAME, username.trim())
            put(U_PASSWORD, PasswordHasher.hash(password))
            put(U_ROLE, role.name)
            put(U_CREATED, System.currentTimeMillis())
        }
        return try {
            db.writableDatabase.insertOrThrow(T_USERS, null, values)
        } catch (e: SQLiteConstraintException) {
            -1L
        }
    }

    fun login(username: String, password: CharArray): LoginResult {
        val now = System.currentTimeMillis()
        val cursor = db.readableDatabase.query(
            T_USERS,
            arrayOf(U_ID, U_USERNAME, U_PASSWORD, U_ROLE, U_FAILED, U_LOCKED_UNTIL),
            "$U_USERNAME = ?", arrayOf(username.trim()), null, null, null
        )
        cursor.use { c ->
            if (!c.moveToFirst()) {
                // Usuario inexistente: se calcula un hash igualmente para no revelar
                // por diferencia de tiempo si el usuario existe o no.
                PasswordHasher.verify(password, PasswordHasher.hash(password))
                return LoginResult.InvalidCredentials(MAX_ATTEMPTS)
            }
            val id = c.getLong(0)
            val name = c.getString(1)
            val hash = c.getString(2)
            val role = Role.fromName(c.getString(3))
            val failed = c.getInt(4)
            val lockedUntil = c.getLong(5)

            if (lockedUntil > now) return LoginResult.Locked((lockedUntil - now + 999) / 1000)

            return if (PasswordHasher.verify(password, hash)) {
                updateAttempts(id, 0, 0)
                LoginResult.Success(User(id, name, role))
            } else {
                val newFailed = failed + 1
                if (newFailed >= MAX_ATTEMPTS) {
                    updateAttempts(id, 0, now + LOCK_MS)
                    LoginResult.Locked(LOCK_MS / 1000)
                } else {
                    updateAttempts(id, newFailed, 0)
                    LoginResult.InvalidCredentials(MAX_ATTEMPTS - newFailed)
                }
            }
        }
    }

    fun listUsers(): List<User> {
        val list = mutableListOf<User>()
        db.readableDatabase.query(T_USERS, arrayOf(U_ID, U_USERNAME, U_ROLE), null, null, null, null, "$U_USERNAME ASC")
            .use { c -> while (c.moveToNext()) list += User(c.getLong(0), c.getString(1), Role.fromName(c.getString(2))) }
        return list
    }

    private fun updateAttempts(id: Long, failed: Int, lockedUntil: Long) {
        val values = ContentValues().apply {
            put(U_FAILED, failed)
            put(U_LOCKED_UNTIL, lockedUntil)
        }
        db.writableDatabase.update(T_USERS, values, "$U_ID = ?", arrayOf(id.toString()))
    }
}

package com.arpalaser.iot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.arpalaser.iot.data.DatabaseHelper.Companion.T_USERS
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_CREATED
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_FAILED
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_ID
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_LOCKED_UNTIL
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_PASSWORD
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_ROLE
import com.arpalaser.iot.data.DatabaseHelper.Companion.U_USERNAME
import com.arpalaser.iot.security.InputValidator
import com.arpalaser.iot.security.OwnerAccount
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

    /** El autorregistro solo crea músicos. Retorna -1 si el nombre está ocupado o reservado. */
    fun registerMusician(username: String, password: CharArray): Long {
        val name = username.trim()
        require(InputValidator.isValidUsername(name))
        if (OwnerAccount.matches(name)) return -1L
        val values = ContentValues().apply {
            put(U_USERNAME, name)
            put(U_PASSWORD, PasswordHasher.hash(password))
            put(U_ROLE, Role.MUSICO.name)
            put(U_CREATED, System.currentTimeMillis())
        }
        return try {
            db.writableDatabase.insertOrThrow(T_USERS, null, values)
        } catch (e: SQLiteConstraintException) {
            -1L
        }
    }

    fun login(username: String, password: CharArray): LoginResult {
        val name = username.trim()
        val isOwner = OwnerAccount.matches(name)
        if (isOwner) ensureOwnerLoginRecord()

        val now = System.currentTimeMillis()
        val cursor = db.readableDatabase.query(
            T_USERS,
            arrayOf(U_ID, U_USERNAME, U_PASSWORD, U_ROLE, U_FAILED, U_LOCKED_UNTIL),
            "$U_USERNAME = ?", arrayOf(name), null, null, null
        )
        cursor.use { c ->
            if (!c.moveToFirst()) {
                // Usuario inexistente: se calcula un hash igualmente para no revelar
                // por diferencia de tiempo si el usuario existe o no.
                PasswordHasher.verify(password, PasswordHasher.hash(password))
                return LoginResult.InvalidCredentials(MAX_ATTEMPTS)
            }
            val id = c.getLong(0)
            val accountName = if (isOwner) OwnerAccount.username else c.getString(1)
            val hash = if (isOwner) OwnerAccount.passwordHash else c.getString(2)
            val storedRole = Role.fromName(c.getString(3))
            val role = when {
                isOwner -> Role.ADMIN
                storedRole == Role.ADMIN -> Role.MUSICO
                else -> storedRole
            }
            val failed = c.getInt(4)
            val lockedUntil = c.getLong(5)

            if (lockedUntil > now) return LoginResult.Locked((lockedUntil - now + 999) / 1000)

            return if (PasswordHasher.verify(password, hash)) {
                updateAttempts(id, 0, 0)
                LoginResult.Success(User(id, accountName, role))
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

    private fun ensureOwnerLoginRecord() {
        // SQLite registra los intentos. La credencial del administrador se verifica con la del APK.
        val values = ContentValues().apply {
            put(U_USERNAME, OwnerAccount.username)
            put(U_PASSWORD, "")
            put(U_ROLE, Role.MUSICO.name)
            put(U_CREATED, System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(T_USERS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun updateAttempts(id: Long, failed: Int, lockedUntil: Long) {
        val values = ContentValues().apply {
            put(U_FAILED, failed)
            put(U_LOCKED_UNTIL, lockedUntil)
        }
        db.writableDatabase.update(T_USERS, values, "$U_ID = ?", arrayOf(id.toString()))
    }
}

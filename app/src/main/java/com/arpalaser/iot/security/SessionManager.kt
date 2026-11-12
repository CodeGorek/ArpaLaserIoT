package com.arpalaser.iot.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.arpalaser.iot.bluetooth.Protocol
import com.arpalaser.iot.data.User

/**
 * Sesión del usuario y secretos de la app guardados en EncryptedSharedPreferences
 * (claves y valores cifrados con AES-256 GCM/SIV; la llave maestra vive en Android Keystore).
 *
 * La sesión expira tras [SESSION_TIMEOUT_MS] de inactividad (control de acceso ISO 27400).
 */
class SessionManager private constructor(context: Context) {

    companion object {
        const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L   // 10 minutos de inactividad
        private const val FILE = "arpa_secure_prefs"
        private const val K_USER_ID = "user_id"
        private const val K_USERNAME = "username"
        private const val K_ROLE = "role"
        private const val K_OWNER_VERIFIER = "owner_verifier"
        private const val K_LAST_ACTIVITY = "last_activity"
        private const val K_DEVICE_KEY = "device_key"
        private const val K_LAST_DEVICE = "last_device"
        private const val K_SAVED_NOTES = "saved_notes"
        private const val K_SAVED_EFFECT = "saved_effect"

        @Volatile private var instance: SessionManager? = null

        fun get(context: Context): SessionManager =
            instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        FILE,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun start(user: User) {
        prefs.edit().apply {
            putLong(K_USER_ID, user.id)
            putString(K_USERNAME, user.username)
            putString(K_ROLE, user.role.name)
            putLong(K_LAST_ACTIVITY, System.currentTimeMillis())
            if (user.role == Role.ADMIN) putString(K_OWNER_VERIFIER, OwnerAccount.passwordHash)
            else remove(K_OWNER_VERIFIER)
        }.apply()
    }

    fun isValid(): Boolean {
        if (prefs.getLong(K_USER_ID, -1L) < 0) return false
        val ownerUsername = OwnerAccount.matches(username)
        if (storedRole == Role.ADMIN) {
            if (!ownerUsername || prefs.getString(K_OWNER_VERIFIER, null) != OwnerAccount.passwordHash) return false
        } else if (ownerUsername) return false
        val idle = System.currentTimeMillis() - prefs.getLong(K_LAST_ACTIVITY, 0L)
        return idle in 0..SESSION_TIMEOUT_MS
    }

    fun touch() {
        if (!isValid()) return
        prefs.edit().putLong(K_LAST_ACTIVITY, System.currentTimeMillis()).apply()
    }

    fun end() {
        prefs.edit()
            .remove(K_USER_ID).remove(K_USERNAME).remove(K_ROLE).remove(K_LAST_ACTIVITY).remove(K_OWNER_VERIFIER)
            .apply()
    }

    val username: String get() = prefs.getString(K_USERNAME, "") ?: ""
    private val storedRole: Role get() = Role.fromName(prefs.getString(K_ROLE, null))
    val role: Role get() = if (isValid()) storedRole else Role.OBSERVADOR

    /** Clave compartida con el arpa (se guarda cifrada, nunca en el código fuente). */
    var deviceKey: String?
        get() = prefs.getString(K_DEVICE_KEY, null)
        set(value) { prefs.edit().putString(K_DEVICE_KEY, value).apply() }

    var lastDeviceAddress: String?
        get() = prefs.getString(K_LAST_DEVICE, null)
        set(value) { prefs.edit().putString(K_LAST_DEVICE, value).apply() }

    /** Frecuencias configuradas para las 6 cuerdas (persistidas localmente). */
    var savedNotes: IntArray
        get() {
            val raw = prefs.getString(K_SAVED_NOTES, null) ?: return Protocol.DEFAULT_NOTES.copyOf()
            val parsed = raw.split(",").mapNotNull { it.toIntOrNull() }
            return if (parsed.size == Protocol.STRING_COUNT &&
                parsed.all { it in Protocol.MIN_FREQ_HZ..Protocol.MAX_FREQ_HZ }
            ) parsed.toIntArray() else Protocol.DEFAULT_NOTES.copyOf()
        }
        set(value) {
            if (value.size == Protocol.STRING_COUNT) {
                prefs.edit().putString(K_SAVED_NOTES, value.joinToString(",")).apply()
            }
        }

    var savedEffect: Protocol.SoundEffect
        get() = Protocol.SoundEffect.fromCode(prefs.getInt(K_SAVED_EFFECT, Protocol.SoundEffect.HARP.code))
            ?: Protocol.SoundEffect.HARP
        set(value) { prefs.edit().putInt(K_SAVED_EFFECT, value.code).apply() }
}

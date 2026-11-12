package com.arpalaser.iot.security

import com.arpalaser.iot.BuildConfig

/** Única cuenta con privilegios, configurada al compilar la APK. */
internal object OwnerAccount {
    val username: String = BuildConfig.OWNER_USERNAME
    val passwordHash: String = BuildConfig.OWNER_PASSWORD_HASH

    fun matches(username: String): Boolean =
        passwordHash.isNotEmpty() && InputValidator.isValidUsername(username) &&
            username.equals(this.username, ignoreCase = true)
}

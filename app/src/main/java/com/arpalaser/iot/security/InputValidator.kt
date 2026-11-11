package com.arpalaser.iot.security

/** Validación de entradas del usuario (evita datos maliciosos o malformados). */
object InputValidator {

    private val USERNAME_REGEX = Regex("^[A-Za-z0-9_.]{4,20}$")
    private val DEVICE_KEY_REGEX = Regex("^[A-Za-z0-9_-]{6,24}$")

    fun isValidUsername(username: String): Boolean = USERNAME_REGEX.matches(username)

    /**
     * Política de contraseñas: 8 a 64 caracteres, con al menos una mayúscula,
     * una minúscula y un dígito. Retorna null si es válida o el mensaje de error.
     */
    fun passwordError(password: String): String? = when {
        password.length < 8 -> "La contraseña debe tener al menos 8 caracteres"
        password.length > 64 -> "La contraseña no puede superar 64 caracteres"
        password.none { it.isUpperCase() } -> "Debe incluir al menos una letra mayúscula"
        password.none { it.isLowerCase() } -> "Debe incluir al menos una letra minúscula"
        password.none { it.isDigit() } -> "Debe incluir al menos un número"
        else -> null
    }

    /** La clave compartida con el arpa no puede contener '*', ':' ni espacios (romperían la trama). */
    fun isValidDeviceKey(key: String): Boolean = DEVICE_KEY_REGEX.matches(key)
}

package com.arpalaser.iot.security

/**
 * Control de acceso basado en roles (RBAC).
 *  - ADMIN: permisos de músico más el borrado del historial.
 *  - MUSICO: controla el arpa (cambio de instrumento), usa el simulador y consulta el historial.
 *  - OBSERVADOR: solo monitorea las cuerdas activas y consulta el historial (solo lectura).
 */
enum class Role(
    val displayName: String,
    val canControl: Boolean,
    val canDeleteHistory: Boolean
) {
    ADMIN("Administrador", canControl = true, canDeleteHistory = true),
    MUSICO("Músico", canControl = true, canDeleteHistory = false),
    OBSERVADOR("Observador", canControl = false, canDeleteHistory = false);

    companion object {
        fun fromName(name: String?): Role = values().firstOrNull { it.name == name } ?: OBSERVADOR
    }
}

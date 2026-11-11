package com.arpalaser.iot.bluetooth

import java.util.UUID

/**
 * Protocolo de comunicación App ⇄ Arpa láser de 6 cuerdas (Arduino + HC-05 o teléfono simulador).
 *
 * Formato de trama (ASCII, una trama por línea terminada en '\n'):
 *
 *     PAYLOAD*CS
 *
 * donde CS es el XOR de todos los caracteres del PAYLOAD en 2 dígitos hexadecimales.
 *
 * App → Dispositivo:
 *   AUTH:<clave> | PING | N<1-6>:<freqHz> | S:<f1>,...,<f6> | E<1-7> | CAL | TEST
 * Dispositivo → App:
 *   AUTH_OK | AUTH_FAIL | AUTH_LOCK | AUTH_REQ | PONG | OK:N<c>:<f> | OK:S | OK:E<e> | OK:CAL | OK:TEST | ERR:<cod> | NOTA_ACTIVADA:<1-6>
 */
object Protocol {

    /** UUID estándar del perfil de puerto serie (SPP), compatible con el módulo HC-05. */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val STRING_COUNT = 6
    const val MIN_FREQ_HZ = 65
    const val MAX_FREQ_HZ = 2000

    /** Longitud máxima de una trama. Las más largas se descartan (anti-overflow). */
    const val MAX_FRAME_LENGTH = 48

    const val CMD_PING = "PING"
    const val CMD_CAL = "CAL"
    const val CMD_TEST = "TEST"
    const val CMD_AUTH_PREFIX = "AUTH:"

    /** Notas musicales predefinidas disponibles para asignar a cualquiera de las 6 cuerdas. */
    data class NoteOption(val label: String, val freqHz: Int)

    val NOTE_CATALOG: List<NoteOption> = listOf(
        NoteOption("Do3", 131), NoteOption("Re3", 147), NoteOption("Mi3", 165),
        NoteOption("Fa3", 175), NoteOption("Sol3", 196), NoteOption("La3", 220), NoteOption("Si3", 247),
        NoteOption("Do4", 262), NoteOption("Do#4", 277), NoteOption("Re4", 294), NoteOption("Re#4", 311),
        NoteOption("Mi4", 330), NoteOption("Fa4", 349), NoteOption("Fa#4", 370),
        NoteOption("Sol4", 392), NoteOption("Sol#4", 415), NoteOption("La4", 440), NoteOption("La#4", 466),
        NoteOption("Si4", 494), NoteOption("Do5", 523), NoteOption("Re5", 587), NoteOption("Mi5", 659)
    )

    val DEFAULT_NOTES = intArrayOf(262, 294, 330, 349, 392, 440) // Do4..La4

    /** Escalas rápidas predefinidas (6 cuerdas). */
    enum class ScalePreset(val displayName: String, val notes: IntArray) {
        MAYOR("Escala Mayor (Do-Re-Mi-Fa-Sol-La)", intArrayOf(262, 294, 330, 349, 392, 440)),
        PENTATONICA("Pentatónica (Do-Re-Mi-Sol-La-Do5)", intArrayOf(262, 294, 330, 392, 440, 523)),
        MENOR("Acorde Menor (La3-Do-Mi-Sol-La-Do5)", intArrayOf(220, 262, 330, 392, 440, 523)),
        GRAVE("Graves / Bajo (Do3-Re3-Mi3-Fa3-Sol3-La3)", intArrayOf(131, 147, 165, 175, 196, 220))
    }

    /** Efectos de sonido / timbres seleccionables en la app, el simulador y el Arduino. */
    enum class SoundEffect(val code: Int, val displayName: String) {
        HARP(1, "Arpa"),
        PIANO(2, "Piano"),
        GUITAR(3, "Guitarra"),
        EIGHT_BIT(4, "8-Bit Retro"),
        SYNTH(5, "Sintetizador"),
        ELECTRIC_GUITAR(6, "Guitarra eléctrica"),
        FLUTE(7, "Flauta");

        companion object {
            fun fromCode(code: Int): SoundEffect? = values().firstOrNull { it.code == code }
        }
    }

    fun noteLabelFor(freqHz: Int): String =
        NOTE_CATALOG.firstOrNull { it.freqHz == freqHz }?.label ?: "${freqHz}Hz"

    /** Calcula el checksum XOR de un payload en hexadecimal de 2 dígitos. */
    fun checksum(payload: String): String {
        var cs = 0
        for (c in payload) cs = cs xor (c.code and 0xFF)
        return String.format("%02X", cs)
    }

    fun frame(payload: String): String = "$payload*${checksum(payload)}"

    fun authFrame(key: String) = frame(CMD_AUTH_PREFIX + key)
    fun pingFrame() = frame(CMD_PING)
    fun calibrateFrame() = frame(CMD_CAL)
    fun testFrame() = frame(CMD_TEST)
    fun effectFrame(effect: SoundEffect) = frame("E${effect.code}")

    fun stringNoteFrame(stringNumber: Int, freqHz: Int): String {
        require(stringNumber in 1..STRING_COUNT)
        require(freqHz in MIN_FREQ_HZ..MAX_FREQ_HZ)
        return frame("N$stringNumber:$freqHz")
    }

    fun allNotesFrame(notes: IntArray): String {
        require(notes.size == STRING_COUNT && notes.all { it in MIN_FREQ_HZ..MAX_FREQ_HZ })
        return frame("S:" + notes.joinToString(","))
    }

    /** Valida una trama recibida y retorna su payload, o null si es inválida o manipulada. */
    fun parseFrame(rawLine: String): String? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.length > MAX_FRAME_LENGTH) return null
        val star = line.lastIndexOf('*')
        if (star <= 0 || star != line.length - 3) return null
        val payload = line.substring(0, star)
        val cs = line.substring(star + 1)
        if (payload.any { it.code < 32 || it.code > 126 }) return null
        if (!cs.equals(checksum(payload), ignoreCase = true)) return null
        return payload
    }

    // ---------------------------------------------------------------- Mensajes del dispositivo

    sealed class DeviceMessage {
        object AuthOk : DeviceMessage()
        object AuthFail : DeviceMessage()
        object AuthLocked : DeviceMessage()
        object AuthRequired : DeviceMessage()
        object Pong : DeviceMessage()
        object AckAllNotes : DeviceMessage()
        object AckCalibrated : DeviceMessage()
        object AckTest : DeviceMessage()
        data class AckNote(val string: Int, val freqHz: Int) : DeviceMessage()
        data class AckEffect(val effect: SoundEffect) : DeviceMessage()
        data class Error(val code: String) : DeviceMessage()
        data class NoteActivated(val string: Int) : DeviceMessage()
        data class Unknown(val payload: String) : DeviceMessage()
    }

    fun decodeDeviceMessage(payload: String): DeviceMessage = when {
        payload == "AUTH_OK" -> DeviceMessage.AuthOk
        payload == "AUTH_FAIL" -> DeviceMessage.AuthFail
        payload == "AUTH_LOCK" -> DeviceMessage.AuthLocked
        payload == "AUTH_REQ" -> DeviceMessage.AuthRequired
        payload == "PONG" -> DeviceMessage.Pong
        payload == "OK:S" -> DeviceMessage.AckAllNotes
        payload == "OK:CAL" -> DeviceMessage.AckCalibrated
        payload == "OK:TEST" -> DeviceMessage.AckTest
        payload.startsWith("OK:E") -> {
            val ef = payload.removePrefix("OK:E").toIntOrNull()?.let { SoundEffect.fromCode(it) }
            if (ef != null) DeviceMessage.AckEffect(ef) else DeviceMessage.Unknown(payload)
        }
        payload.startsWith("OK:N") -> {
            val parts = payload.removePrefix("OK:N").split(":")
            val s = parts.getOrNull(0)?.toIntOrNull()
            val f = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && s != null && s in 1..STRING_COUNT && f != null && f in MIN_FREQ_HZ..MAX_FREQ_HZ) {
                DeviceMessage.AckNote(s, f)
            } else DeviceMessage.Unknown(payload)
        }
        payload.startsWith("ERR:") -> DeviceMessage.Error(payload.removePrefix("ERR:"))
        payload.startsWith("NOTA_ACTIVADA:") -> {
            val n = payload.removePrefix("NOTA_ACTIVADA:").toIntOrNull()
            if (n != null && n in 1..STRING_COUNT) DeviceMessage.NoteActivated(n) else DeviceMessage.Unknown(payload)
        }
        else -> DeviceMessage.Unknown(payload)
    }

    // ---------------------------------------------------------------- Comandos de la app (lado dispositivo/simulador)

    sealed class AppCommand {
        data class Auth(val key: String) : AppCommand()
        data class SetStringNote(val string: Int, val freqHz: Int) : AppCommand()
        data class SetAllNotes(val notes: IntArray) : AppCommand()
        data class SetEffect(val effect: SoundEffect) : AppCommand()
        object Ping : AppCommand()
        object Calibrate : AppCommand()
        object TestTone : AppCommand()
    }

    /** Lista blanca estricta de comandos aceptados por el dispositivo. */
    fun decodeAppCommand(payload: String): AppCommand? = when {
        payload == CMD_PING -> AppCommand.Ping
        payload == CMD_CAL -> AppCommand.Calibrate
        payload == CMD_TEST -> AppCommand.TestTone
        payload.startsWith(CMD_AUTH_PREFIX) -> {
            val key = payload.removePrefix(CMD_AUTH_PREFIX)
            if (key.isNotEmpty()) AppCommand.Auth(key) else null
        }
        payload.length >= 4 && payload[0] == 'N' && payload[2] == ':' -> {
            val s = payload[1].digitToIntOrNull()
            val f = payload.substring(3).toIntOrNull()
            if (s != null && s in 1..STRING_COUNT && f != null && f in MIN_FREQ_HZ..MAX_FREQ_HZ) {
                AppCommand.SetStringNote(s, f)
            } else null
        }
        payload.startsWith("S:") -> {
            val freqs = payload.removePrefix("S:").split(",").mapNotNull { it.toIntOrNull() }
            if (freqs.size == STRING_COUNT && freqs.all { it in MIN_FREQ_HZ..MAX_FREQ_HZ }) {
                AppCommand.SetAllNotes(freqs.toIntArray())
            } else null
        }
        payload.length == 2 && payload[0] == 'E' -> {
            payload.substring(1).toIntOrNull()?.let { SoundEffect.fromCode(it) }?.let { AppCommand.SetEffect(it) }
        }
        else -> null
    }
}

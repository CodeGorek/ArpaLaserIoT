package com.arpalaser.iot

import com.arpalaser.iot.bluetooth.Protocol
import com.arpalaser.iot.bluetooth.Protocol.AppCommand
import com.arpalaser.iot.bluetooth.Protocol.DeviceMessage
import com.arpalaser.iot.bluetooth.Protocol.SoundEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test fun checksum_matchesKnownValues() {
        assertEquals("10", Protocol.checksum("PING"))
        assertEquals("E1*74", Protocol.frame("E1"))
        assertEquals("N6:440*72", Protocol.stringNoteFrame(6, 440))
    }

    @Test fun parseFrame_acceptsValidFrame() {
        val f = Protocol.frame("NOTA_ACTIVADA:6")
        assertEquals("NOTA_ACTIVADA:6", Protocol.parseFrame(f))
        assertEquals("NOTA_ACTIVADA:6", Protocol.parseFrame("$f\r"))

        val sync = Protocol.allNotesFrame(Protocol.DEFAULT_NOTES)
        assertEquals("S:262,294,330,349,392,440", Protocol.parseFrame(sync))
    }

    @Test fun parseFrame_rejectsTamperedOrMalformed() {
        val f = Protocol.frame("E3")
        assertNull(Protocol.parseFrame(f.replace("E3", "E4")))       // payload alterado
        assertNull(Protocol.parseFrame("E3"))                         // sin checksum
        assertNull(Protocol.parseFrame("*00"))                        // sin payload
        assertNull(Protocol.parseFrame(Protocol.frame("A".repeat(60)))) // excede MAX_FRAME_LENGTH
        assertNull(Protocol.parseFrame(""))
    }

    @Test fun decodeDeviceMessages_sixStringsAndEffects() {
        assertEquals(DeviceMessage.AuthOk, Protocol.decodeDeviceMessage("AUTH_OK"))
        assertEquals(DeviceMessage.NoteActivated(6), Protocol.decodeDeviceMessage("NOTA_ACTIVADA:6"))
        assertTrue(Protocol.decodeDeviceMessage("NOTA_ACTIVADA:7") is DeviceMessage.Unknown)
        assertEquals(DeviceMessage.AckNote(5, 392), Protocol.decodeDeviceMessage("OK:N5:392"))
        assertEquals(DeviceMessage.AckEffect(SoundEffect.EIGHT_BIT), Protocol.decodeDeviceMessage("OK:E4"))
        assertEquals(DeviceMessage.AckEffect(SoundEffect.ELECTRIC_GUITAR), Protocol.decodeDeviceMessage("OK:E6"))
        assertEquals(DeviceMessage.AckEffect(SoundEffect.FLUTE), Protocol.decodeDeviceMessage("OK:E7"))
        assertEquals(DeviceMessage.AckAllNotes, Protocol.decodeDeviceMessage("OK:S"))
        assertEquals(DeviceMessage.AckCalibrated, Protocol.decodeDeviceMessage("OK:CAL"))
        assertEquals(DeviceMessage.AckTest, Protocol.decodeDeviceMessage("OK:TEST"))
        assertEquals(DeviceMessage.Error("RANGO"), Protocol.decodeDeviceMessage("ERR:RANGO"))
    }

    @Test fun decodeAppCommands_whitelistOnly() {
        assertEquals(AppCommand.Ping, Protocol.decodeAppCommand("PING"))
        assertEquals(AppCommand.Calibrate, Protocol.decodeAppCommand("CAL"))
        assertEquals(AppCommand.TestTone, Protocol.decodeAppCommand("TEST"))
        assertEquals(AppCommand.Auth("Clave_123"), Protocol.decodeAppCommand("AUTH:Clave_123"))
        assertEquals(AppCommand.SetStringNote(6, 523), Protocol.decodeAppCommand("N6:523"))
        assertEquals(AppCommand.SetEffect(SoundEffect.GUITAR), Protocol.decodeAppCommand("E3"))
        assertEquals(AppCommand.SetEffect(SoundEffect.ELECTRIC_GUITAR), Protocol.decodeAppCommand("E6"))
        assertEquals(AppCommand.SetEffect(SoundEffect.FLUTE), Protocol.decodeAppCommand("E7"))
        assertEquals("E6", Protocol.parseFrame(Protocol.effectFrame(SoundEffect.ELECTRIC_GUITAR)))

        val all = Protocol.decodeAppCommand("S:262,294,330,392,440,523") as AppCommand.SetAllNotes
        assertArrayEquals(intArrayOf(262, 294, 330, 392, 440, 523), all.notes)

        // Comandos rechazados (fuera de rango o no permitidos)
        assertNull(Protocol.decodeAppCommand("N7:440"))      // cuerda inexistente
        assertNull(Protocol.decodeAppCommand("N1:99999"))    // frecuencia fuera de rango
        assertNull(Protocol.decodeAppCommand("S:262,294"))   // faltan cuerdas
        assertNull(Protocol.decodeAppCommand("E9"))          // efecto inválido
        assertNull(Protocol.decodeAppCommand("AUTH:"))
        assertNull(Protocol.decodeAppCommand("DROP TABLE"))
    }
}

package com.arpalaser.iot

import com.arpalaser.iot.bluetooth.Protocol
import com.arpalaser.iot.data.CustomPresetRepository
import org.junit.Assert.*
import org.junit.Test

class CustomPresetTest {
    @Test fun validatesNamesAndAllSixFrequencies() {
        val notes = listOf(65, 147, 262, 440, 880, 2000)
        assertTrue(CustomPresetRepository.validPreset("Star Wars", notes))
        assertFalse(CustomPresetRepository.validPreset("   ", notes))
        assertFalse(CustomPresetRepository.validPreset("x".repeat(41), notes))
        assertFalse(CustomPresetRepository.validPreset("Canción", notes.dropLast(1)))
        assertFalse(CustomPresetRepository.validPreset("Canción", notes.dropLast(1) + 2001))
        assertFalse(CustomPresetRepository.validPreset("Canción", listOf(64) + notes.drop(1)))
        assertEquals(notes, (Protocol.decodeAppCommand(Protocol.parseFrame(Protocol.allNotesFrame(notes.toIntArray()))!!) as Protocol.AppCommand.SetAllNotes).notes.toList())
    }
}

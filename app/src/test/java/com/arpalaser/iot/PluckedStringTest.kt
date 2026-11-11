package com.arpalaser.iot

import com.arpalaser.iot.util.PluckedString
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PluckedStringTest {
    @Test fun pluckProducesSoundThatDecays() {
        val string = PluckedString(220, 22050)
        val samples = DoubleArray(22050) { string.next() }
        val first = samples.sliceArray(0 until 2205).map(::abs).average()
        val last = samples.sliceArray(19845 until 22050).map(::abs).average()
        assertTrue("La cuerda debe producir sonido", first > 0.01)
        assertTrue("La cuerda debe ir apagándose", last < first)
        assertTrue(samples.all { it.isFinite() && abs(it) <= 1.0 })
    }
}

package com.arpalaser.iot.util

import kotlin.math.tanh
import kotlin.random.Random

/** Cuerda pulsada con realimentación filtrada y saturación suave de amplificador. */
internal class PluckedString(frequencyHz: Int, sampleRate: Int) {
    private val delay = DoubleArray((sampleRate.toDouble() / frequencyHz - 0.5).toInt().coerceAtLeast(2))
    private var position = 0
    private var filtered = 0.0

    init {
        val random = Random(frequencyHz)
        for (i in delay.indices) delay[i] = random.nextDouble(-1.0, 1.0)
        val mean = delay.average()
        for (i in delay.indices) delay[i] -= mean
    }

    fun next(): Double {
        val current = delay[position]
        val next = delay[(position + 1) % delay.size]
        delay[position] = (current + next) * 0.498
        position = (position + 1) % delay.size
        // Atenúa el brillo áspero de la saturación y da cuerpo al sonido.
        filtered += 0.22 * (tanh(current * 2.6) - filtered)
        return filtered * 0.8
    }
}

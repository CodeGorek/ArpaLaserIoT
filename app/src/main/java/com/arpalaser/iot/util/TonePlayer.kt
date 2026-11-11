package com.arpalaser.iot.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.arpalaser.iot.bluetooth.Protocol.SoundEffect
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sintetizador de audio PCM en tiempo real para las 6 cuerdas.
 * Genera timbres sintetizados para los instrumentos seleccionados.
 */
class TonePlayer {

    private val sampleRate = 22050
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var current: AudioTrack? = null

    fun play(frequencyHz: Int, effect: SoundEffect = SoundEffect.HARP, durationMs: Int = 480) {
        executor.execute {
            val samples = sampleRate * durationMs / 1000
            val buffer = ShortArray(samples)
            val attackSamples = (sampleRate * 0.01).toInt().coerceAtLeast(1)
            val guitar = if (effect == SoundEffect.ELECTRIC_GUITAR) PluckedString(frequencyHz, sampleRate) else null

            for (i in 0 until samples) {
                val t = i.toDouble() / sampleRate
                val w = 2.0 * PI * frequencyHz * t
                val attack = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
                val release = if (i > samples - attackSamples) (samples - i).toDouble() / attackSamples else 1.0

                val wave = when (effect) {
                    SoundEffect.HARP -> {
                        val env = exp(-4.0 * t) * attack * release
                        (sin(w) + 0.30 * sin(2 * w) + 0.10 * sin(3 * w)) * 0.45 * env
                    }
                    SoundEffect.PIANO -> {
                        val env = exp(-3.2 * t) * attack * release
                        (sin(w) + 0.45 * sin(2 * w) + 0.22 * sin(3 * w) + 0.12 * sin(4 * w)) * 0.38 * env
                    }
                    SoundEffect.GUITAR -> {
                        // Los armónicos superiores decaen más rápido que la fundamental (cuerda pulsada)
                        val body = sin(w) * exp(-2.5 * t) +
                            0.50 * sin(2 * w) * exp(-5.0 * t) +
                            0.30 * sin(3 * w) * exp(-8.0 * t) +
                            0.18 * sin(4 * w) * exp(-11.0 * t)
                        body * 0.42 * attack * release
                    }
                    SoundEffect.ELECTRIC_GUITAR -> {
                        // Una cuerda pulsada excitada por ruido, filtrada y amplificada.
                        guitar!!.next() * attack * release
                    }
                    SoundEffect.FLUTE -> {
                        // Fundamental limpia, segundo armónico tenue y vibrato integrado en fase.
                        val vibratoPhase = 0.9 * (1.0 - kotlin.math.cos(2.0 * PI * 5.0 * t))
                        val phase = w + vibratoPhase
                        val breathAttack = (t / 0.08).coerceIn(0.0, 1.0)
                        (sin(phase) + 0.18 * sin(2 * phase)) * 0.48 * breathAttack * release
                    }
                    SoundEffect.EIGHT_BIT -> {
                        // Onda cuadrada clásica tipo chiptune con arpegio breve de ataque
                        val arpFreq = if (t < 0.05) frequencyHz * 1.25 else frequencyHz.toDouble()
                        val phase = (t * arpFreq) % 1.0
                        val square = if (phase < 0.25) 1.0 else -1.0   // pulso 25% NES
                        val env = exp(-2.0 * t) * release
                        square * 0.35 * env
                    }
                    SoundEffect.SYNTH -> {
                        // Diente de sierra suavizado + ligero detune para sonido analógico
                        val saw1 = 2.0 * ((t * frequencyHz) % 1.0) - 1.0
                        val saw2 = 2.0 * ((t * (frequencyHz * 1.006)) % 1.0) - 1.0
                        val env = exp(-1.6 * t) * attack * release
                        (saw1 + saw2) * 0.28 * env
                    }
                }
                buffer[i] = (wave.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.75).toInt().toShort()
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples * 2)
                .build()
            track.write(buffer, 0, samples)
            current?.let { runCatching { it.stop(); it.release() } }
            current = track
            track.play()
        }
    }

    fun release() {
        executor.execute { current?.let { runCatching { it.stop(); it.release() } }; current = null }
        executor.shutdown()
    }
}

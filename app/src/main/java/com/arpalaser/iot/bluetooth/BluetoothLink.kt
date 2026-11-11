package com.arpalaser.iot.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Enlace Bluetooth Classic (RFCOMM / SPP) reutilizable en dos modos:
 *  - Cliente: la app de control se conecta al HC-05 del Arduino o a otro teléfono en modo simulador.
 *  - Servidor: el teléfono simulador espera la conexión de la app de control.
 *
 * Se usan sockets "seguros" (createRfcommSocketToServiceRecord / listenUsingRfcommWithServiceRecord),
 * que exigen dispositivos vinculados y cifran el enlace a nivel de radio.
 *
 * Toda la E/S se hace en hilos de fondo; los eventos se publican en el hilo principal.
 * El llamador debe verificar el permiso BLUETOOTH_CONNECT antes de usar esta clase.
 */
@SuppressLint("MissingPermission")
class BluetoothLink(
    private val adapter: BluetoothAdapter,
    private val listener: Listener
) {
    enum class State { DISCONNECTED, LISTENING, CONNECTING, CONNECTED }

    interface Listener {
        fun onStateChanged(state: State, detail: String?)
        fun onLineReceived(line: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closedByUser = false

    @Volatile var state: State = State.DISCONNECTED
        private set

    /** Modo cliente: conecta con un dispositivo vinculado. */
    fun connect(device: BluetoothDevice) {
        close()
        closedByUser = false
        setState(State.CONNECTING, device.name ?: device.address)
        Thread({
            try {
                adapter.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(Protocol.SPP_UUID)
                socket = s
                s.connect()
                onConnected(s, device.name ?: device.address)
            } catch (e: IOException) {
                safeCloseSocket()
                if (!closedByUser) setState(State.DISCONNECTED, "No se pudo conectar: ${e.message}")
            } catch (e: SecurityException) {
                setState(State.DISCONNECTED, "Permiso Bluetooth denegado")
            }
        }, "bt-connect").start()
    }

    /** Modo servidor: espera una conexión entrante (teléfono que simula el arpa). */
    fun listen(serviceName: String) {
        close()
        closedByUser = false
        Thread({
            try {
                val server = adapter.listenUsingRfcommWithServiceRecord(serviceName, Protocol.SPP_UUID)
                serverSocket = server
                setState(State.LISTENING, null)
                val s = server.accept()          // bloquea hasta recibir un cliente
                server.close()                   // solo se acepta UN cliente a la vez
                serverSocket = null
                socket = s
                onConnected(s, s.remoteDevice?.name ?: s.remoteDevice?.address ?: "cliente")
            } catch (e: IOException) {
                safeCloseSocket()
                if (!closedByUser) setState(State.DISCONNECTED, "Servidor detenido: ${e.message}")
            } catch (e: SecurityException) {
                setState(State.DISCONNECTED, "Permiso Bluetooth denegado")
            }
        }, "bt-listen").start()
    }

    /** Envía una trama ya construida (se agrega el salto de línea). */
    fun send(frame: String) {
        val out = output ?: return
        writer.execute {
            try {
                out.write((frame + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
            } catch (e: IOException) {
                handleDisconnect("Error de escritura: ${e.message}")
            }
        }
    }

    fun close() {
        closedByUser = true
        try { serverSocket?.close() } catch (_: IOException) { }
        serverSocket = null
        safeCloseSocket()
        if (state != State.DISCONNECTED) setState(State.DISCONNECTED, null)
    }

    fun shutdown() {
        close()
        writer.shutdownNow()
    }

    // ------------------------------------------------------------------------------------------

    private fun onConnected(s: BluetoothSocket, name: String) {
        output = s.outputStream
        setState(State.CONNECTED, name)
        readLoop(s)
    }

    /**
     * Lee bytes y arma líneas manualmente con un límite de tamaño: una línea que supere
     * MAX_FRAME_LENGTH se descarta completa (protección ante desbordamientos o datos basura).
     */
    private fun readLoop(s: BluetoothSocket) {
        val input = s.inputStream
        val buffer = StringBuilder()
        var overflow = false
        val chunk = ByteArray(256)
        try {
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = (chunk[i].toInt() and 0xFF).toChar()
                    when (c) {
                        '\n' -> {
                            if (!overflow && buffer.isNotEmpty()) {
                                val line = buffer.toString()
                                mainHandler.post { listener.onLineReceived(line) }
                            }
                            buffer.setLength(0)
                            overflow = false
                        }
                        '\r' -> Unit
                        else -> {
                            if (buffer.length >= Protocol.MAX_FRAME_LENGTH) overflow = true
                            else buffer.append(c)
                        }
                    }
                }
            }
            handleDisconnect("El dispositivo cerró la conexión")
        } catch (e: IOException) {
            handleDisconnect(if (closedByUser) null else "Conexión perdida")
        }
    }

    private fun handleDisconnect(detail: String?) {
        safeCloseSocket()
        if (state != State.DISCONNECTED) setState(State.DISCONNECTED, detail)
    }

    private fun safeCloseSocket() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
        output = null
    }

    private fun setState(newState: State, detail: String?) {
        state = newState
        mainHandler.post { listener.onStateChanged(newState, detail) }
    }
}

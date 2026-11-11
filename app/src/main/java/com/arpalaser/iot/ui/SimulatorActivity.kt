package com.arpalaser.iot.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.arpalaser.iot.R
import com.arpalaser.iot.bluetooth.BluetoothLink
import com.arpalaser.iot.bluetooth.BtPermissions
import com.arpalaser.iot.bluetooth.Protocol
import com.arpalaser.iot.bluetooth.Protocol.AppCommand
import com.arpalaser.iot.bluetooth.Protocol.SoundEffect
import com.arpalaser.iot.databinding.ActivitySimulatorBinding
import com.arpalaser.iot.security.InputValidator
import com.arpalaser.iot.util.TonePlayer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modo simulador (6 cuerdas): convierte un SEGUNDO teléfono Android en el arpa láser IoT.
 * Replica exactamente el protocolo, efectos de sonido y controles de seguridad del Arduino UNO.
 */
@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class SimulatorActivity : SecureActivity(), BluetoothLink.Listener {

    companion object {
        private const val SERVICE_NAME = "ArpaLaserIoT"
        private const val MAX_AUTH_FAILS = 3
        private const val LOCK_MS = 30_000L
        private const val SESSION_IDLE_MS = 15_000L
    }

    private lateinit var binding: ActivitySimulatorBinding
    private var adapter: BluetoothAdapter? = null
    private var link: BluetoothLink? = null
    private val tone = TonePlayer()
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logLines = ArrayDeque<String>()

    private var running = false
    private var authenticated = false
    private var authFails = 0
    private var lockedUntil = 0L
    private var lastRx = 0L
    private var notes = Protocol.DEFAULT_NOTES.copyOf()
    private var effect = SoundEffect.HARP
    private var pendingStart = false

    private lateinit var stringButtons: List<TextView>

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (adapter?.isEnabled == true) startServer()
            else appendLog("Bluetooth no activado: puedes tocar las 6 cuerdas en modo local.")
        }

    private val watchdog = object : Runnable {
        override fun run() {
            if (authenticated && System.currentTimeMillis() - lastRx > SESSION_IDLE_MS) {
                authenticated = false
                appendLog("Sesión del cliente expirada por inactividad")
                link?.send(Protocol.frame("AUTH_REQ"))
                updateStatus()
            }
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimulatorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.menu_simulator)

        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        binding.etKey.setText(session.deviceKey.orEmpty())
        notes = session.savedNotes
        effect = session.savedEffect

        stringButtons = listOf(binding.sim1, binding.sim2, binding.sim3, binding.sim4, binding.sim5, binding.sim6)
        stringButtons.forEachIndexed { i, b ->
            b.setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) { pluck(i + 1); v.isPressed = true }
                if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) v.isPressed = false
                true
            }
        }

        binding.btnStart.setOnClickListener { if (running) stopServer() else startServer() }
        binding.btnVisible.setOnClickListener { makeDiscoverable() }
        if (adapter == null) {
            binding.btnStart.isEnabled = false
            binding.btnVisible.isEnabled = false
            appendLog("Sin adaptador Bluetooth: toca las 6 cuerdas para prueba local.")
        }
        binding.spSimEffect.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            SoundEffect.values().map { it.displayName }
        )
        binding.spSimEffect.setSelection(effect.ordinal, false)
        binding.spSimEffect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = SoundEffect.values()[position]
                if (selected == effect) return
                effect = selected
                session.savedEffect = selected
                updateLabels()
                tone.play(notes[0], effect)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateLabels()
        updateStatus()
        handler.post(watchdog)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running = false
        link?.shutdown()
        tone.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Servidor

    private fun startServer() {
        val bt = adapter ?: run { toast(getString(R.string.bt_not_supported)); return }
        val key = binding.etKey.text?.toString()?.trim().orEmpty()
        if (!InputValidator.isValidDeviceKey(key)) {
            binding.tilKey.error = getString(R.string.invalid_device_key); return
        }
        binding.tilKey.error = null
        if (!BtPermissions.hasAll(this, asServer = true)) {
            pendingStart = true
            BtPermissions.request(this, asServer = true)
            return
        }
        if (!bt.isEnabled) { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }

        session.deviceKey = key
        running = true
        authenticated = false
        binding.etKey.isEnabled = false
        link?.shutdown()
        link = BluetoothLink(bt, this).also { it.listen(SERVICE_NAME) }
        binding.btnStart.text = getString(R.string.stop_server)
    }

    private fun stopServer() {
        running = false
        authenticated = false
        link?.close()
        binding.etKey.isEnabled = true
        binding.btnStart.text = getString(R.string.start_server)
        updateStatus()
    }

    private fun makeDiscoverable() {
        if (!BtPermissions.hasAll(this, asServer = true)) {
            pendingStart = false
            BtPermissions.request(this, asServer = true)
            return
        }
        discoverableLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BtPermissions.REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (pendingStart) { pendingStart = false; startServer() }
            } else {
                pendingStart = false
                toast(getString(R.string.permission_denied))
            }
        }
    }

    override fun onStateChanged(state: BluetoothLink.State, detail: String?) {
        when (state) {
            BluetoothLink.State.CONNECTED -> appendLog("Cliente conectado: $detail")
            BluetoothLink.State.LISTENING -> appendLog("Esperando conexión de la app de control…")
            BluetoothLink.State.DISCONNECTED -> {
                authenticated = false
                detail?.let { appendLog(it) }
                if (running) handler.postDelayed({
                    if (running && adapter?.isEnabled == true) link?.listen(SERVICE_NAME) else stopServer()
                }, 1_000)
            }
            else -> Unit
        }
        updateStatus()
    }

    override fun onLineReceived(line: String) {
        lastRx = System.currentTimeMillis()
        val payload = Protocol.parseFrame(line)
        if (payload == null) {
            appendLog("⚠ Trama inválida descartada")
            reply("ERR:CHECKSUM"); return
        }
        val cmd = Protocol.decodeAppCommand(payload)
        if (cmd == null) {
            appendLog("⚠ Comando no permitido: ${payload.take(20)}")
            reply("ERR:CMD"); return
        }
        when (cmd) {
            is AppCommand.Auth -> handleAuth(cmd.key)
            AppCommand.Ping -> reply(if (authenticated) "PONG" else "AUTH_REQ")
            is AppCommand.SetStringNote -> {
                if (!authenticated) { reply("AUTH_REQ"); return }
                notes[cmd.string - 1] = cmd.freqHz
                updateLabels()
                tone.play(cmd.freqHz, effect, 160)
                appendLog("Cuerda ${cmd.string} → ${Protocol.noteLabelFor(cmd.freqHz)} (${cmd.freqHz} Hz)")
                reply("OK:N${cmd.string}:${cmd.freqHz}")
            }
            is AppCommand.SetAllNotes -> {
                if (!authenticated) { reply("AUTH_REQ"); return }
                cmd.notes.copyInto(notes)
                updateLabels()
                tone.play(notes[0], effect, 160)
                appendLog("6 notas actualizadas desde la app")
                reply("OK:S")
            }
            is AppCommand.SetEffect -> {
                if (!authenticated) { reply("AUTH_REQ"); return }
                effect = cmd.effect
                session.savedEffect = effect
                binding.spSimEffect.setSelection(effect.ordinal)
                updateLabels()
                tone.play(notes[0], effect, 200)
                appendLog("Efecto cambiado a ${effect.displayName}")
                reply("OK:E${effect.code}")
            }
            AppCommand.Calibrate -> {
                if (!authenticated) { reply("AUTH_REQ"); return }
                tone.play(880, SoundEffect.HARP, 120)
                appendLog("Calibración virtual de 6 sensores OK")
                reply("OK:CAL")
            }
            AppCommand.TestTone -> {
                if (!authenticated) { reply("AUTH_REQ"); return }
                tone.play(440, effect, 220)
                appendLog("Tono de prueba emitido (440 Hz)")
                reply("OK:TEST")
            }
        }
    }

    private fun handleAuth(key: String) {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) { reply("AUTH_LOCK"); return }
        val expected = session.deviceKey.orEmpty()
        val ok = MessageDigest.isEqual(key.toByteArray(), expected.toByteArray())
        if (ok) {
            authenticated = true
            authFails = 0
            appendLog("✔ Cliente autenticado")
            reply("AUTH_OK")
        } else {
            authFails++
            appendLog("✖ Clave incorrecta ($authFails/$MAX_AUTH_FAILS)")
            if (authFails >= MAX_AUTH_FAILS) {
                lockedUntil = now + LOCK_MS
                authFails = 0
                reply("AUTH_LOCK")
            } else reply("AUTH_FAIL")
        }
        updateStatus()
    }

    // ---------------------------------------------------------------- Cuerdas

    private fun pluck(stringNumber: Int) {
        val idx = stringNumber - 1
        tone.play(notes[idx], effect)
        val v = stringButtons[idx]
        v.setBackgroundColor(ContextCompat.getColor(this, R.color.laser_red))
        handler.postDelayed({ v.setBackgroundResource(R.drawable.bg_sim_string) }, 250)
        if (authenticated) reply("NOTA_ACTIVADA:$stringNumber")
    }

    private fun reply(payload: String) {
        link?.send(Protocol.frame(payload))
    }

    private fun updateLabels() {
        stringButtons.forEachIndexed { i, b ->
            val f = notes[i]
            b.text = getString(R.string.sim_string_label, i + 1, Protocol.noteLabelFor(f), f)
        }
        binding.tvSimEffect.text = getString(R.string.effect_label, effect.displayName)
    }

    private fun updateStatus() {
        val state = link?.state ?: BluetoothLink.State.DISCONNECTED
        val (text, color) = when {
            adapter == null -> "● Modo local (sin Bluetooth): toca las cuerdas" to R.color.warning
            !running -> getString(R.string.sim_stopped) to R.color.text_secondary
            state == BluetoothLink.State.CONNECTED && authenticated -> getString(R.string.sim_authenticated) to R.color.laser_green
            state == BluetoothLink.State.CONNECTED -> getString(R.string.status_authenticating) to R.color.warning
            else -> getString(R.string.status_listening) to R.color.warning
        }
        binding.tvSimStatus.text = text
        binding.tvSimStatus.setTextColor(ContextCompat.getColor(this, color))
        binding.tvSimHint.visibility = if (authenticated) View.GONE else View.VISIBLE
    }

    private fun appendLog(msg: String) {
        logLines.addFirst("[${timeFmt.format(Date())}] $msg")
        while (logLines.size > 30) logLines.removeLast()
        binding.tvSimLog.text = logLines.joinToString("\n")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

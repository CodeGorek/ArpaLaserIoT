package com.arpalaser.iot.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.arpalaser.iot.R
import com.arpalaser.iot.bluetooth.BluetoothLink
import com.arpalaser.iot.bluetooth.BtPermissions
import com.arpalaser.iot.bluetooth.Protocol
import com.arpalaser.iot.bluetooth.Protocol.DeviceMessage
import com.arpalaser.iot.bluetooth.Protocol.ScalePreset
import com.arpalaser.iot.bluetooth.Protocol.SoundEffect
import com.arpalaser.iot.data.CustomPreset
import com.arpalaser.iot.data.CustomPresetRepository
import com.arpalaser.iot.data.EventRepository
import com.arpalaser.iot.data.EventType
import com.arpalaser.iot.databinding.ActivityControlBinding
import com.arpalaser.iot.security.InputValidator
import com.arpalaser.iot.util.TonePlayer
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Control y monitoreo del arpa láser de 6 cuerdas por Bluetooth:
 *  1. Conexión segura al HC-05 (Arduino UNO) o al teléfono simulador.
 *  2. Autenticación mutua mediante clave compartida.
 *  3. Asignación individual de notas a botones (C1–C6), presets de escala y efectos de sonido.
 *  4. Recalibración remota de LDR, tono de prueba y monitoreo en tiempo real en SQLite.
 */
@SuppressLint("MissingPermission")
class ControlActivity : SecureActivity(), BluetoothLink.Listener {

    companion object {
        private const val PING_INTERVAL_MS = 5_000L
        private const val TIMEOUT_MS = 15_000L
    }

    private lateinit var binding: ActivityControlBinding
    private lateinit var events: EventRepository
    private var adapter: BluetoothAdapter? = null
    private var link: BluetoothLink? = null
    private val tone = TonePlayer()

    private var deviceName: String? = null
    private var authenticated = false
    private lateinit var notes: IntArray
    private var currentEffect = SoundEffect.HARP
    private var suppressEffectSpinner = true
    private lateinit var presetRepository: CustomPresetRepository
    private var customPresets = mutableListOf<CustomPreset>()
    private val counters = IntArray(Protocol.STRING_COUNT)
    private var invalidFrames = 0
    private var lastPingSent = 0L
    private var lastRx = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logLines = ArrayDeque<String>()

    private lateinit var stringViews: List<View>
    private lateinit var stringLabels: List<TextView>
    private lateinit var counterViews: List<TextView>
    private lateinit var noteButtons: List<MaterialButton>

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (adapter?.isEnabled == true) showDevicePicker()
            else toast(getString(R.string.bt_required))
        }

    private val heartbeat = object : Runnable {
        override fun run() {
            val l = link ?: return
            if (l.state == BluetoothLink.State.CONNECTED && authenticated) {
                if (System.currentTimeMillis() - lastRx > TIMEOUT_MS) {
                    setStatus(getString(R.string.status_no_response), R.color.warning)
                }
                lastPingSent = System.currentTimeMillis()
                l.send(Protocol.pingFrame())
            }
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.menu_control)

        events = EventRepository(this)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        presetRepository = CustomPresetRepository(this)
        customPresets = presetRepository.load().toMutableList()
        notes = session.savedNotes
        currentEffect = session.savedEffect

        stringViews = listOf(binding.string1, binding.string2, binding.string3, binding.string4, binding.string5, binding.string6)
        stringLabels = listOf(binding.label1, binding.label2, binding.label3, binding.label4, binding.label5, binding.label6)
        counterViews = listOf(binding.count1, binding.count2, binding.count3, binding.count4, binding.count5, binding.count6)
        noteButtons = listOf(binding.btnNote1, binding.btnNote2, binding.btnNote3, binding.btnNote4, binding.btnNote5, binding.btnNote6)

        setupSelectors()
        noteButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener { openNotePicker(idx + 1) }
        }
        // Tocar cualquiera de las 6 cuerdas en pantalla permite probar la nota y efecto localmente sin Bluetooth.
        stringViews.forEachIndexed { idx, view ->
            (view.parent as View).setOnClickListener { previewLocalNote(idx + 1) }
        }

        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnDisconnect.setOnClickListener { link?.close() }
        binding.btnPairSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        binding.btnResetCounters.setOnClickListener { counters.fill(0); refreshCounters() }
        binding.btnAddPreset.setOnClickListener { showPresetEditor(null) }
        binding.btnEditPreset.setOnClickListener {
            val index = binding.spPreset.selectedItemPosition - 1 - ScalePreset.values().size
            if (index in customPresets.indices) showPresetEditor(index)
        }
        binding.btnSync.setOnClickListener { syncAllToDevice() }
        binding.btnCalibrate.setOnClickListener { sendCalibrate() }
        binding.btnTestTone.setOnClickListener { sendTestTone() }

        if (!session.role.canControl) {
            binding.tvRoleNotice.visibility = View.VISIBLE
        }
        updateNoteLabels()
        refreshCounters()
        renderState(BluetoothLink.State.DISCONNECTED, null)

        if (adapter == null) {
            binding.btnConnect.isEnabled = false
            setStatus(getString(R.string.bt_not_supported), R.color.laser_red)
        }
    }

    private fun setupSelectors() {
        binding.spEffect.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            SoundEffect.values().map { it.displayName }
        )
        binding.spEffect.setSelection(currentEffect.ordinal, false)
        binding.spEffect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressEffectSpinner) {
                    suppressEffectSpinner = false
                    if (SoundEffect.values()[position] == currentEffect) return
                }
                val ef = SoundEffect.values()[position]
                selectEffect(ef)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        refreshPresetSelector()
        binding.spPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val customIndex = position - 1 - ScalePreset.values().size
                binding.btnEditPreset.visibility = if (session.role.canControl && customIndex in customPresets.indices) View.VISIBLE else View.GONE
                if (position == 0 || !session.role.canControl) return
                if (position <= ScalePreset.values().size) {
                    val preset = ScalePreset.values()[position - 1]
                    applyPreset(preset.displayName, preset.notes)
                } else if (customIndex in customPresets.indices) {
                    val preset = customPresets[customIndex]
                    applyPreset(preset.name, preset.notes.toIntArray())
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun refreshPresetSelector(selection: Int = 0) {
        val labels = listOf(getString(R.string.preset_choose)) +
            ScalePreset.values().map { it.displayName } +
            customPresets.map { getString(R.string.preset_custom, it.name) }
        binding.spPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spPreset.setSelection(selection, false)
    }

    private fun showPresetEditor(index: Int?) {
        if (!session.role.canControl) return
        val existing = index?.let { customPresets.getOrNull(it) }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val hintView = TextView(this).apply { setText(R.string.preset_hint); setTextColor(ContextCompat.getColor(context, R.color.text_secondary)) }
        content.addView(hintView)
        content.addView(TextView(this).apply { setText(R.string.preset_name); setPadding(0, dp(14), 0, 0) })
        val nameInput = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.preset_name)
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            setText(existing?.name.orEmpty())
        }
        content.addView(nameInput)
        val initialNotes = existing?.notes ?: notes.toList()
        val frequencyInputs = initialNotes.mapIndexed { i, frequency ->
            content.addView(TextView(this).apply { text = getString(R.string.preset_frequency, i + 1); setPadding(0, dp(10), 0, 0) })
            EditText(this).apply {
                hint = getString(R.string.preset_frequency, i + 1)
                contentDescription = hint
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setText(frequency.toString())
                content.addView(this)
            }
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.preset_add else R.string.preset_edit)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.preset_save, null)
        if (existing != null) builder.setNeutralButton(R.string.delete, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                var valid = true
                if (name.isEmpty() || name.length > 40) {
                    nameInput.error = getString(R.string.preset_invalid_name); valid = false
                } else if (customPresets.withIndex().any { it.index != index && it.value.name.equals(name, ignoreCase = true) }) {
                    nameInput.error = getString(R.string.preset_duplicate); valid = false
                }
                val values = frequencyInputs.map { input ->
                    val value = input.text.toString().toIntOrNull()
                    if (value == null || value !in Protocol.MIN_FREQ_HZ..Protocol.MAX_FREQ_HZ) {
                        input.error = getString(R.string.preset_invalid_frequency); valid = false
                    }
                    value ?: 0
                }
                if (valid) {
                    val preset = CustomPreset(name, values)
                    val savedIndex = if (index == null) { customPresets.add(preset); customPresets.lastIndex }
                        else { customPresets[index] = preset; index }
                    presetRepository.save(customPresets)
                    refreshPresetSelector(1 + ScalePreset.values().size + savedIndex)
                    applyPreset(preset.name, preset.notes.toIntArray())
                    binding.btnEditPreset.visibility = View.VISIBLE
                    dialog.dismiss()
                }
            }
            if (existing != null && index != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(this).setMessage(getString(R.string.preset_delete_confirm, existing.name))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            customPresets.removeAt(index)
                            presetRepository.save(customPresets)
                            refreshPresetSelector()
                            binding.btnEditPreset.visibility = View.GONE
                            dialog.dismiss()
                        }.show()
                }
            }
        }
        dialog.show()

    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        link?.shutdown()
        tone.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Conexión

    private fun onConnectClicked() {
        val bt = adapter ?: return
        if (!BtPermissions.hasAll(this, asServer = false)) {
            BtPermissions.request(this, asServer = false); return
        }
        if (!bt.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return
        }
        showDevicePicker()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BtPermissions.REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) onConnectClicked()
            else toast(getString(R.string.permission_denied))
        }
    }

    private fun showDevicePicker() {
        val bt = adapter ?: return
        val devices: List<BluetoothDevice> = bt.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_paired_title)
                .setMessage(R.string.no_paired_msg)
                .setPositiveButton(R.string.open_settings) { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val last = session.lastDeviceAddress
        val sorted = devices.sortedByDescending { it.address == last }
        val names = sorted.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_device)
            .setItems(names) { _, which -> askKeyThenConnect(sorted[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askKeyThenConnect(device: BluetoothDevice) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.device_key_hint)
            setText(session.deviceKey.orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.device_key_title)
            .setMessage(R.string.device_key_msg)
            .setView(input)
            .setPositiveButton(R.string.connect) { _, _ ->
                val key = input.text.toString().trim()
                if (!InputValidator.isValidDeviceKey(key)) {
                    toast(getString(R.string.invalid_device_key)); return@setPositiveButton
                }
                session.deviceKey = key
                session.lastDeviceAddress = device.address
                connect(device)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connect(device: BluetoothDevice) {
        val bt = adapter ?: return
        link?.shutdown()
        authenticated = false
        deviceName = device.name ?: device.address
        link = BluetoothLink(bt, this).also { it.connect(device) }
    }

    // ---------------------------------------------------------------- BluetoothLink.Listener

    override fun onStateChanged(state: BluetoothLink.State, detail: String?) {
        renderState(state, detail)
        when (state) {
            BluetoothLink.State.CONNECTED -> {
                events.log(EventType.CONEXION, detail, session.username, deviceName)
                appendLog("Conectado a $detail. Autenticando…")
                lastRx = System.currentTimeMillis()
                link?.send(Protocol.authFrame(session.deviceKey.orEmpty()))
            }
            BluetoothLink.State.DISCONNECTED -> {
                if (authenticated || detail != null) {
                    events.log(EventType.DESCONEXION, detail, session.username, deviceName)
                }
                authenticated = false
                handler.removeCallbacks(heartbeat)
                detail?.let { appendLog(it) }
            }
            else -> Unit
        }
    }

    override fun onLineReceived(line: String) {
        val payload = Protocol.parseFrame(line)
        if (payload == null) {
            invalidFrames++
            binding.tvInvalid.text = getString(R.string.invalid_frames, invalidFrames)
            events.log(EventType.TRAMA_INVALIDA, line.take(40), session.username, deviceName)
            appendLog("⚠ Trama rechazada: ${line.take(40)}")
            return
        }
        lastRx = System.currentTimeMillis()
        when (val msg = Protocol.decodeDeviceMessage(payload)) {
            DeviceMessage.AuthOk -> {
                authenticated = true
                events.log(EventType.AUTH_DISPOSITIVO, "Autenticación exitosa", session.username, deviceName)
                appendLog("✔ Arpa autenticada")
                renderState(BluetoothLink.State.CONNECTED, deviceName)
                handler.removeCallbacks(heartbeat)
                handler.post(heartbeat)
                if (session.role.canControl) syncAllToDevice()
            }
            DeviceMessage.AuthFail -> {
                events.log(EventType.AUTH_DISPOSITIVO, "Clave rechazada", session.username, deviceName)
                appendLog("✖ Clave del arpa incorrecta")
                toast(getString(R.string.device_key_rejected))
                link?.close()
            }
            DeviceMessage.AuthLocked -> {
                events.log(EventType.AUTH_DISPOSITIVO, "Dispositivo bloqueado por intentos", session.username, deviceName)
                appendLog("✖ El arpa bloqueó la autenticación temporalmente")
                link?.close()
            }
            DeviceMessage.AuthRequired -> {
                authenticated = false
                appendLog("El arpa solicita autenticarse nuevamente")
                link?.send(Protocol.authFrame(session.deviceKey.orEmpty()))
            }
            DeviceMessage.Pong -> {
                val rtt = System.currentTimeMillis() - lastPingSent
                binding.tvLatency.text = getString(R.string.latency, rtt)
                if (authenticated) renderState(BluetoothLink.State.CONNECTED, deviceName)
            }
            is DeviceMessage.AckNote -> {
                notes[msg.string - 1] = msg.freqHz
                session.savedNotes = notes
                updateNoteLabels()
                val label = Protocol.noteLabelFor(msg.freqHz)
                events.log(EventType.NOTA_CONFIG, "Cuerda ${msg.string} → $label (${msg.freqHz} Hz)", session.username, deviceName)
                appendLog("✔ Cuerda ${msg.string} asignada a $label (${msg.freqHz} Hz)")
            }
            DeviceMessage.AckAllNotes -> {
                val desc = notes.joinToString(",") { Protocol.noteLabelFor(it) }
                events.log(EventType.NOTA_CONFIG, "6 cuerdas: $desc", session.username, deviceName)
                appendLog("✔ 6 notas sincronizadas en el arpa")
            }
            is DeviceMessage.AckEffect -> {
                currentEffect = msg.effect
                session.savedEffect = msg.effect
                updateNoteLabels()
                events.log(EventType.EFECTO, msg.effect.displayName, session.username, deviceName)
                appendLog("✔ Efecto aplicado: ${msg.effect.displayName}")
            }
            DeviceMessage.AckCalibrated -> {
                events.log(EventType.CALIBRACION, "Recalibración de 6 LDR completada", session.username, deviceName)
                appendLog("✔ 6 sensores láser calibrados")
            }
            DeviceMessage.AckTest -> appendLog("✔ Tono de prueba emitido en el arpa")
            is DeviceMessage.Error -> appendLog("✖ Error del arpa: ${msg.code}")
            is DeviceMessage.NoteActivated -> onNote(msg.string)
            is DeviceMessage.Unknown -> appendLog("? ${msg.payload}")
        }
    }

    // ---------------------------------------------------------------- Comandos y configuración de notas/efectos

    private fun openNotePicker(stringNumber: Int) {
        if (!session.role.canControl) { toast(getString(R.string.not_authorized)); return }
        val catalog = Protocol.NOTE_CATALOG
        val items = catalog.map { "${it.label}  —  ${it.freqHz} Hz" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pick_note_title, stringNumber))
            .setItems(items) { _, which ->
                val chosen = catalog[which]
                binding.spPreset.setSelection(0)
                notes[stringNumber - 1] = chosen.freqHz
                session.savedNotes = notes
                updateNoteLabels()
                if (binding.swLocalAudio.isChecked) tone.play(chosen.freqHz, currentEffect, 280)
                if (authenticated) {
                    link?.send(Protocol.stringNoteFrame(stringNumber, chosen.freqHz))
                    appendLog("→ Cuerda $stringNumber = ${chosen.label} (${chosen.freqHz} Hz)…")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyPreset(name: String, presetNotes: IntArray) {
        if (!session.role.canControl) { toast(getString(R.string.not_authorized)); return }
        notes = presetNotes.copyOf()
        session.savedNotes = notes
        updateNoteLabels()
        if (authenticated) {
            link?.send(Protocol.allNotesFrame(notes))
            appendLog("→ Aplicando $name…")
        }
    }

    private fun selectEffect(effect: SoundEffect) {
        if (!session.role.canControl) { toast(getString(R.string.not_authorized)); return }
        currentEffect = effect
        session.savedEffect = effect
        updateNoteLabels()
        tone.play(notes[0], currentEffect, 480)
        if (authenticated) {
            link?.send(Protocol.effectFrame(effect))
            appendLog("→ Cambiando efecto a ${effect.displayName}…")
        }
    }

    private fun syncAllToDevice() {
        if (!session.role.canControl) { toast(getString(R.string.not_authorized)); return }
        if (!authenticated) { toast(getString(R.string.connect_first)); return }
        link?.send(Protocol.allNotesFrame(notes))
        link?.send(Protocol.effectFrame(currentEffect))
        appendLog("→ Sincronizando 6 notas y efecto ${currentEffect.displayName}…")
    }

    private fun sendCalibrate() {
        if (!session.role.canControl) { toast(getString(R.string.not_authorized)); return }
        if (!authenticated) { toast(getString(R.string.connect_first)); return }
        link?.send(Protocol.calibrateFrame())
        appendLog("→ Solicitando calibración de 6 sensores LDR…")
    }

    private fun sendTestTone() {
        if (!session.role.canControl) { toast(getString(R.string.not_authorized)); return }
        if (!authenticated) {
            tone.play(440, currentEffect, 250)
            appendLog("♪ Tono de prueba local (440 Hz · ${currentEffect.displayName})")
            return
        }
        if (binding.swLocalAudio.isChecked) tone.play(440, currentEffect, 250)
        link?.send(Protocol.testFrame())
        appendLog("→ Enviando tono de prueba al arpa…")
    }

    private fun previewLocalNote(stringNumber: Int) {
        val idx = stringNumber - 1
        val freq = notes[idx]
        val noteName = Protocol.noteLabelFor(freq)
        tone.play(freq, currentEffect)
        animateString(idx, stringNumber, noteName)
    }

    // ---------------------------------------------------------------- Visualización

    private fun onNote(stringNumber: Int) {
        val idx = stringNumber - 1
        counters[idx]++
        refreshCounters()
        val freq = notes[idx]
        val noteName = Protocol.noteLabelFor(freq)
        if (binding.swLocalAudio.isChecked) tone.play(freq, currentEffect)
        events.log(EventType.NOTA, "$stringNumber ($noteName $freq Hz · ${currentEffect.displayName})", session.username, deviceName)
        animateString(idx, stringNumber, noteName)
    }

    private fun animateString(idx: Int, stringNumber: Int, noteName: String) {
        val v = stringViews[idx]
        v.setBackgroundColor(ContextCompat.getColor(this, R.color.laser_red))
        v.scaleX = 1.6f
        v.animate().scaleX(1f).setDuration(350).withEndAction {
            v.setBackgroundColor(ContextCompat.getColor(this, R.color.string_idle))
        }.start()
        binding.tvLastNote.text = getString(R.string.last_note, stringNumber, noteName, timeFmt.format(Date()))
    }

    private fun updateNoteLabels() {
        stringLabels.forEachIndexed { i, tv ->
            val f = notes[i]
            tv.text = "${Protocol.noteLabelFor(f)}\n$f"
        }
        noteButtons.forEachIndexed { i, btn ->
            val f = notes[i]
            btn.text = getString(R.string.note_button_text, i + 1, Protocol.noteLabelFor(f), f)
        }
        binding.tvEffect.text = getString(R.string.effect_label, currentEffect.displayName)
    }

    private fun refreshCounters() {
        counterViews.forEachIndexed { i, tv -> tv.text = counters[i].toString() }
    }

    private fun renderState(state: BluetoothLink.State, detail: String?) {
        val connected = state == BluetoothLink.State.CONNECTED
        when (state) {
            BluetoothLink.State.DISCONNECTED -> setStatus(getString(R.string.status_disconnected), R.color.text_secondary)
            BluetoothLink.State.CONNECTING -> setStatus(getString(R.string.status_connecting, detail ?: ""), R.color.warning)
            BluetoothLink.State.LISTENING -> setStatus(getString(R.string.status_listening), R.color.warning)
            BluetoothLink.State.CONNECTED ->
                if (authenticated) setStatus(getString(R.string.status_authenticated, deviceName ?: ""), R.color.laser_green)
                else setStatus(getString(R.string.status_authenticating), R.color.warning)
        }
        binding.btnConnect.visibility = if (connected || state == BluetoothLink.State.CONNECTING) View.GONE else View.VISIBLE
        binding.btnDisconnect.visibility = if (connected) View.VISIBLE else View.GONE

        val canEditLocally = session.role.canControl
        val canSendRemote = connected && authenticated && canEditLocally
        binding.spEffect.isEnabled = canEditLocally
        binding.spPreset.isEnabled = canEditLocally
        binding.btnAddPreset.isEnabled = canEditLocally
        binding.btnEditPreset.isEnabled = canEditLocally
        noteButtons.forEach { it.isEnabled = canEditLocally }
        binding.btnSync.isEnabled = canSendRemote
        binding.btnCalibrate.isEnabled = canSendRemote
        binding.btnTestTone.isEnabled = canEditLocally
    }

    private fun setStatus(text: String, colorRes: Int) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun appendLog(msg: String) {
        logLines.addFirst("[${timeFmt.format(Date())}] $msg")
        while (logLines.size > 40) logLines.removeLast()
        binding.tvLog.text = logLines.joinToString("\n")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

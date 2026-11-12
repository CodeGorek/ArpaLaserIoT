package com.arpalaser.iot.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.arpalaser.iot.R
import com.arpalaser.iot.data.EventRepository
import com.arpalaser.iot.data.EventType
import com.arpalaser.iot.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Consulta de los datos almacenados en SQLite: telemetría, comandos y bitácora de seguridad. */
class HistoryActivity : SecureActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var events: EventRepository
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
    private val filters: List<EventType.Category?> = listOf(null) + EventType.Category.values().toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.menu_history)
        events = EventRepository(this)

        binding.spFilter.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            filters.map { it?.label ?: getString(R.string.filter_all) }
        )
        binding.spFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = load()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.btnClear.visibility = if (session.role.canDeleteHistory) View.VISIBLE else View.GONE
        binding.btnClear.setOnClickListener { confirmClear() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun load() {
        val category = filters[binding.spFilter.selectedItemPosition]
        val list = events.recent(category)
        val rows = list.map { e ->
            buildString {
                append(fmt.format(Date(e.timestamp))).append("  ").append(e.type.label)
                e.detail?.let { append(": ").append(it) }
                append("\n   usuario: ").append(e.username ?: "-")
                e.device?.let { append("  ·  equipo: ").append(it) }
            }
        }
        binding.lvEvents.adapter = ArrayAdapter(this, R.layout.item_event, rows)
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        val c = events.noteCounts()
        binding.tvNoteStats.text = getString(R.string.note_stats, c[0], c[1], c[2], c[3], c[4], c[5], c.sum())
    }

    private fun confirmClear() {
        if (!session.role.canDeleteHistory) return   // verificación de autorización
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (!session.role.canDeleteHistory) return@setPositiveButton
                events.clearAll()
                // El propio borrado queda auditado (trazabilidad).
                events.log(EventType.HISTORIAL_BORRADO, null, session.username)
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

package com.arpalaser.iot.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.arpalaser.iot.R
import com.arpalaser.iot.data.EventRepository
import com.arpalaser.iot.data.EventType
import com.arpalaser.iot.databinding.ActivityMainBinding

/** Panel principal: muestra las opciones permitidas según el rol del usuario. */
class MainActivity : SecureActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var events: EventRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        events = EventRepository(this)

        binding.cardControl.setOnClickListener { startActivity(Intent(this, ControlActivity::class.java)) }
        binding.cardSimulator.setOnClickListener { startActivity(Intent(this, SimulatorActivity::class.java)) }
        binding.cardHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.btnLogout.setOnClickListener { logout() }
    }

    override fun onResume() {
        super.onResume()
        if (!session.isValid()) return
        val role = session.role
        binding.tvWelcome.text = getString(R.string.welcome, session.username)
        binding.tvRole.text = getString(R.string.role_label, role.displayName)
        binding.cardSimulator.visibility = if (role.canControl) View.VISIBLE else View.GONE
        binding.tvControlDesc.text = getString(if (role.canControl) R.string.control_desc else R.string.monitor_desc)

        val counts = events.noteCounts()
        binding.tvStats.text = getString(R.string.stats_summary, counts.sum())
    }

    private fun logout() {
        events.log(EventType.LOGOUT, null, session.username)
        session.end()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}

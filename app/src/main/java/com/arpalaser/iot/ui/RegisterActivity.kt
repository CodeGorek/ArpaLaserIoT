package com.arpalaser.iot.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arpalaser.iot.R
import com.arpalaser.iot.data.EventRepository
import com.arpalaser.iot.data.EventType
import com.arpalaser.iot.data.UserRepository
import com.arpalaser.iot.databinding.ActivityRegisterBinding
import com.arpalaser.iot.security.InputValidator
import com.arpalaser.iot.security.Role

/** Autorregistro de cuentas de músico. */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var users: UserRepository
    private lateinit var events: EventRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        users = UserRepository(this)
        events = EventRepository(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.create_musician)

        binding.btnRegister.setOnClickListener { register() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun register() {
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        val confirm = binding.etConfirm.text?.toString().orEmpty()
        binding.tilUsername.error = null
        binding.tilPassword.error = null
        binding.tilConfirm.error = null

        if (!InputValidator.isValidUsername(username)) {
            binding.tilUsername.error = getString(R.string.invalid_username); return
        }
        InputValidator.passwordError(password)?.let { binding.tilPassword.error = it; return }
        if (password != confirm) { binding.tilConfirm.error = getString(R.string.password_mismatch); return }

        binding.btnRegister.isEnabled = false
        val chars = password.toCharArray()
        Thread {
            val id = try {
                users.registerMusician(username, chars)
            } finally {
                chars.fill('\u0000')
            }
            if (id >= 0) events.log(EventType.USUARIO_CREADO, Role.MUSICO.name, username)
            runOnUiThread {
                binding.btnRegister.isEnabled = true
                if (id < 0) {
                    binding.tilUsername.error = getString(R.string.username_taken)
                } else {
                    Toast.makeText(this, getString(R.string.user_created, username), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }
}

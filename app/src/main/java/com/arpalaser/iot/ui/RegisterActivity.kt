package com.arpalaser.iot.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arpalaser.iot.R
import com.arpalaser.iot.data.EventRepository
import com.arpalaser.iot.data.EventType
import com.arpalaser.iot.data.UserRepository
import com.arpalaser.iot.databinding.ActivityRegisterBinding
import com.arpalaser.iot.security.InputValidator
import com.arpalaser.iot.security.Role
import com.arpalaser.iot.security.SessionManager

/**
 * Registro de usuarios.
 *  - Modo "bootstrap": solo cuando la BD no tiene usuarios; crea la cuenta ADMIN inicial.
 *  - Modo administrador: requiere sesión activa con permiso canManageUsers; permite elegir el rol.
 */
class RegisterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOTSTRAP = "bootstrap"
    }

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var users: UserRepository
    private lateinit var events: EventRepository
    private lateinit var session: SessionManager
    private var bootstrap = false
    private val roles = Role.values()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        users = UserRepository(this)
        events = EventRepository(this)
        session = SessionManager.get(this)

        bootstrap = intent.getBooleanExtra(EXTRA_BOOTSTRAP, false)

        // Verificación de autorización (no basta con ocultar el botón en la UI).
        val authorized = if (bootstrap) users.count() == 0
        else session.isValid() && session.role.canManageUsers
        if (!authorized) {
            Toast.makeText(this, R.string.not_authorized, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(if (bootstrap) R.string.create_admin else R.string.register_user)

        if (bootstrap) {
            binding.spRole.visibility = View.GONE
            binding.tvRoleLabel.text = getString(R.string.role_admin_fixed)
        } else {
            binding.spRole.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                roles.map { "${it.displayName} (${it.name})" }
            )
            binding.spRole.setSelection(Role.MUSICO.ordinal)
        }

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

        val role = if (bootstrap) Role.ADMIN else roles[binding.spRole.selectedItemPosition]
        binding.btnRegister.isEnabled = false
        val chars = password.toCharArray()
        Thread {
            val id = users.register(username, chars, role)
            chars.fill('\u0000')
            runOnUiThread {
                binding.btnRegister.isEnabled = true
                if (id < 0) {
                    binding.tilUsername.error = getString(R.string.username_taken)
                } else {
                    val actor = if (bootstrap) username else session.username
                    events.log(EventType.USUARIO_CREADO, "$username (${role.name})", actor)
                    Toast.makeText(this, getString(R.string.user_created, username), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }
}

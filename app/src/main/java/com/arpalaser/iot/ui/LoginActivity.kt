package com.arpalaser.iot.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.arpalaser.iot.R
import com.arpalaser.iot.data.EventRepository
import com.arpalaser.iot.data.EventType
import com.arpalaser.iot.data.LoginResult
import com.arpalaser.iot.data.UserRepository
import com.arpalaser.iot.databinding.ActivityLoginBinding
import com.arpalaser.iot.security.SessionManager

/** Pantalla de autenticación local (usuario + contraseña contra SQLite con hash PBKDF2). */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXPIRED = "expired"
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var users: UserRepository
    private lateinit var events: EventRepository
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        users = UserRepository(this)
        events = EventRepository(this)
        session = SessionManager.get(this)

        if (session.isValid()) {
            goToMain()
            return
        }
        if (intent.getBooleanExtra(EXTRA_EXPIRED, false)) {
            binding.tvMessage.text = getString(R.string.session_expired)
            binding.tvMessage.visibility = View.VISIBLE
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.btnCreateAdmin.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java).putExtra(RegisterActivity.EXTRA_BOOTSTRAP, true))
        }
    }

    override fun onResume() {
        super.onResume()
        // Sin usuarios aún: se permite crear SOLO la primera cuenta (administrador).
        // Las siguientes cuentas solo las crea un administrador desde el menú principal.
        val noUsers = users.count() == 0
        binding.btnCreateAdmin.visibility = if (noUsers) View.VISIBLE else View.GONE
        binding.tvFirstRun.visibility = if (noUsers) View.VISIBLE else View.GONE
    }

    private fun attemptLogin() {
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty().toCharArray()
        binding.tilUsername.error = null
        binding.tilPassword.error = null
        if (username.isEmpty()) { binding.tilUsername.error = getString(R.string.required); return }
        if (password.isEmpty()) { binding.tilPassword.error = getString(R.string.required); return }

        setLoading(true)
        // El hash PBKDF2 es costoso a propósito: se ejecuta fuera del hilo principal.
        Thread {
            val result = users.login(username, password)
            password.fill('\u0000')   // se borra la contraseña de memoria
            runOnUiThread { handleResult(username, result) }
        }.start()
    }

    private fun handleResult(username: String, result: LoginResult) {
        setLoading(false)
        when (result) {
            is LoginResult.Success -> {
                session.start(result.user.id, result.user.username, result.user.role)
                events.log(EventType.LOGIN_OK, "Rol ${result.user.role.name}", result.user.username)
                binding.etPassword.text?.clear()
                goToMain()
            }
            is LoginResult.InvalidCredentials -> {
                events.log(EventType.LOGIN_FALLIDO, "Credenciales inválidas", username)
                // Mensaje genérico: no revela si el usuario existe.
                showMessage(getString(R.string.invalid_credentials, result.remainingAttempts))
            }
            is LoginResult.Locked -> {
                events.log(EventType.LOGIN_FALLIDO, "Cuenta bloqueada temporalmente", username)
                showMessage(getString(R.string.account_locked, result.secondsLeft))
            }
        }
    }

    private fun showMessage(msg: String) {
        binding.tvMessage.text = msg
        binding.tvMessage.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

package com.arpalaser.iot.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.arpalaser.iot.security.SessionManager

/**
 * Actividad base para todas las pantallas que requieren sesión iniciada.
 * Si la sesión no existe o expiró por inactividad, se redirige al login (control de acceso).
 */
abstract class SecureActivity : AppCompatActivity() {

    protected val session: SessionManager by lazy { SessionManager.get(this) }

    override fun onResume() {
        super.onResume()
        if (!session.isValid()) {
            session.end()
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_EXPIRED, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }
        session.touch()
    }

    private var lastTouch = 0L

    override fun onUserInteraction() {
        super.onUserInteraction()
        val now = System.currentTimeMillis()
        if (now - lastTouch > 15_000) {   // se limita la escritura en disco a una vez cada 15 s
            lastTouch = now
            session.touch()
        }
    }
}

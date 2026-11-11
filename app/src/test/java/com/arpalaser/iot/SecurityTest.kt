package com.arpalaser.iot

import com.arpalaser.iot.security.InputValidator
import com.arpalaser.iot.security.PasswordHasher
import com.arpalaser.iot.security.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTest {

    @Test fun hash_isSaltedAndVerifiable() {
        val h1 = PasswordHasher.hash("Arpa2026".toCharArray(), iterations = 1000)
        val h2 = PasswordHasher.hash("Arpa2026".toCharArray(), iterations = 1000)
        assertNotEquals(h1, h2)                        // sal distinta => hash distinto
        assertFalse(h1.contains("Arpa2026"))           // nunca en texto plano
        assertTrue(PasswordHasher.verify("Arpa2026".toCharArray(), h1))
        assertFalse(PasswordHasher.verify("arpa2026".toCharArray(), h1))
    }

    @Test fun verify_rejectsCorruptedHash() {
        assertFalse(PasswordHasher.verify("x".toCharArray(), "basura"))
        assertFalse(PasswordHasher.verify("x".toCharArray(), "10:zz:zz"))
    }

    @Test fun passwordPolicy() {
        assertNotNull(InputValidator.passwordError("corta1A"))
        assertNotNull(InputValidator.passwordError("sinnumeros"))
        assertNotNull(InputValidator.passwordError("SINMINUSCULA1"))
        assertNull(InputValidator.passwordError("Arpa2026"))
    }

    @Test fun usernameAndDeviceKeyValidation() {
        assertTrue(InputValidator.isValidUsername("oscar_p"))
        assertFalse(InputValidator.isValidUsername("a' OR '1'='1"))
        assertTrue(InputValidator.isValidDeviceKey("ArpaIoT-2026"))
        assertFalse(InputValidator.isValidDeviceKey("con*asterisco"))
    }

    @Test fun roles_leastPrivilege() {
        assertTrue(Role.ADMIN.canManageUsers)
        assertFalse(Role.MUSICO.canManageUsers)
        assertFalse(Role.OBSERVADOR.canControl)
        assertFalse(Role.OBSERVADOR.canDeleteHistory)
    }
}

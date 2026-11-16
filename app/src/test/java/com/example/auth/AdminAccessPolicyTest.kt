package com.example.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAccessPolicyTest {
    @Test fun acceptsOnlyVerifiedAdminEmail() {
        assertTrue(AdminAccessPolicy.isAuthorizedAdmin("pavanpatil93@gmail.com"))
        assertTrue(AdminAccessPolicy.isAuthorizedAdmin("  PAvanPatil93@GMAIL.com  "))
        assertFalse(AdminAccessPolicy.isAuthorizedAdmin(null))
        assertFalse(AdminAccessPolicy.isAuthorizedAdmin(""))
        assertFalse(AdminAccessPolicy.isAuthorizedAdmin("employee@gmail.com"))
        assertFalse(AdminAccessPolicy.isAuthorizedAdmin("pavanpatil93@gmail.com.attacker.test"))
        assertFalse(AdminAccessPolicy.isAuthorizedAdmin("other+pavanpatil93@gmail.com"))
    }
}


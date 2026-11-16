package com.example.auth

/**
 * Phase 2 administrator authorization policy.
 *
 * Authentication (proving which Google account signed in) is intentionally kept
 * separate from authorization (deciding whether that verified account may enter
 * TLC-1). The UI must only call [isAuthorizedAdmin] with an email returned by a
 * verified Google/Firebase identity provider.
 */
object AdminAccessPolicy {
    const val ADMIN_EMAIL: String = "pavanpatil93@gmail.com"

    fun isAuthorizedAdmin(verifiedEmail: String?): Boolean =
        verifiedEmail
            ?.trim()
            ?.equals(ADMIN_EMAIL, ignoreCase = true)
            ?: false
}


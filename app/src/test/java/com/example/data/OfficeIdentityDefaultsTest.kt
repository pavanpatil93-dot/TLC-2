package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.UserRole
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfficeIdentityDefaultsTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("real_estate_user_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun unregisteredAndUnknownRoleDoNotBecomeAdmin() {
        assertEquals(UserRole.READ_ONLY, UserSettingsManager(context).userProfile.value.role)
        assertEquals(UserRole.READ_ONLY, UserRole.fromString(null))
        assertEquals(UserRole.READ_ONLY, UserRole.fromString("not an admin"))
    }
    @Test fun employeeRegistrationAndProfileEditingCannotSelfPromote() {
        val settings = UserSettingsManager(context)
        settings.registerEmployee("Test employee", "9876543210")
        assertEquals(UserRole.SALES_EXECUTIVE, settings.userProfile.value.role)
        settings.updateProfile("Test employee", "9876543210", "test@example.invalid", UserRole.ADMIN)
        assertEquals(UserRole.SALES_EXECUTIVE, settings.userProfile.value.role)
        assertEquals(UserRole.SALES_EXECUTIVE, UserSettingsManager(context).userProfile.value.role)
    }
}


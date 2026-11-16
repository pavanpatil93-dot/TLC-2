package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Build04AccessPolicyTest {
    @Test fun allRequiredRolesExist() {
        assertTrue(UserRole.entries.map { it.name }.toSet().containsAll(
            setOf("ADMIN", "COMMERCIAL_MANAGER", "RESIDENTIAL_MANAGER", "SALES_EXECUTIVE", "INVENTORY_MANAGER", "READ_ONLY")
        ))
    }

    @Test fun onlyManagersCanPerformSensitiveActions() {
        assertTrue(UserRole.ADMIN.canPerformSensitiveAction("DELETE_RECORD"))
        assertTrue(UserRole.COMMERCIAL_MANAGER.canPerformSensitiveAction("EXPORT_CONTACTS"))
        assertFalse(UserRole.SALES_EXECUTIVE.canPerformSensitiveAction("DELETE_RECORD"))
        assertFalse(UserRole.READ_ONLY.canPerformSensitiveAction("BULK_EDIT"))
    }

    @Test fun brandScopeNamesAreStable() {
        assertEquals(ActiveBrand.ASCEND_COMMERCIAL, ActiveBrand.fromString("ASCEND COMMERCIAL"))
        assertEquals(ActiveBrand.AUREA_LIVING, ActiveBrand.fromString("AUREA LIVING"))
        assertEquals(ActiveBrand.ALL_BUSINESSES, ActiveBrand.fromString("All Businesses"))
    }
}

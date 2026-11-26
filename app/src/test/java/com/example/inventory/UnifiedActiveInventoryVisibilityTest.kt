package com.example.inventory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SheetsRepository
import com.example.model.ArchiveStatus
import com.example.model.AvailabilityStatus
import com.example.model.Brand
import com.example.model.ImportPreviewRow
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import com.example.model.UserRole
import com.example.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnifiedActiveInventoryVisibilityTest {

    private fun sampleProperty(
        id: String = "prop_test_1",
        code: String = "OFF-KHD-101",
        status: AvailabilityStatus = AvailabilityStatus.NOT_CONFIRMED,
        verificationStatus: VerificationStatus = VerificationStatus.PENDING,
        archiveStatus: ArchiveStatus = ArchiveStatus.ACTIVE
    ): PropertyItem = PropertyItem(
        id = id,
        propertyCode = code,
        projectName = "Gera Imperium Alpha",
        location = "Kharadi",
        unitNumber = "405",
        propertyType = "Office Space",
        tab = InventoryTab.OFF_LEASE,
        carpetAreaSqFt = 1500.0,
        priceOrRent = 125000.0,
        rent = 125000.0,
        status = status,
        verificationStatus = verificationStatus,
        archiveStatus = archiveStatus,
        portfolio = "Commercial",
        transactionType = "Lease"
    )

    @Test
    fun activeInventoryRule_showsPendingAndNotConfirmedSheetRows_andSeparatesClosedAndArchived() {
        val pendingUnconfirmed = sampleProperty(
            status = AvailabilityStatus.NOT_CONFIRMED,
            verificationStatus = VerificationStatus.PENDING,
            archiveStatus = ArchiveStatus.ACTIVE
        )
        val underNegotiationPending = sampleProperty(
            status = AvailabilityStatus.UNDER_NEGOTIATION,
            verificationStatus = VerificationStatus.PENDING,
            archiveStatus = ArchiveStatus.ACTIVE
        )
        val verifiedAvailable = sampleProperty(
            status = AvailabilityStatus.AVAILABLE,
            verificationStatus = VerificationStatus.VERIFIED,
            archiveStatus = ArchiveStatus.ACTIVE
        )

        assertTrue(InventoryQuality.isActiveInventory(pendingUnconfirmed))
        assertFalse(InventoryQuality.isClosedOrArchived(pendingUnconfirmed))
        assertTrue(InventoryQuality.isActiveInventory(underNegotiationPending))
        assertTrue(InventoryQuality.isActiveInventory(verifiedAvailable))

        // Closed & Archived units belong in Archive / Closed
        val sold = sampleProperty(status = AvailabilityStatus.SOLD, verificationStatus = VerificationStatus.VERIFIED)
        val leased = sampleProperty(status = AvailabilityStatus.LEASED, verificationStatus = VerificationStatus.VERIFIED)
        val withdrawn = sampleProperty(status = AvailabilityStatus.WITHDRAWN, verificationStatus = VerificationStatus.VERIFIED)
        val notAvailable = sampleProperty(status = AvailabilityStatus.NOT_AVAILABLE, verificationStatus = VerificationStatus.VERIFIED)
        val archived = sampleProperty(
            status = AvailabilityStatus.AVAILABLE,
            verificationStatus = VerificationStatus.VERIFIED,
            archiveStatus = ArchiveStatus.ARCHIVED
        )

        assertTrue(InventoryQuality.isClosedOrArchived(sold))
        assertTrue(InventoryQuality.isClosedOrArchived(leased))
        assertTrue(InventoryQuality.isClosedOrArchived(withdrawn))
        assertTrue(InventoryQuality.isClosedOrArchived(notAvailable))
        assertTrue(InventoryQuality.isClosedOrArchived(archived))
        assertFalse(InventoryQuality.isActiveInventory(sold))
        assertFalse(InventoryQuality.isActiveInventory(archived))
    }

    @Test
    fun clientMatchingEngine_includesPendingAndNotConfirmedActiveSheetProperties_excludesClosedAndArchived() {
        val criteriaAvailable = SmartFilterCriteria(
            brand = Brand.COMMERCIAL,
            availabilityFilter = AvailabilityFilterOption.AVAILABLE
        )
        val pendingUnconfirmed = sampleProperty(
            status = AvailabilityStatus.NOT_CONFIRMED,
            verificationStatus = VerificationStatus.PENDING
        )
        val leased = sampleProperty(
            id = "prop_leased",
            status = AvailabilityStatus.LEASED,
            verificationStatus = VerificationStatus.VERIFIED
        )
        val archived = sampleProperty(
            id = "prop_arch",
            status = AvailabilityStatus.AVAILABLE,
            verificationStatus = VerificationStatus.VERIFIED,
            archiveStatus = ArchiveStatus.ARCHIVED
        )

        assertTrue(
            PropertyMatchingEngine.passesMandatoryConstraints(
                property = pendingUnconfirmed,
                criteria = criteriaAvailable,
                userRole = UserRole.ADMIN,
                allowedBrands = setOf(Brand.COMMERCIAL, Brand.RESIDENTIAL)
            )
        )
        assertFalse(
            PropertyMatchingEngine.passesMandatoryConstraints(
                property = leased,
                criteria = criteriaAvailable,
                userRole = UserRole.ADMIN,
                allowedBrands = setOf(Brand.COMMERCIAL, Brand.RESIDENTIAL)
            )
        )
        assertFalse(
            PropertyMatchingEngine.passesMandatoryConstraints(
                property = archived,
                criteria = criteriaAvailable,
                userRole = UserRole.ADMIN,
                allowedBrands = setOf(Brand.COMMERCIAL, Brand.RESIDENTIAL)
            )
        )
    }

    @Test
    fun sheetSyncMerge_blankVerificationCellDoesNotResetVerifiedPropertyToPending() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SheetsRepository(context, startForegroundSync = false)
        val uniqueCode = "TLC-SYNC-${System.currentTimeMillis()}"

        // 1. Seed a synced Verified + Available property (as if previously verified & synced with Sheet)
        val initialVerifiedRow = ImportPreviewRow(
            rowNumber = 2,
            propertyCode = uniqueCode,
            projectName = "Gera Imperium Alpha",
            location = "Kharadi",
            unitNumber = "405",
            propertyType = "Office Space",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1500.0,
            priceOrRent = 125000.0,
            rent = 125000.0,
            status = AvailabilityStatus.AVAILABLE,
            hasExplicitStatus = true,
            verificationStatus = VerificationStatus.VERIFIED,
            hasExplicitVerificationStatus = true,
            isDuplicateCode = false,
            isValid = true,
            portfolio = "Commercial",
            transactionType = "Lease"
        )
        repo.importRowsDetailed(listOf(initialVerifiedRow), duplicateHandling = "SYNC_MERGE")
        val seeded = repo.properties.value.first { it.propertyCode == uniqueCode }
        assertEquals(VerificationStatus.VERIFIED, seeded.verificationStatus)
        assertEquals(AvailabilityStatus.AVAILABLE, seeded.status)

        // 2. Simulate app-open Google Sheet pull where Verification Status & Status cells are blank
        val blankSheetRow = initialVerifiedRow.copy(
            projectName = "Gera Imperium Alpha Updated",
            status = AvailabilityStatus.NOT_CONFIRMED,
            hasExplicitStatus = false,
            verificationStatus = VerificationStatus.PENDING,
            hasExplicitVerificationStatus = false,
            isDuplicateCode = true
        )
        repo.importRowsDetailed(listOf(blankSheetRow), duplicateHandling = "SYNC_MERGE")

        val afterBlankPull = repo.properties.value.first { it.propertyCode == uniqueCode }
        // Confirm SYNC_MERGE actually ran (projectName updated) while preserving Verified & Available
        assertEquals("Gera Imperium Alpha Updated", afterBlankPull.projectName)
        assertEquals(VerificationStatus.VERIFIED, afterBlankPull.verificationStatus)
        assertEquals(AvailabilityStatus.AVAILABLE, afterBlankPull.status)

        // 3. Simulate explicit Sheet update marking the property Sold
        val explicitSoldRow = blankSheetRow.copy(
            status = AvailabilityStatus.SOLD,
            hasExplicitStatus = true
        )
        repo.importRowsDetailed(listOf(explicitSoldRow), duplicateHandling = "SYNC_MERGE")
        val afterExplicitSold = repo.properties.value.first { it.propertyCode == uniqueCode }
        assertEquals(AvailabilityStatus.SOLD, afterExplicitSold.status)
        assertTrue(InventoryQuality.isClosedOrArchived(afterExplicitSold))
    }
}

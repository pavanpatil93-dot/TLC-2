package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PropertiesLocalDatabaseRegressionTest {
    private lateinit var context: Context
    private val fileName = "real_estate_inventory_db.json"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf(fileName, "$fileName.bak", "$fileName.new", "ascend_aurea_inventory_db.json")
            .forEach { File(context.filesDir, it).delete() }
    }

    @Test fun missingDetailsRemainUnknownAfterReload() {
        val raw = """[{"id":"legacy-1","propertyCode":"OL-1001"}]"""
        val file = File(context.filesDir, fileName)
        file.writeText(raw)

        val item = PropertiesLocalDatabase.loadProperties(context).single()
        assertEquals("legacy-1", item.id)
        assertEquals("OL-1001", item.propertyCode)
        listOf(item.date, item.location, item.projectName, item.unitNumber,
            item.floor, item.propertyType, item.possession, item.parking,
            item.lastVerificationDate, item.lastVerifiedDateClean, item.syncUpdatedAt)
            .forEach { assertEquals("", it) }
        // Zero is the existing non-null numeric model's unknown sentinel.
        assertEquals(0.0, item.carpetAreaSqFt, 0.0)
        assertEquals(0.0, item.priceOrRent, 0.0)
        assertNull(item.builtUpAreaSqFt)
        assertNull(item.rent)
        assertNull(item.priceSale)
        assertEquals(AvailabilityStatus.NOT_CONFIRMED, item.status)
        assertEquals(raw, file.readText())
    }

    @Test fun suppliedValuesAndOwnerIdentitySurviveSaveReload() {
        val original = PropertyItem(
            id = "legacy-id", propertyCode = "OO-1081", recordId = "original-record",
            date = "11/11/2026", location = "Kharadi", projectName = "Owner project",
            unitNumber = "901", floor = "9", propertyType = "Office",
            tab = InventoryTab.OFF_OUT, carpetAreaSqFt = 1830.0,
            builtUpAreaSqFt = 2500.0, priceOrRent = 27500000.0,
            priceSale = 27500000.0, possession = "Dec 2027",
            status = AvailabilityStatus.AVAILABLE, ownerName = "Owner",
            ownerContact = "TEST-CONTACT", sourceInfo = "Uploaded source",
            parking = "2 covered", lastVerificationDate = "11 Nov 2026",
            lastVerifiedDateClean = "11 Nov 2026", syncUpdatedAt = "2026-11-11"
        )
        assertTrue(PropertiesLocalDatabase.saveProperties(context, listOf(original)))
        val loaded = PropertiesLocalDatabase.loadProperties(context).single()
        assertEquals(original, loaded)
    }

    @Test fun invalidAvailabilityDoesNotBecomeAvailable() {
        File(context.filesDir, fileName).writeText(
            """[{"id":"1","propertyCode":"OL-1","status":"unknown-legacy-value"}]"""
        )
        assertEquals(AvailabilityStatus.NOT_CONFIRMED,
            PropertiesLocalDatabase.loadProperties(context).single().status)
    }

    @Test fun legacyFileStillLoadsWithoutChangingItsContent() {
        val raw = """[{"id":"old","propertyCode":"SL-1007","location":"Mundhwa"}]"""
        val legacy = File(context.filesDir, "ascend_aurea_inventory_db.json")
        legacy.writeText(raw)
        assertEquals("Mundhwa", PropertiesLocalDatabase.loadProperties(context).single().location)
        assertEquals(raw, legacy.readText())
    }

    private fun completeProperty(): PropertyItem {
        val snapshot = PropertyItemSnapshot(
            carpetAreaSqFt = 700.0, builtUpAreaSqFt = 1000.0,
            priceOrRent = 70000.0, priceSale = 15000000.0, rent = 70000.0,
            deposit = 210000.0, roiPercent = 5.6,
            furnishing = FurnishingType.SEMI_FURNISHED, possession = "2026-12",
            status = AvailabilityStatus.NOT_CONFIRMED,
            ownerName = "Previous owner", ownerContact = "OLD-TEST-CONTACT",
            remarks = "Previous terms"
        )
        return PropertyItem(
            id = "complete-id", propertyCode = "PL-TEST-1", date = "2026-11-11",
            location = "Kharadi", projectName = "Test project", unitNumber = "901",
            floor = "9", propertyType = "Office", tab = InventoryTab.PRE_LEASED,
            carpetAreaSqFt = 770.0, builtUpAreaSqFt = 1100.0,
            priceOrRent = 17000000.0, priceSale = 17000000.0, rent = 85000.0,
            deposit = 255000.0, roiPercent = 6.0, possession = "2027-03",
            furnishing = FurnishingType.FULLY_FURNISHED,
            status = AvailabilityStatus.AVAILABLE,
            ownerName = "Test owner", ownerContact = "TEST-CONTACT",
            sourceInfo = "Uploaded test source", remarks = "Current terms",
            listingLink = "https://example.invalid/listing",
            lastVerificationDate = "11 Nov 2026", internalRemarks = "Internal notes",
            images = listOf("content://test/image1", "content://test/image2"),
            floorPlanUrl = "content://test/floor", videoUrl = "content://test/video",
            brochureUrl = "content://test/brochure",
            amenities = listOf("Backup power", "Lift", "Visitor parking"),
            parking = "2 covered", maintenance = 5000.0,
            lat = 18.55, lng = 73.94,
            workstations = 12, cabins = 2, conferenceRooms = 1, frontageFeet = 20.5,
            floorLevel = "Upper", ceilingHeightFeet = 11.5, isMainRoadFacing = true,
            bhk = "3 BHK", balconies = 2, builderName = "Test developer",
            monthlyRentalIncome = 85000.0, tenantName = "Test tenant",
            leaseDurationYears = 9, lockInPeriodYears = 3, escalationPercent = 15.0,
            escalationIntervalYears = 3, brokerName = "Test broker",
            brokerContact = "BROKER-TEST-CONTACT", rowNumber = 42,
            editHistory = listOf(
                PropertyEditHistory("history-2", "2026-11-11T12:00:00", "Rent revised", snapshot),
                PropertyEditHistory("history-1", "2026-11-10T12:00:00", "Initial terms",
                    snapshot.copy(builtUpAreaSqFt = null, priceSale = null, rent = null,
                        deposit = null, roiPercent = null, ownerName = null,
                        ownerContact = null, remarks = null))
            ),
            attachments = listOf(PropertyAttachment(
                id = "attachment-1", name = "Floor plan", type = AttachmentType.FLOOR_PLAN,
                localUriOrPath = "content://test/attachment", fileSizeBytes = 12345L,
                uploadDate = "11 Nov 2026", mimeType = "image/png"
            )),
            isSyncedToSheet = false, portfolio = "Commercial",
            transactionType = "Pre-Leased Sale",
            googleMapsUrl = "https://example.invalid/map",
            latitude = 18.56, longitude = 73.95,
            verificationStatus = VerificationStatus.VERIFIED,
            lastVerifiedDateClean = "11 Nov 2026", verifiedBy = "Test employee",
            recordId = "COM-PERMANENT-TEST", syncUpdatedAt = "2026-11-11T12:30:00",
            archiveStatus = ArchiveStatus.ARCHIVED, isAreaEstimated = true,
            lastViewedTimestamp = 1794398400123L
        )
    }

    @Test fun allPropertyFieldsAndNestedHistorySurviveRepeatedReloads() {
        val original = completeProperty()
        assertTrue(PropertiesLocalDatabase.saveProperties(context, listOf(original)))
        val first = PropertiesLocalDatabase.loadProperties(context).single()
        assertEquals(original, first)
        assertTrue(PropertiesLocalDatabase.saveProperties(context, listOf(first)))
        assertEquals(original, PropertiesLocalDatabase.loadProperties(context).single())
    }

    @Test fun pendingAndAcknowledgedSyncFlagsArePreservedSeparately() {
        val pending = completeProperty()
        val acknowledged = pending.copy(id = "ack-id", propertyCode = "PL-TEST-2",
            recordId = "COM-TEST-2", isSyncedToSheet = true)
        assertTrue(PropertiesLocalDatabase.saveProperties(context, listOf(pending, acknowledged)))
        assertEquals(listOf(pending, acknowledged), PropertiesLocalDatabase.loadProperties(context))
    }

    @Test fun legacyMissingFieldsUseUnknownDefaultsAndStayPending() {
        val raw = """[{"id":"legacy","propertyCode":"OL-OLD"}]"""
        val file = File(context.filesDir, fileName)
        file.writeText(raw)
        val loaded = PropertiesLocalDatabase.loadProperties(context).single()
        assertNull(loaded.maintenance)
        assertNull(loaded.monthlyRentalIncome)
        assertNull(loaded.tenantName)
        assertNull(loaded.leaseDurationYears)
        assertNull(loaded.lockInPeriodYears)
        assertNull(loaded.escalationPercent)
        assertNull(loaded.escalationIntervalYears)
        assertTrue(loaded.amenities.isEmpty())
        assertTrue(loaded.editHistory.isEmpty())
        assertFalse(loaded.isSyncedToSheet)
        assertEquals(0L, loaded.lastViewedTimestamp)
        assertEquals(raw, file.readText())
    }

    @Test fun explicitNullNewOptionalFieldsDoNotBecomeZeroOrTextNull() {
        File(context.filesDir, fileName).writeText(
            """[{"id":"null-test","propertyCode":"OL-NULL","maintenance":null,
                "monthlyRentalIncome":null,"tenantName":null,"leaseDurationYears":null,
                "lockInPeriodYears":null,"escalationPercent":null,
                "escalationIntervalYears":null,"lat":null,"lng":null}]"""
        )
        val loaded = PropertiesLocalDatabase.loadProperties(context).single()
        assertNull(loaded.maintenance)
        assertNull(loaded.monthlyRentalIncome)
        assertNull(loaded.tenantName)
        assertNull(loaded.leaseDurationYears)
        assertNull(loaded.lockInPeriodYears)
        assertNull(loaded.escalationPercent)
        assertNull(loaded.escalationIntervalYears)
        assertNull(loaded.lat)
        assertNull(loaded.lng)
    }

    @Test fun legacyCoordinateAliasesRemainReadable() {
        File(context.filesDir, fileName).writeText(
            """[{"id":"geo-old","propertyCode":"OL-GEO","lat":18.55,"lng":73.94}]"""
        )
        val loaded = PropertiesLocalDatabase.loadProperties(context).single()
        assertEquals(18.55, loaded.latitude!!, 0.0)
        assertEquals(73.94, loaded.longitude!!, 0.0)
        assertTrue(PropertiesLocalDatabase.saveProperties(context, listOf(loaded)))
        assertEquals(loaded, PropertiesLocalDatabase.loadProperties(context).single())
    }

    @Test fun versionTwoDoesNotConflateDistinctCoordinateFields() {
        val original = completeProperty().copy(latitude = null, longitude = null)
        assertTrue(PropertiesLocalDatabase.saveProperties(context, listOf(original)))
        val loaded = PropertiesLocalDatabase.loadProperties(context).single()
        assertEquals(original, loaded)
        assertNull(loaded.latitude)
        assertNull(loaded.longitude)
        assertEquals(18.55, loaded.lat!!, 0.0)
    }

    @Test fun atomicBackupRestoresAllExtendedFields() {
        val original = completeProperty()
        assertTrue(PropertiesLocalDatabase.saveProperties(context, listOf(original)))
        val file = File(context.filesDir, fileName)
        assertTrue(file.renameTo(File(context.filesDir, "$fileName.bak")))
        assertEquals(original, PropertiesLocalDatabase.loadProperties(context).single())
    }

}


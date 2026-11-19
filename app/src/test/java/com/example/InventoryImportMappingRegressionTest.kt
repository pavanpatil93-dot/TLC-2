package com.example

import com.example.data.SheetsRepository
import com.example.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InventoryImportMappingRegressionTest {
    private lateinit var repository: SheetsRepository

    @Before fun setUp() { repository = SheetsRepository(context = null) }

    // Same column order as the live three-tab master, with synthetic values.
    private val commercialHeaders = listOf(
        "Property ID", "Legacy Property Code", "Date Added", "Location", "Micro Market",
        "Project / Building", "Unit No", "Floor", "Property Type", "Sub Type",
        "Transaction Type", "Carpet SqFt", "Built-up SqFt", "Chargeable SqFt",
        "Sale Price INR", "Rent INR/Month", "Deposit INR", "ROI Percent",
        "Possession Date", "Furnishing", "Availability Status", "Tenant / Brand",
        "Lease Start", "Lease End", "Lock-in Months", "Escalation Terms",
        "Maintenance Terms", "Parking", "Owner / POC", "Owner Contact", "Source",
        "Source Reference", "Remarks", "Listing URL", "Latitude", "Longitude",
        "Last Verified", "Last Updated", "Verification Status", "Record Status"
    )

    private val residentialHeaders = listOf(
        "Property ID", "Legacy Property Code", "Date Added", "Location", "Micro Market",
        "Project / Building", "Developer", "Tower / Wing", "Unit No", "Floor",
        "Property Type", "Configuration", "Transaction Type", "RERA Number",
        "Carpet SqFt", "Built-up SqFt", "Plot SqFt", "Sale Price INR", "Rent INR/Month",
        "Deposit INR", "Possession Date", "Furnishing", "Availability Status",
        "Parking", "Amenities", "Owner / POC", "Owner Contact", "Source",
        "Source Reference", "Remarks", "Listing URL", "Latitude", "Longitude",
        "Last Verified", "Last Updated", "Verification Status", "Record Status"
    )

    private fun parse(
        fields: Map<String, String>, residential: Boolean = false
    ): ImportPreviewRow {
        val headers = if (residential) residentialHeaders else commercialHeaders
        fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        val csv = headers.joinToString(",") { quote(it) } + "\n" +
            headers.joinToString(",") { quote(fields[it].orEmpty()) }
        return repository.parseCsvOrTextData(csv,
            if (residential) "Residential Inventory" else "Commercial Inventory").single()
    }

    private fun office(vararg extra: Pair<String, String>) = mapOf(
        "Property ID" to "COM-TEST-1", "Legacy Property Code" to "OL-TEST-1",
        "Location" to "Kharadi", "Property Type" to "Office",
        "Transaction Type" to "Lease", "Rent INR/Month" to "80000",
        "Verification Status" to "Needs Verification"
    ) + extra

    @Test fun blankMicroMarketDoesNotEraseLocation() {
        val row = parse(office("Micro Market" to ""))
        assertEquals("Kharadi", row.location)
        assertTrue(row.isValid)
        assertEquals("COM-TEST-1", row.recordId)
        assertEquals("OL-TEST-1", row.propertyCode)
    }

    @Test fun microMarketIsPreservedWithoutReplacingLocation() {
        val row = parse(office("Micro Market" to "East Pune"))
        assertEquals("Kharadi", row.location)
        assertEquals("East Pune", row.unmappedColumns["Micro Market"])
        assertTrue(row.remarks.orEmpty().contains("East Pune"))
    }

    @Test fun microMarketCanSupplyAnOtherwiseMissingLocation() {
        val row = parse(office("Location" to "", "Micro Market" to "Viman Nagar"))
        assertEquals("Viman Nagar", row.location)
        assertTrue(row.isValid)
    }

    @Test fun negativeVerificationLabelsStayPending() {
        listOf("Needs Verification", "Unverified", "Not Verified", "", "Pending").forEach {
            assertEquals(VerificationStatus.PENDING,
                parse(office("Verification Status" to it)).verificationStatus)
        }
        assertEquals(VerificationStatus.VERIFIED,
            parse(office("Verification Status" to "Verified")).verificationStatus)
    }

    @Test fun unavailableLabelsCannotBecomeAvailable() {
        mapOf("Not Available" to AvailabilityStatus.NOT_AVAILABLE, "Unavailable" to AvailabilityStatus.NOT_AVAILABLE,
            "Sold" to AvailabilityStatus.SOLD, "Leased Out" to AvailabilityStatus.LEASED, "Closed" to AvailabilityStatus.NOT_AVAILABLE).forEach { (label, state) ->
            val actual = parse(office("Availability Status" to label)).status
            assertEquals(label, state, actual)
            assertNotEquals(AvailabilityStatus.AVAILABLE, actual)
        }
        assertEquals(AvailabilityStatus.AVAILABLE,
            parse(office("Availability Status" to "Available")).status)
    }

    @Test fun preLeasedDescribesTenancyRatherThanAvailability() {
        val row = parse(office("Legacy Property Code" to "PL-TEST-1",
            "Sub Type" to "Pre-Leased", "Availability Status" to "Pre-Leased",
            "Transaction Type" to "Sale", "Sale Price INR" to "10000000"))
        assertEquals(InventoryTab.PRE_LEASED, row.tab)
        assertEquals("Pre-Leased Sale", row.transactionType)
        assertEquals(AvailabilityStatus.NOT_CONFIRMED, row.status)
    }

    @Test fun residentialSaleWithoutLegacyCodeIsNotAnOfficeLease() {
        val row = parse(mapOf("Property ID" to "RES-TEST-1", "Location" to "Mundhwa",
            "Property Type" to "Apartment", "Configuration" to "3 BHK",
            "Transaction Type" to "Sale", "Sale Price INR" to "20000000"), true)
        assertEquals(InventoryTab.RESI_RESALE, row.tab)
        assertEquals("Residential", row.portfolio)
        assertEquals("Sale", row.transactionType)
        assertEquals("RES-TEST-1", row.recordId)
        assertTrue(row.propertyCode.startsWith("RS-"))
        assertTrue(row.isValid)
    }

    @Test fun residentialRentWithoutLegacyCodeIsClassifiedCorrectly() {
        val row = parse(mapOf("Property ID" to "RES-TEST-2", "Location" to "Kharadi",
            "Property Type" to "Apartment", "Transaction Type" to "Lease",
            "Rent INR/Month" to "40000"), true)
        assertEquals(InventoryTab.RESI_RENT, row.tab)
        assertEquals("Residential", row.portfolio)
        assertEquals("Lease", row.transactionType)
        assertTrue(row.propertyCode.startsWith("RR-"))
    }

    @Test fun retailSaleWithoutLegacyCodeUsesShopSaleCategory() {
        val row = parse(office("Legacy Property Code" to "", "Property Type" to "Showroom",
            "Transaction Type" to "Sale", "Sale Price INR" to "20000000"))
        assertEquals(InventoryTab.SHOP_OUT, row.tab)
        assertEquals("Sale", row.transactionType)
    }

    @Test fun dualTransactionWithoutLegacyCategoryRequiresReview() {
        val row = parse(office("Legacy Property Code" to "",
            "Transaction Type" to "Sale / Lease", "Sale Price INR" to "20000000"))
        assertFalse(row.isValid)
        assertTrue(repository.dataReviewQueue.value.any {
            it.issueReason == "AMBIGUOUS_CLASSIFICATION"
        })
    }

    @Test fun legacyCategoryAndOwnerDetailsArePreserved() {
        val row = parse(office("Legacy Property Code" to "OO-TEST-9",
            "Transaction Type" to "Sale / Lease", "Sale Price INR" to "20000000",
            "Owner / POC" to "Test owner", "Owner Contact" to "TEST-CONTACT"))
        assertEquals(InventoryTab.OFF_OUT, row.tab)
        assertEquals("OO-TEST-9", row.propertyCode)
        assertEquals("Test owner", row.ownerName)
        assertEquals("TEST-CONTACT", row.ownerContact)
        assertTrue(row.isValid)
    }

    @Test fun missingTermsAreNotInvented() {
        val row = parse(office())
        assertNull(row.deposit)
        assertEquals("", row.possession)
        assertEquals("", row.floor)
        assertEquals("", row.unitNumber)
        assertEquals("", row.projectName)
        assertEquals(AvailabilityStatus.NOT_CONFIRMED, row.status)
    }

    @Test fun repeatedImportReusesCodeForPermanentResidentialId() {
        val fields = mapOf("Property ID" to "RES-TEST-STABLE", "Location" to "Kharadi",
            "Property Type" to "Apartment", "Transaction Type" to "Sale",
            "Sale Price INR" to "20000000")
        val first = parse(fields, true)
        assertEquals(1, repository.importRowsDetailed(listOf(first)).addedCount)
        val second = parse(fields, true)
        assertEquals(first.propertyCode, second.propertyCode)
        assertTrue(second.isDuplicateCode)
        val stats = repository.importRowsDetailed(listOf(second))
        assertEquals(0, stats.addedCount)
        assertEquals(1, stats.skippedCount)
        assertEquals(1, repository.properties.value.size)
    }

    @Test fun insertingTypeOfPocBeforeOwnerNeverShiftsCommercialOrResidentialFields() {
        val comHeadersWithPoc = commercialHeaders.toMutableList().apply {
            add(indexOf("Owner / POC"), "Type of POC")
        }
        val resHeadersWithPoc = residentialHeaders.toMutableList().apply {
            add(indexOf("Owner / POC"), "Type of POC")
        }

        fun parseWithHeaders(headers: List<String>, fields: Map<String, String>, tabName: String): ImportPreviewRow {
            fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
            val csv = headers.joinToString(",") { quote(it) } + "\n" +
                headers.joinToString(",") { quote(fields[it].orEmpty()) }
            return repository.parseCsvOrTextData(csv, tabName).single()
        }

        val comRow = parseWithHeaders(
            comHeadersWithPoc,
            office(
                "Type of POC" to "Broker",
                "Owner / POC" to "Rajesh Sharma",
                "Owner Contact" to "9822001122",
                "Source" to "Channel Partner",
                "Remarks" to "Corner unit on 5th floor",
                "Availability Status" to "Available"
            ),
            "Commercial Inventory"
        )
        assertEquals("Broker", comRow.pocType)
        assertEquals("Rajesh Sharma", comRow.ownerName)
        assertEquals("9822001122", comRow.ownerContact)
        assertEquals("Channel Partner", comRow.sourceInfo)
        assertEquals("Corner unit on 5th floor", comRow.remarks)
        assertEquals(AvailabilityStatus.AVAILABLE, comRow.status)

        val resRow = parseWithHeaders(
            resHeadersWithPoc,
            mapOf(
                "Property ID" to "RES-POC-1",
                "Location" to "Kharadi",
                "Property Type" to "Apartment",
                "Configuration" to "3 BHK",
                "Transaction Type" to "Sale",
                "Sale Price INR" to "20000000",
                "Type of POC" to "Developer Sales Team",
                "Owner / POC" to "Gera Sales Desk",
                "Owner Contact" to "9850002211",
                "Source" to "Builder Stock",
                "Remarks" to "Immediate possession",
                "Availability Status" to "Available"
            ),
            "Residential Inventory"
        )
        assertEquals("Developer Sales Team", resRow.pocType)
        assertEquals("Gera Sales Desk", resRow.ownerName)
        assertEquals("9850002211", resRow.ownerContact)
        assertEquals("Builder Stock", resRow.sourceInfo)
        assertEquals("Immediate possession", resRow.remarks)
        assertEquals(AvailabilityStatus.AVAILABLE, resRow.status)

        val stats = repository.importRowsDetailed(listOf(comRow, resRow))
        assertEquals(2, stats.addedCount)
        val savedCom = repository.properties.value.first { it.recordId == "COM-TEST-1" }
        val savedRes = repository.properties.value.first { it.recordId == "RES-POC-1" }
        assertEquals("Broker", savedCom.pocType)
        assertEquals("Developer Sales Team", savedRes.pocType)
    }

    @Test fun arbitraryHeaderOrderMapsTypeOfPocAndContactsByName() {
        val shuffledHeaders = listOf(
            "Remarks", "Owner Contact", "Type of POC", "Location",
            "Property Type", "Transaction Type", "Rent INR/Month",
            "Availability Status", "Owner / POC", "Source", "Property ID", "Legacy Property Code"
        )
        val values = office(
            "Type of POC" to "Property Owner",
            "Owner / POC" to "Meera Kulkarni",
            "Owner Contact" to "9922113344",
            "Source" to "Direct Owner",
            "Remarks" to "Single owner corporate floor",
            "Availability Status" to "On Hold"
        )
        fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        val csv = shuffledHeaders.joinToString(",") { quote(it) } + "\n" +
            shuffledHeaders.joinToString(",") { quote(values[it].orEmpty()) }

        val row = repository.parseCsvOrTextData(csv, "Commercial Inventory").single()
        assertEquals("Property Owner", row.pocType)
        assertEquals("Meera Kulkarni", row.ownerName)
        assertEquals("9922113344", row.ownerContact)
        assertEquals("Direct Owner", row.sourceInfo)
        assertEquals("Single owner corporate floor", row.remarks)
        assertEquals(AvailabilityStatus.ON_HOLD, row.status)
    }
}


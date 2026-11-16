package com.example.model

enum class Brand(val displayName: String, val tagline: String) {
    COMMERCIAL(
        "COMMERCIAL INVENTORY",
        "Commercial Real Estate & Institutional Inventory"
    ),
    RESIDENTIAL(
        "RESIDENTIAL INVENTORY",
        "Residential Living & Luxury Inventory"
    );

    companion object {
        val ASCEND_COMMERCIAL = COMMERCIAL
        val AUREA_LIVING = RESIDENTIAL
        val AUREA_RESIDENTIAL = RESIDENTIAL
    }
}

/** Business scope selected by the signed-in employee. */
enum class ActiveBrand(val label: String) {
    ASCEND_COMMERCIAL("ASCEND COMMERCIAL"),
    AUREA_LIVING("AUREA LIVING"),
    ALL_BUSINESSES("All Businesses");

    companion object {
        fun fromString(value: String?): ActiveBrand = entries.firstOrNull {
            it.name.equals(value?.trim(), true) || it.label.equals(value?.trim(), true)
        } ?: ASCEND_COMMERCIAL
    }
}

enum class InventoryTab(
    val code: String,
    val title: String,
    val brand: Brand,
    val isCommercial: Boolean,
    val isRental: Boolean
) {
    OFF_LEASE("Off Lease", "Commercial Offices (Lease)", Brand.COMMERCIAL, true, true),
    OFF_OUT("Off Out", "Offices (Outright Sale)", Brand.COMMERCIAL, true, false),
    SHOP_LEASE("Shop Lease", "Retail Shops & Showrooms (Lease)", Brand.COMMERCIAL, true, true),
    SHOP_OUT("Shop Out", "Retail Shops (Outright Sale)", Brand.COMMERCIAL, true, false),
    PRE_LEASED("Pre - Leased", "Pre-Leased Investment", Brand.COMMERCIAL, true, false),
    RESI_RENT("Resi Rent", "Residential Rentals", Brand.RESIDENTIAL, false, true),
    RESI_RESALE("Resi Resale", "Residential Resale", Brand.RESIDENTIAL, false, false),
    UND_CONST("Und Const", "Under Construction Projects", Brand.RESIDENTIAL, false, false)
}

enum class AvailabilityStatus(val label: String) {
    AVAILABLE("Available"),
    NOT_AVAILABLE("Not Available"),
    NOT_CONFIRMED("Not Confirmed"), // Legacy value retained for existing files
    ON_HOLD("On Hold"), UNDER_NEGOTIATION("Under Negotiation"),
    TOKEN_RECEIVED("Token Received"), SOLD("Sold"), LEASED("Leased"),
    WITHDRAWN("Withdrawn"), VERIFICATION_PENDING("Verification Pending");

    companion object {
        val lifecycleStates get() = entries.filter { it != NOT_AVAILABLE && it != NOT_CONFIRMED }
        fun parse(value: String): AvailabilityStatus = entries.firstOrNull {
            it.name.equals(value.trim(), true) || it.label.equals(value.trim(), true)
        } ?: when(value.trim().lowercase()) { "leased out" -> LEASED; "unavailable", "closed" -> NOT_AVAILABLE; else -> NOT_CONFIRMED }
    }
}

enum class StandardPortfolio(val label: String) {
    COMMERCIAL("Commercial"),
    RESIDENTIAL("Residential");

    companion object {
        val ASCEND_COMMERCIAL = COMMERCIAL
        val AUREA_RESIDENTIAL = RESIDENTIAL

        fun fromString(value: String?, tab: InventoryTab? = null): StandardPortfolio {
            if (!value.isNullOrBlank()) {
                val v = value.lowercase()
                if (v.contains("commercial") || v.contains("ascend")) return COMMERCIAL
                if (v.contains("residential") || v.contains("aurea")) return RESIDENTIAL
            }
            return if (tab?.brand == Brand.RESIDENTIAL) RESIDENTIAL else COMMERCIAL
        }
    }
}

enum class StandardTransactionType(val label: String) {
    LEASE("Lease"),
    SALE("Sale"),
    PRE_LEASED_SALE("Pre-Leased Sale");

    companion object {
        fun fromString(value: String?, tab: InventoryTab? = null): StandardTransactionType {
            if (!value.isNullOrBlank()) {
                val v = value.lowercase()
                if (v.contains("pre-lease") || v.contains("pre leased")) return PRE_LEASED_SALE
                if (v.contains("lease") || v.contains("rent")) return LEASE
                if (v.contains("sale") || v.contains("outright") || v.contains("resale")) return SALE
            }
            return when {
                tab == InventoryTab.PRE_LEASED -> PRE_LEASED_SALE
                tab?.isRental == true -> LEASE
                else -> SALE
            }
        }
    }
}

enum class StandardPropertyType(val label: String) {
    OFFICE("Office"),
    SHOP("Shop"),
    SHOWROOM("Showroom"),
    WAREHOUSE("Warehouse"),
    COMMERCIAL_LAND("Commercial Land"),
    APARTMENT("Apartment"),
    VILLA("Villa"),
    BUNGALOW("Bungalow"),
    RESIDENTIAL_PLOT("Residential Plot");

    companion object {
        fun fromString(value: String?): StandardPropertyType {
            val v = value?.lowercase()?.trim() ?: ""
            return when {
                v.contains("showroom") -> SHOWROOM
                v.contains("office") -> OFFICE
                v.contains("shop") || v.contains("retail") -> SHOP
                v.contains("warehouse") || v.contains("godown") -> WAREHOUSE
                v.contains("commercial land") -> COMMERCIAL_LAND
                v.contains("villa") -> VILLA
                v.contains("bungalow") -> BUNGALOW
                v.contains("plot") || v.contains("land") -> RESIDENTIAL_PLOT
                v.contains("apartment") || v.contains("flat") || v.contains("condo") || v.contains("resi") -> APARTMENT
                else -> OFFICE
            }
        }
    }
}

enum class StandardFurnishing(val label: String) {
    BARE("Bare"),
    WARM_SHELL("Warm Shell"),
    SEMI_FURNISHED("Semi-Furnished"),
    FULLY_FURNISHED("Fully Furnished"),
    PLUG_AND_PLAY("Plug-and-Play"),
    NOT_MENTIONED("Not Mentioned");

    companion object {
        fun fromString(value: String?): StandardFurnishing {
            val v = value?.lowercase()?.trim() ?: ""
            return when {
                v.contains("plug") -> PLUG_AND_PLAY
                v.contains("full") -> FULLY_FURNISHED
                v.contains("semi") -> SEMI_FURNISHED
                v.contains("warm") -> WARM_SHELL
                v.contains("bare") || v.contains("unfurnish") -> BARE
                v.isBlank() || v.contains("not") -> NOT_MENTIONED
                else -> NOT_MENTIONED
            }
        }
    }
}

enum class VerificationStatus(val label: String) {
    VERIFIED("Verified"),
    PENDING("Pending");

    companion object {
        fun fromString(value: String?): VerificationStatus {
            val v = value?.lowercase()?.trim() ?: ""
            // Only an explicit positive label verifies a record. "Needs Verification",
            // "Unverified" and "Not Verified" must remain pending.
            return if (v == "verified") VERIFIED else PENDING
        }
    }
}

enum class ArchiveStatus(val label: String) {
    ACTIVE("Active"),
    ARCHIVED("Archived");

    companion object {
        fun fromString(value: String?): ArchiveStatus {
            val v = value?.lowercase()?.trim() ?: ""
            return if (v.contains("archive")) ARCHIVED else ACTIVE
        }
    }
}

enum class FurnishingType(val label: String) {
    BARE_SHELL("Bare Shell"),
    WARM_SHELL("Warm Shell"),
    SEMI_FURNISHED("Semi-Furnished"),
    FULLY_FURNISHED("Fully Furnished"),
    UNFURNISHED("Unfurnished")
}

data class PropertyEditHistory(
    val historyId: String,
    val timestamp: String,
    val summary: String,
    val snapshot: PropertyItemSnapshot
)

data class PropertyItemSnapshot(
    val carpetAreaSqFt: Double,
    val builtUpAreaSqFt: Double?,
    val priceOrRent: Double,
    val priceSale: Double?,
    val rent: Double?,
    val deposit: Double?,
    val roiPercent: Double?,
    val furnishing: FurnishingType,
    val possession: String,
    val status: AvailabilityStatus,
    val ownerName: String?,
    val ownerContact: String?,
    val remarks: String?
)

data class PropertyItem(
    val id: String = "",
    // Standard Master Inventory 24 Columns
    val propertyCode: String = "",
    val date: String = "13/10/2026",
    val location: String = "",
    val projectName: String = "", // Project / Building
    val unitNumber: String = "", // Unit / Shop / Office No
    val floor: String = "",
    val propertyType: String = "Commercial", // Property Type
    val tab: InventoryTab = InventoryTab.OFF_LEASE, // Sub Type
    val carpetAreaSqFt: Double = 0.0,
    val builtUpAreaSqFt: Double? = null,
    val priceOrRent: Double = 0.0, // Price / Sale or Rent
    val priceSale: Double? = null, // Price / Sale
    val rent: Double? = null, // Rent
    val deposit: Double? = null,
    val roiPercent: Double? = null, // ROI %
    val possession: String = "",
    val furnishing: FurnishingType = FurnishingType.UNFURNISHED,
    val status: AvailabilityStatus = AvailabilityStatus.NOT_CONFIRMED,
    val ownerName: String? = null, // Owner / Contact Person
    val ownerContact: String? = null, // Contact Number
    val sourceInfo: String? = null, // Source
    val remarks: String? = null, // Remarks
    val listingLink: String? = null, // Listing Link
    val lastVerificationDate: String = "13 Oct 2026", // Last Updated
    val internalRemarks: String? = null, // Remark

    // Photos & Media
    val images: List<String> = emptyList(),
    val floorPlanUrl: String? = null,
    val videoUrl: String? = null,
    val brochureUrl: String? = null,
    val amenities: List<String> = emptyList(),
    val parking: String = "",
    val maintenance: Double? = null,
    val lat: Double? = null,
    val lng: Double? = null,

    // Specific specs
    val workstations: Int? = null,
    val cabins: Int? = null,
    val conferenceRooms: Int? = null,
    val frontageFeet: Double? = null,
    val floorLevel: String? = null,
    val ceilingHeightFeet: Double? = null,
    val isMainRoadFacing: Boolean = false,
    val bhk: String? = null,
    val balconies: Int? = null,
    val builderName: String? = null,
    val monthlyRentalIncome: Double? = null,
    val tenantName: String? = null,
    val leaseDurationYears: Int? = null,
    val lockInPeriodYears: Int? = null,
    val escalationPercent: Double? = null,
    val escalationIntervalYears: Int? = null,
    val brokerName: String? = null,
    val brokerContact: String? = null,
    val rowNumber: Int = 0,
    val editHistory: List<PropertyEditHistory> = emptyList(),
    val attachments: List<PropertyAttachment> = emptyList(),
    val isSyncedToSheet: Boolean = true, // True for imported items, false for locally created/edited

    // Appended Standard 11 Master Management Columns (25 to 35)
    val portfolio: String = "Commercial", // 25. Portfolio
    val transactionType: String = "Lease", // 26. Transaction Type
    val googleMapsUrl: String? = null, // 27. Google Maps URL
    val latitude: Double? = null, // 28. Latitude
    val longitude: Double? = null, // 29. Longitude
    val verificationStatus: VerificationStatus = VerificationStatus.PENDING, // 30. Verification Status
    val lastVerifiedDateClean: String = "", // 31. Last Verified Date
    val verifiedBy: String? = null, // 32. Verified By
    val recordId: String = "", // 33. Record ID (AST-xxxxxx unique permanent identifier)
    val syncUpdatedAt: String = "14 Oct 2026, 09:00 AM", // 34. Sync Updated At
    val archiveStatus: ArchiveStatus = ArchiveStatus.ACTIVE, // 35. Archive Status
    val isAreaEstimated: Boolean = false, // Commercial loading area estimated indicator
    val lastViewedTimestamp: Long = 0L,
    val updatedBy: String = "",
    val updatedAt: String = "",
    val lastPriceConfirmationDate: String = "",
    val ownerConfirmationStatus: String = "Not Mentioned",
    val leaseExpiryDate: String = "",
    val upcomingVacancyDate: String = "",
    val sameUnitGroupId: String = "",
    val duplicateDecisions: Map<String, String> = emptyMap(),
    val projectId: String = "",
    val maintenanceBasis: String = "UNKNOWN",
    val maintenanceIncluded: Boolean? = null,
    val parkingCount: Int? = null,
    val lockInMonths: Int? = null,
    val escalationIntervalMonths: Int? = null,
    val createdAt: String = "",
    val recordVersion: Int = 0,
    val unmappedColumns: Map<String, String> = emptyMap(),
    val fullAddress: String = "",
    val city: String = "",
    val locationPrecision: LocationPrecision = LocationPrecision.UNKNOWN,
    val physicalUnitKey: String = "",
    val hasDualSaleAndLeaseOffer: Boolean = false,
    val saleOfferStatus: AvailabilityStatus? = null,
    val leaseOfferStatus: AvailabilityStatus? = null,
    val saleSourceInfo: String? = null,
    val leaseSourceInfo: String? = null
)

enum class LocationPrecision(val label: String) {
    EXACT_PIN("Exact Pin Recorded"),
    PROJECT("Project-Level Location"),
    LOCALITY("Locality / Micro-Market"),
    UNKNOWN("Search location — exact pin not verified")
}

enum class DestinationMode {
    COORDINATES,
    MAP_LINK,
    ADDRESS_SEARCH,
    UNAVAILABLE
}

data class ResolvedMapDestination(
    val propertyId: String,
    val propertyCode: String,
    val projectName: String,
    val mode: DestinationMode,
    val precision: LocationPrecision,
    val primaryUri: String,
    val directionsUri: String,
    val copyableText: String,
    val hasConflict: Boolean = false,
    val alternateCoordinateUri: String? = null,
    val alternateMapLinkUri: String? = null,
    val warningLabel: String = ""
)

enum class PropertyEditStatus {
    SUCCESS,
    NO_CHANGES,
    VALIDATION_ERROR,
    PERMISSION_DENIED,
    CONFLICT,
    NOT_FOUND,
    STORAGE_ERROR
}

data class PropertyEditOutcome(
    val status: PropertyEditStatus,
    val updatedProperty: PropertyItem? = null,
    val message: String = ""
)

enum class AttachmentType(val displayName: String, val badgeColor: Long) {
    PDF_BROCHURE("PDF Brochure", 0xFFEF4444),
    FLOOR_PLAN("Floor Plan", 0xFF3B82F6),
    DOCUMENT("Document", 0xFFD4AF37),
    VIDEO("Video", 0xFF8B5CF6)
}

data class PropertyAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: AttachmentType,
    val localUriOrPath: String,
    val fileSizeBytes: Long = 0,
    val uploadDate: String = "13 Oct 2026",
    val mimeType: String = "application/pdf"
)


data class UnderConstConfig(
    val id: String,
    val configName: String,
    val carpetAreaSqFt: Double,
    val price: Double,
    val possessionDate: String,
    val floorPlanUrl: String? = null,
    val unitsAvailable: Int = 1
)

data class UnderConstProject(
    val id: String,
    val projectCode: String,
    val projectName: String,
    val location: String,
    val builderName: String,
    val possessionYear: String,
    val mahaReraNumber: String,
    val startingPrice: Double,
    val configurations: List<UnderConstConfig>,
    val photoGallery: List<String> = emptyList(),
    val videoUrl: String? = null,
    val brochureUrl: String? = null,
    val amenities: List<String> = emptyList(),
    val lat: Double? = null,
    val lng: Double? = null,
    val status: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    val lastVerificationDate: String = "10 Oct 2026",
    val builderContactPerson: String? = null,
    val builderPhone: String? = null,
    val internalRemarks: String? = null
)

data class BackupSnapshot(
    val backupId: String,
    val createdAt: String,
    val propertyCount: Int,
    val notes: String,
    val dataJson: String
)

data class ImportPreviewRow(
    val rowNumber: Int,
    val propertyCode: String,
    val projectName: String,
    val location: String,
    val unitNumber: String,
    val floor: String = "",
    val propertyType: String = "Commercial",
    val tab: InventoryTab,
    val carpetAreaSqFt: Double,
    val builtUpAreaSqFt: Double? = null,
    val priceOrRent: Double,
    val priceSale: Double? = null,
    val rent: Double? = null,
    val deposit: Double? = null,
    val roiPercent: Double? = null,
    val possession: String = "",
    val furnishing: FurnishingType = FurnishingType.UNFURNISHED,
    val status: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    val ownerName: String? = null,
    val ownerContact: String? = null,
    val sourceInfo: String? = null,
    val remarks: String? = null,
    val listingLink: String? = null,
    val isDuplicateCode: Boolean,
    val isValid: Boolean,
    val validationMessage: String = "Valid Record",
    // Standard 35-column additions
    val recordId: String = "",
    val portfolio: String = "Ascend Commercial",
    val transactionType: String = "Lease",
    val googleMapsUrl: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val verificationStatus: VerificationStatus = VerificationStatus.PENDING,
    val lastVerifiedDateClean: String = "",
    val verifiedBy: String? = null,
    val isAreaEstimated: Boolean = false,
    val unmappedColumns: Map<String, String> = emptyMap(),
    val conflictResolution: String = "REVIEW" // "SKIP", "UPDATE", "CANCEL", "NEW_CODE"
)

// 35 Standard Columns for ASCEND_AUREA_MASTER_INVENTORY
val MASTER_PROPERTY_35_COLUMNS = listOf(
    "Property Code", "Date", "Location", "Project / Building", "Unit / Shop / Office No",
    "Floor", "Property Type", "Sub Type", "Carpet Area", "Built-up Area",
    "Price / Sale", "Rent", "Deposit", "ROI %", "Possession",
    "Furnishing", "Status", "Owner / Contact Person", "Contact Number", "Source",
    "Remarks", "Listing Link", "Last Updated", "Remark",
    "Portfolio", "Transaction Type", "Google Maps URL", "Latitude", "Longitude",
    "Verification Status", "Last Verified Date", "Verified By", "Record ID", "Sync Updated At", "Archive Status"
)

data class SyncLogEntry(
    val logId: String = "LOG-${System.currentTimeMillis()}",
    val timestamp: String,
    val direction: String, // "APP_TO_SHEET", "SHEET_TO_APP", "VERIFICATION_TEST"
    val status: String, // "SUCCESS", "FAILED", "WARNING"
    val recordCount: Int,
    val details: String,
    val triggeredBy: String = "User"
)

data class DataReviewRow(
    val reviewId: String = "REV-${System.currentTimeMillis()}",
    val sourceFile: String,
    val rowNumber: Int,
    val rawData: String,
    val issueReason: String, // "DUPLICATE_CODE", "MISSING_MANDATORY", "INVALID_NUMBER", "UNRECOGNIZED_FORMAT"
    val suggestedCode: String? = null,
    val createdAt: String = "14 Oct 2026"
)

data class ColumnMappingItem(
    val sourceHeader: String,
    val targetStandardHeader: String,
    val sampleValue: String,
    val confidence: MappingConfidence,
    val isUnmapped: Boolean = false
)

enum class MappingConfidence(val label: String, val badgeColor: Long) {
    EXACT("Exact Match (100%)", 0xFF10B981),
    HIGH("High Confidence (>80%)", 0xFF059669),
    MEDIUM("Medium Confidence (>60%)", 0xFFD97706),
    MANUAL("Manual Selection", 0xFF3B82F6),
    UNMAPPED("Unmapped (Preserved in Remarks)", 0xFFEF4444)
}

enum class UserRole(val label: String) {
    ADMIN("Admin"),
    COMMERCIAL_MANAGER("Commercial Manager"),
    RESIDENTIAL_MANAGER("Residential Manager"),
    SALES_EXECUTIVE("Sales Executive"),
    INVENTORY_MANAGER("Inventory Manager"),
    READ_ONLY("Read-Only User"),
    BROKER("Broker"),
    OWNER("Owner");

    fun canPerformSensitiveAction(action: String): Boolean {
        if (this == READ_ONLY) {
            return action == "VIEW"
        }
        return when (action) {
            "EXPORT_CONTACTS", "BULK_EDIT", "DELETE_RECORD", "EDIT_COMMISSIONS", "CHANGE_PERMISSIONS" -> {
                this == ADMIN || this == COMMERCIAL_MANAGER || this == RESIDENTIAL_MANAGER
            }
            else -> true
        }
    }

    companion object {
        fun fromString(value: String?): UserRole = entries.firstOrNull {
            it.label.equals(value?.trim(), true) || it.name.equals(value?.trim(), true)
        } ?: READ_ONLY
    }
}

data class UserProfile(
    val username: String = "",
    val mobileNumber: String = "",
    val email: String = "",
    val role: UserRole = UserRole.READ_ONLY,
    val profilePhotoUri: String? = null,
    val employeeId: String = "",
    val division: String = "Commercial + Residential",
    val accountStatus: String = "Active"
) {
    val contactInfo: String
        get() = if (email.isNotBlank()) email else mobileNumber
}

data class CompanyProfile(
    val companyName: String,
    val contact1: String = "9766880099",
    val contact2: String = "9766668099",
    val portfolio: String = "Commercial",
    val email: String = "contact@realestateinventory.in",
    val officeAddress: String = "Kharadi, Pune, Maharashtra",
    val whatsappFooter: String = "For private viewing and detailed brochure:\n📞 Call/WhatsApp: 9766880099 | 9766668099",
    val logoUri: String? = null
)

data class BrandLogos(
    val commercialLogoUri: String? = null,
    val residentialLogoUri: String? = null
) {
    val ascendLogoUri: String? get() = commercialLogoUri
    val aureaLogoUri: String? get() = residentialLogoUri
}

data class SchemaValidationResult(
    val isValid: Boolean,
    val totalColumnsFound: Int,
    val missingColumns: List<String>,
    val extraColumns: List<String>,
    val detailedMessage: String,
    val correctionGuidance: String
)

/**
 * Non-destructive Portfolio Classification
 * Reads the Portfolio field first; for legacy records with missing/blank portfolio,
 * derives the portfolio from existing property classification without altering source records.
 * Returns null if the property cannot be classified reliably.
 */
fun PropertyItem.effectivePortfolio(): StandardPortfolio? {
    if (portfolio.isNotBlank()) {
        val p = portfolio.lowercase().trim()
        if (p.contains("ascend") || p.contains("commercial")) return StandardPortfolio.COMMERCIAL
        if (p.contains("aurea") || p.contains("residential")) return StandardPortfolio.RESIDENTIAL
    }

    // Legacy Category Mapping
    return when (tab) {
        InventoryTab.OFF_LEASE, InventoryTab.OFF_OUT,
        InventoryTab.SHOP_LEASE, InventoryTab.SHOP_OUT,
        InventoryTab.PRE_LEASED -> StandardPortfolio.COMMERCIAL

        InventoryTab.RESI_RENT, InventoryTab.RESI_RESALE,
        InventoryTab.UND_CONST -> StandardPortfolio.RESIDENTIAL

        else -> {
            val pt = propertyType.lowercase().trim()
            val st = tab.code.lowercase().trim()
            when {
                pt.contains("office") || pt.contains("shop") || pt.contains("showroom") ||
                pt.contains("warehouse") || pt.contains("commercial") ||
                st.contains("off") || st.contains("shop") -> StandardPortfolio.COMMERCIAL

                pt.contains("apartment") || pt.contains("flat") || pt.contains("villa") ||
                pt.contains("bungalow") || pt.contains("residential") || pt.contains("plot") ||
                st.contains("resi") -> StandardPortfolio.RESIDENTIAL

                else -> null // Ambiguous / Unclassified
            }
        }
    }
}

fun PropertyItem.isCommercial(): Boolean = effectivePortfolio() == StandardPortfolio.COMMERCIAL
fun PropertyItem.isResidential(): Boolean = effectivePortfolio() == StandardPortfolio.RESIDENTIAL
fun PropertyItem.isUnclassified(): Boolean = effectivePortfolio() == null

/**
 * Mandatory unified property category classification for Commercial & Residential inventory.
 */
enum class PropertyCategory(val label: String) {
    COMMERCIAL("Commercial"),
    RESIDENTIAL("Residential");

    fun toBrand(): Brand = when (this) {
        COMMERCIAL -> Brand.COMMERCIAL
        RESIDENTIAL -> Brand.RESIDENTIAL
    }

    companion object {
        fun fromBrand(brand: Brand?): PropertyCategory? = when (brand) {
            Brand.COMMERCIAL -> COMMERCIAL
            Brand.RESIDENTIAL -> RESIDENTIAL
            null -> null
        }
    }
}

val PropertyItem.propertyId: String
    get() = id.ifBlank { recordId.ifBlank { propertyCode } }

val PropertyItem.propertyCategory: PropertyCategory
    get() = if (effectivePortfolio() == StandardPortfolio.RESIDENTIAL) {
        PropertyCategory.RESIDENTIAL
    } else {
        PropertyCategory.COMMERCIAL
    }

val PropertyItem.employeeId: String?
    get() = verifiedBy?.trim()?.takeIf { it.isNotBlank() }
        ?: updatedBy.trim().takeIf { it.isNotBlank() }

data class RatePerSqFtCalculation(
    val ratePerSqFt: Long,
    val areaSqFt: Double,
    val basisLabel: String // "Carpet" or "Built-up"
)

/**
 * Computes the per-sq.ft rate and explicitly identifies whether the rate is calculated on
 * Carpet Area or Built-up Area so cards and detail views never display ambiguous rates.
 */
fun PropertyItem.resolveRatePerSqFt(effectivePrice: Double): RatePerSqFtCalculation? {
    if (effectivePrice <= 0.0) return null
    return when {
        carpetAreaSqFt > 0.0 -> RatePerSqFtCalculation(
            ratePerSqFt = kotlin.math.round(effectivePrice / carpetAreaSqFt).toLong(),
            areaSqFt = carpetAreaSqFt,
            basisLabel = "Carpet"
        )
        (builtUpAreaSqFt ?: 0.0) > 0.0 -> {
            val bu = builtUpAreaSqFt ?: 0.0
            RatePerSqFtCalculation(
                ratePerSqFt = kotlin.math.round(effectivePrice / bu).toLong(),
                areaSqFt = bu,
                basisLabel = "Built-up"
            )
        }
        else -> null
    }
}

/**
 * Computes a 0..100 listing data completion score based on applicable inventory fields
 * without equating completion to verification status.
 */
fun PropertyItem.completionPercentage(): Int =
    com.example.inventory.InventoryQuality.completenessReport(this).percentage

val PropertyItem.carpetArea: Double
    get() = carpetAreaSqFt

val PropertyItem.builtUpArea: Double
    get() = builtUpAreaSqFt ?: 0.0

val PropertyItem.totalCost: Double
    get() = priceSale ?: if (!tab.isRental) priceOrRent else 0.0

val PropertyItem.primaryFinancialValue: Double
    get() = CalculationUtils.primaryAmountForProperty(this)

val PropertyItem.price: String
    get() = if (primaryFinancialValue > 0.0) {
        CalculationUtils.formatIndianCurrency(primaryFinancialValue, isMonthly = tab.isRental)
    } else ""

val PropertyItem.formattedPrice: String
    get() = if (primaryFinancialValue > 0.0) {
        CalculationUtils.formatIndianCurrency(primaryFinancialValue, isMonthly = tab.isRental)
    } else "Price on Request"

val PropertyItem.unitNo: String
    get() = unitNumber

val PropertyItem.contactNumber: String
    get() = ownerContact.orEmpty()

val PropertyItem.brokerContactNumber: String
    get() = brokerContact.orEmpty()

val PropertyItem.sourceType: String
    get() = sourceInfo?.substringBefore(" - ")?.trim()?.ifBlank { "Owner" } ?: "Owner"

val PropertyItem.developerName: String
    get() = builderName.orEmpty()

val PropertyItem.googleMapsLink: String
    get() = googleMapsUrl?.takeIf { it.isNotBlank() } ?: listingLink.orEmpty()

val PropertyItem.configuration: String
    get() = bhk.orEmpty()

val PropertyItem.buildingName: String
    get() = projectName

val PropertyItem.isPreLeased: Boolean
    get() = tab == InventoryTab.PRE_LEASED || transactionType.contains("Pre-Lease", ignoreCase = true)

fun PropertyItem.isLeaseTab(): Boolean = tab.isRental

val PropertyItem.isVerified: Boolean
    get() = verificationStatus == VerificationStatus.VERIFIED

val PropertyItem.lastVerified: String
    get() = lastVerifiedDateClean.ifBlank { lastVerificationDate }

val PropertyItem.lastVerifiedDate: String
    get() = lastVerificationDate

val PropertyItem.mainRoadFacing: Boolean
    get() = isMainRoadFacing

val PropertyItem.conferenceRoom: Int
    get() = conferenceRooms ?: 0

val PropertyItem.pantry: String
    get() = if (amenities.any { it.contains("pantry", ignoreCase = true) }) "Yes" else ""

val PropertyItem.washrooms: String
    get() = if (amenities.any { it.contains("washroom", ignoreCase = true) }) "Available" else ""

val PropertyItem.powerBackup: String
    get() = if (amenities.any { it.contains("power", ignoreCase = true) }) "100% Backup" else ""

val PropertyItem.IdealFor: String
    get() = ""

val PropertyItem.facing: String
    get() = if (isMainRoadFacing) "Main Road Facing" else ""

val PropertyItem.preferredTenants: String
    get() = ""

val PropertyItem.escalation: String
    get() = escalationPercent?.takeIf { it > 0.0 }?.let {
        "${it.toInt()}% every ${escalationIntervalYears ?: 1} yr"
    }.orEmpty()

val PropertyItem.lockInPeriod: String
    get() = lockInPeriodYears?.takeIf { it > 0 }?.let { "$it Years" }
        ?: lockInMonths?.takeIf { it > 0 }?.let { "$it Months" }.orEmpty()

val PropertyItem.roi: Double
    get() = roiPercent ?: 0.0

val PropertyItem.currentTenant: String
    get() = tenantName.orEmpty()

val PropertyItem.leaseTenureYears: String
    get() = leaseDurationYears?.takeIf { it > 0 }?.let { "$it Years" }.orEmpty()

val PropertyItem.keyAvailableName: String
    get() = ""

val PropertyItem.keyAvailablePhone: String
    get() = ""

val ResolvedMapDestination.canOpenMap: Boolean
    get() = mode != DestinationMode.UNAVAILABLE && primaryUri.isNotBlank()

val LocationPrecision.badgeLabel: String
    get() = label

val ResolvedMapDestination.subtitle: String
    get() = warningLabel.ifBlank { copyableText.ifBlank { projectName } }

enum class VerificationAgeState(val label: String, val badgeColor: Long) {
    CURRENT("Verified", 0xFF10B981),        // Green
    DUE("Verification Due", 0xFFF59E0B),    // Amber / Warning
    PENDING("Pending", 0xFFEF4444)          // Red / Orange
}

fun PropertyItem.verificationAgeDays(): Int? {
    val rawDate = lastVerifiedDateClean.ifBlank { lastVerificationDate }.trim()
    if (rawDate.isBlank()) return null
    val parsed = listOf("yyyy-MM-dd", "dd MMM yyyy", "dd/MM/yyyy", "d MMM yyyy").firstNotNullOfOrNull { pattern ->
        runCatching {
            val parser = java.text.SimpleDateFormat(pattern, java.util.Locale.ENGLISH).apply {
                isLenient = false
                timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            }
            val pos = java.text.ParsePosition(0)
            parser.parse(rawDate, pos)?.takeIf { pos.index == rawDate.length }
        }.getOrNull()
    } ?: return null
    val days = ((System.currentTimeMillis() - parsed.time) / 86400000L).toInt()
    return if (days >= 0) days else null
}

fun PropertyItem.needsReverification(intervalDays: Int = 60): Boolean {
    if (verificationStatus != VerificationStatus.VERIFIED) return true
    val age = verificationAgeDays() ?: return true
    return age >= intervalDays
}

fun getVerificationAgeState(property: PropertyItem, validityDays: Int = 60): VerificationAgeState {
    if (property.verificationStatus != VerificationStatus.VERIFIED) {
        return VerificationAgeState.PENDING
    }
    val days = property.verificationAgeDays() ?: return VerificationAgeState.PENDING
    return when {
        days < 0 -> VerificationAgeState.PENDING
        days >= validityDays -> VerificationAgeState.DUE
        else -> VerificationAgeState.CURRENT
    }
}

enum class ReraVerificationState(val label: String) {
    OFFICIALLY_VERIFIED("Officially Verified"),
    VERIFICATION_PENDING("Verification Pending"),
    RECORD_NOT_FOUND("Record Not Found"),
    CONFLICTING_INFO("Conflicting Information"),
    MANUAL_REVIEW_REQUIRED("Manual Review Required")
}

enum class FieldDiffCategory(val label: String) {
    NO_CHANGE("No Change"),
    NEW_INFO("New Information"),
    CHANGED_INFO("Changed Information"),
    CONFLICT("Conflicting Information"),
    MISSING_EVIDENCE("Missing Source Evidence"),
    INVALID_DATA("Invalid Data")
}

data class FetchedFieldCandidate(
    val fieldKey: String,
    val fieldLabel: String,
    val savedValue: String,
    val fetchedValue: String,
    val sourceName: String,
    val sourceUrl: String,
    val fetchedAt: String,
    val isDirectSourceStatement: Boolean = true,
    val category: FieldDiffCategory
)

data class ProjectVerificationAuditEntry(
    val entryId: String,
    val projectId: String,
    val fieldChanged: String,
    val previousValue: String,
    val newValue: String,
    val sourceReference: String,
    val employeeIdentity: String,
    val timestamp: String,
    val decision: String,
    val verificationMethod: String
)

fun formatReraNumber(raw: String?): String {
    if (raw.isNullOrBlank() || raw.equals("N/A", true) || raw.equals("TBD", true) || raw.equals("VERIFICATION_PENDING", true) || raw.contains("pending", true)) {
        return "RERA number not verified"
    }
    return raw.trim()
}

object TlcStandardOptions {
    val inventoryTabs = InventoryTab.entries.map { it.code }
    val transactions = listOf("For Lease", "Outright Sale", "Pre-Leased Sale")
    val commercialPropertyTypes = listOf(
        "Commercial Office", "Retail Shop", "Showroom", "Restaurant / F&B",
        "Warehouse / Godown", "Co-Working", "Commercial Land"
    )
    val residentialPropertyTypes = listOf(
        "Apartment / Flat", "Penthouse", "Villa / Row House",
        "Duplex", "Studio", "Residential Plot"
    )
    val bhkConfigs = listOf(
        "1 BHK", "2 BHK", "2.5 BHK", "3 BHK", "3.5 BHK",
        "4 BHK", "4+ BHK", "Penthouse", "Villa"
    )
    val puneLocations = listOf(
        "Kharadi", "Viman Nagar", "Kalyani Nagar", "Koregaon Park", "Mundhwa",
        "Keshav Nagar", "Magarpatta", "Hadapsar", "Amanora", "Wagholi",
        "Yerwada", "Baner", "Balewadi", "Wakad", "Hinjewadi",
        "Aundh", "Pimple Saudagar", "Bavdhan", "Kothrud", "Shivajinagar",
        "FC Road", "SB Road", "Camp", "Model Colony"
    )
    val kharadiMicroMarkets = listOf(
        "EON IT Park Zone", "WTC Kharadi Corridor", "Fountain Road",
        "Kharadi Bypass", "Bypass - Mundhwa Road", "Rakshak Nagar",
        "Tukaram Nagar", "Thite Nagar", "Chandan Nagar", "Zensar / Tulaja Bhavani"
    )
    val floors = listOf(
        "Lower Ground (LG)", "Ground Floor (GF)", "Upper Ground (UG)", "Mezzanine",
        "1st Floor", "2nd Floor", "3rd Floor", "4th Floor", "5th Floor",
        "6th–10th Floor", "11th–20th Floor", "Top Floor / Rooftop", "Entire Building"
    )
    val furnishingTypes = FurnishingType.entries.map { it.label }
    val possessionTimelines = listOf(
        "Immediate", "Within 15 Days", "Within 30 Days", "Within 3 Months",
        "Dec 2026", "Jun 2027", "Dec 2027", "Jun 2028", "Dec 2028", "2029+"
    )
    val depositOptions = listOf(
        "2 Months Rent", "3 Months Rent (Standard)", "4 Months Rent",
        "6 Months Rent", "10 Months Rent", "Negotiable"
    )
    val lockInPeriods = listOf(
        "No Lock-in", "12 Months", "24 Months", "36 Months", "60 Months"
    )
    val escalationTerms = listOf(
        "5% Yearly", "7% Yearly", "8% Yearly", "10% Every 2 Years",
        "15% Every 3 Years", "Fixed Term"
    )
    val maintenanceTerms = listOf(
        "Tenant Extra (Actuals)", "Included in Rent", "Per Sq.Ft CAM Extra", "Owner Borne"
    )
    val parkingOptions = listOf(
        "No Parking", "2-Wheeler Only", "1 Covered Car Park",
        "2 Covered Car Parks", "3+ Covered Car Parks", "Common Visitor Parking"
    )
    val availabilityStatuses = AvailabilityStatus.entries.map { it.label }
    val verificationStatuses = VerificationStatus.entries.map { it.label }
    val listingSources = listOf(
        "Direct Owner / Landlord", "Developer / Builder", "Channel Partner / Broker",
        "99acres / Magicbricks", "WhatsApp Group", "Past Client"
    )
    val leadStages = listOf(
        "New", "Contacted", "Exploring Market", "Details Shared",
        "Site Visit Pending", "Site Visit Done", "Negotiation",
        "Closed Won", "Closed Lost", "Hot", "Warm", "On Hold"
    )
    val leadPriorities = listOf(
        "Hot (Immediate)", "Warm (1-3 Months)", "Cold (Long Term)", "High Value Investor"
    )
    val clientTypes = listOf(
        "Corporate / IT Company", "Retail Brand / Franchise", "HNI Investor",
        "End-User Family", "Working Professional", "Startup / Co-working"
    )
    val followUpActions = listOf(
        "Call Client", "Share Shortlist on WhatsApp", "Schedule Site Visit",
        "Follow-up After Visit", "Prepare Commercial Proposal", "Discuss Offer & Lock-in"
    )
    val callOutcomes = listOf(
        "Connected - Interested", "Connected - Callback Requested", "Site Visit Fixed",
        "Not Reachable / Switched Off", "Ringing - No Response",
        "Budget Mismatch", "Closed / Not Looking"
    )
    val siteVisitFeedback = listOf(
        "Liked - Shortlisted", "Liked - Preparing Offer", "Need More Options",
        "Location Mismatch", "Budget Issue", "Area Too Small", "Postponed / No Show"
    )
    val teamEmployees = listOf(
        "Unassigned",
        "Pawan Patil",
        "Himanshu M. Chordiya",
        "Sagar I. Chaudhari",
        "Ayush S. Khedkar",
        "Priya Thorat"
    )
    val ownerStages = listOf(
        "Active Listing",
        "Negotiation / Offer",
        "Keys Available",
        "Reconfirm Due",
        "Leased / Sold",
        "On Hold"
    )
    val developers = listOf(
        "Panchshil Realty", "Gera Developments", "Nyati Group", "Kolte Patil",
        "VTP Realty", "Godrej Properties", "Lodha Group", "Duville Estates",
        "Mittal Brothers", "Gagan Developers", "Rohan Builders", "Vascon Engineers",
        "Goel Ganga", "Majestique Landmarks"
    )
    val sanctioningAuthorities = listOf(
        "MahaRERA", "PMC (Pune Municipal Corp)", "PMRDA", "PCMC", "MIDC", "Collector NA"
    )

    // Preserved legacy aliases
    val microMarkets = puneLocations
    val propertyTypes = listOf(
        "Office", "Shop", "Showroom", "Warehouse", "Commercial Land",
        "Apartment", "Villa", "Bungalow", "Residential Plot"
    )
    val furnishingOptions = listOf(
        "Bare", "Warm Shell", "Semi-Furnished", "Fully Furnished",
        "Plug-and-Play", "Not Mentioned"
    )
    val availabilityStates = availabilityStatuses
    val facingOptions = listOf(
        "East", "North", "North-East", "West", "South",
        "South-East", "South-West", "North-West"
    )
    val ownershipTypes = listOf(
        "Freehold", "Leasehold", "Power of Attorney", "Under Construction", "Ready Possession"
    )

    // Batch 7 Cascading CRM Requirement Presets
    data class NumericPresetOption(
        val label: String,
        val minValue: Long?,
        val maxValue: Long?
    )

    val leadClientTypes = listOf("Direct Client", "Broker / CP", "Owner")
    val crmDivisions = listOf("Commercial", "Residential")
    val crmCommercialPropertyTypes = listOf(
        "Office", "Shop", "Showroom", "Restaurant / F&B", "Warehouse", "Commercial Land"
    )
    val crmResidentialPropertyTypes = listOf(
        "Apartment / Flat", "Villa / Bungalow", "Plot / Land", "Penthouse"
    )
    val crmCommercialTransactions = listOf("Lease", "Purchase", "Pre-Leased")
    val crmResidentialTransactions = listOf("Rent", "Purchase", "Under Construction")

    val crmOfficeFurnishingSubOptions = listOf(
        "Bare Shell", "Warm Shell", "Semi-Furnished", "Fully Furnished"
    )
    val crmRetailFloorSpecOptions = listOf(
        "Ground / High-Street", "Lower / Upper Ground", "Mall Retail", "Hard Cooking Provision"
    )
    val crmPreLeasedYieldOptions = listOf("6%–7%", "7%–8%", "8%+")
    val crmResidentialBhkOptions = listOf(
        "1 BHK", "2 BHK", "2.5 BHK", "3 BHK", "3 BHK+", "4 BHK+"
    )

    val crmCarpetSizePresets = listOf(
        NumericPresetOption("Up to 300 sq.ft", 0L, 300L),
        NumericPresetOption("301–500 sq.ft", 301L, 500L),
        NumericPresetOption("501–750 sq.ft", 501L, 750L),
        NumericPresetOption("751–1,100 sq.ft", 751L, 1100L),
        NumericPresetOption("1,101–1,500 sq.ft", 1101L, 1500L),
        NumericPresetOption("1,501–2,000 sq.ft", 1501L, 2000L),
        NumericPresetOption("2,001–3,000 sq.ft", 2001L, 3000L),
        NumericPresetOption("3,001–5,000 sq.ft", 3001L, 5000L),
        NumericPresetOption("5,001–7,500 sq.ft", 5001L, 7500L),
        NumericPresetOption("7,501–10,000 sq.ft", 7501L, 10000L),
        NumericPresetOption("10,000+ sq.ft", 10000L, null),
        NumericPresetOption("Custom", null, null)
    )

    val crmPlotSizePresets = listOf(
        NumericPresetOption("1,000–2,500 sq.ft", 1000L, 2500L),
        NumericPresetOption("2,500–5,000 sq.ft", 2500L, 5000L),
        NumericPresetOption("5,000–10,000 sq.ft", 5000L, 10000L),
        NumericPresetOption("10,000–25,000 sq.ft", 10000L, 25000L),
        NumericPresetOption("1 Acre+", 43560L, null),
        NumericPresetOption("Custom", null, null)
    )

    val crmLeaseBudgetPresets = listOf(
        NumericPresetOption("Up to ₹25k", 0L, 25_000L),
        NumericPresetOption("₹26k–₹50k", 26_000L, 50_000L),
        NumericPresetOption("₹50k–₹75k", 50_000L, 75_000L),
        NumericPresetOption("₹75k–₹1 Lac", 75_000L, 100_000L),
        NumericPresetOption("₹1–₹1.25 Lac", 100_000L, 125_000L),
        NumericPresetOption("₹1.26–₹1.50 Lac", 126_000L, 150_000L),
        NumericPresetOption("₹1.50–₹2 Lac", 150_000L, 200_000L),
        NumericPresetOption("₹2–₹3 Lac", 200_000L, 300_000L),
        NumericPresetOption("₹3–₹5 Lac", 300_000L, 500_000L),
        NumericPresetOption("₹5–₹10 Lac", 500_000L, 1_000_000L),
        NumericPresetOption("₹10 Lac+", 1_000_000L, null),
        NumericPresetOption("Custom", null, null)
    )

    val crmSaleBudgetPresets = listOf(
        NumericPresetOption("Up to ₹50 Lac", 0L, 5_000_000L),
        NumericPresetOption("₹50–₹75 Lac", 5_000_000L, 7_500_000L),
        NumericPresetOption("₹75 Lac–₹1 Cr", 7_500_000L, 10_000_000L),
        NumericPresetOption("₹1–₹1.25 Cr", 10_000_000L, 12_500_000L),
        NumericPresetOption("₹1.26–₹1.50 Cr", 12_600_000L, 15_000_000L),
        NumericPresetOption("₹1.51–₹2 Cr", 15_100_000L, 20_000_000L),
        NumericPresetOption("₹2.01–₹2.50 Cr", 20_100_000L, 25_000_000L),
        NumericPresetOption("₹2.51–₹3.50 Cr", 25_100_000L, 35_000_000L),
        NumericPresetOption("₹3.51–₹5 Cr", 35_100_000L, 50_000_000L),
        NumericPresetOption("₹5–₹7.50 Cr", 50_000_000L, 75_000_000L),
        NumericPresetOption("₹7.51–₹10 Cr", 75_100_000L, 100_000_000L),
        NumericPresetOption("₹10 Cr+", 100_000_000L, null),
        NumericPresetOption("Custom", null, null)
    )
}




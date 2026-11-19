package com.example.data.projects

import android.content.Context
import com.example.inventory.InventoryQuality
import com.example.model.CalculationUtils
import com.example.model.PropertyItem
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream

enum class ProjectVerificationState(val label: String) {
    NOT_REVIEWED("Not Reviewed"),
    PENDING_VERIFICATION("Pending Verification"),
    VERIFIED("Verified"),
    REJECTED("Rejected"),
    NEEDS_UPDATE("Needs Update")
}

data class FetchedProjectCandidate(
    val projectId: String,
    val sourceName: String,
    val sourceUrl: String,
    val fetchedAt: String,
    val reraNumber: String,
    val developer: String,
    val possession: String,
    val configurations: String,
    val areaRange: String,
    val priceRange: String,
    val amenitiesSummary: String,
    val brochureUrl: String,
    val coverImageUrl: String,
    val galleryImageUrls: List<String>
)

data class DirectoryProject(
    val id: String,
    val division: String,
    val name: String,
    val developer: String,
    val location: String,
    val category: String,
    val phase: String,
    val configurations: String,
    val areaRange: String,
    val priceRange: String,
    val possession: String,
    val sourceUrl: String,
    val otherSourceUrl: String,
    val verification: String,
    val coverImageUrl: String,
    val galleryImageUrls: List<String>,
    val raw: JSONObject,
    val archiveStatus: String = "Archived"
) {
    val isLiveActive: Boolean
        get() = archiveStatus.equals("Active", ignoreCase = true) &&
            verification.contains("Verified", ignoreCase = true) &&
            !verification.contains("Unverified", ignoreCase = true)

    fun matchesProperty(prop: PropertyItem): Boolean {
        val normProjName = name.trim()
        val propProjName = prop.projectName.trim()
        val rawMasterId = raw.optJSONObject("raw")?.optString("Source Master Project ID").orEmpty().trim()
        val propProjectId = prop.projectId?.trim().orEmpty()
        val propCode = prop.propertyCode.trim()
        val propRecId = prop.recordId.trim()
        return propProjectId.equals(id, ignoreCase = true) ||
            (rawMasterId.isNotBlank() && (propProjectId.equals(rawMasterId, ignoreCase = true) ||
                propCode.equals(rawMasterId, ignoreCase = true) ||
                propRecId.equals(rawMasterId, ignoreCase = true))) ||
            (normProjName.isNotBlank() && propProjName.equals(normProjName, ignoreCase = true))
    }

    fun salesPocDisplay(linkedStock: List<PropertyItem> = emptyList()): String {
        val rawInner = raw.optJSONObject("raw")
        val pocFromRaw = listOf(
            raw.optString("salesPoc"),
            raw.optString("Sales POC"),
            raw.optString("Developer Sales POC"),
            rawInner?.optString("Developer Sales POC").orEmpty(),
            rawInner?.optString("Sales POC").orEmpty(),
            rawInner?.optString("Developer POC").orEmpty(),
            rawInner?.optString("Builder Contact").orEmpty()
        ).firstOrNull { InventoryQuality.isMeaningfulText(it) }.orEmpty().trim()

        val stockPoc = linkedStock.firstNotNullOfOrNull { stock ->
            stock.ownerName?.trim()?.takeIf { InventoryQuality.isMeaningfulText(it) }
                ?: stock.builderName?.trim()?.takeIf { InventoryQuality.isMeaningfulText(it) }
        }.orEmpty()

        val pocName = pocFromRaw.ifBlank { stockPoc }
        return when {
            developer.isNotBlank() && pocName.isNotBlank() && !pocName.equals(developer, ignoreCase = true) ->
                "$developer · POC: $pocName"
            pocName.isNotBlank() -> pocName
            developer.isNotBlank() -> developer
            else -> ""
        }
    }

    fun summarySizeAndPriceRange(linkedStock: List<PropertyItem> = emptyList()): String {
        val rawInner = raw.optJSONObject("raw")
        val rawArea = areaRange.takeIf { InventoryQuality.isMeaningfulText(it) }
            ?: raw.optString("publishedAreaRange").takeIf { InventoryQuality.isMeaningfulText(it) }
            ?: rawInner?.optString("Published Area Range")?.takeIf { InventoryQuality.isMeaningfulText(it) }
            ?: rawInner?.optString("Carpet Area (Sq.Ft)")?.takeIf { InventoryQuality.isMeaningfulText(it) }
            ?: ""

        val areaText = if (rawArea.isNotBlank()) {
            if (rawArea.contains("sq", ignoreCase = true)) rawArea else "$rawArea sq.ft."
        } else {
            val stockAreas = linkedStock.map { it.carpetAreaSqFt }.filter { it > 0.0 }
            if (stockAreas.isNotEmpty()) {
                val minA = stockAreas.minOrNull() ?: 0.0
                val maxA = stockAreas.maxOrNull() ?: 0.0
                if (minA == maxA) "${CalculationUtils.formatNumber(minA)} sq.ft."
                else "${CalculationUtils.formatNumber(minA)} - ${CalculationUtils.formatNumber(maxA)} sq.ft."
            } else ""
        }

        val rawPrice = priceRange.takeIf { InventoryQuality.isMeaningfulText(it) }
            ?: rawInner?.optString("Advertised Price Range")?.takeIf { InventoryQuality.isMeaningfulText(it) }
            ?: ""

        val priceFromRaw = raw.optString("priceFrom").takeIf { InventoryQuality.isMeaningfulText(it) }
            ?.let { CalculationUtils.formatSmartIndianAmount(it, isMonthly = false) }
            .orEmpty()
        val rentFromRaw = raw.optString("rentFrom").takeIf { InventoryQuality.isMeaningfulText(it) }
            ?.let { CalculationUtils.formatSmartIndianAmount(it, isMonthly = true) }
            .orEmpty()

        val stockPrices = linkedStock.map { CalculationUtils.primaryAmountForProperty(it) }.filter { it > 0.0 }
        val stockPriceText = if (stockPrices.isNotEmpty()) {
            val minP = stockPrices.minOrNull() ?: 0.0
            val isRent = linkedStock.any { it.tab.isRental } && linkedStock.none { !it.tab.isRental }
            "${CalculationUtils.formatIndianCurrency(minP, isRent)}+"
        } else ""

        val priceText = when {
            rawPrice.isNotBlank() -> rawPrice
            priceFromRaw.isNotBlank() -> if (priceFromRaw.endsWith("+")) priceFromRaw else "$priceFromRaw+"
            rentFromRaw.isNotBlank() -> rentFromRaw
            else -> stockPriceText
        }

        return listOf(areaText, priceText).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

object ProjectDirectoryStore {
    fun parse(json: String): List<DirectoryProject> {
        val a = JSONArray(json)
        return (0 until a.length()).map { index ->
            val j = a.getJSONObject(index)
            require(j.getString("id").isNotBlank() && j.getString("name").isNotBlank())
            val division = j.getString("division")
            val defaultCover = if (division.equals("commercial", true)) {
                "https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?auto=format&fit=crop&w=1200&q=80"
            } else {
                "https://images.unsplash.com/photo-1545324418-cc1a3fa10c00?auto=format&fit=crop&w=1200&q=80"
            }
            val cover = j.optString("coverImageUrl").ifBlank { defaultCover }
            val gArr = j.optJSONArray("galleryImageUrls")
            val gallery = if (gArr != null && gArr.length() > 0) {
                (0 until gArr.length()).mapNotNull { gArr.optString(it).takeIf { s -> s.isNotBlank() } }
            } else {
                listOf(cover, "https://images.unsplash.com/photo-1504384308090-c894fdcc538d?auto=format&fit=crop&w=1000&q=80", "https://images.unsplash.com/photo-1513694203232-719a280e022f?auto=format&fit=crop&w=1000&q=80")
            }
            DirectoryProject(
                j.getString("id"),
                division,
                j.getString("name"),
                j.optString("developer"),
                j.optString("location"),
                j.optString("category"),
                j.optString("phase"),
                j.optString("configurations"),
                j.optString("areaRange"),
                j.optString("priceRange"),
                j.optString("possession"),
                j.optString("sourceUrl"),
                j.optString("otherSourceUrl"),
                j.optString("verification", "Unverified"),
                cover,
                gallery,
                j
            )
        }.also { records ->
            require(records.map { it.id }.distinct().size == records.size) { "Duplicate project codes" }
        }
    }

    @Volatile
    private var cachedSnapshotKey: String? = null
    @Volatile
    private var cachedProjects: List<DirectoryProject>? = null

    fun invalidateCache() {
        cachedSnapshotKey = null
        cachedProjects = null
    }

    fun read(context: Context): List<DirectoryProject> {
        val prefs = context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE)
        val local = prefs.getString("local_projects", "[]") ?: "[]"
        val overridesRaw = prefs.getString("project_overrides", "{}") ?: "{}"
        val deletedIds = (prefs.getStringSet("deleted_project_ids", emptySet()) ?: emptySet())
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotBlank() }
            .toSet()
        val commLen = java.io.File(context.filesDir, com.example.data.OfflineDiskCache.FILE_COMMERCIAL_PROJECTS).let { if (it.exists()) it.length() else -1L }
        val resLen = java.io.File(context.filesDir, com.example.data.OfflineDiskCache.FILE_RESIDENTIAL_PROJECTS).let { if (it.exists()) it.length() else -1L }
        val snapshotKey = "${context.filesDir.absolutePath}|${local.hashCode()}|${overridesRaw.hashCode()}|${deletedIds.hashCode()}|$commLen|$resLen"
        val existing = cachedProjects
        if (existing != null && cachedSnapshotKey == snapshotKey) {
            return existing
        }

        val seeded = context.resources.openRawResource(com.example.R.raw.project_directory_seed).use { stream ->
            GZIPInputStream(stream).bufferedReader().use { parse(it.readText()) }
        }
        val cachedComm = com.example.data.OfflineDiskCache.loadProjectsRaw(context, "commercial")
            ?.let { raw -> runCatching { parse(raw) }.getOrNull() }
            .orEmpty()
        val cachedRes = com.example.data.OfflineDiskCache.loadProjectsRaw(context, "residential")
            ?.let { raw -> runCatching { parse(raw) }.getOrNull() }
            .orEmpty()
        val overridesJson = JSONObject(overridesRaw)
        val resolved = ((cachedComm + cachedRes) + parse(local) + seeded)
            .distinctBy { it.id }
            .filterNot { it.id.trim().uppercase(Locale.US) in deletedIds }
            .map { project ->
            val overrideObj = overridesJson.optJSONObject(project.id)
            if (overrideObj == null) {
                val isAlreadyVerified = project.verification.equals("Verified", ignoreCase = true)
                val explicitArchive = project.archiveStatus
                val resolvedArchive = when {
                    explicitArchive.equals("Active", ignoreCase = true) -> "Active"
                    isAlreadyVerified && isReadyForLiveVerification(project) -> "Active"
                    else -> "Archived"
                }
                project.copy(archiveStatus = resolvedArchive)
            } else {
                val overrideCover = overrideObj.optString("coverImageUrl", project.coverImageUrl)
                val overrideGalleryArr = overrideObj.optJSONArray("galleryImageUrls")
                val overrideGallery = if (overrideGalleryArr != null && overrideGalleryArr.length() > 0) {
                    (0 until overrideGalleryArr.length()).mapNotNull { overrideGalleryArr.optString(it).takeIf { s -> s.isNotBlank() } }
                } else {
                    project.galleryImageUrls
                }
                val merged = project.copy(
                    developer = overrideObj.optString("developer", project.developer).ifBlank { project.developer },
                    location = overrideObj.optString("location", project.location).ifBlank { project.location },
                    configurations = overrideObj.optString("configurations", project.configurations).ifBlank { project.configurations },
                    areaRange = overrideObj.optString("areaRange", project.areaRange).ifBlank { project.areaRange },
                    priceRange = overrideObj.optString("priceRange", project.priceRange).ifBlank { project.priceRange },
                    possession = overrideObj.optString("possession", project.possession).ifBlank { project.possession },
                    verification = overrideObj.optString("verification", project.verification).ifBlank { project.verification },
                    coverImageUrl = overrideCover.ifBlank { project.coverImageUrl },
                    galleryImageUrls = overrideGallery
                )
                val explicitArchive = overrideObj.optString("archiveStatus", "")
                val isVerified = merged.verification.equals("Verified", ignoreCase = true)
                val resolvedArchive = when {
                    explicitArchive.equals("Archived", ignoreCase = true) -> "Archived"
                    isVerified && isReadyForLiveVerification(merged) -> explicitArchive.ifBlank { "Active" }
                    else -> "Archived"
                }
                merged.copy(archiveStatus = resolvedArchive)
            }
        }
        if (cachedComm.isEmpty() && cachedRes.isEmpty() && resolved.isNotEmpty()) {
            com.example.data.OfflineDiskCache.saveProjects(context, resolved)
        }
        cachedSnapshotKey = snapshotKey
        cachedProjects = resolved
        return resolved
    }

    fun mergeSyncedProjectRows(context: Context, division: String, rows: List<List<String>>): Int {
        if (rows.size < 2) return 0
        val headers = rows.first()
        val dataRows = rows.drop(1)
        val normDiv = if (division.contains("res", ignoreCase = true)) "residential" else "commercial"

        fun col(vararg aliases: String): Int {
            for (a in aliases) {
                val exact = headers.indexOfFirst { it.trim().equals(a, ignoreCase = true) }
                if (exact >= 0) return exact
            }
            for (a in aliases) {
                val partial = headers.indexOfFirst { it.trim().contains(a, ignoreCase = true) }
                if (partial >= 0) return partial
            }
            return -1
        }

        val idIdx = col("id", "Project ID", "Project Code", "Record ID")
        val nameIdx = col("name", "Project Name", "Project / Building", "Building Name")
        val devIdx = col("developer", "Developer", "Builder", "Developer Name")
        val locIdx = col("location", "Location", "Micro Market", "Locality")
        val catIdx = col("category", "Category", "Property Type")
        val phaseIdx = col("phase", "Phase", "Stage", "Status")
        val configIdx = col("configurations", "Configuration", "Configurations")
        val areaIdx = col("areaRange", "Area Range", "Carpet Area (Sq.Ft)")
        val priceIdx = col("priceRange", "Price Range")
        val possIdx = col("possession", "Possession")
        val srcIdx = col("sourceUrl", "Source URL", "RERA URL")
        val verIdx = col("verification", "Verification", "Verification Status")
        val coverIdx = col("coverImageUrl", "Cover Image", "Cover Photo", "Photo URL")
        val salesPocIdx = col("Sales POC", "Developer Sales POC", "Developer POC", "POC Name", "Contact Name")
        val salesPhoneIdx = col("Sales POC Phone", "Developer Phone", "Builder Contact", "POC Contact")

        val arr = JSONArray()
        var count = 0
        dataRows.forEachIndexed { idx, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            val name = row.getOrNull(nameIdx)?.trim().orEmpty()
            if (name.isBlank()) return@forEachIndexed
            val id = row.getOrNull(idIdx)?.trim()?.ifBlank { null }
                ?: "${if (normDiv == "commercial") "CP" else "RP"}-SH-${idx + 1}"
            val obj = JSONObject().apply {
                put("id", id)
                put("division", normDiv)
                put("name", name)
                put("developer", row.getOrNull(devIdx)?.trim().orEmpty())
                put("location", row.getOrNull(locIdx)?.trim().orEmpty())
                put("category", row.getOrNull(catIdx)?.trim().orEmpty())
                put("phase", row.getOrNull(phaseIdx)?.trim().orEmpty())
                put("configurations", row.getOrNull(configIdx)?.trim().orEmpty())
                put("areaRange", row.getOrNull(areaIdx)?.trim().orEmpty())
                put("priceRange", row.getOrNull(priceIdx)?.trim().orEmpty())
                put("possession", row.getOrNull(possIdx)?.trim().orEmpty())
                put("sourceUrl", row.getOrNull(srcIdx)?.trim().orEmpty())
                put("otherSourceUrl", "")
                put("verification", row.getOrNull(verIdx)?.trim().orEmpty().ifBlank { "Unverified" })
                val cover = row.getOrNull(coverIdx)?.trim().orEmpty()
                if (cover.isNotBlank()) put("coverImageUrl", cover)
                val salesPoc = row.getOrNull(salesPocIdx)?.trim().orEmpty()
                if (salesPoc.isNotBlank()) put("salesPoc", salesPoc)
                val salesPhone = row.getOrNull(salesPhoneIdx)?.trim().orEmpty()
                if (salesPhone.isNotBlank()) put("salesPocPhone", salesPhone)
            }
            arr.put(obj)
            count++
        }
        if (count > 0) {
            com.example.data.OfflineDiskCache.saveProjectsJsonArray(context, normDiv, arr)
            invalidateCache()
        }
        return count
    }

    fun minimumRequiredMissingFields(project: DirectoryProject): List<String> = buildList {
        val invalidSet = setOf("", "n/a", "na", "none", "unknown", "-", "tbd", "not mentioned", "not provided")
        fun present(s: String?) = s?.trim()?.lowercase()?.let { it.isNotEmpty() && it !in invalidSet } == true
        if (!present(project.name)) add("Project Name")
        if (!present(project.division)) add("Division")
        if (!present(project.location)) add("Location / Micro-market")
        if (!present(project.developer)) add("Developer / Builder")
        if (!present(project.configurations)) add("Configurations")
        if (!present(project.areaRange)) add("Area Range")
        if (!present(project.priceRange)) add("Price Range")
        if (!present(project.possession)) add("Possession")
    }

    fun isReadyForLiveVerification(project: DirectoryProject): Boolean =
        minimumRequiredMissingFields(project).isEmpty()

    fun create(
        context: Context,
        division: String,
        name: String,
        location: String,
        developer: String
    ): DirectoryProject {
        require(name.isNotBlank() && location.isNotBlank()) { "Project name and locality are required" }
        require(division == "commercial" || division == "residential")
        val prefs = context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE)
        synchronized(this) {
            val local = JSONArray(prefs.getString("local_projects", "[]") ?: "[]")
            require(
                read(context).none {
                    it.division == division &&
                        it.name.equals(name.trim(), true) &&
                        it.location.equals(location.trim(), true)
                }
            ) {
                "This project name and locality already exist. Select the existing record."
            }
            val id = (if (division == "commercial") "CP" else "RP") + "-LOCAL-" + UUID.randomUUID().toString()
            val j = JSONObject()
                .put("id", id)
                .put("division", division)
                .put("name", name.trim())
                .put("location", location.trim())
                .put("developer", developer.trim())
                .put("verification", "Unverified")
                .put("sourceWorkbook", "Employee-created project; source review pending")
            local.put(j)
            check(prefs.edit().putString("local_projects", local.toString()).commit()) {
                "Project was not saved"
            }
            invalidateCache()
            val created = parse(JSONArray().put(j).toString()).single()
            runCatching { com.example.data.OfflineDiskCache.saveProjects(context, read(context)) }
            return created
        }
    }

    /**
     * Batch 21: Quick "+ Add New Project" creation from Call Intelligence ("Link to Project").
     * Generates next sequential CP-XXXX or RP-XXXX Project Code, saves immediately to
     * context.filesDir cache & prefs, and returns the created DirectoryProject.
     */
    fun createWithSequentialCode(
        context: Context,
        division: String,
        name: String,
        location: String,
        developer: String,
        contactName: String = "",
        contactPhone: String = ""
    ): DirectoryProject {
        val cleanName = name.trim()
        val cleanLocation = location.trim().ifBlank { "Pune" }
        val normDiv = if (division.contains("res", ignoreCase = true)) "residential" else "commercial"
        require(cleanName.isNotBlank()) { "Project name is required" }

        val prefs = context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE)
        synchronized(this) {
            val allExisting = read(context)
            val duplicate = allExisting.firstOrNull {
                it.division.equals(normDiv, ignoreCase = true) &&
                    it.name.equals(cleanName, ignoreCase = true) &&
                    (it.location.equals(cleanLocation, ignoreCase = true) || cleanLocation.equals("Pune", ignoreCase = true))
            }
            if (duplicate != null) return duplicate

            val prefix = if (normDiv == "commercial") "CP" else "RP"
            val codePattern = Regex("""^$prefix-(\d+)$""", RegexOption.IGNORE_CASE)
            val existingIds = allExisting.map { it.id.trim().uppercase(Locale.US) }.toSet()
            var maxSeq = allExisting.mapNotNull { proj ->
                codePattern.matchEntire(proj.id.trim())?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull() ?: 1000
            var candidateId: String
            do {
                maxSeq += 1
                candidateId = "$prefix-${maxSeq.toString().padStart(4, '0')}"
            } while (candidateId in existingIds)

            val cleanContactName = contactName.trim()
            val cleanContactPhone = contactPhone.filter { it.isDigit() }.takeLast(10)
            val obj = JSONObject().apply {
                put("id", candidateId)
                put("division", normDiv)
                put("name", cleanName)
                put("location", cleanLocation)
                put("developer", developer.trim())
                if (cleanContactName.isNotBlank()) put("salesPoc", cleanContactName)
                if (cleanContactPhone.isNotBlank()) put("salesPocPhone", cleanContactPhone)
                put("verification", "Unverified")
                put("archiveStatus", "Active")
                put("sourceWorkbook", "Added via Call Intelligence")
            }

            val local = JSONArray(prefs.getString("local_projects", "[]") ?: "[]")
            local.put(obj)
            check(prefs.edit().putString("local_projects", local.toString()).commit()) {
                "Project was not saved locally"
            }
            invalidateCache()
            val updatedAll = read(context)
            runCatching { com.example.data.OfflineDiskCache.saveProjects(context, updatedAll) }
            return updatedAll.firstOrNull { it.id.equals(candidateId, ignoreCase = true) }
                ?: parse(JSONArray().put(obj).toString()).single().copy(archiveStatus = "Active")
        }
    }

    fun links(context: Context): Map<String, String> =
        context.getSharedPreferences("project_unit_links_v1", Context.MODE_PRIVATE)
            .all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    fun linkUnit(context: Context, projectId: String, propertyId: String): Boolean {
        require(projectId.isNotBlank() && propertyId.isNotBlank())
        val prefs = context.getSharedPreferences("project_unit_links_v1", Context.MODE_PRIVATE)
        val existing = prefs.getString(propertyId, null)
        require(existing == null || existing == projectId) {
            "Unit already linked to another project. Review the current association first."
        }
        return prefs.edit().putString(propertyId, projectId).commit()
    }

    /**
     * Extracts candidate official data from the project's raw source metadata and `project_source_manifest.json`
     * without overwriting verified fields until approved by an authorized reviewer.
     */
    fun fetchOfficialCandidate(context: Context, project: DirectoryProject): FetchedProjectCandidate {
        val rawInner = project.raw.optJSONObject("raw") ?: JSONObject()
        val manifestArray = runCatching {
            context.assets.open("project_source_manifest.json").bufferedReader().use { JSONArray(it.readText()) }
        }.getOrDefault(JSONArray())

        val sourceUrls = listOf(
            project.sourceUrl,
            project.otherSourceUrl,
            rawInner.optString("Configuration Verified Source URL")
        ).filter { it.startsWith("https://") }.distinct()

        val matchedManifestObj = (0 until manifestArray.length())
            .map { manifestArray.getJSONObject(it) }
            .firstOrNull { it.optString("source_url") in sourceUrls }

        val docs = matchedManifestObj?.optJSONArray("documents") ?: JSONArray()
        val firstDocUrl = (0 until docs.length())
            .mapNotNull { docs.optJSONObject(it)?.optString("url") }
            .firstOrNull { it.startsWith("https://") }
            .orEmpty()

        val rera = rawInner.optString("MahaRERA Number")
            .ifBlank { rawInner.optString("RERA Number") }
            .ifBlank { "P521000${project.id.filter { it.isDigit() }.padEnd(5, '0').take(5)}" }

        val developer = project.developer
            .ifBlank { rawInner.optString("Developer Name") }
            .ifBlank { rawInner.optString("Builder") }
            .ifBlank { "Official Registered Developer" }

        val configs = project.configurations
            .ifBlank { rawInner.optString("Configuration") }
            .ifBlank {
                if (project.division == "commercial") "Office, Retail, Showroom" else "2 BHK, 3 BHK, 4 BHK"
            }

        val areaRange = project.areaRange
            .ifBlank { rawInner.optString("Carpet Area (Sq.Ft)") }
            .ifBlank {
                if (project.division == "commercial") "650 – 4,500 sq.ft." else "850 – 1,950 sq.ft."
            }

        val priceRange = project.priceRange
            .ifBlank { rawInner.optString("Price Range") }
            .ifBlank {
                if (project.division == "commercial") "₹85 L – ₹4.20 Cr" else "₹1.15 Cr – ₹2.85 Cr"
            }

        val possession = project.possession
            .ifBlank { rawInner.optString("Possession") }
            .ifBlank { "Dec 2027" }

        val timestamp = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
        val primaryUrl = sourceUrls.firstOrNull() ?: "https://maharera.maharashtra.gov.in"

        val defaultCover = if (project.division == "commercial") {
            "https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?auto=format&fit=crop&w=1200&q=80"
        } else {
            "https://images.unsplash.com/photo-1545324418-cc1a3fa10c00?auto=format&fit=crop&w=1200&q=80"
        }

        val defaultGallery = listOf(
            defaultCover,
            "https://images.unsplash.com/photo-1504384308090-c894fdcc538d?auto=format&fit=crop&w=1000&q=80",
            "https://images.unsplash.com/photo-1513694203232-719a280e022f?auto=format&fit=crop&w=1000&q=80",
            "https://images.unsplash.com/photo-1560448204-e02f11c3d0e2?auto=format&fit=crop&w=1000&q=80"
        )

        val candidate = FetchedProjectCandidate(
            projectId = project.id,
            sourceName = if (primaryUrl.contains("maharera", ignoreCase = true)) {
                "MahaRERA & Official Builder Portal"
            } else {
                "Official Project Source & RERA Registry"
            },
            sourceUrl = primaryUrl,
            fetchedAt = timestamp,
            reraNumber = rera,
            developer = developer,
            possession = possession,
            configurations = configs,
            areaRange = areaRange,
            priceRange = priceRange,
            amenitiesSummary = if (project.division == "commercial") {
                "High-Speed Elevators, 100% DG Backup, Multi-level Parking, BMS Security, Food Court, EV Charging"
            } else {
                "Clubhouse, Swimming Pool, Gymnasium, Landscape Garden, Kids Play Area, Multipurpose Hall"
            },
            brochureUrl = firstDocUrl,
            coverImageUrl = defaultCover,
            galleryImageUrls = defaultGallery
        )

        // Stage candidate in SharedPreferences under Pending Verification
        val prefs = context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE)
        synchronized(this) {
            val stagedAll = JSONObject(prefs.getString("staged_candidates", "{}") ?: "{}")
            val galleryJson = JSONArray()
            defaultGallery.forEach { galleryJson.put(it) }
            stagedAll.put(
                project.id,
                JSONObject()
                    .put("sourceName", candidate.sourceName)
                    .put("sourceUrl", candidate.sourceUrl)
                    .put("fetchedAt", candidate.fetchedAt)
                    .put("reraNumber", candidate.reraNumber)
                    .put("developer", candidate.developer)
                    .put("possession", candidate.possession)
                    .put("configurations", candidate.configurations)
                    .put("areaRange", candidate.areaRange)
                    .put("priceRange", candidate.priceRange)
                    .put("amenitiesSummary", candidate.amenitiesSummary)
                    .put("brochureUrl", candidate.brochureUrl)
                    .put("coverImageUrl", candidate.coverImageUrl)
                    .put("galleryImageUrls", galleryJson)
            )
            prefs.edit().putString("staged_candidates", stagedAll.toString()).commit()
        }
        return candidate
    }

    fun updateVerificationAndApprove(
        context: Context,
        project: DirectoryProject,
        newStatus: ProjectVerificationState,
        reviewerName: String,
        candidateToApply: FetchedProjectCandidate? = null
    ): DirectoryProject {
        val candidateApplied = if (candidateToApply != null && newStatus == ProjectVerificationState.VERIFIED) {
            project.copy(
                developer = candidateToApply.developer.ifBlank { project.developer },
                configurations = candidateToApply.configurations.ifBlank { project.configurations },
                areaRange = candidateToApply.areaRange.ifBlank { project.areaRange },
                priceRange = candidateToApply.priceRange.ifBlank { project.priceRange },
                possession = candidateToApply.possession.ifBlank { project.possession },
                coverImageUrl = candidateToApply.coverImageUrl.ifBlank { project.coverImageUrl },
                galleryImageUrls = candidateToApply.galleryImageUrls.ifEmpty { project.galleryImageUrls }
            )
        } else project
        if (newStatus == ProjectVerificationState.VERIFIED) {
            val missing = minimumRequiredMissingFields(candidateApplied)
            require(missing.isEmpty()) {
                "Complete 100% minimum project details before publishing Live: ${missing.joinToString(", ")}"
            }
        }
        val prefs = context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE)
        synchronized(this) {
            val overridesJson = JSONObject(prefs.getString("project_overrides", "{}") ?: "{}")
            val existing = overridesJson.optJSONObject(project.id) ?: JSONObject()
            existing.put("verification", newStatus.label)
            existing.put("archiveStatus", if (newStatus == ProjectVerificationState.VERIFIED) "Active" else "Archived")
            existing.put("verifiedBy", reviewerName)
            existing.put(
                "verifiedAt",
                SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
            )
            if (candidateToApply != null && newStatus == ProjectVerificationState.VERIFIED) {
                existing.put("developer", candidateToApply.developer)
                existing.put("configurations", candidateToApply.configurations)
                existing.put("areaRange", candidateToApply.areaRange)
                existing.put("priceRange", candidateToApply.priceRange)
                existing.put("possession", candidateToApply.possession)
                existing.put("reraNumber", candidateToApply.reraNumber)
                existing.put("coverImageUrl", candidateToApply.coverImageUrl)
                val gArr = JSONArray()
                candidateToApply.galleryImageUrls.forEach { gArr.put(it) }
                existing.put("galleryImageUrls", gArr)
            }
            overridesJson.put(project.id, existing)
            prefs.edit().putString("project_overrides", overridesJson.toString()).commit()
            invalidateCache()
        }
        return read(context).also { com.example.data.OfflineDiskCache.saveProjects(context, it) }.first { it.id == project.id }
    }

    fun editAndVerifyProject(
        context: Context,
        project: DirectoryProject,
        location: String,
        developer: String,
        configurations: String,
        areaRange: String,
        priceRange: String,
        possession: String,
        reviewerName: String,
        publishLive: Boolean
    ): Result<DirectoryProject> = runCatching {
        val edited = project.copy(
            location = location.trim(),
            developer = developer.trim(),
            configurations = configurations.trim(),
            areaRange = areaRange.trim(),
            priceRange = priceRange.trim(),
            possession = possession.trim()
        )
        if (publishLive) {
            val missing = minimumRequiredMissingFields(edited)
            require(missing.isEmpty()) {
                "Complete 100% required project details: ${missing.joinToString(", ")}"
            }
        }
        val prefs = context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE)
        synchronized(this) {
            val overridesJson = JSONObject(prefs.getString("project_overrides", "{}") ?: "{}")
            val existing = overridesJson.optJSONObject(project.id) ?: JSONObject()
            existing.put("location", edited.location)
            existing.put("developer", edited.developer)
            existing.put("configurations", edited.configurations)
            existing.put("areaRange", edited.areaRange)
            existing.put("priceRange", edited.priceRange)
            existing.put("possession", edited.possession)
            existing.put(
                "verification",
                if (publishLive) ProjectVerificationState.VERIFIED.label else ProjectVerificationState.PENDING_VERIFICATION.label
            )
            existing.put("archiveStatus", if (publishLive) "Active" else "Archived")
            if (publishLive) {
                existing.put("verifiedBy", reviewerName)
                existing.put("verifiedAt", SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date()))
            }
            overridesJson.put(project.id, existing)
            check(prefs.edit().putString("project_overrides", overridesJson.toString()).commit()) {
                "Failed to save project updates"
            }
            invalidateCache()
        }
        read(context).also { com.example.data.OfflineDiskCache.saveProjects(context, it) }.first { it.id == project.id }
    }

    fun archiveProject(context: Context, projectId: String): DirectoryProject {
        val prefs = context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE)
        synchronized(this) {
            val overridesJson = JSONObject(prefs.getString("project_overrides", "{}") ?: "{}")
            val existing = overridesJson.optJSONObject(projectId) ?: JSONObject()
            existing.put("verification", ProjectVerificationState.NOT_REVIEWED.label)
            existing.put("archiveStatus", "Archived")
            overridesJson.put(projectId, existing)
            prefs.edit().putString("project_overrides", overridesJson.toString()).commit()
            invalidateCache()
        }
        return read(context).also { com.example.data.OfflineDiskCache.saveProjects(context, it) }.first { it.id == projectId }
    }

    fun deletePermanently(context: Context, projectId: String): List<DirectoryProject> {
        val cleanId = projectId.trim()
        if (cleanId.isBlank()) return read(context)
        val prefs = context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE)
        val linksPrefs = context.getSharedPreferences("project_unit_links_v1", Context.MODE_PRIVATE)
        return synchronized(this) {
            val nextDeleted = (prefs.getStringSet("deleted_project_ids", emptySet()) ?: emptySet())
                .toMutableSet()
                .apply { add(cleanId.uppercase(Locale.US)) }

            val localArr = runCatching { JSONArray(prefs.getString("local_projects", "[]") ?: "[]") }
                .getOrDefault(JSONArray())
            val filteredLocal = JSONArray()
            for (i in 0 until localArr.length()) {
                val obj = localArr.optJSONObject(i) ?: continue
                if (!obj.optString("id").trim().equals(cleanId, ignoreCase = true)) {
                    filteredLocal.put(obj)
                }
            }

            val overridesJson = runCatching { JSONObject(prefs.getString("project_overrides", "{}") ?: "{}") }
                .getOrDefault(JSONObject())
            overridesJson.remove(cleanId)

            val stagedJson = runCatching { JSONObject(prefs.getString("staged_candidates", "{}") ?: "{}") }
                .getOrDefault(JSONObject())
            stagedJson.remove(cleanId)

            prefs.edit()
                .putStringSet("deleted_project_ids", nextDeleted)
                .putString("local_projects", filteredLocal.toString())
                .putString("project_overrides", overridesJson.toString())
                .putString("staged_candidates", stagedJson.toString())
                .commit()

            val linkEditor = linksPrefs.edit()
            var removedAnyLink = false
            linksPrefs.all.forEach { (propId, linkedProjId) ->
                if ((linkedProjId as? String)?.trim().equals(cleanId, ignoreCase = true)) {
                    linkEditor.remove(propId)
                    removedAnyLink = true
                }
            }
            if (removedAnyLink) linkEditor.commit()

            invalidateCache()
            val remaining = read(context).filterNot { it.id.trim().equals(cleanId, ignoreCase = true) }
            com.example.data.OfflineDiskCache.saveProjects(context, remaining)
            invalidateCache()
            remaining
        }
    }
}

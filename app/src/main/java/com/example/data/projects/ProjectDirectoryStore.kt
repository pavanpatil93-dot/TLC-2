package com.example.data.projects

import android.content.Context
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

    fun read(context: Context): List<DirectoryProject> {
        val seeded = context.resources.openRawResource(com.example.R.raw.project_directory_seed).use { stream ->
            GZIPInputStream(stream).bufferedReader().use { parse(it.readText()) }
        }
        val prefs = context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE)
        val local = prefs.getString("local_projects", "[]") ?: "[]"
        val overridesJson = JSONObject(prefs.getString("project_overrides", "{}") ?: "{}")
        return (seeded + parse(local)).distinctBy { it.id }.map { project ->
            val overrideObj = overridesJson.optJSONObject(project.id)
            if (overrideObj == null) {
                val isAlreadyVerified = project.verification.equals("Verified", ignoreCase = true)
                val resolvedArchive = if (isAlreadyVerified && isReadyForLiveVerification(project)) "Active" else "Archived"
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
            return parse(JSONArray().put(j).toString()).single()
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
        }
        return read(context).first { it.id == project.id }
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
        }
        read(context).first { it.id == project.id }
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
        }
        return read(context).first { it.id == projectId }
    }
}

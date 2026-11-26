package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.TlcAreaCalculationResult
import com.example.data.TlcDualRateResult
import com.example.data.master.MasterProjectDatabase
import com.example.data.master.ProjectStatus
import com.example.data.master.ProjectType
import com.example.data.projects.ProjectDirectoryStore
import com.example.data.pune.LocationEntity
import com.example.data.pune.PuneIntelligenceRepository
import com.example.model.CalculationUtils
import com.example.ui.screens.formatIndianShortPreview
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class ProjectDirectoryAutoFillEntry(
    val projectId: String,
    val projectName: String,
    val location: String,
    val microMarket: String = "",
    val developer: String = "",
    val puneZone: String = "",
    val jurisdiction: String = "",
    val ocStatus: String = "",
    val isCommercial: Boolean = true
)

object TlcProjectDirectoryHelper {

    fun formatPuneZone(rawRegion: String?, location: String): String {
        val clean = rawRegion?.trim().orEmpty()
        if (clean.isNotBlank()) {
            val lower = clean.lowercase(Locale.ENGLISH)
            return when {
                lower.contains("east") -> "East Pune"
                lower.contains("west") -> "West Pune"
                lower.contains("north") -> "North Pune"
                lower.contains("south") -> "South Pune"
                lower.contains("central") -> "Central Pune"
                lower.contains("pcmc") -> "PCMC"
                else -> clean
            }
        }
        val loc = location.trim().lowercase(Locale.ENGLISH)
        return when {
            loc.contains("kharadi") || loc.contains("viman") || loc.contains("kalyani") ||
                loc.contains("magarpatta") || loc.contains("hadapsar") || loc.contains("koregaon") ||
                loc.contains("wagholi") || loc.contains("mundhwa") || loc.contains("yerwada") -> "East Pune"
            loc.contains("baner") || loc.contains("balewadi") || loc.contains("wakad") ||
                loc.contains("hinjewadi") || loc.contains("aundh") || loc.contains("pashan") ||
                loc.contains("kothrud") || loc.contains("bavdhan") || loc.contains("sus") -> "West Pune"
            loc.contains("pimpri") || loc.contains("chinchwad") || loc.contains("bhosari") ||
                loc.contains("chakan") || loc.contains("nigdi") || loc.contains("akurdi") ||
                loc.contains("moshi") || loc.contains("ravet") -> "PCMC"
            loc.contains("shivaji") || loc.contains("deccan") || loc.contains("camp") ||
                loc.contains("fc road") || loc.contains("jm road") || loc.contains("sb road") ||
                loc.contains("swargate") || loc.contains("boat club") -> "Central Pune"
            loc.contains("undri") || loc.contains("nibm") || loc.contains("kondhwa") ||
                loc.contains("bibwewadi") || loc.contains("katraj") || loc.contains("sinhagad") ||
                loc.contains("wanowrie") -> "South Pune"
            else -> ""
        }
    }

    fun inferJurisdiction(rawJurisdiction: String?, location: String): String {
        val clean = rawJurisdiction?.trim().orEmpty()
        if (clean.isNotBlank()) return clean
        val loc = location.trim().lowercase(Locale.ENGLISH)
        return when {
            loc.contains("wakad") || loc.contains("pimpri") || loc.contains("chinchwad") ||
                loc.contains("ravet") || loc.contains("punawale") || loc.contains("tathawade") ||
                loc.contains("bhosari") || loc.contains("moshi") || loc.contains("nigdi") ||
                loc.contains("akurdi") || loc.contains("pimple") -> "PCMC"
            loc.contains("hinjewadi") || loc.contains("chakan") || loc.contains("talegaon") ||
                loc.contains("ranjangaon") || loc.contains("shirur") || loc.contains("marunji") ||
                loc.contains("maaan") || loc.contains("maan") -> "PMRDA"
            loc.contains("camp") || loc.contains("kirkee") || loc.contains("khadki") -> "Cantonment"
            loc.isNotBlank() -> "PMC"
            else -> ""
        }
    }

    private fun normalizeOcStatus(rawStatus: String?): String {
        val clean = rawStatus?.trim().orEmpty()
        if (clean.isBlank()) return ""
        val lower = clean.lowercase(Locale.ENGLISH)
        return when {
            lower.contains("oc received") || lower == "received" -> "OC Received"
            lower.contains("part") -> "Part OC"
            lower.contains("completed") || lower.contains("ready") -> "OC Received"
            lower.contains("under") || lower.contains("ongoing") || lower.contains("construction") -> "Under Construction"
            else -> clean
        }
    }

    suspend fun loadUnifiedProjectDirectory(
        context: Context,
        puneRepo: PuneIntelligenceRepository,
        masterLocations: List<LocationEntity>
    ): Map<String, ProjectDirectoryAutoFillEntry> = withContext(Dispatchers.IO) {
        val locById = masterLocations.associateBy { it.id }
        val result = LinkedHashMap<String, ProjectDirectoryAutoFillEntry>()

        // 1. Synced Project Directory (Commercial Projects & Residential Projects tabs)
        runCatching { ProjectDirectoryStore.read(context) }.getOrDefault(emptyList()).forEach { dp ->
            val name = dp.name.trim()
            if (name.isNotBlank()) {
                val rawObj = dp.raw.optJSONObject("raw") ?: dp.raw
                val rawZone = rawObj.optString("Pune Zone").ifBlank { rawObj.optString("Zone") }
                val rawJurisdiction = rawObj.optString("Jurisdiction").ifBlank { rawObj.optString("Authority") }
                val rawOc = rawObj.optString("OC Status").ifBlank { rawObj.optString("Status").ifBlank { dp.possession } }
                val rawMicro = rawObj.optString("Micro-Market").ifBlank { rawObj.optString("Micro Market") }
                val loc = dp.location.trim()
                result[name] = ProjectDirectoryAutoFillEntry(
                    projectId = dp.id.trim(),
                    projectName = name,
                    location = loc,
                    microMarket = rawMicro.trim(),
                    developer = dp.developer.trim(),
                    puneZone = formatPuneZone(rawZone, loc),
                    jurisdiction = inferJurisdiction(rawJurisdiction, loc),
                    ocStatus = normalizeOcStatus(rawOc),
                    isCommercial = !dp.division.contains("Residential", ignoreCase = true)
                )
            }
        }

        // 2. Room MasterProjectDatabase (`projects_master`)
        runCatching {
            MasterProjectDatabase.getDatabase(context).masterProjectDao().getAllProjectsSync()
        }.getOrDefault(emptyList()).forEach { proj ->
            val name = proj.projectName.trim()
            if (name.isNotBlank() && !result.containsKey(name)) {
                val loc = proj.microMarket.ifBlank { proj.location }.trim()
                val zone = formatPuneZone(proj.location, loc)
                val oc = when (proj.status) {
                    ProjectStatus.READY, ProjectStatus.COMPLETED -> "OC Received"
                    ProjectStatus.UNDER_CONSTRUCTION -> "Under Construction"
                    ProjectStatus.PRE_LAUNCH -> "Pre-Launch (Awaited)"
                }
                result[name] = ProjectDirectoryAutoFillEntry(
                    projectId = proj.projectId.trim(),
                    projectName = name,
                    location = loc,
                    microMarket = if (proj.microMarket.isNotBlank() && !proj.microMarket.equals(loc, ignoreCase = true)) proj.microMarket else "",
                    developer = proj.developerName.trim(),
                    puneZone = zone,
                    jurisdiction = inferJurisdiction(null, loc),
                    ocStatus = oc,
                    isCommercial = proj.projectType == ProjectType.COMMERCIAL
                )
            }
        }

        // 3. Room PuneIntelligenceDatabase (`commercial_projects`)
        runCatching { puneRepo.getAllCommercialProjects() }.getOrDefault(emptyList()).forEach { cp ->
            val name = cp.name.trim()
            if (name.isNotBlank() && !result.containsKey(name)) {
                val locEntity = locById[cp.locationId]
                val locName = locEntity?.name?.trim().orEmpty()
                result[name] = ProjectDirectoryAutoFillEntry(
                    projectId = cp.id.trim(),
                    projectName = name,
                    location = locName,
                    puneZone = formatPuneZone(locEntity?.region, locName),
                    jurisdiction = inferJurisdiction(null, locName),
                    ocStatus = normalizeOcStatus(cp.projectStatus),
                    isCommercial = true
                )
            }
        }

        // 4. Room PuneIntelligenceDatabase (`residential_projects`)
        runCatching { puneRepo.getAllResidentialProjects() }.getOrDefault(emptyList()).forEach { rp ->
            val name = rp.name.trim()
            if (name.isNotBlank() && !result.containsKey(name)) {
                val locEntity = locById[rp.locationId]
                val locName = locEntity?.name?.trim().orEmpty()
                result[name] = ProjectDirectoryAutoFillEntry(
                    projectId = rp.id.trim(),
                    projectName = name,
                    location = locName,
                    puneZone = formatPuneZone(locEntity?.region, locName),
                    jurisdiction = inferJurisdiction(null, locName),
                    ocStatus = "",
                    isCommercial = false
                )
            }
        }

        result
    }
}

@Composable
fun YesNoToggleField(
    label: String,
    selectedValue: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "yes_no"
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = SecondaryText,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Yes", "No").forEach { option ->
                val isSelected = selectedValue.equals(option, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(if (isSelected) null else option) },
                    label = {
                        Text(
                            text = option,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryActionOrange.copy(alpha = 0.16f),
                        selectedLabelColor = PrimaryActionOrange
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = BorderGrey,
                        selectedBorderColor = PrimaryActionOrange
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .testTag("${testTagPrefix}_${option.lowercase(Locale.ENGLISH)}")
                )
            }
        }
    }
}

@Composable
fun LiveAreaFinancialPreviewCard(
    areaResult: TlcAreaCalculationResult,
    dualRates: TlcDualRateResult,
    primaryAmount: Double,
    isLease: Boolean,
    grossRoiPercent: Double?,
    isPreLeased: Boolean,
    isShopOrShowroom: Boolean,
    modifier: Modifier = Modifier
) {
    val hasMetrics = primaryAmount > 0.0 ||
        areaResult.totalCarpetSqFt > 0.0 ||
        (areaResult.totalBuiltUpSqFt ?: 0.0) > 0.0 ||
        grossRoiPercent != null

    if (!hasMetrics) return

    Surface(
        color = SurfaceMuted,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_auto_calc_preview_card")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val priceHeader = if (primaryAmount > 0.0) {
                    formatIndianShortPreview(primaryAmount) + if (isLease) "/mo" else ""
                } else {
                    "Live Auto-Calculations"
                }
                Text(
                    text = priceHeader,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                areaResult.loadingPercent?.let { loadPct ->
                    val loadTag = when {
                        areaResult.isManualBuiltUpOrLoadingOverride -> String.format(Locale.US, "Load: %.1f%% (Actual)", loadPct)
                        else -> String.format(Locale.US, "Load: %.0f%% (Auto)", loadPct)
                    }
                    Text(
                        text = loadTag,
                        color = FocusOutlineColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (areaResult.totalCarpetSqFt > 0.0 || (areaResult.totalBuiltUpSqFt ?: 0.0) > 0.0) {
                val carpetLabel = buildString {
                    append("Total Carpet: ${CalculationUtils.formatNumber(areaResult.totalCarpetSqFt)} sq.ft")
                    if (isShopOrShowroom && (areaResult.mezzanineCarpetSqFt ?: 0.0) > 0.0) {
                        append(" (GF ${CalculationUtils.formatNumber(areaResult.groundFloorCarpetSqFt ?: 0.0)} + Mezz ${CalculationUtils.formatNumber(areaResult.mezzanineCarpetSqFt ?: 0.0)})")
                    } else if (areaResult.isCarpetAutoCalculated) {
                        append(" (Auto)")
                    }
                }
                val builtUpLabel = areaResult.totalBuiltUpSqFt?.takeIf { it > 0.0 }?.let { b ->
                    "Built-Up: ${CalculationUtils.formatNumber(b)} sq.ft" + if (areaResult.isBuiltUpAutoCalculated) " (Auto)" else ""
                }
                Text(
                    text = listOfNotNull(carpetLabel.takeIf { areaResult.totalCarpetSqFt > 0.0 }, builtUpLabel).joinToString("  •  "),
                    color = SecondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            val rateUnit = if (isLease) "/sq.ft/mo" else "/sq.ft"
            val rateParts = buildList {
                dualRates.ratePerCarpetSqFt?.let {
                    add("Carpet: ${CalculationUtils.formatRateValue(it)}$rateUnit")
                }
                dualRates.ratePerBuiltUpSqFt?.let {
                    add("Built-Up: ${CalculationUtils.formatRateValue(it)}$rateUnit")
                }
            }
            if (rateParts.isNotEmpty()) {
                Text(
                    text = rateParts.joinToString("  |  "),
                    color = PrimaryActionOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isPreLeased && grossRoiPercent != null) {
                Text(
                    text = String.format(Locale.US, "Gross ROI / Yield: %.2f%% p.a.", grossRoiPercent),
                    color = StatusSuccessGreen,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

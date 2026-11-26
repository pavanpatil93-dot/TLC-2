package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationServices
import com.example.inventory.InventoryQuality
import com.example.model.*
import com.example.data.PropertiesLocalDatabase
import com.example.data.pune.*
import com.example.ui.components.PropertyCard
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import kotlin.math.*

data class GeoPoint(val lat: Double, val lng: Double)

object PuneGeoUtils {
    val microMarkets = mapOf(
        "baner" to GeoPoint(18.5590, 73.7868),
        "kharadi" to GeoPoint(18.5515, 73.9462),
        "hinjewadi" to GeoPoint(18.5912, 73.7389),
        "hinjawadi" to GeoPoint(18.5912, 73.7389),
        "kalyani nagar" to GeoPoint(18.5463, 73.9033),
        "viman nagar" to GeoPoint(18.5679, 73.9143),
        "balewadi" to GeoPoint(18.5789, 73.7707),
        "hadapsar" to GeoPoint(18.5089, 73.9259),
        "koregaon park" to GeoPoint(18.5362, 73.8940),
        "wakad" to GeoPoint(18.5987, 73.7661),
        "kothrud" to GeoPoint(18.5074, 73.8077),
        "pimple saudagar" to GeoPoint(18.5987, 73.7997),
        "senapati bapat road" to GeoPoint(18.5314, 73.8298),
        "sb road" to GeoPoint(18.5314, 73.8298),
        "shivaji nagar" to GeoPoint(18.5314, 73.8446),
        "bund garden" to GeoPoint(18.5362, 73.8786),
        "camp" to GeoPoint(18.5158, 73.8786),
        "aundh" to GeoPoint(18.5580, 73.8075),
        "bavdhan" to GeoPoint(18.5117, 73.7806),
        "magarpatta" to GeoPoint(18.5186, 73.9288),
        "deccan" to GeoPoint(18.5167, 73.8417),
        "chinchwad" to GeoPoint(18.6298, 73.7997),
        "pimpri" to GeoPoint(18.6298, 73.8120),
        "punawale" to GeoPoint(18.6250, 73.7360),
        "tathawade" to GeoPoint(18.6110, 73.7520),
        "ravet" to GeoPoint(18.6480, 73.7490),
        "undri" to GeoPoint(18.4600, 73.9100),
        "kondhwa" to GeoPoint(18.4700, 73.8800),
        "wagholi" to GeoPoint(18.5800, 73.9800),
        "warje" to GeoPoint(18.4800, 73.8000),
        "sinhagad road" to GeoPoint(18.4700, 73.8300)
    )

    fun parseCoordsString(coordStr: String?): GeoPoint? {
        if (coordStr.isNullOrBlank()) return null
        val parts = coordStr.split(",")
        if (parts.size >= 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) {
                return GeoPoint(lat, lng)
            }
        }
        return null
    }

    fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).pow(2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}

enum class MapViewMode {
    MAP,
    LIST
}

enum class NearMeRadius {
    OFF,
    KM_2,
    KM_5
}

data class BuildingCluster(
    val key: String,
    val title: String,
    val geo: GeoPoint,
    val properties: List<PropertyItem>
) {
    val isCommercial: Boolean get() = properties.firstOrNull()?.isCommercial() ?: true
}

data class BuilderProjectPin(
    val id: String,
    val name: String,
    val geo: GeoPoint,
    val isCommercial: Boolean,
    val assetClass: String,
    val locationName: String
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapSearchScreen(
    allProperties: List<PropertyItem>,
    initialTargetProperty: PropertyItem? = null,
    initialPortfolio: Brand? = null,
    initialLocationFilter: String? = null,
    onPropertyClick: (PropertyItem) -> Unit,
    onShareWhatsApp: (PropertyItem) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val puneRepo = remember { PuneIntelligenceRepository.getInstance(context) }

    var commercialProjects by remember { mutableStateOf<List<CommercialProjectEntity>>(emptyList()) }
    var residentialProjects by remember { mutableStateOf<List<ResidentialProjectEntity>>(emptyList()) }
    var locations by remember { mutableStateOf<List<LocationEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            commercialProjects = puneRepo.getAllCommercialProjects()
            residentialProjects = puneRepo.getAllResidentialProjects()
            locations = puneRepo.getAllLocations()
        }
    }

    // State
    var showFilterScreen by remember { mutableStateOf(initialTargetProperty == null && initialLocationFilter == null) }
    var viewMode by remember { mutableStateOf(MapViewMode.MAP) }

    // Filter Fields & Search Query
    var selectedPortfolio by remember { mutableStateOf(initialPortfolio ?: Brand.COMMERCIAL) }
    var selectedTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedBhkFilter by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedTransactionType by remember { mutableStateOf("Both") }
    var minPriceText by remember { mutableStateOf("") }
    var maxPriceText by remember { mutableStateOf("") }
    var minAreaText by remember { mutableStateOf("") }
    var maxAreaText by remember { mutableStateOf("") }
    var selectedLocation by remember { mutableStateOf(initialLocationFilter ?: "All Areas") }
    var mapSearchQuery by remember { mutableStateOf("") }

    var selectedCategoryFilter by remember { mutableStateOf("All") }

    // Near Me GPS Radius Mode
    var nearMeRadius by remember { mutableStateOf(NearMeRadius.OFF) }
    var userGpsLocation by remember { mutableStateOf<GeoPoint?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        userGpsLocation = GeoPoint(loc.latitude, loc.longitude)
                    } else {
                        userGpsLocation = GeoPoint(18.5204, 73.8567)
                    }
                }
            } catch (e: Exception) {
                userGpsLocation = GeoPoint(18.5204, 73.8567)
            }
        } else {
            Toast.makeText(context, "Location permission required for Near Me mode", Toast.LENGTH_SHORT).show()
            nearMeRadius = NearMeRadius.OFF
        }
    }

    var showUnpinnedSheet by remember { mutableStateOf(false) }
    var selectedBuildingCluster by remember { mutableStateOf<BuildingCluster?>(null) }
    var selectedBuilderProject by remember { mutableStateOf<BuilderProjectPin?>(null) }
    var propertyToPin by remember { mutableStateOf<PropertyItem?>(null) }

    // Compute Filtered Properties — all Active Direct Units (including Pending / Not Confirmed; excluding Archived/Closed & Builder Stock)
    val filteredProperties = remember(
        allProperties, initialTargetProperty, selectedPortfolio, selectedTypes, selectedBhkFilter,
        selectedTransactionType, minPriceText, maxPriceText, minAreaText, maxAreaText, selectedLocation, mapSearchQuery
    ) {
        allProperties.filter { prop ->
            val isExplicitTarget = initialTargetProperty != null && prop.id == initialTargetProperty.id
            if (!isExplicitTarget) {
                if (!InventoryQuality.isActiveInventory(prop)) return@filter false
                if (InventoryQuality.isBuilderProjectStock(prop)) return@filter false
            }
            val matchesPortfolio = when (selectedPortfolio) {
                Brand.COMMERCIAL -> prop.isCommercial()
                Brand.RESIDENTIAL -> prop.isResidential()
            }
            if (!matchesPortfolio && !isExplicitTarget) return@filter false

            if (selectedTypes.isNotEmpty()) {
                val propTypeLower = prop.propertyType.lowercase()
                val matchesType = selectedTypes.any { type ->
                    val t = type.lowercase()
                    propTypeLower.contains(t) ||
                            (t == "office" && prop.isCommercial()) ||
                            (t == "apartment" && (propTypeLower.contains("flat") || propTypeLower.contains("ready"))) ||
                            (t == "villa" && propTypeLower.contains("bungalow"))
                }
                if (!matchesType) return@filter false
            }

            if (selectedBhkFilter.isNotEmpty() && prop.isResidential()) {
                val propBhk = prop.bhk?.uppercase() ?: ""
                val matchesBhk = selectedBhkFilter.any { bhk ->
                    if (bhk == "4+ BHK") {
                        propBhk.contains("4.") || propBhk.contains("5") || propBhk.contains("6")
                    } else {
                        propBhk.contains(bhk.replace(" BHK", "").trim())
                    }
                }
                if (!matchesBhk) return@filter false
            }

            if (selectedTransactionType != "Both") {
                val isLease = prop.tab == InventoryTab.OFF_LEASE || prop.tab == InventoryTab.SHOP_LEASE || prop.tab == InventoryTab.RESI_RENT
                if (selectedTransactionType == "Lease / Rent" && !isLease) return@filter false
                if (selectedTransactionType == "Outright / Sale" && isLease) return@filter false
            }

            val minP = minPriceText.toDoubleOrNull()
            val maxP = maxPriceText.toDoubleOrNull()
            if (minP != null && prop.priceOrRent < minP) return@filter false
            if (maxP != null && prop.priceOrRent > maxP) return@filter false

            val minA = minAreaText.toDoubleOrNull()
            val maxA = maxAreaText.toDoubleOrNull()
            if (minA != null && prop.carpetAreaSqFt < minA) return@filter false
            if (maxA != null && prop.carpetAreaSqFt > maxA) return@filter false

            if (selectedLocation != "All Areas") {
                val locLower = prop.location.lowercase()
                val projLower = prop.projectName.lowercase()
                val targetLoc = selectedLocation.lowercase()
                if (!locLower.contains(targetLoc) && !projLower.contains(targetLoc)) return@filter false
            }

            if (mapSearchQuery.isNotBlank()) {
                val q = mapSearchQuery.lowercase()
                val matchesQuery = prop.projectName.lowercase().contains(q) ||
                        prop.location.lowercase().contains(q) ||
                        prop.propertyCode.lowercase().contains(q) ||
                        prop.unitNumber.lowercase().contains(q)
                if (!matchesQuery) return@filter false
            }

            true
        }
    }

    // Coordinate resolution helper for Batch 36:
    val resolvedCoordinatesMap = remember(filteredProperties, commercialProjects, residentialProjects, locations) {
        val map = mutableMapOf<String, GeoPoint>()
        val commMap = commercialProjects.associateBy { it.name.lowercase().trim() }
        val resMap = residentialProjects.associateBy { it.name.lowercase().trim() }
        val locMap = locations.associateBy { it.id }

        filteredProperties.forEach { prop ->
            val lat = prop.latitude ?: prop.lat
            val lng = prop.longitude ?: prop.lng
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) {
                map[prop.id] = GeoPoint(lat, lng)
            } else {
                val pName = prop.projectName.lowercase().trim()
                val commMatch = commMap[pName] ?: commMap.entries.find { pName.contains(it.key) || it.key.contains(pName) }?.value
                if (commMatch != null) {
                    val gp = PuneGeoUtils.parseCoordsString(commMatch.coordinates)
                    if (gp != null) {
                        map[prop.id] = gp
                    } else {
                        val loc = locMap[commMatch.locationId]
                        if (loc != null && loc.geoLat != null && loc.geoLon != null) {
                            map[prop.id] = GeoPoint(loc.geoLat, loc.geoLon)
                        }
                    }
                } else {
                    val resMatch = resMap[pName] ?: resMap.entries.find { pName.contains(it.key) || it.key.contains(pName) }?.value
                    if (resMatch != null) {
                        val loc = locMap[resMatch.locationId]
                        if (loc != null && loc.geoLat != null && loc.geoLon != null) {
                            map[prop.id] = GeoPoint(loc.geoLat, loc.geoLon)
                        } else {
                            val micro = PuneGeoUtils.microMarkets[prop.location.lowercase().trim()]
                            if (micro != null) map[prop.id] = micro
                        }
                    } else {
                        val micro = PuneGeoUtils.microMarkets[prop.location.lowercase().trim()]
                        if (micro != null) map[prop.id] = micro
                    }
                }
            }
        }
        map
    }

    val pinnedProperties = remember(filteredProperties, resolvedCoordinatesMap) {
        filteredProperties.filter { resolvedCoordinatesMap.containsKey(it.id) }
    }

    val unpinnedProperties = remember(filteredProperties, resolvedCoordinatesMap) {
        filteredProperties.filter { !resolvedCoordinatesMap.containsKey(it.id) }
    }

    // Group/cluster markers by projectKey OR ~0.0005 deg (~50m) so same-building units merge into ONE clean pin
    val buildingClusters = remember(pinnedProperties, resolvedCoordinatesMap) {
        val groups = LinkedHashMap<String, MutableList<PropertyItem>>()
        val groupGeos = LinkedHashMap<String, GeoPoint>()

        pinnedProperties.forEach { prop ->
            val geo = resolvedCoordinatesMap[prop.id]!!
            val normalizedProjectKey = (prop.projectId?.trim()?.takeIf { it.isNotBlank() }
                ?: prop.projectName.lowercase().replace(Regex("[^a-z0-9]"), ""))
            val gridLat = (geo.lat / 0.0005).roundToInt()
            val gridLng = (geo.lng / 0.0005).roundToInt()
            val gridKey = "grid_${gridLat}_${gridLng}"

            val matchedKey = groups.keys.firstOrNull { existingKey ->
                val existingGeo = groupGeos[existingKey]!!
                val within50m = abs(existingGeo.lat - geo.lat) <= 0.0005 && abs(existingGeo.lng - geo.lng) <= 0.0005
                val sameProject = normalizedProjectKey.isNotBlank() && existingKey == "prj_$normalizedProjectKey"
                sameProject || within50m
            } ?: if (normalizedProjectKey.isNotBlank()) "prj_$normalizedProjectKey" else gridKey

            groups.getOrPut(matchedKey) { mutableListOf() }.add(prop)
            groupGeos.putIfAbsent(matchedKey, geo)
        }

        groups.map { (key, props) ->
            val first = props.first()
            val avgLat = props.mapNotNull { resolvedCoordinatesMap[it.id]?.lat }.average()
            val avgLng = props.mapNotNull { resolvedCoordinatesMap[it.id]?.lng }.average()
            BuildingCluster(
                key = key,
                title = first.projectName.ifBlank { first.location },
                geo = GeoPoint(avgLat, avgLng),
                properties = props
            )
        }
    }

    val builderProjectPins = remember(commercialProjects, residentialProjects, locations, selectedPortfolio) {
        val pins = mutableListOf<BuilderProjectPin>()
        val locMap = locations.associateBy { it.id }

        if (selectedPortfolio == Brand.COMMERCIAL || selectedPortfolio == null) {
            commercialProjects.forEach { cp ->
                val gp = PuneGeoUtils.parseCoordsString(cp.coordinates)
                val loc = locMap[cp.locationId]
                val finalGeo = gp ?: (if (loc != null && loc.geoLat != null && loc.geoLon != null) GeoPoint(loc.geoLat, loc.geoLon) else null)
                if (finalGeo != null) {
                    pins.add(
                        BuilderProjectPin(
                            id = cp.id,
                            name = cp.name,
                            geo = finalGeo,
                            isCommercial = true,
                            assetClass = cp.assetClass,
                            locationName = loc?.name ?: "Pune"
                        )
                    )
                }
            }
        }

        if (selectedPortfolio == Brand.RESIDENTIAL || selectedPortfolio == null) {
            residentialProjects.forEach { rp ->
                val loc = locMap[rp.locationId]
                if (loc != null && loc.geoLat != null && loc.geoLon != null) {
                    pins.add(
                        BuilderProjectPin(
                            id = rp.id,
                            name = rp.name,
                            geo = GeoPoint(loc.geoLat, loc.geoLon),
                            isCommercial = false,
                            assetClass = rp.assetClass,
                            locationName = loc.name
                        )
                    )
                }
            }
        }
        pins
    }

    val radiusFilteredClusters = remember(buildingClusters, nearMeRadius, userGpsLocation) {
        if (nearMeRadius == NearMeRadius.OFF || userGpsLocation == null) {
            buildingClusters
        } else {
            val maxKm = if (nearMeRadius == NearMeRadius.KM_2) 2.0 else 5.0
            buildingClusters.filter { cluster ->
                PuneGeoUtils.calculateDistanceKm(
                    userGpsLocation!!.lat, userGpsLocation!!.lng,
                    cluster.geo.lat, cluster.geo.lng
                ) <= maxKm
            }
        }
    }

    val radiusFilteredBuilderPins = remember(builderProjectPins, nearMeRadius, userGpsLocation) {
        if (nearMeRadius == NearMeRadius.OFF || userGpsLocation == null) {
            builderProjectPins
        } else {
            val maxKm = if (nearMeRadius == NearMeRadius.KM_2) 2.0 else 5.0
            builderProjectPins.filter { pin ->
                PuneGeoUtils.calculateDistanceKm(
                    userGpsLocation!!.lat, userGpsLocation!!.lng,
                    pin.geo.lat, pin.geo.lng
                ) <= maxKm
            }
        }
    }

    BackHandler {
        if (!showFilterScreen && initialTargetProperty == null) {
            showFilterScreen = true
        } else {
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .testTag("map_search_screen")
    ) {
        // TOP APP BAR
        Surface(
            color = BrandCharcoalSurface,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(
                            onClick = {
                                if (!showFilterScreen && initialTargetProperty == null) {
                                    showFilterScreen = true
                                } else {
                                    onBack()
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(BrandCharcoalCard)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Map, contentDescription = null, tint = BrandGold, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Property Map",
                                    color = TextPrimaryDark,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = if (showFilterScreen) "Select Search Criteria" else "${filteredProperties.size} Properties · ${buildingClusters.size} Buildings",
                                color = BrandGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (!showFilterScreen) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // "Near Me" Pill Button
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (nearMeRadius != NearMeRadius.OFF) BrandGold else BrandCharcoalCard)
                                    .border(1.dp, if (nearMeRadius != NearMeRadius.OFF) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        when (nearMeRadius) {
                                            NearMeRadius.OFF -> {
                                                nearMeRadius = NearMeRadius.KM_2
                                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                                    try {
                                                        LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener { loc ->
                                                            if (loc != null) userGpsLocation = GeoPoint(loc.latitude, loc.longitude)
                                                        }
                                                    } catch (e: Exception) {}
                                                } else {
                                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                                }
                                            }
                                            NearMeRadius.KM_2 -> nearMeRadius = NearMeRadius.KM_5
                                            NearMeRadius.KM_5 -> {
                                                nearMeRadius = NearMeRadius.OFF
                                                userGpsLocation = null
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.NearMe,
                                        contentDescription = null,
                                        tint = if (nearMeRadius != NearMeRadius.OFF) BrandCharcoalDark else BrandGold,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = when (nearMeRadius) {
                                            NearMeRadius.OFF -> "Near Me"
                                            NearMeRadius.KM_2 -> "2 km"
                                            NearMeRadius.KM_5 -> "5 km"
                                        },
                                        color = if (nearMeRadius != NearMeRadius.OFF) BrandCharcoalDark else TextPrimaryDark,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Filter button
                            IconButton(
                                onClick = { showFilterScreen = true },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BrandCharcoalCard)
                                    .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = "Edit Filters", tint = BrandGold)
                            }

                            // Map / List Toggle
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BrandCharcoalCard)
                                    .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                                    .padding(2.dp)
                            ) {
                                Row {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (viewMode == MapViewMode.MAP) BrandGold else Color.Transparent)
                                            .clickable { viewMode = MapViewMode.MAP }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Map",
                                            color = if (viewMode == MapViewMode.MAP) Color.White else TextSecondaryDark,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (viewMode == MapViewMode.LIST) BrandGold else Color.Transparent)
                                            .clickable { viewMode = MapViewMode.LIST }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            "List",
                                            color = if (viewMode == MapViewMode.LIST) Color.White else TextSecondaryDark,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (!showFilterScreen) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val categories = listOf("All", "Commercial", "Residential", "Lease", "Sale", "Direct Units", "Builder Projects", "Unpinned (${unpinnedProperties.size})")
                        items(categories) { cat ->
                            val isSel = selectedCategoryFilter == cat || (cat.startsWith("Unpinned") && selectedCategoryFilter.startsWith("Unpinned"))
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    if (cat.startsWith("Unpinned")) {
                                        showUnpinnedSheet = true
                                    } else {
                                        selectedCategoryFilter = cat
                                        if (cat == "Commercial") selectedPortfolio = Brand.COMMERCIAL
                                        if (cat == "Residential") selectedPortfolio = Brand.RESIDENTIAL
                                        if (cat == "Lease") selectedTransactionType = "Lease / Rent"
                                        if (cat == "Sale") selectedTransactionType = "Outright / Sale"
                                    }
                                },
                                label = { Text(cat, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = BrandCharcoalCard,
                                    labelColor = TextPrimaryDark,
                                    selectedContainerColor = BrandGold,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = mapSearchQuery,
                        onValueChange = { mapSearchQuery = it },
                        placeholder = { Text("Search building name, location or code...", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BrandGold, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (mapSearchQuery.isNotBlank()) {
                                IconButton(onClick = { mapSearchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondaryDark, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandGold,
                            unfocusedBorderColor = BrandCharcoalBorder,
                            focusedContainerColor = BrandCharcoalCard,
                            unfocusedContainerColor = BrandCharcoalCard,
                            focusedTextColor = TextPrimaryDark,
                            unfocusedTextColor = TextPrimaryDark
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }

        if (showFilterScreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "SEARCH FILTERS",
                    color = BrandGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Filter Pune Master Inventory",
                    color = TextPrimaryDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Select Inventory", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PortfolioChip(
                        title = "Commercial Inventory",
                        subtitle = "Office, Shop, Land",
                        isSelected = selectedPortfolio == Brand.COMMERCIAL,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedPortfolio = Brand.COMMERCIAL
                            selectedTypes = emptySet()
                        }
                    )
                    PortfolioChip(
                        title = "Residential Inventory",
                        subtitle = "Flat, Villa, Plot",
                        isSelected = selectedPortfolio == Brand.RESIDENTIAL,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedPortfolio = Brand.RESIDENTIAL
                            selectedTypes = emptySet()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Property Type", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                val availableTypes = if (selectedPortfolio == Brand.COMMERCIAL) {
                    listOf("Office", "Shop", "Showroom", "Warehouse", "Pre-Leased", "Commercial Land")
                } else {
                    listOf("Apartment", "Villa", "Bungalow", "Residential Plot")
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterMultiChip(
                        label = "All Types",
                        isSelected = selectedTypes.isEmpty(),
                        onClick = { selectedTypes = emptySet() }
                    )
                    availableTypes.forEach { type ->
                        val isSel = selectedTypes.contains(type)
                        FilterMultiChip(
                            label = type,
                            isSelected = isSel,
                            onClick = {
                                selectedTypes = if (isSel) selectedTypes - type else selectedTypes + type
                            }
                        )
                    }
                }

                if (selectedPortfolio == Brand.RESIDENTIAL) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Configuration (BHK)", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("1 BHK", "2 BHK", "3 BHK", "4 BHK", "4+ BHK").forEach { bhk ->
                            val isSel = selectedBhkFilter.contains(bhk)
                            FilterMultiChip(
                                label = bhk,
                                isSelected = isSel,
                                onClick = {
                                    selectedBhkFilter = if (isSel) selectedBhkFilter - bhk else selectedBhkFilter + bhk
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Transaction Type", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Both", "Lease / Rent", "Outright / Sale").forEach { tx ->
                        FilterSingleChip(
                            label = tx,
                            isSelected = selectedTransactionType == tx,
                            onClick = { selectedTransactionType = tx }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Min Price / Rent (₹)", color = TextSecondaryDark, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = minPriceText,
                            onValueChange = { minPriceText = it },
                            placeholder = { Text("e.g. 50000", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandGold,
                                unfocusedBorderColor = BrandCharcoalBorder,
                                focusedContainerColor = BrandCharcoalSurface,
                                unfocusedContainerColor = BrandCharcoalSurface,
                                focusedTextColor = TextPrimaryDark,
                                unfocusedTextColor = TextPrimaryDark
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Max Price / Rent (₹)", color = TextSecondaryDark, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = maxPriceText,
                            onValueChange = { maxPriceText = it },
                            placeholder = { Text("e.g. 500000", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandGold,
                                unfocusedBorderColor = BrandCharcoalBorder,
                                focusedContainerColor = BrandCharcoalSurface,
                                unfocusedContainerColor = BrandCharcoalSurface,
                                focusedTextColor = TextPrimaryDark,
                                unfocusedTextColor = TextPrimaryDark
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { showFilterScreen = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "View ${filteredProperties.size} Properties on Map",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            val showDirectUnits = selectedCategoryFilter != "Builder Projects"
            val showBuilderProjects = selectedCategoryFilter != "Direct Units"
            val visibleClusters = if (showDirectUnits) radiusFilteredClusters else emptyList()
            val visibleBuilderPins = if (showBuilderProjects) radiusFilteredBuilderPins else emptyList()

            if (filteredProperties.isEmpty() && visibleBuilderPins.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(BrandCharcoalSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = TextMutedDark, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No Live Verified Properties Match", color = TextPrimaryDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Try widening your price range, carpet area or area criteria.",
                            color = TextSecondaryDark,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = { showFilterScreen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                        ) {
                            Text("Adjust Filters", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (viewMode == MapViewMode.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredProperties) { prop ->
                        PropertyCard(
                            property = prop,
                            isInternalMode = true,
                            isFavorite = false,
                            isInComparison = false,
                            onCardClick = { onPropertyClick(prop) },
                            onToggleFavorite = { },
                            onToggleCompare = { },
                            onShareWhatsApp = { onShareWhatsApp(prop) }
                        )
                    }
                }
            } else {
                val targetGeo = initialTargetProperty?.let { resolvedCoordinatesMap[it.id] }
                Box(modifier = Modifier.fillMaxSize()) {
                    OsmPunePropertyMap(
                        buildingClusters = visibleClusters,
                        builderProjects = visibleBuilderPins,
                        userGpsLocation = userGpsLocation,
                        nearMeRadius = nearMeRadius,
                        initialCenter = targetGeo ?: GeoPoint(18.5515, 73.9348),
                        initialZoom = if (targetGeo != null) 15.5 else 13.5,
                        onSelectCluster = { cluster ->
                            selectedBuildingCluster = cluster
                        },
                        onSelectBuilderProject = { pin ->
                            selectedBuilderProject = pin
                        }
                    )

                    // Bottom-left legend cleanly clear of top chips and gesture navigation bar
                    Surface(
                        color = BrandCharcoalSurface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.8.dp, BrandCharcoalBorder),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .padding(12.dp)
                            .testTag("map_legend_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFE96932)))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Direct Units", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                            Spacer(modifier = Modifier.width(12.dp))

                            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF1E3A5F)))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Builder Projects", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 1. Building Cluster Bottom Sheet
    if (selectedBuildingCluster != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedBuildingCluster = null },
            containerColor = BrandCharcoalDark,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = selectedBuildingCluster!!.title,
                            color = TextPrimaryDark,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "${selectedBuildingCluster!!.properties.size} Live Direct Units · Burnt-Orange (#E96932)",
                            color = BrandGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(onClick = { selectedBuildingCluster = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                selectedBuildingCluster!!.properties.forEach { unit ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BrandCharcoalBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(BrandGold.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(unit.propertyCode, color = BrandGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(unit.unitNumber, color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    text = CalculationUtils.formatIndianCurrency(CalculationUtils.primaryAmountForProperty(unit)),
                                    color = BrandGold,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Area: ${unit.carpetAreaSqFt.toInt()} sq.ft", color = TextSecondaryDark, fontSize = 11.sp)
                                Text("Type: ${unit.propertyType}", color = TextSecondaryDark, fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val geo = resolvedCoordinatesMap[unit.id] ?: selectedBuildingCluster?.geo
                                        val label = Uri.encode("${unit.projectName}, ${unit.location}, Pune")
                                        val geoUri = if (geo != null) {
                                            Uri.parse("geo:${geo.lat},${geo.lng}?q=${geo.lat},${geo.lng}($label)")
                                        } else {
                                            Uri.parse("geo:0,0?q=$label")
                                        }
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                                        }.onFailure {
                                            val fallbackUrl = if (geo != null) {
                                                "https://www.openstreetmap.org/?mlat=${geo.lat}&mlon=${geo.lng}#map=16/${geo.lat}/${geo.lng}"
                                            } else {
                                                "https://www.openstreetmap.org/search?query=$label"
                                            }
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Navigate", fontSize = 10.sp)
                                }

                                OutlinedButton(
                                    onClick = { onShareWhatsApp(unit) },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF25D366)),
                                    border = BorderStroke(1.dp, Color(0xFF25D366)),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Share WA", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        selectedBuildingCluster = null
                                        onPropertyClick(unit)
                                    },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Details", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }

    // 2. Unpinned Properties Bottom Sheet
    if (showUnpinnedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showUnpinnedSheet = false },
            containerColor = BrandCharcoalDark,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Unpinned Properties (${unpinnedProperties.size})",
                            color = TextPrimaryDark,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Properties without exact coordinates. Set pin to plot on map.",
                            color = TextSecondaryDark,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { showUnpinnedSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                unpinnedProperties.forEach { prop ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BrandCharcoalBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(prop.projectName, color = TextPrimaryDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(CalculationUtils.formatIndianCurrency(CalculationUtils.primaryAmountForProperty(prop)), color = BrandGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("Unit: ${prop.unitNumber} · ${prop.location}", color = TextSecondaryDark, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    propertyToPin = prop
                                    showUnpinnedSheet = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.AddLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Set Maps Link / Pin", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }

    // 3. Set Coordinates Dialog
    if (propertyToPin != null) {
        var latInput by remember { mutableStateOf(propertyToPin!!.lat?.toString() ?: "18.5204") }
        var lngInput by remember { mutableStateOf(propertyToPin!!.lng?.toString() ?: "73.8567") }

        AlertDialog(
            onDismissRequest = { propertyToPin = null },
            containerColor = BrandCharcoalSurface,
            title = { Text("Set Pin Coordinates", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter exact latitude and longitude for ${propertyToPin!!.projectName} (${propertyToPin!!.unitNumber}):", color = TextSecondaryDark, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = latInput,
                        onValueChange = { latInput = it },
                        label = { Text("Latitude") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BrandGold, unfocusedBorderColor = BrandCharcoalBorder, focusedTextColor = TextPrimaryDark, unfocusedTextColor = TextPrimaryDark)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lngInput,
                        onValueChange = { lngInput = it },
                        label = { Text("Longitude") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BrandGold, unfocusedBorderColor = BrandCharcoalBorder, focusedTextColor = TextPrimaryDark, unfocusedTextColor = TextPrimaryDark)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val lat = latInput.toDoubleOrNull()
                        val lng = lngInput.toDoubleOrNull()
                        if (lat != null && lng != null) {
                            val updatedList = allProperties.map { p ->
                                if (p.id == propertyToPin!!.id) p.copy(lat = lat, lng = lng) else p
                            }
                            PropertiesLocalDatabase.saveProperties(context, updatedList)
                            Toast.makeText(context, "Coordinates updated & saved!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid latitude or longitude", Toast.LENGTH_SHORT).show()
                        }
                        propertyToPin = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                ) {
                    Text("Save Pin", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { propertyToPin = null }) {
                    Text("Cancel", color = TextSecondaryDark)
                }
            }
        )
    }

    // 4. Builder Project Bottom Sheet
    if (selectedBuilderProject != null) {
        AlertDialog(
            onDismissRequest = { selectedBuilderProject = null },
            containerColor = BrandCharcoalSurface,
            title = { Text(selectedBuilderProject!!.name, color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Asset Class: ${selectedBuilderProject!!.assetClass}", color = BrandGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Location: ${selectedBuilderProject!!.locationName}, Pune", color = TextSecondaryDark, fontSize = 12.sp)
                    Text("Navy Marker (#1E3A5F) — Builder Project Master Record", color = TextMutedDark, fontSize = 11.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pin = selectedBuilderProject!!
                        val label = Uri.encode("${pin.name}, ${pin.locationName}, Pune")
                        val geoUri = Uri.parse("geo:${pin.geo.lat},${pin.geo.lng}?q=${pin.geo.lat},${pin.geo.lng}($label)")
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                        }.onFailure {
                            val fallbackUrl = "https://www.openstreetmap.org/?mlat=${pin.geo.lat}&mlon=${pin.geo.lng}#map=16/${pin.geo.lat}/${pin.geo.lng}"
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
                            }
                        }
                        selectedBuilderProject = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                ) {
                    Text("Navigate to Project", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedBuilderProject = null }) {
                    Text("Close", color = TextSecondaryDark)
                }
            }
        )
    }
}

@Composable
fun OsmPunePropertyMap(
    buildingClusters: List<BuildingCluster>,
    builderProjects: List<BuilderProjectPin>,
    userGpsLocation: GeoPoint?,
    nearMeRadius: NearMeRadius,
    initialCenter: GeoPoint = GeoPoint(18.5515, 73.9348),
    initialZoom: Double = 13.5,
    onSelectCluster: (BuildingCluster) -> Unit,
    onSelectBuilderProject: (BuilderProjectPin) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Configure osmdroid user agent & cache inside app-private cacheDir (zero storage permissions needed)
    remember(context) {
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = context.packageName
        val basePath = File(context.cacheDir, "osmdroid").apply { mkdirs() }
        cfg.osmdroidBasePath = basePath
        cfg.osmdroidTileCache = File(basePath, "tiles").apply { mkdirs() }
        true
    }

    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 10.0
            maxZoomLevel = 19.5
            controller.setZoom(initialZoom)
            controller.setCenter(OsmGeoPoint(initialCenter.lat, initialCenter.lng))
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(initialCenter, initialZoom) {
        mapView.controller.setZoom(initialZoom)
        mapView.controller.animateTo(OsmGeoPoint(initialCenter.lat, initialCenter.lng))
    }

    LaunchedEffect(userGpsLocation, nearMeRadius) {
        if (userGpsLocation != null && nearMeRadius != NearMeRadius.OFF) {
            mapView.controller.setZoom(if (nearMeRadius == NearMeRadius.KM_2) 14.0 else 12.8)
            mapView.controller.animateTo(OsmGeoPoint(userGpsLocation.lat, userGpsLocation.lng))
        }
    }

    val latestSelectCluster by rememberUpdatedState(onSelectCluster)
    val latestSelectBuilder by rememberUpdatedState(onSelectBuilderProject)

    AndroidView(
        factory = { mapView },
        update = { view ->
            view.overlays.clear()

            // Optional Near Me radius ring
            if (userGpsLocation != null && nearMeRadius != NearMeRadius.OFF) {
                val centerPt = OsmGeoPoint(userGpsLocation.lat, userGpsLocation.lng)
                val radiusMeters = if (nearMeRadius == NearMeRadius.KM_2) 2000.0 else 5000.0
                val circle = Polygon(view).apply {
                    points = Polygon.pointsAsCircle(centerPt, radiusMeters)
                    fillPaint.color = android.graphics.Color.argb(42, 233, 105, 50)
                    outlinePaint.color = android.graphics.Color.rgb(233, 105, 50)
                    outlinePaint.strokeWidth = 4f
                }
                view.overlays.add(circle)

                val userMarker = Marker(view).apply {
                    position = centerPt
                    title = "Your Location"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = createPillMarkerDrawable(
                        context = context,
                        label = "You",
                        fillColor = android.graphics.Color.rgb(18, 22, 25),
                        borderColor = android.graphics.Color.rgb(233, 105, 50)
                    )
                }
                view.overlays.add(userMarker)
            }

            // Builder Project pins (#1E3A5F Navy)
            builderProjects.forEach { pin ->
                val shortLabel = shortenPinLabel(pin.name, maxLen = 18)
                val marker = Marker(view).apply {
                    position = OsmGeoPoint(pin.geo.lat, pin.geo.lng)
                    title = pin.name
                    snippet = "${pin.assetClass} · ${pin.locationName}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = createPillMarkerDrawable(
                        context = context,
                        label = shortLabel,
                        fillColor = android.graphics.Color.rgb(30, 58, 95), // #1E3A5F Navy
                        borderColor = android.graphics.Color.rgb(233, 105, 50)
                    )
                    setOnMarkerClickListener { _, _ ->
                        latestSelectBuilder(pin)
                        true
                    }
                }
                view.overlays.add(marker)
            }

            // Direct Unit Cluster pins (#E96932 Burnt Orange)
            buildingClusters.forEach { cluster ->
                val pinLabel = if (cluster.properties.size > 1) {
                    "${shortenPinLabel(cluster.title, maxLen = 14)} · ${cluster.properties.size}"
                } else {
                    CalculationUtils.formatIndianCurrency(
                        CalculationUtils.primaryAmountForProperty(cluster.properties.first())
                    )
                }
                val marker = Marker(view).apply {
                    position = OsmGeoPoint(cluster.geo.lat, cluster.geo.lng)
                    title = cluster.title
                    snippet = "${cluster.properties.size} Live Direct Units"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = createPillMarkerDrawable(
                        context = context,
                        label = pinLabel,
                        fillColor = android.graphics.Color.rgb(233, 105, 50), // #E96932 Primary CTA
                        borderColor = android.graphics.Color.WHITE
                    )
                    setOnMarkerClickListener { _, _ ->
                        latestSelectCluster(cluster)
                        true
                    }
                }
                view.overlays.add(marker)
            }

            view.invalidate()
        },
        modifier = Modifier
            .fillMaxSize()
            .testTag("osm_property_map_view")
    )
}

private fun shortenPinLabel(raw: String, maxLen: Int): String {
    val clean = raw.trim()
    if (clean.length <= maxLen) return clean
    return clean.take(maxLen - 1).trimEnd() + "…"
}

private fun createPillMarkerDrawable(
    context: Context,
    label: String,
    fillColor: Int,
    borderColor: Int
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 11.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textWidth = textPaint.measureText(label)
    val fm = textPaint.fontMetrics
    val textHeight = fm.descent - fm.ascent

    val hPad = 9f * density
    val vPad = 5.5f * density
    val pointerHeight = 7f * density
    val pointerHalfWidth = 6f * density
    val strokeW = 1.6f * density
    val shadowPad = 2f * density

    val pillW = (textWidth + hPad * 2f).coerceAtLeast(36f * density)
    val pillH = (textHeight + vPad * 2f).coerceAtLeast(24f * density)
    val totalW = (pillW + strokeW * 2f + shadowPad * 2f).roundToInt()
    val totalH = (pillH + pointerHeight + strokeW * 2f + shadowPad * 2f).roundToInt()

    val bitmap = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    val left = shadowPad + strokeW
    val top = shadowPad + strokeW
    val right = left + pillW
    val bottom = top + pillH
    val radius = pillH / 2f

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        style = Paint.Style.STROKE
        strokeWidth = strokeW
    }

    val rect = RectF(left, top, right, bottom)
    canvas.drawRoundRect(rect, radius, radius, bgPaint)
    canvas.drawRoundRect(rect, radius, radius, borderPaint)

    val centerX = (left + right) / 2f
    val triangle = AndroidPath().apply {
        moveTo(centerX - pointerHalfWidth, bottom - strokeW * 0.5f)
        lineTo(centerX + pointerHalfWidth, bottom - strokeW * 0.5f)
        lineTo(centerX, bottom + pointerHeight)
        close()
    }
    canvas.drawPath(triangle, bgPaint)
    canvas.drawLine(centerX - pointerHalfWidth, bottom, centerX, bottom + pointerHeight, borderPaint)
    canvas.drawLine(centerX + pointerHalfWidth, bottom, centerX, bottom + pointerHeight, borderPaint)

    val textX = left + (pillW - textWidth) / 2f
    val textY = top + (pillH - textHeight) / 2f - fm.ascent
    canvas.drawText(label, textX, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

@Composable
fun PortfolioChip(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) BrandGold.copy(alpha = 0.15f) else BrandCharcoalSurface)
            .border(1.2.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column {
            Text(title, color = if (isSelected) BrandGold else TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondaryDark, fontSize = 10.sp)
        }
    }
}

@Composable
fun FilterMultiChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) BrandGold else BrandCharcoalSurface)
            .border(0.8.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) BrandCharcoalDark else TextPrimaryDark,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun FilterSingleChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) BrandGold else BrandCharcoalSurface)
            .border(0.8.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) BrandCharcoalDark else TextPrimaryDark,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

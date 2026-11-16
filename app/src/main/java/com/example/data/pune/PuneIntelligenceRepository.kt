package com.example.data.pune

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader

data class SearchResolution(
    val matchedAliases: List<SearchAliasEntity> = emptyList(),
    val matchedLocations: List<LocationEntity> = emptyList(),
    val matchedCommercialProjects: List<CommercialProjectEntity> = emptyList(),
    val searchTerms: Set<String> = emptySet()
)

data class InvestmentAnalysisResult(
    val grossRentalYield: Double,
    val netRentalYield: Double,
    val totalAcquisitionCost: Double,
    val annualGrossRent: Double,
    val annualNetInflow: Double,
    val rentPerSqFtCarpet: Double,
    val paybackPeriodYears: Double
)

class PuneIntelligenceRepository private constructor(context: Context) {

    private val db = PuneIntelligenceDatabase.getDatabase(context.applicationContext)
    private val dao = db.puneIntelligenceDao()

    val locationsFlow: Flow<List<LocationEntity>> = dao.getAllLocationsFlow()
    val commercialProjectsFlow: Flow<List<CommercialProjectEntity>> = dao.getAllCommercialProjectsFlow()
    val metroStationsFlow: Flow<List<MetroStationEntity>> = dao.getAllMetroStationsFlow()

    companion object {
        private const val TAG = "PuneIntelligenceRepo"

        @Volatile
        private var INSTANCE: PuneIntelligenceRepository? = null

        fun getInstance(context: Context): PuneIntelligenceRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = PuneIntelligenceRepository(context)
                INSTANCE = instance
                instance
            }
        }
    }

    suspend fun ensureDatabaseInitialized(context: Context): Int = withContext(Dispatchers.IO) {
        val existingCount = dao.getLocationCount()
        if (existingCount > 0) {
            return@withContext existingCount
        }

        try {
            val assetManager = context.assets

            // 1. Locations
            val locations = parseJsonArray(assetManager.open("pune_intelligence/locations.json")) { obj ->
                LocationEntity(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    region = obj.optString("region", "east"),
                    city = obj.optString("city", "Pune"),
                    state = obj.optString("state", "Maharashtra"),
                    country = obj.optString("country", "IN"),
                    pinCodeStatus = obj.optString("pin_code_status", "not_address_verified"),
                    geoLat = if (obj.has("geo_lat") && !obj.isNull("geo_lat")) obj.getDouble("geo_lat") else null,
                    geoLon = if (obj.has("geo_lon") && !obj.isNull("geo_lon")) obj.getDouble("geo_lon") else null,
                    dataStatus = obj.optString("data_status", "geographic_label_seed")
                )
            }
            dao.insertLocations(locations)

            // 2. Commercial Projects
            val commercial = parseJsonArray(assetManager.open("pune_intelligence/commercial_projects.json")) { obj ->
                CommercialProjectEntity(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    locationId = obj.getString("location_id"),
                    developerId = obj.optString("developer_id").ifBlank { null },
                    assetClass = obj.optString("asset_class", "office_park"),
                    projectStatus = obj.optString("project_status", "completed"),
                    reraRegistration = obj.optString("rera_registration").ifBlank { null },
                    coordinates = obj.optString("coordinates").ifBlank { null },
                    verificationStatus = obj.optString("verification_status", "developer_listed"),
                    asOf = obj.optString("as_of", "2026-11-10")
                )
            }
            dao.insertCommercialProjects(commercial)

            // 3. Residential Projects
            val residential = parseJsonArray(assetManager.open("pune_intelligence/residential_projects.json")) { obj ->
                ResidentialProjectEntity(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    locationId = obj.getString("location_id"),
                    developerId = obj.optString("developer_id").ifBlank { null },
                    assetClass = obj.optString("asset_class", "luxury_residential"),
                    verificationStatus = obj.optString("verification_status", "developer_listed"),
                    asOf = obj.optString("as_of", "2026-11-10")
                )
            }
            dao.insertResidentialProjects(residential)

            // 4. Developers
            val developers = parseJsonArray(assetManager.open("pune_intelligence/developers.json")) { obj ->
                DeveloperEntity(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    verificationStatus = obj.optString("verification_status", "developer_listed")
                )
            }
            dao.insertDevelopers(developers)

            // 5. Metro Stations
            val metro = parseJsonArray(assetManager.open("pune_intelligence/metro_stations.json")) { obj ->
                val linesArray = obj.optJSONArray("lines")
                val linesStr = (0 until (linesArray?.length() ?: 0)).joinToString(",") { linesArray!!.getString(it) }
                MetroStationEntity(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    mode = obj.optString("mode", "metro"),
                    status = obj.optString("status", "operational"),
                    lines = linesStr.ifBlank { "1" },
                    latitude = if (obj.has("latitude") && !obj.isNull("latitude")) obj.getDouble("latitude") else null,
                    longitude = if (obj.has("longitude") && !obj.isNull("longitude")) obj.getDouble("longitude") else null,
                    verificationStatus = obj.optString("verification_status", "official_profile")
                )
            }
            dao.insertMetroStations(metro)

            // 6. Postal Offices
            val postal = parseJsonArray(assetManager.open("pune_intelligence/postal_offices.json")) { obj ->
                PostalOfficeEntity(
                    id = obj.getString("id"),
                    locationId = obj.getString("location_id"),
                    pinCode = obj.getString("pin_code"),
                    postOfficeName = obj.getString("post_office_name"),
                    scope = obj.optString("scope", "post_office_reference_not_full_micro_market"),
                    verificationStatus = obj.optString("verification_status", "official_directory")
                )
            }
            dao.insertPostalOffices(postal)

            // 7. Railway Stations
            val railways = parseJsonArray(assetManager.open("pune_intelligence/railway_stations.json")) { obj ->
                RailwayStationEntity(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    code = obj.optString("code", "PUNE"),
                    verificationStatus = obj.optString("verification_status", "official_directory")
                )
            }
            dao.insertRailwayStations(railways)

            // 8. Search Aliases
            val aliases = parseJsonArray(assetManager.open("pune_intelligence/search_aliases.json")) { obj ->
                SearchAliasEntity(
                    id = obj.getString("id"),
                    entityType = obj.getString("entity_type"),
                    entityId = obj.getString("entity_id"),
                    alias = obj.getString("alias"),
                    matchType = obj.optString("match_type", "normalized_name"),
                    verified = obj.optBoolean("verified", true)
                )
            }
            dao.insertSearchAliases(aliases)

            // 9. Road Candidates (Quarantined)
            val roads = parseJsonArray(assetManager.open("pune_intelligence/road_candidates.json")) { obj ->
                RoadCandidateEntity(
                    id = obj.getString("id"),
                    locationId = obj.getString("location_id"),
                    roadName = obj.getString("road_name"),
                    roadType = obj.optString("road_type", "unknown"),
                    verificationStatus = obj.optString("verification_status", "candidate_unverified")
                )
            }
            dao.insertRoadCandidates(roads)

            // 10. Legacy Enrichment Candidates (Quarantined)
            val legacy = parseJsonArray(assetManager.open("pune_intelligence/legacy_enrichment_candidates.json")) { obj ->
                val transit = obj.optJSONObject("candidate_transit")
                val pinCodes = obj.optJSONArray("candidate_pin_codes")
                val roadsArr = obj.optJSONArray("candidate_roads")
                val hubsArr = obj.optJSONArray("candidate_business_hubs")

                LegacyEnrichmentEntity(
                    locationId = obj.getString("location_id"),
                    candidatePinCodes = (0 until (pinCodes?.length() ?: 0)).joinToString(",") { pinCodes!!.getString(it) },
                    candidateRoads = (0 until (roadsArr?.length() ?: 0)).joinToString(", ") { roadsArr!!.getString(it) },
                    candidateBusinessHubs = (0 until (hubsArr?.length() ?: 0)).joinToString(", ") { hubsArr!!.getString(it) },
                    candidateTransitMetro = transit?.optString("metro_station").orEmpty(),
                    candidateRailway = transit?.optString("railway_station").orEmpty(),
                    candidateAirport = transit?.optString("airport_code", "PNQ").orEmpty(),
                    status = obj.optString("status", "legacy_unverified_do_not_autofill")
                )
            }
            dao.insertLegacyEnrichment(legacy)

            Log.i(TAG, "Successfully seeded Pune Intelligence Database v2.0 (${locations.size} locations, ${commercial.size} commercial projects)")
            locations.size
        } catch (e: Exception) {
            Log.e(TAG, "Error seeding Pune Intelligence Database: ${e.message}", e)
            0
        }
    }

    suspend fun getAllLocations(): List<LocationEntity> = dao.getAllLocations()

    suspend fun getLocationsByRegion(region: String): List<LocationEntity> = dao.getLocationsByRegion(region)

    suspend fun getAllCommercialProjects(): List<CommercialProjectEntity> = dao.getAllCommercialProjects()

    suspend fun getCommercialProjectsByLocation(locationId: String): List<CommercialProjectEntity> =
        dao.getCommercialProjectsByLocation(locationId)

    suspend fun getAllMetroStations(): List<MetroStationEntity> = dao.getAllMetroStations()

    suspend fun getAllPostalOffices(): List<PostalOfficeEntity> = dao.getAllPostalOffices()

    suspend fun getPostalOfficesForLocation(locationId: String): List<PostalOfficeEntity> =
        dao.getPostalOfficesForLocation(locationId)

    suspend fun getRoadCandidates(locationId: String): List<RoadCandidateEntity> =
        dao.getRoadCandidates(locationId)

    suspend fun getLegacyEnrichment(locationId: String): LegacyEnrichmentEntity? =
        dao.getLegacyEnrichment(locationId)

    // -------------------------------------------------------------
    // Intelligent Offline Search Resolver (Aliases & Entities)
    // -------------------------------------------------------------
    suspend fun resolveSearchQuery(query: String): SearchResolution = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return@withContext SearchResolution()

        val terms = mutableSetOf(cleanQuery)
        val matchedAliases = dao.matchAliases(cleanQuery)
        val matchedLocations = mutableListOf<LocationEntity>()
        val matchedCommercialProjects = mutableListOf<CommercialProjectEntity>()

        // Check aliases for entity expansion
        matchedAliases.forEach { alias ->
            when (alias.entityType) {
                "location" -> {
                    dao.getLocationById(alias.entityId)?.let { loc ->
                        matchedLocations.add(loc)
                        terms.add(loc.name.lowercase())
                    }
                }
                "commercial_project" -> {
                    dao.findCommercialProjectByName(alias.alias)?.let { proj ->
                        matchedCommercialProjects.add(proj)
                        terms.add(proj.name.lowercase())
                        dao.getLocationById(proj.locationId)?.let { terms.add(it.name.lowercase()) }
                    }
                }
            }
        }

        // Direct search matching
        val directLocs = dao.searchLocations(cleanQuery)
        directLocs.forEach { loc ->
            matchedLocations.add(loc)
            terms.add(loc.name.lowercase())
        }

        SearchResolution(
            matchedAliases = matchedAliases,
            matchedLocations = matchedLocations.distinctBy { it.id },
            matchedCommercialProjects = matchedCommercialProjects.distinctBy { it.id },
            searchTerms = terms
        )
    }

    // -------------------------------------------------------------
    // Matching helper for individual property items
    // -------------------------------------------------------------
    suspend fun matchPropertyToPuneMaster(locationName: String, projectName: String): PunePropertyMatch = withContext(Dispatchers.IO) {
        val allLocs = dao.getAllLocations()
        val matchedLocation = allLocs.find { loc ->
            loc.name.equals(locationName.trim(), ignoreCase = true) ||
            locationName.contains(loc.name, ignoreCase = true)
        }

        val allComm = dao.getAllCommercialProjects()
        val matchedProject = allComm.find { proj ->
            proj.name.equals(projectName.trim(), ignoreCase = true) ||
            projectName.contains(proj.name, ignoreCase = true) ||
            (matchedLocation != null && proj.locationId == matchedLocation.id && projectName.contains(proj.name.take(6), ignoreCase = true))
        }

        val postalOffices = if (matchedLocation != null) dao.getPostalOfficesForLocation(matchedLocation.id) else emptyList()
        val nearestMetro = if (matchedLocation != null) {
            val legacy = dao.getLegacyEnrichment(matchedLocation.id)
            if (legacy?.candidateTransitMetro?.isNotBlank() == true) {
                dao.getAllMetroStations().find { it.name.contains(legacy.candidateTransitMetro, true) }
            } else null
        } else null

        PunePropertyMatch(
            locationEntity = matchedLocation,
            commercialProject = matchedProject,
            postalOffices = postalOffices,
            nearestMetroStation = nearestMetro,
            isVerifiedProject = matchedProject != null
        )
    }

    // -------------------------------------------------------------
    // Investment & ROI Analysis (Rules from investment_analytics_rules.json)
    // -------------------------------------------------------------
    fun calculateInvestmentMetrics(
        purchasePrice: Double,
        stampDutyPercent: Double = 6.0,
        registrationFee: Double = 30000.0,
        brokeragePercent: Double = 2.0,
        fitOutCost: Double = 0.0,
        monthlyRent: Double,
        carpetAreaSqFt: Double,
        monthlyMaintenance: Double = 0.0,
        annualPropertyTax: Double = 0.0,
        vacancyAllowanceMonths: Double = 1.0
    ): InvestmentAnalysisResult {
        val stampDuty = purchasePrice * (stampDutyPercent / 100.0)
        val brokerage = purchasePrice * (brokeragePercent / 100.0)
        val totalAcquisitionCost = purchasePrice + stampDuty + registrationFee + brokerage + fitOutCost

        val annualGrossRent = monthlyRent * 12.0
        val vacancyAllowance = monthlyRent * vacancyAllowanceMonths
        val annualOperatingExpenses = (monthlyMaintenance * 12.0) + annualPropertyTax + vacancyAllowance
        val annualNetInflow = annualGrossRent - annualOperatingExpenses

        val grossRentalYield = if (totalAcquisitionCost > 0) (annualGrossRent / totalAcquisitionCost) * 100.0 else 0.0
        val netRentalYield = if (totalAcquisitionCost > 0) (annualNetInflow / totalAcquisitionCost) * 100.0 else 0.0
        val rentPerSqFtCarpet = if (carpetAreaSqFt > 0) monthlyRent / carpetAreaSqFt else 0.0
        val paybackPeriodYears = if (annualNetInflow > 0) totalAcquisitionCost / annualNetInflow else 0.0

        return InvestmentAnalysisResult(
            grossRentalYield = grossRentalYield,
            netRentalYield = netRentalYield,
            totalAcquisitionCost = totalAcquisitionCost,
            annualGrossRent = annualGrossRent,
            annualNetInflow = annualNetInflow,
            rentPerSqFtCarpet = rentPerSqFtCarpet,
            paybackPeriodYears = paybackPeriodYears
        )
    }

    private fun <T> parseJsonArray(inputStream: java.io.InputStream, mapper: (JSONObject) -> T): List<T> {
        val reader = InputStreamReader(inputStream)
        val content = reader.readText()
        reader.close()
        val array = JSONArray(content)
        val list = mutableListOf<T>()
        for (i in 0 until array.length()) {
            list.add(mapper(array.getJSONObject(i)))
        }
        return list
    }
}

data class PunePropertyMatch(
    val locationEntity: LocationEntity? = null,
    val commercialProject: CommercialProjectEntity? = null,
    val postalOffices: List<PostalOfficeEntity> = emptyList(),
    val nearestMetroStation: MetroStationEntity? = null,
    val isVerifiedProject: Boolean = false
)


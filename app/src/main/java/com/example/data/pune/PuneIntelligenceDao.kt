package com.example.data.pune

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PuneIntelligenceDao {

    // -------------------------------------------------------------
    // Locations
    // -------------------------------------------------------------
    @Query("SELECT COUNT(*) FROM locations")
    suspend fun getLocationCount(): Int

    @Query("SELECT * FROM locations ORDER BY name ASC")
    fun getAllLocationsFlow(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations ORDER BY name ASC")
    suspend fun getAllLocations(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE region = :region ORDER BY name ASC")
    suspend fun getLocationsByRegion(region: String): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE id = :id LIMIT 1")
    suspend fun getLocationById(id: String): LocationEntity?

    @Query("SELECT * FROM locations WHERE name LIKE '%' || :query || '%' LIMIT 10")
    suspend fun searchLocations(query: String): List<LocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocations(locations: List<LocationEntity>)

    // -------------------------------------------------------------
    // Commercial Projects
    // -------------------------------------------------------------
    @Query("SELECT * FROM commercial_projects ORDER BY name ASC")
    fun getAllCommercialProjectsFlow(): Flow<List<CommercialProjectEntity>>

    @Query("SELECT * FROM commercial_projects ORDER BY name ASC")
    suspend fun getAllCommercialProjects(): List<CommercialProjectEntity>

    @Query("SELECT * FROM commercial_projects WHERE locationId = :locationId ORDER BY name ASC")
    suspend fun getCommercialProjectsByLocation(locationId: String): List<CommercialProjectEntity>

    @Query("SELECT * FROM commercial_projects WHERE name LIKE '%' || :name || '%' LIMIT 1")
    suspend fun findCommercialProjectByName(name: String): CommercialProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommercialProjects(projects: List<CommercialProjectEntity>)

    // -------------------------------------------------------------
    // Residential Projects
    // -------------------------------------------------------------
    @Query("SELECT * FROM residential_projects ORDER BY name ASC")
    fun getAllResidentialProjectsFlow(): Flow<List<ResidentialProjectEntity>>

    @Query("SELECT * FROM residential_projects ORDER BY name ASC")
    suspend fun getAllResidentialProjects(): List<ResidentialProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResidentialProjects(projects: List<ResidentialProjectEntity>)

    // -------------------------------------------------------------
    // Developers
    // -------------------------------------------------------------
    @Query("SELECT * FROM developers ORDER BY name ASC")
    suspend fun getAllDevelopers(): List<DeveloperEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevelopers(developers: List<DeveloperEntity>)

    // -------------------------------------------------------------
    // Metro Stations
    // -------------------------------------------------------------
    @Query("SELECT * FROM metro_stations ORDER BY name ASC")
    fun getAllMetroStationsFlow(): Flow<List<MetroStationEntity>>

    @Query("SELECT * FROM metro_stations ORDER BY name ASC")
    suspend fun getAllMetroStations(): List<MetroStationEntity>

    @Query("SELECT * FROM metro_stations WHERE status = :status ORDER BY name ASC")
    suspend fun getMetroStationsByStatus(status: String): List<MetroStationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetroStations(stations: List<MetroStationEntity>)

    // -------------------------------------------------------------
    // Postal Offices
    // -------------------------------------------------------------
    @Query("SELECT * FROM postal_offices ORDER BY postOfficeName ASC")
    suspend fun getAllPostalOffices(): List<PostalOfficeEntity>

    @Query("SELECT * FROM postal_offices WHERE locationId = :locationId ORDER BY postOfficeName ASC")
    suspend fun getPostalOfficesForLocation(locationId: String): List<PostalOfficeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPostalOffices(offices: List<PostalOfficeEntity>)

    // -------------------------------------------------------------
    // Railway Stations
    // -------------------------------------------------------------
    @Query("SELECT * FROM railway_stations ORDER BY name ASC")
    suspend fun getAllRailwayStations(): List<RailwayStationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRailwayStations(stations: List<RailwayStationEntity>)

    // -------------------------------------------------------------
    // Search Aliases
    // -------------------------------------------------------------
    @Query("SELECT * FROM search_aliases")
    suspend fun getAllSearchAliases(): List<SearchAliasEntity>

    @Query("SELECT * FROM search_aliases WHERE alias LIKE '%' || :query || '%' LIMIT 15")
    suspend fun matchAliases(query: String): List<SearchAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchAliases(aliases: List<SearchAliasEntity>)

    // -------------------------------------------------------------
    // Road Candidates (Quarantined)
    // -------------------------------------------------------------
    @Query("SELECT * FROM road_candidates WHERE locationId = :locationId ORDER BY roadName ASC")
    suspend fun getRoadCandidates(locationId: String): List<RoadCandidateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoadCandidates(roads: List<RoadCandidateEntity>)

    // -------------------------------------------------------------
    // Legacy Enrichment Candidates (Quarantined)
    // -------------------------------------------------------------
    @Query("SELECT * FROM legacy_enrichment_candidates WHERE locationId = :locationId LIMIT 1")
    suspend fun getLegacyEnrichment(locationId: String): LegacyEnrichmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLegacyEnrichment(candidates: List<LegacyEnrichmentEntity>)
}


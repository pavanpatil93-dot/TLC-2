package com.example.data.pune

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "locations",
    indices = [
        Index(value = ["name"]),
        Index(value = ["region"])
    ]
)
data class LocationEntity(
    @PrimaryKey val id: String, // e.g. "LOC-001"
    val name: String, // "Kharadi"
    val region: String, // "east", "west", "north", "central", "south"
    val city: String = "Pune",
    val state: String = "Maharashtra",
    val country: String = "IN",
    val pinCodeStatus: String = "not_address_verified",
    val geoLat: Double? = null,
    val geoLon: Double? = null,
    val dataStatus: String = "geographic_label_seed"
)

@Entity(
    tableName = "commercial_projects",
    indices = [
        Index(value = ["name"]),
        Index(value = ["locationId"]),
        Index(value = ["developerId"])
    ]
)
data class CommercialProjectEntity(
    @PrimaryKey val id: String, // "COM-001"
    val name: String, // "EON Free Zone I"
    val locationId: String, // "LOC-001"
    val developerId: String? = null, // "DEV-001"
    val assetClass: String = "office_park",
    val projectStatus: String = "completed",
    val reraRegistration: String? = null,
    val coordinates: String? = null,
    val verificationStatus: String = "developer_listed",
    val asOf: String = "2026-11-10"
)

@Entity(
    tableName = "residential_projects",
    indices = [
        Index(value = ["name"]),
        Index(value = ["locationId"])
    ]
)
data class ResidentialProjectEntity(
    @PrimaryKey val id: String, // "RES-001"
    val name: String,
    val locationId: String,
    val developerId: String? = null,
    val assetClass: String = "luxury_residential",
    val verificationStatus: String = "developer_listed",
    val asOf: String = "2026-11-10"
)

@Entity(tableName = "developers")
data class DeveloperEntity(
    @PrimaryKey val id: String, // "DEV-001"
    val name: String, // "Panchshil Realty"
    val verificationStatus: String = "developer_listed"
)

@Entity(
    tableName = "metro_stations",
    indices = [
        Index(value = ["name"]),
        Index(value = ["status"])
    ]
)
data class MetroStationEntity(
    @PrimaryKey val id: String, // "MET-001"
    val name: String, // "PCMC"
    val mode: String = "metro",
    val status: String = "operational", // "operational", "under_construction", "proposed"
    val lines: String = "1", // comma-separated if multiple
    val latitude: Double? = null,
    val longitude: Double? = null,
    val verificationStatus: String = "official_profile"
)

@Entity(
    tableName = "postal_offices",
    indices = [
        Index(value = ["locationId"]),
        Index(value = ["pinCode"])
    ]
)
data class PostalOfficeEntity(
    @PrimaryKey val id: String, // "PIN-411014-LOC-002"
    val locationId: String,
    val pinCode: String,
    val postOfficeName: String,
    val scope: String = "post_office_reference_not_full_micro_market",
    val verificationStatus: String = "official_directory"
)

@Entity(tableName = "railway_stations")
data class RailwayStationEntity(
    @PrimaryKey val id: String, // "RLY-001"
    val name: String, // "Pune Junction"
    val code: String = "PUNE",
    val verificationStatus: String = "official_directory"
)

@Entity(
    tableName = "search_aliases",
    indices = [
        Index(value = ["alias"]),
        Index(value = ["entityId"])
    ]
)
data class SearchAliasEntity(
    @PrimaryKey val id: String, // "ALIAS-0001"
    val entityType: String, // "location", "commercial_project", "metro_station"
    val entityId: String, // "LOC-001"
    val alias: String, // "kharadi"
    val matchType: String = "normalized_name",
    val verified: Boolean = true
)

@Entity(
    tableName = "road_candidates",
    indices = [
        Index(value = ["locationId"])
    ]
)
data class RoadCandidateEntity(
    @PrimaryKey val id: String, // "ROAD-0001"
    val locationId: String,
    val roadName: String,
    val roadType: String = "unknown",
    val verificationStatus: String = "candidate_unverified"
)

@Entity(tableName = "legacy_enrichment_candidates")
data class LegacyEnrichmentEntity(
    @PrimaryKey val locationId: String,
    val candidatePinCodes: String = "", // Comma-separated or JSON
    val candidateRoads: String = "",
    val candidateBusinessHubs: String = "",
    val candidateTransitMetro: String = "",
    val candidateRailway: String = "",
    val candidateAirport: String = "PNQ",
    val status: String = "legacy_unverified_do_not_autofill"
)


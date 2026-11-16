package com.example.callintelligence

import kotlin.math.*

/** Pure, deterministic geographic matching; no network or personal-data access. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun isValid() = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        latitude.isFinite() && longitude.isFinite()
}

data class MatchCandidate(
    val id: String,
    val point: GeoPoint,
    val priceInr: Double?,
    val areaSqFt: Double?,
    val furnishing: String?,
    val locality: String?
)

data class MatchRequirement(
    val center: GeoPoint,
    val maxBudgetInr: Double?,
    val desiredAreaSqFt: Double?,
    val furnishing: String?,
    val locality: String?,
    val radiusKm: Double = 2.0
)

data class RankedMatch(val candidate: MatchCandidate, val distanceKm: Double, val score: Int)

object GeoMatchEngine {
    private const val EARTH_RADIUS_KM = 6371.0088

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        if (!a.isValid() || !b.isValid()) return Double.POSITIVE_INFINITY
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun match(request: MatchRequirement, candidates: List<MatchCandidate>): List<RankedMatch> {
        if (!request.center.isValid() || !request.radiusKm.isFinite() || request.radiusKm <= 0) return emptyList()
        val latDelta = request.radiusKm / 111.0
        val longitudeDelta = if (abs(request.center.latitude) >= 89.9) 180.0 else
            (request.radiusKm / (111.0 * abs(cos(Math.toRadians(request.center.latitude))).coerceAtLeast(0.001))).coerceAtMost(180.0)
        return candidates.asSequence().filter { it.point.isValid() }
            .filter { abs(it.point.latitude - request.center.latitude) <= latDelta + 0.001 &&
                abs(normalizeLongitude(it.point.longitude - request.center.longitude)) <= longitudeDelta + 0.001 }
            .map { it to distanceKm(request.center, it.point) }
            .filter { it.second <= request.radiusKm }
            .map { (candidate, distance) ->
                val distanceScore = (1 - distance / request.radiusKm).coerceIn(0.0, 1.0)
                val budgetScore = when {
                    request.maxBudgetInr == null || request.maxBudgetInr <= 0 || candidate.priceInr == null -> 0.0
                    candidate.priceInr <= request.maxBudgetInr -> 1.0
                    else -> (request.maxBudgetInr / candidate.priceInr).coerceIn(0.0, 1.0)
                }
                val areaScore = when {
                    request.desiredAreaSqFt == null || request.desiredAreaSqFt <= 0 || candidate.areaSqFt == null -> 0.0
                    else -> (1 - abs(candidate.areaSqFt - request.desiredAreaSqFt) / request.desiredAreaSqFt).coerceIn(0.0, 1.0)
                }
                val furnishingScore = if (!request.furnishing.isNullOrBlank() && request.furnishing.equals(candidate.furnishing, true)) 1.0 else 0.0
                val localityScore = if (!request.locality.isNullOrBlank() && request.locality.equals(candidate.locality, true)) 1.0 else 0.0
                RankedMatch(candidate, distance, ((distanceScore * 30 + budgetScore * 30 +
                    areaScore * 20 + (furnishingScore + localityScore) * 10).roundToInt()).coerceIn(0, 100))
            }.sortedWith(compareByDescending<RankedMatch> { it.score }.thenBy { it.distanceKm }).toList()
    }

    private fun normalizeLongitude(value: Double): Double = ((value + 540) % 360) - 180
}


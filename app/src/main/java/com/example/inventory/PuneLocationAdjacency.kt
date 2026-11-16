package com.example.inventory

import com.example.model.PropertyItem

enum class LocationMatchKind {
    NONE_SELECTED,
    EXACT,
    NEARBY,
    OTHER,
    MISSING
}

/**
 * Verified East Pune & Pune Micro-Market Adjacency Model.
 * Exact matches are strictly preserved and never silently replaced by nearby matches.
 */
object PuneLocationAdjacency {

    val EAST_PUNE_PRIORITY_LOCATIONS: List<String> = listOf(
        "Kharadi",
        "Viman Nagar",
        "Kalyani Nagar",
        "Koregaon Park",
        "Koregaon Park Annexe",
        "Mundhwa",
        "Keshav Nagar",
        "Magarpatta",
        "Hadapsar",
        "Wagholi"
    )

    private val ADJACENCY_MAP: Map<String, Set<String>> = mapOf(
        "kharadi" to setOf("viman nagar", "wagholi", "mundhwa", "keshav nagar", "magarpatta", "hadapsar", "chandannagar"),
        "viman nagar" to setOf("kharadi", "kalyani nagar", "yerawada", "lohegaon", "dhanori", "wadgaon sheri"),
        "kalyani nagar" to setOf("viman nagar", "koregaon park", "koregaon park annexe", "yerawada", "mundhwa", "wadgaon sheri"),
        "koregaon park" to setOf("kalyani nagar", "koregaon park annexe", "mundhwa", "camp", "yerawada"),
        "koregaon park annexe" to setOf("koregaon park", "mundhwa", "kalyani nagar", "keshav nagar"),
        "mundhwa" to setOf("koregaon park", "koregaon park annexe", "keshav nagar", "kharadi", "magarpatta", "kalyani nagar"),
        "keshav nagar" to setOf("mundhwa", "kharadi", "magarpatta", "hadapsar", "koregaon park annexe"),
        "magarpatta" to setOf("hadapsar", "amanora", "kharadi", "mundhwa", "keshav nagar"),
        "hadapsar" to setOf("magarpatta", "amanora", "keshav nagar", "mundhwa", "kharadi"),
        "amanora" to setOf("magarpatta", "hadapsar", "keshav nagar"),
        "wagholi" to setOf("kharadi", "lohegaon", "viman nagar"),
        "yerawada" to setOf("kalyani nagar", "viman nagar", "koregaon park", "shivajinagar"),
        "baner" to setOf("balewadi", "aundh", "wakad", "pashan"),
        "balewadi" to setOf("baner", "wakad", "hinjewadi"),
        "wakad" to setOf("hinjewadi", "balewadi", "baner", "pimpri"),
        "hinjewadi" to setOf("wakad", "balewadi", "baner"),
        "aundh" to setOf("baner", "shivajinagar", "balewadi"),
        "shivajinagar" to setOf("aundh", "camp", "kothrud", "yerawada"),
        "kothrud" to setOf("shivajinagar", "bavdhan", "baner")
    )

    fun canonicalize(rawLocation: String): String {
        val cleaned = rawLocation.trim()
            .replace(Regex(",?\\s*pune$", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",?\\s*maharashtra$", RegexOption.IGNORE_CASE), "")
            .trim()
        val lower = cleaned.lowercase()
        return when {
            lower.isBlank() -> ""
            lower == "kp" || lower.startsWith("kp ") || lower.contains("koregaon park") && !lower.contains("annexe") -> {
                if (lower.contains("annexe")) "koregaon park annexe" else "koregaon park"
            }
            lower.contains("koregaon park annexe") || lower.contains("kp annexe") -> "koregaon park annexe"
            lower.contains("magarpatta") -> "magarpatta"
            lower.contains("viman nagar") -> "viman nagar"
            lower.contains("kalyani nagar") -> "kalyani nagar"
            lower.contains("keshav nagar") -> "keshav nagar"
            lower.contains("kharadi") -> "kharadi"
            lower.contains("mundhwa") -> "mundhwa"
            lower.contains("hadapsar") -> "hadapsar"
            lower.contains("wagholi") -> "wagholi"
            lower.contains("amanora") -> "amanora"
            lower.contains("yerawada") -> "yerawada"
            lower.contains("hinjawadi") || lower.contains("hinjewadi") -> "hinjewadi"
            lower.contains("camp") || lower.contains("mg road") -> "camp"
            lower.contains("baner") -> "baner"
            lower.contains("balewadi") -> "balewadi"
            lower.contains("wakad") -> "wakad"
            lower.contains("aundh") -> "aundh"
            lower.contains("kothrud") -> "kothrud"
            lower.contains("shivajinagar") -> "shivajinagar"
            else -> lower
        }
    }

    fun displayName(rawLocation: String): String {
        val canon = canonicalize(rawLocation)
        return when (canon) {
            "kharadi" -> "Kharadi"
            "viman nagar" -> "Viman Nagar"
            "kalyani nagar" -> "Kalyani Nagar"
            "koregaon park" -> "Koregaon Park"
            "koregaon park annexe" -> "Koregaon Park Annexe"
            "mundhwa" -> "Mundhwa"
            "keshav nagar" -> "Keshav Nagar"
            "magarpatta" -> "Magarpatta"
            "hadapsar" -> "Hadapsar"
            "wagholi" -> "Wagholi"
            "amanora" -> "Amanora"
            "yerawada" -> "Yerawada"
            "hinjewadi" -> "Hinjewadi"
            "camp" -> "Camp / MG Road"
            "baner" -> "Baner"
            "balewadi" -> "Balewadi"
            "wakad" -> "Wakad"
            "aundh" -> "Aundh"
            "kothrud" -> "Kothrud"
            "shivajinagar" -> "Shivajinagar"
            else -> rawLocation.trim()
                .replace(Regex(",?\\s*pune$", RegexOption.IGNORE_CASE), "")
                .trim()
        }
    }

    fun adjacentMarketsFor(location: String): Set<String> {
        val canon = canonicalize(location)
        return ADJACENCY_MAP[canon].orEmpty()
    }

    fun classifyLocationMatch(
        propertyLocation: String,
        selectedLocations: Set<String>
    ): LocationMatchKind {
        if (selectedLocations.isEmpty()) return LocationMatchKind.NONE_SELECTED
        val propCanon = canonicalize(propertyLocation)
        if (propCanon.isBlank()) return LocationMatchKind.MISSING

        val selectedCanon = selectedLocations.map { canonicalize(it) }.filter { it.isNotBlank() }.toSet()
        if (selectedCanon.isEmpty()) return LocationMatchKind.NONE_SELECTED

        // Exact match check (canonical or substring containment on canonical label)
        if (selectedCanon.any { sel -> propCanon == sel || propCanon.contains(sel) || sel.contains(propCanon) }) {
            return LocationMatchKind.EXACT
        }

        // Verified adjacent micro-market check
        val isNearby = selectedCanon.any { sel ->
            val adjacent = ADJACENCY_MAP[sel].orEmpty()
            adjacent.contains(propCanon) || ADJACENCY_MAP[propCanon].orEmpty().contains(sel)
        }
        return if (isNearby) LocationMatchKind.NEARBY else LocationMatchKind.OTHER
    }

    /**
     * Returns deduplicated selectable micro-market list prioritizing East Pune,
     * followed by custom locations discovered from existing properties.
     */
    fun getAvailableFilterLocations(properties: List<PropertyItem>): List<String> {
        val result = linkedMapOf<String, String>()
        EAST_PUNE_PRIORITY_LOCATIONS.forEach { loc ->
            result[canonicalize(loc)] = loc
        }
        properties.forEach { prop ->
            val raw = prop.location.trim()
            if (raw.isNotBlank()) {
                val canon = canonicalize(raw)
                val display = displayName(raw)
                if (canon.isNotBlank() && display.isNotBlank() && !result.containsKey(canon)) {
                    result[canon] = display
                }
            }
        }
        return result.values.toList()
    }

    fun buildOrderedLocationChoices(dbLocations: List<String>): List<String> {
        val result = linkedMapOf<String, String>()
        EAST_PUNE_PRIORITY_LOCATIONS.forEach { loc ->
            result[canonicalize(loc)] = loc
        }
        dbLocations.forEach { raw ->
            val clean = raw.trim()
            if (clean.isNotBlank()) {
                val canon = canonicalize(clean)
                val display = displayName(clean)
                if (canon.isNotBlank() && display.isNotBlank() && !result.containsKey(canon)) {
                    result[canon] = display
                }
            }
        }
        return result.values.toList()
    }
}

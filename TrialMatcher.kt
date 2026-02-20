package com.example.karootrailnames

import android.location.Location
import kotlin.math.*

class TrailMatcher {

    data class TrailMatch(
        val trail: Trail?,
        val distance: Double,
        val status: TrailStatus
    )

    enum class TrailStatus {
        ON_TRAIL,           // Within 50m
        APPROACHING,        // Moving toward trail
        LEAVING,            // Moving away from trail
        NO_TRAIL            // Nothing nearby
    }

    private var lastLocation: Location? = null
    private var lastMatch: TrailMatch? = null

    fun findCurrentTrail(
        currentLat: Double,
        currentLon: Double,
        trails: List<Trail>,
        bearing: Float? = null
    ): TrailMatch {
        var closestTrail: Trail? = null
        var minDistance = Double.MAX_VALUE
        var closestNode: TrailNode? = null

        // Find closest trail and node
        trails.forEach { trail ->
            trail.nodes.forEach { node ->
                val distance = calculateDistance(currentLat, currentLon, node.lat, node.lon)
                if (distance < minDistance) {
                    minDistance = distance
                    closestTrail = trail
                    closestNode = node
                }
            }
        }

        // Determine status
        val status = when {
            minDistance < 50.0 -> TrailStatus.ON_TRAIL
            minDistance < 200.0 -> {
                // Check if approaching or leaving based on previous distance
                val prevMatch = lastMatch
                if (prevMatch != null && prevMatch.trail?.id == closestTrail?.id) {
                    if (minDistance < prevMatch.distance) {
                        TrailStatus.APPROACHING
                    } else {
                        TrailStatus.LEAVING
                    }
                } else {
                    TrailStatus.APPROACHING
                }
            }
            else -> TrailStatus.NO_TRAIL
        }

        val match = TrailMatch(
            trail = if (status != TrailStatus.NO_TRAIL) closestTrail else null,
            distance = minDistance,
            status = status
        )

        lastMatch = match
        return match
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    fun formatTrailStatus(match: TrailMatch): String {
        return when (match.status) {
            TrailStatus.ON_TRAIL -> "On: ${match.trail?.name}"
            TrailStatus.APPROACHING -> "→ ${match.trail?.name} - ${match.distance.toInt()}m"
            TrailStatus.LEAVING -> "← ${match.trail?.name} - ${match.distance.toInt()}m"
            TrailStatus.NO_TRAIL -> "No Trail"
        }
    }
}
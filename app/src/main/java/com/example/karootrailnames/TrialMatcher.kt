package com.example.karootrailnames

import android.location.Location
import kotlin.math.*

class TrailMatcher {

    data class TrailMatch(
        val trail: Trail?,
        val distance: Double,
        val status: TrailStatus,
        val bearingToTrail: Float = 0f,
        val compassDirection: String = "",
        val relativeDirection: String = ""
    )

    enum class TrailStatus {
        ON_TRAIL,
        APPROACHING,
        LEAVING,
        NO_TRAIL
    }

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

        // Calculate bearing to closest trail node
        val bearingToTrail = if (closestNode != null) {
            calculateBearing(currentLat, currentLon, closestNode!!.lat, closestNode!!.lon)
        } else 0f

        // Compass direction (N, NE, E, etc.)
        val compass = bearingToCompass(bearingToTrail)

        // Relative direction (left, right, ahead, behind)
        val relative = if (bearing != null && bearing > 0f && closestNode != null) {
            calculateRelativeDirection(bearing, bearingToTrail)
        } else ""

        val status = when {
            minDistance < 50.0 -> TrailStatus.ON_TRAIL
            minDistance < 200.0 -> {
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
            status = status,
            bearingToTrail = bearingToTrail,
            compassDirection = compass,
            relativeDirection = relative
        )

        lastMatch = match
        return match
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        val bearing = Math.toDegrees(atan2(y, x)).toFloat()
        return (bearing + 360) % 360
    }

    private fun bearingToCompass(bearing: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((bearing + 22.5) / 45).toInt() % 8
        return directions[index]
    }

    private fun calculateRelativeDirection(myBearing: Float, bearingToTrail: Float): String {
        var diff = bearingToTrail - myBearing
        // Normalize to -180 to 180
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360

        return when {
            diff >= -30 && diff <= 30 -> "↑"      // ahead
            diff > 30 && diff < 150 -> "→"         // right
            diff < -30 && diff > -150 -> "←"       // left
            else -> "↓"                             // behind
        }
    }

    fun formatTrailStatus(match: TrailMatch): String {
        return when (match.status) {
            TrailStatus.ON_TRAIL -> {
                "On: ${match.trail?.name}"
            }
            TrailStatus.APPROACHING -> {
                val dir = if (match.relativeDirection.isNotEmpty()) {
                    "${match.relativeDirection} "
                } else ""
                "${dir}${match.trail?.name} (${match.compassDirection})\n${match.distance.toInt()}m"
            }
            TrailStatus.LEAVING -> {
                val dir = if (match.relativeDirection.isNotEmpty()) {
                    "${match.relativeDirection} "
                } else ""
                "${dir}${match.trail?.name} (${match.compassDirection})\n${match.distance.toInt()}m"
            }
            TrailStatus.NO_TRAIL -> "No Trail"
        }
    }
}
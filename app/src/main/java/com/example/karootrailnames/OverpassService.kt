package com.example.karootrailnames

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OverpassService {

    // New: download by center point + radius in miles
    suspend fun downloadTrailsNearby(centerLat: Double, centerLon: Double, radiusMiles: Double = 20.0): List<Trail> {
        // Convert miles to rough lat/lon offset (1 degree lat ~ 69 miles)
        val latOffset = radiusMiles / 69.0
        val lonOffset = radiusMiles / (69.0 * Math.cos(Math.toRadians(centerLat)))

        return downloadTrails(
            minLat = centerLat - latOffset,
            minLon = centerLon - lonOffset,
            maxLat = centerLat + latOffset,
            maxLon = centerLon + lonOffset
        )
    }

    suspend fun downloadTrails(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Trail> {
        return withContext(Dispatchers.IO) {
            try {
                val query = buildQuery(minLat, minLon, maxLat, maxLon)
                val response = makeRequest(query)
                parseTrails(response)
            } catch (e: Exception) {
                Log.e("OverpassService", "Error downloading trails", e)
                emptyList()
            }
        }
    }

    private fun buildQuery(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String {
        return """
            [out:json][timeout:60];
            (
              way["highway"="path"]["name"]($minLat,$minLon,$maxLat,$maxLon);
              way["highway"="track"]["name"]($minLat,$minLon,$maxLat,$maxLon);
              way["highway"="cycleway"]["name"]($minLat,$minLon,$maxLat,$maxLon);
              way["mtb:scale"]($minLat,$minLon,$maxLat,$maxLon);
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    private fun makeRequest(query: String): String {
        Log.d("OverpassService", "Query: $query")

        val url = URL("https://overpass-api.de/api/interpreter")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        connection.outputStream.use { os ->
            os.write("data=$query".toByteArray())
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        Log.d("OverpassService", "Response length: ${response.length}")

        return response
    }

    private fun parseTrails(jsonResponse: String): List<Trail> {
        Log.d("OverpassService", "Parsing response, length: ${jsonResponse.length}")

        val trails = mutableListOf<Trail>()
        val json = JSONObject(jsonResponse)
        val elements = json.getJSONArray("elements")

        val nodeMap = mutableMapOf<Long, TrailNode>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            if (element.getString("type") == "node") {
                val id = element.getLong("id")
                val lat = element.getDouble("lat")
                val lon = element.getDouble("lon")
                nodeMap[id] = TrailNode(lat, lon)
            }
        }

        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            if (element.getString("type") == "way") {
                val id = element.getLong("id")
                val tags = element.optJSONObject("tags")
                val name = tags?.optString("name") ?: "Unnamed Trail"
                val difficulty = tags?.optString("mtb:scale")

                // Skip unnamed trails unless they have mtb:scale
                if (name == "Unnamed Trail" && difficulty == null) continue

                val nodeIds = element.getJSONArray("nodes")
                val trailNodes = mutableListOf<TrailNode>()
                for (j in 0 until nodeIds.length()) {
                    val nodeId = nodeIds.getLong(j)
                    nodeMap[nodeId]?.let { trailNodes.add(it) }
                }

                if (trailNodes.isNotEmpty()) {
                    trails.add(Trail(id, name, trailNodes, difficulty))
                }
            }
        }

        Log.d("OverpassService", "Parsed ${trails.size} named trails")
        return trails
    }
}
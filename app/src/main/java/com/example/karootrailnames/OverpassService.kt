package com.example.karootrailnames

// ============================================================
// Overpass Service - Trail Data Downloader
// Downloads trail data from OpenStreetMap via the Overpass API.
// This is the ONLY network-dependent component in the extension.
// Called once when user taps "Download Trails Near Me" in the app.
// During rides, everything runs 100% offline from cached data.
//
// API: OpenStreetMap Overpass API
//   - Free, open, no API key, no authentication
//   - No rate limits for normal use
//   - Returns structured JSON with trail geometry and metadata
//
// Fallback: If the primary server fails (504, timeout, etc.),
// automatically retries with a mirror server.
// ============================================================

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class OverpassService {

    // ============================================================
    // PUBLIC API: Download trails within a radius of a GPS point
    // Called from MainActivity when user taps download button.
    // Default radius: 30 miles — covers a typical riding area.
    //
    // Converts miles to a lat/lon bounding box since Overpass
    // uses bounding box queries, not radius queries.
    // Uses cosine correction for longitude (degrees get narrower
    // as you move away from the equator).
    // ============================================================
    suspend fun downloadTrailsNearby(centerLat: Double, centerLon: Double, radiusMiles: Double = 25.0): List<Trail> {
        val latOffset = radiusMiles / 69.0
        val lonOffset = radiusMiles / (69.0 * Math.cos(Math.toRadians(centerLat)))

        return downloadTrails(
            minLat = centerLat - latOffset,
            minLon = centerLon - lonOffset,
            maxLat = centerLat + latOffset,
            maxLon = centerLon + lonOffset
        )
    }

    // ============================================================
    // DOWNLOAD PIPELINE
    // Runs on IO dispatcher (background thread) to avoid blocking UI.
    // Tries primary Overpass server first, falls back to mirror
    // if the primary fails (504, timeout, etc.).
    // Returns empty list if all servers fail.
    // ============================================================
    suspend fun downloadTrails(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Trail> {
        return withContext(Dispatchers.IO) {
            val query = buildQuery(minLat, minLon, maxLat, maxLon)
            val servers = listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter"
            )
            for (server in servers) {
                try {
                    Log.d("OverpassService", "Trying server: $server")
                    val response = makeRequest(query, server)
                    return@withContext parseTrails(response)
                } catch (e: Exception) {
                    Log.e("OverpassService", "Server $server failed: ${e.message}")
                }
            }
            Log.e("OverpassService", "All servers failed")
            emptyList()
        }
    }

    // ============================================================
    // OVERPASS QUERY BUILDER
    // Queries OpenStreetMap for trails within the bounding box.
    // We request four types of ways:
    //
    //   1. highway=path  + name  → Named hiking/MTB singletrack
    //   2. highway=track + name  → Named dirt roads/doubletrack
    //   3. highway=cycleway + name → Named bike paths
    //   4. mtb:scale (any)       → Any way with MTB difficulty rating
    //
    // "out body" returns full way data with tags
    // ">" (recurse down) fetches all nodes referenced by those ways
    // "out skel qt" returns node coordinates in optimized format
    //
    // Timeout: 90 seconds — large areas with dense trail networks
    // can return thousands of elements. K3 companion app routing
    // can be slower than direct WiFi.
    // ============================================================
    private fun buildQuery(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String {
        return """
        [out:json][timeout:90];
        (
         way["highway"="path"]["name"]($minLat,$minLon,$maxLat,$maxLon);
         way["highway"="track"]["name"]($minLat,$minLon,$maxLat,$maxLon);
         way["highway"="cycleway"]["name"]($minLat,$minLon,$maxLat,$maxLon);
         way["highway"="secondary"]["name"]($minLat,$minLon,$maxLat,$maxLon);
         way["surface"="gravel"]["name"]($minLat,$minLon,$maxLat,$maxLon);
         way["surface"="dirt"]["name"]($minLat,$minLon,$maxLat,$maxLon);
         way["surface"="unpaved"]["name"]($minLat,$minLon,$maxLat,$maxLon);
         way["mtb:scale"]($minLat,$minLon,$maxLat,$maxLon);
        );
        out body;
        >;
        out skel qt;
    """.trimIndent()
    }

    // ============================================================
    // HTTP REQUEST
    // POST request to Overpass API interpreter endpoint.
    // Uses standard HttpURLConnection (no third-party HTTP libraries).
    //
    // Timeouts: 30 seconds connect + 120 seconds read
    // Content-Type: form-urlencoded (Overpass expects "data=<query>")
    // Query is URL-encoded to handle special characters properly.
    //
    // Error handling: checks HTTP response code before reading.
    // If Overpass returns an error (400, 429, 500, etc.), the error
    // body is logged and an exception is thrown with the HTTP code.
    //
    // Server URL is passed in by downloadTrails() for fallback support.
    // ============================================================
    private fun makeRequest(query: String, serverUrl: String): String {
        Log.d("OverpassService", "Query: $query")

        val url = URL(serverUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 120000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("User-Agent", "KarooTrailNames/1.5.1")

        // URL-encode the query to handle special characters
        val encodedData = "data=${URLEncoder.encode(query, "UTF-8")}"
        connection.outputStream.use { os ->
            os.write(encodedData.toByteArray())
        }

        // Check response code before reading
        val responseCode = connection.responseCode
        Log.d("OverpassService", "HTTP response code: $responseCode")

        if (responseCode != 200) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
            Log.e("OverpassService", "HTTP $responseCode: $errorBody")
            throw Exception("Overpass API returned HTTP $responseCode")
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        Log.d("OverpassService", "Response length: ${response.length}")

        return response
    }

    // ============================================================
    // RESPONSE PARSER
    // Overpass returns JSON with two types of elements:
    //
    //   1. NODES — individual GPS points (lat/lon)
    //      These are the vertices that make up trail geometry.
    //      Stored temporarily in nodeMap for lookup by ID.
    //
    //   2. WAYS — ordered sequences of node IDs + metadata tags
    //      Each way represents one trail or trail segment.
    //      Tags include: name, highway type, mtb:scale, etc.
    //
    // Parse strategy (two-pass):
    //   Pass 1: Build a lookup map of all nodes (id → lat/lon)
    //   Pass 2: Process ways — resolve node IDs to coordinates,
    //           extract name and difficulty, build Trail objects
    //
    // Filtering:
    //   - Skip unnamed trails that have no mtb:scale tag
    //   - Keep unnamed trails WITH mtb:scale
    //   - Skip ways with zero resolved nodes
    //
    // Output: List<Trail> ready for TrailStorage to cache locally.
    // ============================================================
    private fun parseTrails(jsonResponse: String): List<Trail> {
        Log.d("OverpassService", "Parsing response, length: ${jsonResponse.length}")

        val trails = mutableListOf<Trail>()
        val json = JSONObject(jsonResponse)
        val elements = json.getJSONArray("elements")

        // --- PASS 1: Build node lookup map ---
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

        // --- PASS 2: Build trail objects from ways ---
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            if (element.getString("type") == "way") {
                val id = element.getLong("id")
                val tags = element.optJSONObject("tags")
                val name = tags?.optString("name") ?: "Unnamed Trail"
                val difficulty = tags?.optString("mtb:scale")

                // Skip unnamed trails unless they have mtb:scale
                if (name == "Unnamed Trail" && difficulty == null) continue

                // Resolve node IDs to actual GPS coordinates
                val nodeIds = element.getJSONArray("nodes")
                val trailNodes = mutableListOf<TrailNode>()
                for (j in 0 until nodeIds.length()) {
                    val nodeId = nodeIds.getLong(j)
                    nodeMap[nodeId]?.let { trailNodes.add(it) }
                }

                // Only add trails with valid geometry
                if (trailNodes.isNotEmpty()) {
                    trails.add(Trail(id, name, trailNodes, difficulty))
                }
            }
        }

        Log.d("OverpassService", "Parsed ${trails.size} named trails")
        return trails
    }
}
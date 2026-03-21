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
// ============================================================

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OverpassService {

    // ============================================================
    // PUBLIC API: Download trails within a radius of a GPS point
    // Called from MainActivity when user taps download button.
    // Default radius: 20 miles — covers a typical riding area.
    //
    // Converts miles to a lat/lon bounding box since Overpass
    // uses bounding box queries, not radius queries.
    // Uses cosine correction for longitude (degrees get narrower
    // as you move away from the equator).
    // ============================================================
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

    // ============================================================
    // DOWNLOAD PIPELINE
    // Runs on IO dispatcher (background thread) to avoid blocking UI.
    // Three steps: build query → make HTTP request → parse response
    // Returns empty list on any failure (network error, timeout, etc.)
    // ============================================================
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

    // ============================================================
    // OVERPASS QUERY BUILDER
    // Queries OpenStreetMap for trails within the bounding box.
    // We request four types of ways:
    //
    //   1. highway=path  + name  → Named hiking/MTB singletrack
    //   2. highway=track + name  → Named dirt roads/doubletrack
    //   3. highway=cycleway + name → Named bike paths
    //   4. mtb:scale (any)       → Any way with MTB difficulty rating
    //                               (catches trails tagged with difficulty
    //                                but missing standard highway tags)
    //
    // "out body" returns full way data with tags
    // ">" (recurse down) fetches all nodes referenced by those ways
    // "out skel qt" returns node coordinates in optimized format
    //
    // Timeout: 60 seconds — large areas with dense trail networks
    // can return thousands of elements.
    // ============================================================
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

    // ============================================================
    // HTTP REQUEST
    // POST request to Overpass API interpreter endpoint.
    // Uses standard HttpURLConnection (no third-party HTTP libraries).
    //
    // Timeouts: 30 seconds connect + 30 seconds read
    // Content-Type: form-urlencoded (Overpass expects "data=<query>")
    //
    // Note: The Karoo needs WiFi or hotspot connectivity for this.
    // The download happens once; all ride-time matching is offline.
    // ============================================================
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
    //     (unnamed paths without difficulty are usually driveways,
    //      service roads, or other non-trail features)
    //   - Keep unnamed trails WITH mtb:scale (they're real trails
    //     that just haven't been named in OSM yet)
    //   - Skip ways with zero resolved nodes (data integrity check)
    //
    // Output: List<Trail> ready for TrailStorage to cache locally.
    // Typical result: 700+ trails in a trail-dense 20-mile radius.
    // ============================================================
    private fun parseTrails(jsonResponse: String): List<Trail> {
        Log.d("OverpassService", "Parsing response, length: ${jsonResponse.length}")

        val trails = mutableListOf<Trail>()
        val json = JSONObject(jsonResponse)
        val elements = json.getJSONArray("elements")

        // --- PASS 1: Build node lookup map ---
        // Nodes are individual GPS coordinates referenced by ID in ways
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
        // Each way is an ordered list of node IDs that form the trail geometry
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            if (element.getString("type") == "way") {
                val id = element.getLong("id")
                val tags = element.optJSONObject("tags")
                val name = tags?.optString("name") ?: "Unnamed Trail"
                val difficulty = tags?.optString("mtb:scale")

                // Skip unnamed trails unless they have mtb:scale
                // (unnamed + no difficulty = likely not a real trail)
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
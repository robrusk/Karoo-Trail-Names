package com.example.karootrailnames

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TrailStorage(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "trail_areas")

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun saveArea(area: TrailArea) {
        val jsonObject = JSONObject().apply {
            put("name", area.name)
            put("centerLat", area.centerLat)
            put("centerLon", area.centerLon)
            put("downloadedAt", area.downloadedAt)

            val trailsArray = JSONArray()
            area.trails.forEach { trail ->
                val trailJson = JSONObject().apply {
                    put("id", trail.id)
                    put("name", trail.name)
                    put("difficulty", trail.difficulty ?: "")
                    val nodesArray = JSONArray()
                    trail.nodes.forEach { node ->
                        nodesArray.put(JSONObject().apply {
                            put("lat", node.lat)
                            put("lon", node.lon)
                        })
                    }
                    put("nodes", nodesArray)
                }
                trailsArray.put(trailJson)
            }
            put("trails", trailsArray)
        }

        // Use sanitized name as filename
        val filename = area.name.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".json"
        File(dir, filename).writeText(jsonObject.toString())
        Log.d("TrailStorage", "Saved area '${area.name}' with ${area.trails.size} trails")
    }

    fun loadAreas(): List<TrailArea> {
        val areas = mutableListOf<TrailArea>()
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val name = json.getString("name")
                val centerLat = json.getDouble("centerLat")
                val centerLon = json.getDouble("centerLon")
                val downloadedAt = json.optLong("downloadedAt", 0)

                val trails = mutableListOf<Trail>()
                val trailsArray = json.getJSONArray("trails")
                for (i in 0 until trailsArray.length()) {
                    val t = trailsArray.getJSONObject(i)
                    val nodes = mutableListOf<TrailNode>()
                    val nodesArray = t.getJSONArray("nodes")
                    for (j in 0 until nodesArray.length()) {
                        val n = nodesArray.getJSONObject(j)
                        nodes.add(TrailNode(n.getDouble("lat"), n.getDouble("lon")))
                    }
                    trails.add(Trail(
                        t.getLong("id"),
                        t.getString("name"),
                        nodes,
                        t.getString("difficulty").takeIf { it.isNotEmpty() }
                    ))
                }

                areas.add(TrailArea(name, centerLat, centerLon, trails, downloadedAt))
            } catch (e: Exception) {
                Log.e("TrailStorage", "Error loading ${file.name}", e)
            }
        }
        Log.d("TrailStorage", "Loaded ${areas.size} areas")
        return areas
    }

    // Get all trails from all areas combined
    fun loadAllTrails(): List<Trail> {
        return loadAreas().flatMap { it.trails }
    }

    fun deleteArea(areaName: String) {
        val filename = areaName.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".json"
        val file = File(dir, filename)
        if (file.exists()) {
            file.delete()
            Log.d("TrailStorage", "Deleted area '$areaName'")
        }
    }

    // Migration: load old flat trail file if it exists
    fun migrateOldTrails(): List<Trail> {
        val oldFile = File(dir.parentFile, "trails.json")
        if (oldFile.exists()) {
            try {
                val jsonString = oldFile.readText()
                if (jsonString.isNotEmpty()) {
                    val trails = mutableListOf<Trail>()
                    val jsonArray = JSONArray(jsonString)
                    for (i in 0 until jsonArray.length()) {
                        val t = jsonArray.getJSONObject(i)
                        val nodes = mutableListOf<TrailNode>()
                        val nodesArray = t.getJSONArray("nodes")
                        for (j in 0 until nodesArray.length()) {
                            val n = nodesArray.getJSONObject(j)
                            nodes.add(TrailNode(n.getDouble("lat"), n.getDouble("lon")))
                        }
                        trails.add(Trail(
                            t.getLong("id"),
                            t.getString("name"),
                            nodes,
                            t.getString("difficulty").takeIf { it.isNotEmpty() }
                        ))
                    }
                    // Save as "Aztec NM" area and delete old file
                    if (trails.isNotEmpty()) {
                        saveArea(TrailArea("Aztec NM", 36.88, -107.855, trails))
                        oldFile.delete()
                        Log.d("TrailStorage", "Migrated ${trails.size} old trails to 'Aztec NM' area")
                        return trails
                    }
                }
            } catch (e: Exception) {
                Log.e("TrailStorage", "Migration error", e)
            }
        }
        return emptyList()
    }
}
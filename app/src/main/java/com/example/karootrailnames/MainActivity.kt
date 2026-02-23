
package com.example.karootrailnames

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var downloadButton: Button
    private lateinit var statusText: TextView
    private lateinit var locationText: TextView
    private lateinit var areaList: LinearLayout
    private val overpassService = OverpassService()
    private lateinit var trailStorage: TrailStorage
    private lateinit var trailNameDataField: TrailNameDataField

    private var currentLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        trailStorage = TrailStorage(this)
        trailNameDataField = TrailNameDataField(this)

        downloadButton = findViewById(R.id.downloadButton)
        statusText = findViewById(R.id.statusText)
        locationText = findViewById(R.id.locationText)
        areaList = findViewById(R.id.areaList)

        // Migrate old trails.json to new area format
        trailStorage.migrateOldTrails()

        // Show saved areas on startup
        refreshAreaList()

        downloadButton.setOnClickListener {
            downloadTrailsNearMe()
        }

        // Start GPS for location display
        startGPS()

        // Start trail name tracking
        trailNameDataField.start()
        startStatusUpdates()
    }

    private fun startGPS() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                locationText.text = "GPS: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 50f, listener)

        val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (lastKnown != null) {
            currentLocation = lastKnown
            locationText.text = "GPS: ${String.format("%.4f", lastKnown.latitude)}, ${String.format("%.4f", lastKnown.longitude)}"
        }
    }

    private fun downloadTrailsNearMe() {
        val loc = currentLocation
        if (loc == null) {
            statusText.text = "No GPS fix yet. Wait for location or go outside."
            return
        }

        lifecycleScope.launch {
            try {
                downloadButton.isEnabled = false
                statusText.text = "Downloading trails within 20 miles..."

                val trails = overpassService.downloadTrailsNearby(loc.latitude, loc.longitude, 10.0)

                if (trails.isNotEmpty()) {
                    val areaName = getAreaName(loc.latitude, loc.longitude)

                    val area = TrailArea(
                        name = areaName,
                        centerLat = loc.latitude,
                        centerLon = loc.longitude,
                        trails = trails
                    )
                    trailStorage.saveArea(area)

                    statusText.text = "Saved '$areaName': ${trails.size} trails"
                    refreshAreaList()
                } else {
                    statusText.text = "No trails found nearby. Check WiFi."
                }

            } catch (e: Exception) {
                statusText.text = "Download failed: ${e.message}\nSaved trails unchanged."
            } finally {
                downloadButton.isEnabled = true
            }
        }
    }

    private suspend fun getAreaName(lat: Double, lon: Double): String {
        return withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL(
                    "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=10"
                )
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", "KarooTrailNames/1.0")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(response)
                val address = json.optJSONObject("address")

                if (address != null) {
                    val city = address.optString("city", "")
                        .ifEmpty { address.optString("town", "") }
                        .ifEmpty { address.optString("village", "") }
                        .ifEmpty { address.optString("county", "") }
                    val state = address.optString("state", "")

                    if (city.isNotEmpty() && state.isNotEmpty()) {
                        "$city, $state"
                    } else if (city.isNotEmpty()) {
                        city
                    } else if (state.isNotEmpty()) {
                        state
                    } else {
                        "${String.format("%.2f", lat)}, ${String.format("%.2f", lon)}"
                    }
                } else {
                    "${String.format("%.2f", lat)}, ${String.format("%.2f", lon)}"
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Reverse geocoding failed", e)
                "${String.format("%.2f", lat)}, ${String.format("%.2f", lon)}"
            }
        }
    }

    private fun refreshAreaList() {
        areaList.removeAllViews()

        val areas = trailStorage.loadAreas()
        if (areas.isEmpty()) {
            statusText.text = "No trail areas saved.\nGet a GPS fix and tap Download."
            return
        }

        val totalTrails = areas.sumOf { it.trails.size }
        statusText.text = "$totalTrails trails across ${areas.size} areas:"

        areas.forEach { area ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 4)
            }

            val label = TextView(this).apply {
                text = "■ ${area.name} (${area.trails.size} trails)"
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val deleteBtn = Button(this).apply {
                text = "X"
                textSize = 12f
                minimumWidth = 0
                minimumHeight = 0
                setPadding(16, 4, 16, 4)
                setOnClickListener {
                    trailStorage.deleteArea(area.name)
                    refreshAreaList()
                }
            }

            row.addView(label)
            row.addView(deleteBtn)
            areaList.addView(row)

            val trailText = TextView(this).apply {
                val sb = StringBuilder()
                area.trails.take(5).forEach { trail ->
                    sb.appendLine("  • ${trail.name}")
                }
                if (area.trails.size > 5) {
                    sb.appendLine("  ... and ${area.trails.size - 5} more")
                }
                text = sb.toString()
                textSize = 13f
                setPadding(16, 0, 0, 0)
            }
            areaList.addView(trailText)
        }
    }

    private fun startStatusUpdates() {
        lifecycleScope.launch {
            while (true) {
                delay(3000)
                val currentTrail = trailNameDataField.currentTrailName
                if (currentTrail != "No Trail") {
                    val areas = trailStorage.loadAreas()
                    val totalTrails = areas.sumOf { it.trails.size }
                    statusText.text = "Current: $currentTrail\n\n" +
                            "$totalTrails trails across ${areas.size} areas"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        trailNameDataField.stop()
    }
}
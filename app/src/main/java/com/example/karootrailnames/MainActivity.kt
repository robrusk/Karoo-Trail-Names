package com.example.karootrailnames

// ============================================================
// Main Activity - Configuration & Trail Management Screen
// This is the app's home screen on the Karoo. It provides:
//   1. GPS location display
//   2. "Download Trails Near Me" button
//   3. Beep on/off toggle (saved in SharedPreferences)
//   4. List of saved trail areas with trail counts
//   5. Delete individual areas
//   6. Live trail name preview (updates every 3 seconds)
//
// This screen is NOT used during rides — it's for setup only.
// During rides, the extension service and data type handle
// everything independently via startStream() and startView().
// ============================================================

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

        // Handle migration from early versions that stored all trails
        // in a single flat file instead of organized by area
        trailStorage.migrateOldTrails()

        // Show previously downloaded areas on startup
        refreshAreaList()

        downloadButton.setOnClickListener {
            downloadTrailsNearMe()
        }

        // ============================================================
        // BEEP TOGGLE
        // Allows user to enable/disable beep and flash alerts.
        // Preference is stored in SharedPreferences and read by
        // the extension during rides — no restart required.
        // Default: ON
        // ============================================================
        // Beep on/off toggle — persists in SharedPreferences
        val prefs = getSharedPreferences("trail_names_prefs", MODE_PRIVATE)
        val beepToggleBtn = findViewById<Button>(R.id.beepToggleButton)
        val beepOn = prefs.getBoolean("beep_enabled", true)
        beepToggleBtn.text = if (beepOn) "Beep: ON" else "Beep: OFF"
        beepToggleBtn.setOnClickListener {
            val current = prefs.getBoolean("beep_enabled", true)
            val newValue = !current
            prefs.edit().putBoolean("beep_enabled", newValue).apply()
            beepToggleBtn.text = if (newValue) "Beep: ON" else "Beep: OFF"
            statusText.text = "Beep alerts ${if (newValue) "enabled" else "disabled"}"
        }

        // Start GPS for location display on this screen
        startGPS()

        // TrailNameDataField provides a live trail name preview
        // on the main screen (separate from the ride data field)
        trailNameDataField.start()
        startStatusUpdates()
    }

    // ============================================================
    // GPS SETUP
    // Gets the rider's current position for two purposes:
    //   1. Display coordinates on screen (confirms GPS lock)
    //   2. Center point for trail download radius
    //
    // Update interval: 5 seconds, 50m minimum distance
    // (Relaxed compared to ride mode — this is just for UI display)
    //
    // Also grabs last known location as a fallback so the screen
    // isn't blank while waiting for a fresh GPS fix.
    // ============================================================
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

        // Use last known location so user doesn't wait for a fresh fix
        val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (lastKnown != null) {
            currentLocation = lastKnown
            locationText.text = "GPS: ${String.format("%.4f", lastKnown.latitude)}, ${String.format("%.4f", lastKnown.longitude)}"
        }
    }

    // ============================================================
    // TRAIL DOWNLOAD
    // Triggered by the "Download Trails Near Me" button.
    // Uses current GPS position as center, downloads trails within
    // 10-mile radius via OverpassService, then:
    //   1. Reverse geocodes the location to get a human-readable
    //      area name (e.g., "Aztec, New Mexico")
    //   2. Saves as a named TrailArea in TrailStorage
    //   3. Refreshes the area list on screen
    //
    // Runs as a coroutine so the UI stays responsive during
    // the network request. Button is disabled during download
    // to prevent duplicate requests.
    // ============================================================
    private fun downloadTrailsNearMe() {
        val loc = currentLocation
        if (loc == null) {
            statusText.text = "Waiting for GPS fix..."
            downloadButton.isEnabled = false
            // Retry every 3 seconds until GPS locks
            lifecycleScope.launch {
                var attempts = 0
                while (currentLocation == null && attempts < 20) {
                    delay(3000)
                    attempts++
                    statusText.text = "Waiting for GPS fix... (${attempts * 3}s)"
                }
                downloadButton.isEnabled = true
                if (currentLocation != null) {
                    downloadTrailsNearMe()
                } else {
                    statusText.text = "Could not get GPS fix. Try going outside."
                }
            }
            return
        }

        lifecycleScope.launch {
            try {
                downloadButton.isEnabled = false
                statusText.text = "Downloading trails within 30 miles..."

                val trails = overpassService.downloadTrailsNearby(loc.latitude, loc.longitude, 15.0)

                if (trails.isNotEmpty()) {
                    // Get a human-readable name for this download area
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

    // ============================================================
    // REVERSE GEOCODING
    // Converts GPS coordinates to a human-readable area name
    // using OpenStreetMap's Nominatim API (free, no API key).
    //
    // Zoom level 10 = city/town level granularity.
    // Falls back through: city → town → village → county → state
    // If all else fails, uses raw lat/lon coordinates as the name.
    //
    // User-Agent header required by Nominatim's terms of use.
    // Short timeouts (5s) since this is a nice-to-have, not critical.
    // ============================================================
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

                // Try to build a meaningful name from the address components
                // Priority: city > town > village > county, plus state
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
                // Fallback to coordinates — download still works, just no pretty name
                "${String.format("%.2f", lat)}, ${String.format("%.2f", lon)}"
            }
        }
    }

    // ============================================================
    // AREA LIST DISPLAY
    // Shows all downloaded trail areas with:
    //   - Area name and trail count
    //   - Delete button (X) to remove individual areas
    //   - Preview of first 5 trail names in each area
    //   - Total trail count across all areas
    //
    // Built dynamically with programmatic views since the Karoo's
    // small screen benefits from a simple scrollable list.
    // ============================================================
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
            // Row: area name + trail count + delete button
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

            // Trail name preview — first 5 trails in the area
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

    // ============================================================
    // LIVE TRAIL PREVIEW
    // Updates the status text every 3 seconds with the current
    // trail name from TrailNameDataField (the MainActivity's own
    // GPS listener, separate from the ride data field).
    //
    // This lets users verify trail detection is working without
    // starting a ride — useful for testing after a fresh download.
    // ============================================================
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
        // Stop the preview GPS listener when leaving the app
        trailNameDataField.stop()
    }
}
package com.example.karootrailnames

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var downloadButton: Button
    private lateinit var statusText: TextView
    private lateinit var locationText: TextView
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

        // Try to get last known location immediately
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
                statusText.text = "Downloading trails within 10 miles..."

                val trails = overpassService.downloadTrailsNearby(loc.latitude, loc.longitude, 10.0)

                if (trails.isNotEmpty()) {
                    // Try to get area name from reverse geocoding
                    val areaName = getAreaName(loc.latitude, loc.longitude)

                    val area = TrailArea(
                        name = areaName,
                        centerLat = loc.latitude,
                        centerLon = loc.longitude,
                        trails = trails
                    )
                    trailStorage.saveArea(area)

                    statusText.text = "Saved '$areaName': ${trails.size} trails\n\n"
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
                val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                    val state = addr.adminArea
                    if (city != null && state != null && city != state) {
                        "$city, $state"
                    } else {
                        city ?: state ?: "${String.format("%.2f", lat)}, ${String.format("%.2f", lon)}"
                    }
                } else {
                    "${String.format("%.2f", lat)}, ${String.format("%.2f", lon)}"
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Geocoding failed", e)
                "${String.format("%.2f", lat)}, ${String.format("%.2f", lon)}"
            }
        }
    }

    private fun refreshAreaList() {
        val areas = trailStorage.loadAreas()
        if (areas.isEmpty()) {
            statusText.text = "No trail areas saved.\nGet a GPS fix and tap Download."
            return
        }

        val totalTrails = areas.sumOf { it.trails.size }
        val sb = StringBuilder()
        sb.appendLine("$totalTrails trails across ${areas.size} areas:\n")

        areas.forEach { area ->
            sb.appendLine("■ ${area.name} (${area.trails.size} trails)")
            area.trails.take(5).forEach { trail ->
                sb.appendLine("  • ${trail.name}")
            }
            if (area.trails.size > 5) {
                sb.appendLine("  ... and ${area.trails.size - 5} more")
            }
            sb.appendLine("  [Long press to delete]\n")
        }

        statusText.text = sb.toString()
    }

    private fun startStatusUpdates() {
        lifecycleScope.launch {
            while (true) {
                delay(3000)
                val currentTrail = trailNameDataField.currentTrailName
                if (currentTrail != "No Trail") {
                    // Only update if actually on a trail
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
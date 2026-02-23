package com.example.karootrailnames

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

class TrailNameDataField(private val context: Context) : LocationListener {

    private val trailStorage = TrailStorage(context)
    private val trailMatcher = TrailMatcher()
    private var trails: List<Trail> = emptyList()
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var currentTrailName: String = "No Trail"
        private set

    fun start() {
        // FIX: Changed from loadTrails() to loadAllTrails() to match your new storage logic
        trails = trailStorage.loadAllTrails()
        Log.d("TrailNameDataField", "Started tracking with ${trails.size} trails loaded")

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000, // 1 second
                5f,   // 5 meters
                this
            )
        } catch (e: SecurityException) {
            currentTrailName = "GPS Permission Denied"
            Log.e("TrailNameDataField", "GPS Permission Denied")
        }
    }

    fun stop() {
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        val match = trailMatcher.findCurrentTrail(
            currentLat = location.latitude,
            currentLon = location.longitude,
            trails = trails,
            bearing = location.bearing
        )

        currentTrailName = trailMatcher.formatTrailStatus(match)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        currentTrailName = "GPS Disabled"
    }
}
package com.example.karootrailnames

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

class TrailNameDataField(private val context: Context) : LocationListener {

    private lateinit var trailStorage: TrailStorage
    private lateinit var matcher: TrailMatcher
    private var trails: List<Trail> = emptyList()
    var currentTrailName: String = "No Trail"

    fun start() {
        trailStorage = TrailStorage(context)
        matcher = TrailMatcher()
        trails = trailStorage.loadAllTrails()
        Log.d("TrailNameDataField", "Started tracking with ${trails.size} trails loaded")

        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000, 5f, this
            )
        } catch (e: SecurityException) {
            Log.e("TrailNameDataField", "Location permission denied", e)
        }
    }

    fun stop() {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            Log.e("TrailNameDataField", "Error stopping", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        if (trails.isEmpty()) return

        val match = matcher.findCurrentTrail(
            currentLat = location.latitude,
            currentLon = location.longitude,
            trails = trails,
            bearing = location.bearing
        )

        currentTrailName = matcher.formatTrailStatus(match)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
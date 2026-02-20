package com.example.karootrailnames

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig

class TrailNameExtension : KarooExtension("trail-name", "1") {
    override val types: List<DataTypeImpl> by lazy {
        listOf(TrailNameDataType(extension, applicationContext))
    }
}

class TrailNameDataType(
    extension: String,
    private val appContext: Context
) : DataTypeImpl(extension, "current-trail") {

    private var currentTrailStatus: String = "No Trail"
    private var lastBeepTrail: String = ""
    private var karooSystem: KarooSystemService? = null

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d("TrailNameDataType", "🎨 startView called")

        val views = RemoteViews(context.packageName, R.layout.trail_name_view)
        views.setTextViewText(R.id.trail_status, currentTrailStatus)
        emitter.updateView(views)

        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val updatedViews = RemoteViews(context.packageName, R.layout.trail_name_view)
                updatedViews.setTextViewText(R.id.trail_status, currentTrailStatus)
                emitter.updateView(updatedViews)
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(runnable, 1000)

        emitter.setCancellable {
            handler.removeCallbacks(runnable)
        }
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d("TrailNameDataType", "🔥🔥🔥 STARTING STREAM 🔥🔥🔥")

        // Connect to Karoo system for beeps
        val ks = KarooSystemService(appContext)
        karooSystem = ks
        ks.connect { connected ->
            Log.d("TrailNameDataType", "KarooSystem connected: $connected")
        }

        val storage = TrailStorage(appContext)
        val matcher = TrailMatcher()
        val trails = storage.loadAllTrails()

        Log.d("TrailNameDataType", "🔥🔥🔥 EXTENSION LOADED ${trails.size} TRAILS 🔥🔥🔥")

        if (trails.isEmpty()) {
            currentTrailStatus = "No Trails"
            emitter.onNext(StreamState.Searching)
            return
        }

        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val mainHandler = Handler(Looper.getMainLooper())

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val match = matcher.findCurrentTrail(
                    currentLat = location.latitude,
                    currentLon = location.longitude,
                    trails = trails,
                    bearing = location.bearing
                )

                currentTrailStatus = matcher.formatTrailStatus(match)
                Log.d("TrailNameDataType", "Status: $currentTrailStatus")

                // Beep when arriving on a new trail (within 15 meters)
                val trailName = match.trail?.name ?: ""
                if (match.distance < 15.0 && trailName.isNotEmpty() && trailName != lastBeepTrail) {
                    Log.d("TrailNameDataType", "🔔 BEEP! Arrived on: $trailName")
                    lastBeepTrail = trailName
                    try {
                        ks.dispatch(
                            PlayBeepPattern(
                                listOf(
                                    PlayBeepPattern.Tone(800, 200),
                                    PlayBeepPattern.Tone(1000, 200)
                                )
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("TrailNameDataType", "Beep failed: ${e.message}")
                    }
                }

                // Reset beep tracking when leaving all trails
                if (match.distance > 50.0) {
                    lastBeepTrail = ""
                }

                val dataPoint = DataPoint(
                    dataTypeId = "current-trail",
                    values = mapOf("value" to match.distance)
                )

                emitter.onNext(StreamState.Streaming(dataPoint))
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            mainHandler.post {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 2000L, 10f, locationListener
                    )
                    Log.d("TrailNameDataType", "GPS listener registered on main thread")
                } catch (e: SecurityException) {
                    Log.e("TrailNameDataType", "Location permission denied", e)
                    emitter.onNext(StreamState.NotAvailable)
                }
            }
            emitter.onNext(StreamState.Searching)
        } catch (e: Exception) {
            Log.e("TrailNameDataType", "Error starting GPS", e)
            emitter.onNext(StreamState.NotAvailable)
        }

        emitter.setCancellable {
            Log.d("TrailNameDataType", "Stream cancelled, removing GPS listener")
            mainHandler.post {
                locationManager.removeUpdates(locationListener)
            }
            ks.disconnect()
        }
    }
}
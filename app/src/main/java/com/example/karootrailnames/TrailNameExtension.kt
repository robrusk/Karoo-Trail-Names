package com.example.karootrailnames

import android.content.Context
import android.graphics.Color
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
    var karooSystem: KarooSystemService? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("TrailNameExtension", "TRAILEXT onCreate called")
        Log.e("TrailNameExtension", "TRAILEXT onCreate called ERROR LEVEL")
        try {
            karooSystem = KarooSystemService(this)
            Log.d("TrailNameExtension", "TRAILEXT KarooSystemService created")
            karooSystem?.connect { connected ->
                Log.d("TrailNameExtension", "TRAILEXT connected: $connected")
                Log.e("TrailNameExtension", "TRAILEXT connected ERROR LEVEL: $connected")
            }
        } catch (e: Exception) {
            Log.e("TrailNameExtension", "TRAILEXT onCreate CRASHED: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        Log.e("TrailNameExtension", "TRAILEXT onStartCommand called")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        Log.d("TrailNameExtension", "TRAILEXT onDestroy called")
        karooSystem?.disconnect()
        karooSystem = null
        super.onDestroy()
    }

    override val types: List<DataTypeImpl> by lazy {
        listOf(TrailNameDataType(extension, applicationContext, this))
    }
}

class TrailNameDataType(
    extension: String,
    private val appContext: Context,
    private val ext: TrailNameExtension
) : DataTypeImpl(extension, "current-trail") {

    @Volatile
    private var currentTrailStatus: String = "No Trail"
    @Volatile
    private var currentTrailColor: Int = Color.GRAY
    @Volatile
    private var currentProximity: Int = 0
    private var lastBeepTrail: String = ""

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d("TrailNameDataType", "startView called")

        val views = RemoteViews(context.packageName, R.layout.trail_name_view)
        views.setTextViewText(R.id.trail_status, currentTrailStatus)
        views.setTextColor(R.id.trail_status, currentTrailColor)
        views.setProgressBar(R.id.proximity_bar, 100, currentProximity, false)
        emitter.updateView(views)

        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val updatedViews = RemoteViews(context.packageName, R.layout.trail_name_view)
                updatedViews.setTextViewText(R.id.trail_status, currentTrailStatus)
                updatedViews.setTextColor(R.id.trail_status, currentTrailColor)
                updatedViews.setProgressBar(R.id.proximity_bar, 100, currentProximity, false)
                emitter.updateView(updatedViews)
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(runnable, 1000)

        emitter.setCancellable {
            handler.removeCallbacks(runnable)
        }
    }

    private fun difficultyToColor(difficulty: String?): Int {
        return when (difficulty) {
            "0" -> Color.parseColor("#228B22")
            "1" -> Color.parseColor("#1E90FF")
            "2", "3" -> Color.BLACK
            "4", "5" -> Color.RED
            else -> Color.GRAY
        }
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d("TrailNameDataType", "STARTING STREAM")

        val storage = TrailStorage(appContext)
        val matcher = TrailMatcher()
        val trails = storage.loadAllTrails()

        Log.d("TrailNameDataType", "EXTENSION LOADED ${trails.size} TRAILS")

        if (trails.isEmpty()) {
            currentTrailStatus = "No Trails"
            currentTrailColor = Color.GRAY
            currentProximity = 0
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
                currentTrailColor = difficultyToColor(match.trail?.difficulty)

                currentProximity = if (match.distance < 300.0) {
                    ((300.0 - match.distance) / 300.0 * 100).toInt()
                } else {
                    0
                }

                Log.d("TrailNameDataType", "Status: $currentTrailStatus | Difficulty: ${match.trail?.difficulty} | Proximity: $currentProximity")

                val trailName = match.trail?.name ?: ""
                if (match.distance < 50.0 && trailName.isNotEmpty() && trailName != lastBeepTrail) {
                    Log.d("TrailNameDataType", "BEEP! Arrived on: $trailName")
                    lastBeepTrail = trailName

                    val ks = ext.karooSystem
                    Log.d("TrailNameDataType", "KarooSystem is ${if (ks != null) "AVAILABLE" else "NULL"}")

                    if (ks != null) {
                        try {
                            ks.dispatch(
                                PlayBeepPattern(
                                    listOf(
                                        PlayBeepPattern.Tone(800, 200),
                                        PlayBeepPattern.Tone(1000, 200)
                                    )
                                )
                            )
                            Log.d("TrailNameDataType", "PlayBeepPattern dispatched successfully")
                        } catch (e: Exception) {
                            Log.e("TrailNameDataType", "Beep failed: ${e.message}")
                        }
                    } else {
                        Log.e("TrailNameDataType", "Cannot beep - KarooSystem is NULL!")
                    }
                }

                if (match.distance > 100.0) {
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
        }
    }
}
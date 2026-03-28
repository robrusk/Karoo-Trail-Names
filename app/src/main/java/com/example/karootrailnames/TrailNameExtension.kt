package com.example.karootrailnames

// ============================================================
// Karoo Trail Names Extension - v1.3
// Real-time trail name display for Karoo K2/K3
// Built on karoo-ext 1.1.8 SDK
// GitHub: https://github.com/robrusk/Karoo-Trail-Names
// License: MIT
// ============================================================

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

// ============================================================
// EXTENSION SERVICE
// Registered in AndroidManifest.xml with intent filter:
//   io.hammerhead.karooext.KAROO_EXTENSION
// Karoo OS discovers and binds to this service automatically.
// ============================================================
class TrailNameExtension : KarooExtension("trail-name", "1") {

    override fun onCreate() {
        super.onCreate()
        Log.d("TrailNameExtension", "TRAILEXT onCreate called")
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        Log.e("TrailNameExtension", "TRAILEXT onStartCommand called")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        Log.d("TrailNameExtension", "TRAILEXT onDestroy called")
        super.onDestroy()
    }

    // Register our data type with the Karoo system
    override val types: List<DataTypeImpl> by lazy {
        listOf(TrailNameDataType(extension, applicationContext))
    }
}

// ============================================================
// DATA TYPE
// This is the core of the extension. It does three things:
//   1. startStream() - GPS listener that matches position to trails
//   2. startView()   - Renders trail info on the Karoo data field
//   3. Alerts        - Hardware buzzer beep + screen flash on arrival
//
// KarooSystemService is created inside startStream() following
// the pattern from Hammerhead's official sample app and the
// eiradar extension. This ensures the connection is established
// within the correct context before any beep dispatch.
// ============================================================
class TrailNameDataType(
    extension: String,
    private val appContext: Context
) : DataTypeImpl(extension, "current-trail") {

    // --- Shared state between startStream() and startView() ---
    // @Volatile ensures thread-safe reads across stream and UI threads
    @Volatile
    private var currentTrailStatus: String = "No Trail"
    @Volatile
    private var currentTrailColor: Int = Color.GRAY
    @Volatile
    private var currentProximity: Int = 0

    // Flash alert: when true, data field background flashes black
    // for 2 seconds to provide a visual trail arrival notification
    @Volatile
    private var flashActive: Boolean = false

    // Beep tracking: prevents repeated beeps for the same trail
    // Resets when rider moves > 100m from any trail
    private var lastBeepTrail: String = ""

    // ============================================================
    // VIEW RENDERING
    // Uses RemoteViews (Android's cross-process view system)
    // Layout defined in res/layout/trail_name_view.xml:
    //   - TextView for trail status (name, direction, distance, difficulty symbol)
    //   - ProgressBar for proximity (fills as rider approaches trail)
    //
    // When flashActive is true, background inverts to black with
    // white text for 2 seconds as a visual trail arrival alert.
    // Polls shared @Volatile variables every 1 second via Handler.
    // ============================================================
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
                updatedViews.setProgressBar(R.id.proximity_bar, 100, currentProximity, false)

                // Flash alert: invert colors for 2 seconds on trail arrival
                if (flashActive) {
                    updatedViews.setInt(R.id.trail_layout, "setBackgroundColor", Color.BLACK)
                    updatedViews.setTextColor(R.id.trail_status, Color.WHITE)
                } else {
                    updatedViews.setInt(R.id.trail_layout, "setBackgroundColor", Color.WHITE)
                    updatedViews.setTextColor(R.id.trail_status, currentTrailColor)
                }

                emitter.updateView(updatedViews)
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(runnable, 1000)

        emitter.setCancellable {
            handler.removeCallbacks(runnable)
        }
    }

    // ============================================================
    // DIFFICULTY SYMBOLS
    // Maps OSM mtb:scale tag values to standard trail markers
    // ============================================================
    private fun difficultySymbol(difficulty: String?): String {
        return when (difficulty) {
            "0" -> "\u25CF "          // ● Green circle - beginner
            "1" -> "\u25A0 "          // ■ Blue square - intermediate
            "2", "3" -> "\u25C6 "     // ◆ Black diamond - advanced
            "4", "5" -> "\u25C6\u25C6 " // ◆◆ Double black diamond - expert
            else -> ""                // No rating
        }
    }

    private fun difficultyColor(difficulty: String?): Int {
        return when (difficulty) {
            "0" -> Color.parseColor("#228B22")   // Green
            "1" -> Color.parseColor("#1E90FF")   // Blue
            "2", "3" -> Color.BLACK              // Black
            "4", "5" -> Color.RED                // Red
            else -> Color.BLACK                  // Unrated
        }
    }

    // ============================================================
    // DATA STREAM
    // This is the engine of the extension. Runs during active rides.
    //
    // KarooSystemService is created HERE (not in the extension's
    // onCreate) following the pattern used by Hammerhead's sample
    // app and the eiradar extension. The official sample uses
    // lazy initialization in an Activity context — creating it
    // inside startStream() ensures the connection is established
    // in the correct running context for dispatch to work.
    // ============================================================
    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d("TrailNameDataType", "STARTING STREAM")

        // --- KAROO SYSTEM CONNECTION FOR BEEP ---
        // Created here per Hammerhead's recommended pattern.
        // connect() callback confirms when dispatch is ready.
        val karooSystem = KarooSystemService(appContext)
        var karooConnected = false
        karooSystem.connect { connected ->
            karooConnected = connected
            Log.d("TrailNameDataType", "BEEP KarooSystem connected: $connected")
        }

        // Load trails from local cache (downloaded via OverpassService)
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

                // --- TRAIL MATCHING ---
                val match = matcher.findCurrentTrail(
                    currentLat = location.latitude,
                    currentLon = location.longitude,
                    trails = trails,
                    bearing = location.bearing
                )

                // --- FORMAT DISPLAY ---
                val symbol = difficultySymbol(match.trail?.difficulty)
                currentTrailStatus = symbol + matcher.formatTrailStatus(match)
                currentTrailColor = difficultyColor(match.trail?.difficulty)

                // --- PROXIMITY BAR ---
                currentProximity = if (match.distance < 300.0) {
                    ((300.0 - match.distance) / 300.0 * 100).toInt()
                } else {
                    0
                }

                Log.d("TrailNameDataType", "Status: $currentTrailStatus | Difficulty: ${match.trail?.difficulty} | Proximity: $currentProximity")

                // --- BEEP + FLASH ALERT ---
                // Triggers when rider arrives within 50m of a new trail.
                // 1. Dispatches 10 low-pitch beeps (500Hz) to hardware buzzer
                // 2. Flashes data field background black for 2 seconds
                // Only dispatches if KarooSystem is confirmed connected.
                val trailName = match.trail?.name ?: ""
                if (match.distance < 50.0 && trailName.isNotEmpty() && trailName != lastBeepTrail) {
                    Log.d("TrailNameDataType", "BEEP! Arrived on: $trailName (karooConnected=$karooConnected)")
                    lastBeepTrail = trailName

                    // Visual alert: flash background black for 2 seconds
                    flashActive = true
                    mainHandler.postDelayed({ flashActive = false }, 2000)

                    // Audio alert: 10 low-pitch beeps via hardware buzzer
                    if (karooConnected) {
                        try {
                            karooSystem.dispatch(
                                PlayBeepPattern(
                                    listOf(
                                        PlayBeepPattern.Tone(4000, 300),
                                        PlayBeepPattern.Tone(0, 100),
                                        PlayBeepPattern.Tone(5000, 300),
                                        PlayBeepPattern.Tone(0, 100),
                                        PlayBeepPattern.Tone(6000, 300),
                                        PlayBeepPattern.Tone(0, 100),
                                        PlayBeepPattern.Tone(4000, 300),
                                        PlayBeepPattern.Tone(0, 100),
                                        PlayBeepPattern.Tone(5000, 300),
                                        PlayBeepPattern.Tone(0, 100),
                                        PlayBeepPattern.Tone(6000, 300),
                                        PlayBeepPattern.Tone(0, 100),
                                        PlayBeepPattern.Tone(4000, 300),
                                        PlayBeepPattern.Tone(0, 100),
                                        PlayBeepPattern.Tone(5000, 300),
                                        PlayBeepPattern.Tone(0, 100),
                                        PlayBeepPattern.Tone(6000, 300),
                                        PlayBeepPattern.Tone(0, 100),
                                        PlayBeepPattern.Tone(500, 300)
                                    )
                                )
                            )
                            Log.d("TrailNameDataType", "PlayBeepPattern dispatched successfully")
                        } catch (e: Exception) {
                            Log.e("TrailNameDataType", "Beep failed: ${e.message}")
                        }
                    } else {
                        Log.e("TrailNameDataType", "Cannot beep - KarooSystem not connected!")
                    }
                }

                // Reset beep tracking when leaving all trails
                if (match.distance > 100.0) {
                    lastBeepTrail = ""
                }

                // --- EMIT DATA POINT ---
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

        // Register GPS listener on main thread (required by LocationManager)
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

        // Cleanup when Karoo stops the stream
        emitter.setCancellable {
            Log.d("TrailNameDataType", "Stream cancelled, cleaning up")
            karooSystem.disconnect()
            mainHandler.post {
                locationManager.removeUpdates(locationListener)
            }
        }
    }
}
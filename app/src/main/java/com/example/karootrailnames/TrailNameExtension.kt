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
// KarooSystemService is created here so it's available for
// hardware actions (beep alerts) dispatched from the data type.
// ============================================================
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

    // Register our data type with the Karoo system
    // "extension" is the extension ID ("trail-name")
    // "applicationContext" provides Android context for GPS, storage, etc.
    // "this" passes the extension reference so the data type can access karooSystem for beeps
    override val types: List<DataTypeImpl> by lazy {
        listOf(TrailNameDataType(extension, applicationContext, this))
    }
}

// ============================================================
// DATA TYPE
// This is the core of the extension. It does three things:
//   1. startStream() - GPS listener that matches position to trails
//   2. startView()   - Renders trail info on the Karoo data field
//   3. Beep alerts   - Hardware buzzer when arriving on a new trail
//
// Communication between stream and view uses @Volatile variables
// since they run on different threads.
// ============================================================
class TrailNameDataType(
    extension: String,
    private val appContext: Context,
    private val ext: TrailNameExtension  // Reference to extension for KarooSystem access
) : DataTypeImpl(extension, "current-trail") {

    // --- Shared state between startStream() and startView() ---
    // @Volatile ensures thread-safe reads across stream and UI threads
    @Volatile
    private var currentTrailStatus: String = "No Trail"
    @Volatile
    private var currentTrailColor: Int = Color.GRAY
    @Volatile
    private var currentProximity: Int = 0    // 0-100, drives the proximity progress bar

    // Beep tracking: prevents repeated beeps for the same trail
    // Resets when rider moves > 100m from any trail
    private var lastBeepTrail: String = ""

    // ============================================================
    // VIEW RENDERING
    // Uses RemoteViews (Android's cross-process view system)
    // This is the only way to render custom UI in a Karoo data field.
    // Layout defined in res/layout/trail_name_view.xml:
    //   - TextView for trail status (name, direction, distance, difficulty symbol)
    //   - ProgressBar for proximity (fills as rider approaches trail)
    // Polls shared @Volatile variables every 1 second via Handler.
    // ============================================================
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d("TrailNameDataType", "startView called")

        // Initial view render
        val views = RemoteViews(context.packageName, R.layout.trail_name_view)
        views.setTextViewText(R.id.trail_status, currentTrailStatus)
        views.setTextColor(R.id.trail_status, currentTrailColor)
        views.setProgressBar(R.id.proximity_bar, 100, currentProximity, false)
        emitter.updateView(views)

        // Refresh loop — updates the data field every second
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

    // ============================================================
    // DIFFICULTY SYMBOLS
    // Maps OSM mtb:scale tag values to standard trail markers:
    //   ● Green circle   = Beginner (mtb:scale 0)
    //   ■ Blue square    = Intermediate (mtb:scale 1)
    //   ◆ Black diamond  = Advanced (mtb:scale 2-3)
    //   ◆◆ Double diamond = Expert (mtb:scale 4-5)
    //   (none)           = Unrated trail
    // ============================================================
    private fun difficultySymbol(difficulty: String?): String {
        return when (difficulty) {
            "0" -> "\u25CF "          // ● Green circle - beginner
            "1" -> "\u25A0 "          // ■ Blue square - intermediate
            "2", "3" -> "\u25C6 "     // ◆ Black diamond - advanced
            "4", "5" -> "\u25C6\u25C6 " // ◆◆ Double black diamond - expert
            else -> ""                // No rating — no symbol
        }
    }

    // Text color matches difficulty for visual reinforcement
    private fun difficultyColor(difficulty: String?): Int {
        return when (difficulty) {
            "0" -> Color.parseColor("#228B22")   // Green
            "1" -> Color.parseColor("#1E90FF")   // Blue
            "2", "3" -> Color.BLACK              // Black
            "4", "5" -> Color.RED                // Red
            else -> Color.BLACK                  // Unrated — clean black text
        }
    }

    // ============================================================
    // DATA STREAM
    // This is the engine of the extension. Runs during active rides.
    //
    // Flow:
    //   1. Load locally cached trails from TrailStorage (JSON)
    //   2. Register GPS listener (2s interval, 10m min distance)
    //   3. On each GPS update:
    //      a. TrailMatcher finds closest trail within 300m
    //      b. Format status text with direction arrows and distance
    //      c. Prepend difficulty symbol (●, ■, ◆)
    //      d. Update proximity bar (0% at 300m → 100% on trail)
    //      e. Trigger beep if arriving on new trail (< 50m)
    //      f. Emit data point to Karoo's stream system
    //
    // All trail matching runs OFFLINE from pre-downloaded OSM data.
    // Zero network usage during rides.
    // ============================================================
    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d("TrailNameDataType", "STARTING STREAM")

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
                // TrailMatcher.findCurrentTrail() returns the closest trail
                // within 300m, with distance, bearing, and direction info
                val match = matcher.findCurrentTrail(
                    currentLat = location.latitude,
                    currentLon = location.longitude,
                    trails = trails,
                    bearing = location.bearing
                )

                // --- FORMAT DISPLAY ---
                // formatTrailStatus() returns strings like:
                //   "On: Ruskers Ridge"           (< 30m, on trail)
                //   "↑ Shred City (NW) 150m"      (approaching, with arrow + compass)
                //   "No Trail"                     (> 300m from any trail)
                val symbol = difficultySymbol(match.trail?.difficulty)
                currentTrailStatus = symbol + matcher.formatTrailStatus(match)
                currentTrailColor = difficultyColor(match.trail?.difficulty)

                // --- PROXIMITY BAR ---
                // Linear scale: 300m = 0%, 0m = 100%
                // Displayed as horizontal ProgressBar in the data field
                currentProximity = if (match.distance < 300.0) {
                    ((300.0 - match.distance) / 300.0 * 100).toInt()
                } else {
                    0
                }

                Log.d("TrailNameDataType", "Status: $currentTrailStatus | Difficulty: ${match.trail?.difficulty} | Proximity: $currentProximity")

                // --- BEEP ALERT ---
                // Triggers when rider arrives within 50m of a new trail.
                // Uses PlayBeepPattern to fire the Karoo hardware buzzer.
                // lastBeepTrail prevents repeated beeps for the same trail.
                // Resets when rider moves > 100m away (ready to beep again).
                val trailName = match.trail?.name ?: ""
                if (match.distance < 50.0 && trailName.isNotEmpty() && trailName != lastBeepTrail) {
                    Log.d("TrailNameDataType", "BEEP! Arrived on: $trailName")
                    lastBeepTrail = trailName

                    // Access KarooSystemService via extension reference
                    val ks = ext.karooSystem
                    Log.d("TrailNameDataType", "KarooSystem is ${if (ks != null) "AVAILABLE" else "NULL"}")

                    if (ks != null) {
                        try {
                            // Two-tone beep: 800Hz then 1000Hz, 200ms each
                            // Uses Karoo's hardware buzzer, not Android audio
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

                // Reset beep tracking when leaving all trails
                if (match.distance > 100.0) {
                    lastBeepTrail = ""
                }

                // --- EMIT DATA POINT ---
                // Required by karoo-ext to keep the stream alive
                // Distance value can be consumed by other data fields if needed
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
        // 2000ms update interval, 10m minimum distance between updates
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

        // Cleanup when Karoo stops the stream (ride ends, field removed, etc.)
        emitter.setCancellable {
            Log.d("TrailNameDataType", "Stream cancelled, removing GPS listener")
            mainHandler.post {
                locationManager.removeUpdates(locationListener)
            }
        }
    }
}
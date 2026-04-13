package com.example.karootrailnames

// ============================================================
// Karoo Trail Names Extension - v1.5.2
// Real-time trail name display for Karoo K2/K3
// Built on karoo-ext 1.1.8 SDK
// GitHub: https://github.com/robrusk/Karoo-Trail-Names
// License: MIT
//
// DUAL GPS MODE:
//   K3: Uses Karoo's built-in LOCATION data stream via
//       addConsumer/callbackFlow. Works in background.
//   K2: Uses Android LocationManager. K2 runs older Android
//       where background location works without special perms.
//       K2 firmware stopped at 1.613 and doesn't have the
//       LOCATION data stream available.
//
// Device detection uses Build.DEVICE to determine K2 vs K3.
// ============================================================

import android.content.Context
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

// ============================================================
// EXTENSION SERVICE
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

    override val types: List<DataTypeImpl> by lazy {
        listOf(TrailNameDataType(extension, applicationContext))
    }
}

// ============================================================
// DATA TYPE
// Dual GPS mode: K3 uses Karoo LOCATION stream, K2 uses
// Android LocationManager. Both paths feed into the same
// trail matching and alert logic.
// ============================================================
class TrailNameDataType(
    extension: String,
    private val appContext: Context
) : DataTypeImpl(extension, "current-trail") {

    @Volatile
    private var currentTrailStatus: String = "No Trail"
    @Volatile
    private var currentTrailColor: Int = Color.GRAY
    @Volatile
    private var currentProximity: Int = 0
    @Volatile
    private var flashActive: Boolean = false

    private var lastBeepTrail: String = ""

    // ============================================================
    // DEVICE DETECTION
    // K2 runs Android 8.1 on Qualcomm hardware (device "karoo")
    // K3 runs newer Android (device "k24" or similar)
    // If we can't determine, default to K3 path (safer)
    // ============================================================
    private fun isK2(): Boolean {
        val device = Build.DEVICE?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        Log.d("TrailNameDataType", "Device: $device, Model: $model")
        return device == "karoo" || model.contains("karoo 2")
    }

    // ============================================================
    // HELPER: Stream Karoo's built-in LOCATION data type (K3 only)
    // ============================================================
    private fun KarooSystemService.streamLocationFlow(): Flow<StreamState> {
        return callbackFlow {
            val listenerId = addConsumer(OnStreamState.StartStreaming(DataType.Type.LOCATION)) { event: OnStreamState ->
                trySendBlocking(event.state)
            }
            awaitClose {
                removeConsumer(listenerId)
            }
        }
    }

    // ============================================================
    // VIEW RENDERING
    // ============================================================
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d("TrailNameDataType", "startView called")

        val views = RemoteViews(context.packageName, R.layout.trail_name_view)
        views.setTextViewText(R.id.trail_status, currentTrailStatus)
        views.setTextColor(R.id.trail_status, currentTrailColor)
        views.setProgressBar(R.id.proximity_bar, 100, currentProximity, false)
        views.setInt(R.id.trail_layout, "setBackgroundColor", Color.WHITE)
        emitter.updateView(views)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val updatedViews = RemoteViews(context.packageName, R.layout.trail_name_view)
                updatedViews.setTextViewText(R.id.trail_status, currentTrailStatus)
                updatedViews.setProgressBar(R.id.proximity_bar, 100, currentProximity, false)

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
    // ============================================================
    private fun difficultySymbol(difficulty: String?): String {
        return when (difficulty) {
            "0" -> "\u25CF "
            "1" -> "\u25A0 "
            "2", "3" -> "\u25C6 "
            "4", "5" -> "\u25C6\u25C6 "
            else -> ""
        }
    }

    private fun difficultyColor(difficulty: String?): Int {
        return when (difficulty) {
            "0" -> Color.parseColor("#228B22")
            "1" -> Color.parseColor("#1E90FF")
            "2", "3" -> Color.BLACK
            "4", "5" -> Color.RED
            else -> Color.BLACK
        }
    }

    // ============================================================
    // SHARED TRAIL MATCHING LOGIC
    // Called by both K2 and K3 GPS paths with lat/lon/bearing.
    // Handles trail matching, display update, beep, flash, and
    // data point emission.
    // ============================================================
    private fun processLocation(
        lat: Double,
        lng: Double,
        bearing: Float,
        matcher: TrailMatcher,
        trails: List<Trail>,
        prefs: android.content.SharedPreferences,
        karooSystem: KarooSystemService,
        karooConnected: Boolean,
        mainHandler: android.os.Handler,
        emitter: Emitter<StreamState>
    ) {
        val match = matcher.findCurrentTrail(
            currentLat = lat,
            currentLon = lng,
            trails = trails,
            bearing = bearing
        )

        val symbol = difficultySymbol(match.trail?.difficulty)
        currentTrailStatus = symbol + matcher.formatTrailStatus(match)
        currentTrailColor = difficultyColor(match.trail?.difficulty)

        currentProximity = if (match.distance < 200.0) {
            ((200.0 - match.distance) / 200.0 * 100).toInt()
        } else {
            0
        }

        Log.d("TrailNameDataType", "Status: $currentTrailStatus | Difficulty: ${match.trail?.difficulty} | Proximity: $currentProximity")

        val trailName = match.trail?.name ?: ""
        val beepEnabled = prefs.getBoolean("beep_enabled", true)
        if (match.distance < 50.0 && trailName.isNotEmpty() && trailName != lastBeepTrail && beepEnabled) {
            Log.d("TrailNameDataType", "BEEP! Arrived on: $trailName (karooConnected=$karooConnected)")
            lastBeepTrail = trailName

            flashActive = true
            mainHandler.postDelayed({ flashActive = false }, 2000)

            if (karooConnected) {
                try {
                    karooSystem.dispatch(
                        PlayBeepPattern(
                            listOf(
                                PlayBeepPattern.Tone(4000, 300),
                                PlayBeepPattern.Tone(0, 100),
                                PlayBeepPattern.Tone(5000, 300),
                                PlayBeepPattern.Tone(0, 100),
                                PlayBeepPattern.Tone(6000, 300)
                            )
                        )
                    )
                    Log.d("TrailNameDataType", "PlayBeepPattern dispatched successfully")
                } catch (e: Exception) {
                    Log.e("TrailNameDataType", "Beep failed: ${e.message}")
                }
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

    // ============================================================
    // DATA STREAM - MAIN ENTRY POINT
    // Detects K2 vs K3 and uses the appropriate GPS method.
    // ============================================================
    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d("TrailNameDataType", "STARTING STREAM")

        val karooSystem = KarooSystemService(appContext)
        var karooConnected = false
        karooSystem.connect { connected ->
            karooConnected = connected
            Log.d("TrailNameDataType", "BEEP KarooSystem connected: $connected")
        }

        val storage = TrailStorage(appContext)
        val matcher = TrailMatcher()
        val trails = storage.loadAllTrails()

        Log.d("TrailNameDataType", "EXTENSION LOADED ${trails.size} TRAILS")

        val prefs = appContext.getSharedPreferences("trail_names_prefs", Context.MODE_PRIVATE)

        if (trails.isEmpty()) {
            currentTrailStatus = "No Trails"
            currentTrailColor = Color.GRAY
            currentProximity = 0
            emitter.onNext(StreamState.Searching)
            return
        }

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        if (isK2()) {
            // ============================================================
            // K2 PATH: Android LocationManager
            // K2 runs Android 8.1 where background location works
            // without special permissions. K2 firmware is frozen at
            // 1.613 and doesn't have the LOCATION data stream.
            // ============================================================
            Log.d("TrailNameDataType", "K2 detected — using LocationManager")

            val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    processLocation(
                        lat = location.latitude,
                        lng = location.longitude,
                        bearing = location.bearing,
                        matcher = matcher,
                        trails = trails,
                        prefs = prefs,
                        karooSystem = karooSystem,
                        karooConnected = karooConnected,
                        mainHandler = mainHandler,
                        emitter = emitter
                    )
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            try {
                mainHandler.post {
                    try {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, 2000L, 10f, locationListener
                        )
                        Log.d("TrailNameDataType", "K2 GPS listener registered on main thread")
                    } catch (e: SecurityException) {
                        Log.e("TrailNameDataType", "K2 location permission denied", e)
                        emitter.onNext(StreamState.NotAvailable)
                    }
                }
                emitter.onNext(StreamState.Searching)
            } catch (e: Exception) {
                Log.e("TrailNameDataType", "K2 error starting GPS", e)
                emitter.onNext(StreamState.NotAvailable)
            }

            emitter.setCancellable {
                Log.d("TrailNameDataType", "K2 stream cancelled, cleaning up")
                karooSystem.disconnect()
                mainHandler.post {
                    locationManager.removeUpdates(locationListener)
                }
            }

        } else {
            // ============================================================
            // K3 PATH: Karoo LOCATION data stream
            // Uses addConsumer/callbackFlow to subscribe to Karoo's
            // built-in LOCATION data type. Works in background because
            // Karoo OS manages GPS at the system level.
            // THIS CODE IS IDENTICAL TO THE WORKING v1.5.2 K3 CODE.
            // ============================================================
            Log.d("TrailNameDataType", "K3 detected — using Karoo LOCATION stream")

            val job = CoroutineScope(Dispatchers.IO).launch {
                karooSystem.streamLocationFlow()
                    .mapNotNull { it as? StreamState.Streaming }
                    .mapNotNull { streamState ->
                        val lat = streamState.dataPoint.values[DataType.Field.LOC_LATITUDE]
                        val lng = streamState.dataPoint.values[DataType.Field.LOC_LONGITUDE]
                        val bearingVal = streamState.dataPoint.values[DataType.Field.LOC_BEARING]

                        if (lat != null && lng != null) {
                            Triple(lat, lng, bearingVal ?: 0.0)
                        } else {
                            null
                        }
                    }
                    .collect { (lat, lng, bearing) ->
                        processLocation(
                            lat = lat,
                            lng = lng,
                            bearing = bearing.toFloat(),
                            matcher = matcher,
                            trails = trails,
                            prefs = prefs,
                            karooSystem = karooSystem,
                            karooConnected = karooConnected,
                            mainHandler = mainHandler,
                            emitter = emitter
                        )
                    }
            }

            emitter.onNext(StreamState.Searching)

            emitter.setCancellable {
                Log.d("TrailNameDataType", "K3 stream cancelled, cleaning up")
                job.cancel()
                karooSystem.disconnect()
            }
        }
    }
}
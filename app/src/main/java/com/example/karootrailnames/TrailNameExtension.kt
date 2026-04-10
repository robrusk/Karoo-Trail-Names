package com.example.karootrailnames

// ============================================================
// Karoo Trail Names Extension - v1.5
// Real-time trail name display for Karoo K2/K3
// Built on karoo-ext 1.1.8 SDK
// GitHub: https://github.com/robrusk/Karoo-Trail-Names
// License: MIT
//
// IMPORTANT: Uses Karoo's built-in LOCATION data stream instead
// of Android LocationManager. This ensures background GPS works
// on both K2 and K3 without needing ACCESS_BACKGROUND_LOCATION.
// Karoo OS manages location at the system level — we just
// subscribe to the LOCATION data type like any other sensor.
//
// Pattern borrowed from karoo-headwind by timklge (MIT license).
// ============================================================

import android.content.Context
import android.graphics.Color
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

    override val types: List<DataTypeImpl> by lazy {
        listOf(TrailNameDataType(extension, applicationContext))
    }
}

// ============================================================
// DATA TYPE
// This is the core of the extension. It does three things:
//   1. startStream() - Karoo LOCATION stream for trail matching
//   2. startView()   - Renders trail info on the Karoo data field
//   3. Alerts        - Hardware buzzer beep + screen flash on arrival
//
// GPS is provided by subscribing to Karoo's built-in LOCATION
// data type via streamDataFlow(DataType.Type.LOCATION). This
// streams lat/lon/bearing from Karoo's own GPS system — no
// Android LocationManager needed, no background permissions
// required. Works on both K2 and K3.
//
// KarooSystemService is created inside startStream() for both
// location consumption and beep dispatch.
// ============================================================
class TrailNameDataType(
    extension: String,
    private val appContext: Context
) : DataTypeImpl(extension, "current-trail") {

    // --- Shared state between startStream() and startView() ---
    @Volatile
    private var currentTrailStatus: String = "No Trail"
    @Volatile
    private var currentTrailColor: Int = Color.GRAY
    @Volatile
    private var currentProximity: Int = 0

    // Flash alert
    @Volatile
    private var flashActive: Boolean = false

    // Beep tracking
    private var lastBeepTrail: String = ""

    // ============================================================
    // HELPER: Stream Karoo's built-in LOCATION data type
    // Uses addConsumer with callbackFlow pattern from karoo-headwind.
    // Returns a Flow of StreamState that we filter for GPS data.
    // Works in background on both K2 and K3.
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
    // DATA STREAM
    // Subscribes to Karoo's built-in LOCATION data type to get
    // GPS coordinates. This is the same GPS stream that powers
    // Karoo's native speed, distance, and map features.
    //
    // Pattern from karoo-headwind (timklge, MIT license):
    //   streamDataFlow(DataType.Type.LOCATION) returns a Flow
    //   of StreamState. We filter for Streaming states and
    //   extract lat/lon/bearing from the DataPoint values.
    //
    // Works on both K2 and K3 because we're consuming Karoo's
    // own data stream, not requesting Android GPS directly.
    // ============================================================
    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d("TrailNameDataType", "STARTING STREAM")

        // --- KAROO SYSTEM CONNECTION ---
        val karooSystem = KarooSystemService(appContext)
        var karooConnected = false
        karooSystem.connect { connected ->
            karooConnected = connected
            Log.d("TrailNameDataType", "BEEP KarooSystem connected: $connected")
        }

        // Load trails from local cache
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

        // --- KAROO LOCATION STREAM ---
        // Subscribe to Karoo's LOCATION data type for GPS updates.
        // This replaces Android LocationManager and works in the
        // background on both K2 and K3.
        val job = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.streamLocationFlow()
                .mapNotNull { it as? StreamState.Streaming }
                .mapNotNull { streamState ->
                    val lat = streamState.dataPoint.values[DataType.Field.LOC_LATITUDE]
                    val lng = streamState.dataPoint.values[DataType.Field.LOC_LONGITUDE]
                    val bearing = streamState.dataPoint.values[DataType.Field.LOC_BEARING]

                    if (lat != null && lng != null) {
                        Triple(lat, lng, bearing ?: 0.0)
                    } else {
                        null
                    }
                }
                .collect { (lat, lng, bearing) ->

                    // --- TRAIL MATCHING ---
                    val match = matcher.findCurrentTrail(
                        currentLat = lat,
                        currentLon = lng,
                        trails = trails,
                        bearing = bearing.toFloat()
                    )

                    // --- FORMAT DISPLAY ---
                    val symbol = difficultySymbol(match.trail?.difficulty)
                    currentTrailStatus = symbol + matcher.formatTrailStatus(match)
                    currentTrailColor = difficultyColor(match.trail?.difficulty)

                    // --- PROXIMITY BAR ---
                    currentProximity = if (match.distance < 200.0) {
                        ((200.0 - match.distance) / 200.0 * 100).toInt()
                    } else {
                        0
                    }

                    Log.d("TrailNameDataType", "Status: $currentTrailStatus | Difficulty: ${match.trail?.difficulty} | Proximity: $currentProximity")

                    // --- BEEP + FLASH ALERT ---
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
                                            PlayBeepPattern.Tone(500, 300)
                                        )
                                    )
                                )
                                Log.d("TrailNameDataType", "PlayBeepPattern dispatched successfully")
                            } catch (e: Exception) {
                                Log.e("TrailNameDataType", "Beep failed: ${e.message}")
                            }
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
        }

        emitter.onNext(StreamState.Searching)

        // Cleanup when Karoo stops the stream
        emitter.setCancellable {
            Log.d("TrailNameDataType", "Stream cancelled, cleaning up")
            job.cancel()
            karooSystem.disconnect()
        }
    }
}
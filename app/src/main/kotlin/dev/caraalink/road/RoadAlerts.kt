package dev.caraalink.road

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.caraalink.R
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** What the car-screen overlay shows. */
data class RoadState(
    val speedKmh: Int? = null,
    /** Limit in force: the road's own when known, else the driver's manual one; null = none. */
    val limit: Int? = null,
    /** The road's signed limit from OpenStreetMap. */
    val limitFromRoad: Boolean = false,
    /** Not signed on OpenStreetMap: the announced limit of this named road, or the legal default for its kind. */
    val limitEstimated: Boolean = false,
    /** Set by the driver by voice for this road. */
    val limitFromUser: Boolean = false,
    /** A road is matched (so a spoken limit can be saved for it). */
    val onRoad: Boolean = false,
    /** Outside temperature (Open-Meteo) and the city the car is in (phone geocoder). */
    val tempC: Int? = null,
    val city: String? = null,
    val over: Boolean = false,
    /** Well over: 20 km/h or 20 % above the limit, whichever is more. */
    val wayOver: Boolean = false,
    /** Metres to the next camera on our line, when one is within 1 km. */
    val cameraM: Int? = null,
    val cameraLimit: Int? = null,
    /** Metres to the next speed bump on our line, while it is close enough to warn about. */
    val bumpM: Int? = null,
    /** Summary of the drive just finished, shown while parked. */
    val trip: String? = null,
    val muted: Boolean = false,
    /** Short confirmation shown for a few seconds ("Camera saved here"). */
    val note: String? = null,
)

/**
 * Speed warnings and speed-camera alerts while Android Auto is on the car
 * screen. Uses the phone's GPS, road limits and cameras from OpenStreetMap
 * (Overpass API), and speaks through the phone, which reaches the car over
 * Bluetooth like the rest of Android Auto's audio. Ported from Saga OBD's
 * road_service.dart / alerts.dart.
 *
 * All road state lives on one background thread ("road-alerts"); listeners
 * are called on that thread and must post to their own.
 */
object RoadAlerts {
    private const val UA = "AABridge-RoadAlerts/1.0 (+https://github.com/em07amed/AA-Bridge)"
    /** Public Overpass servers; each tile asks all of them at once and keeps the first answer. */
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    private const val FIXES = "road_alerts"

    private val listeners = CopyOnWriteArraySet<(RoadState) -> Unit>()

    @Volatile
    var state = RoadState()
        private set

    /**
     * true = parked (GPS speed under 3 km/h for 30 s), false = moving (over 8 km/h),
     * null = not known yet. Read by the Android Auto session to report the parking
     * brake / gear, so Android Auto can allow parked-only features. A lost fix keeps
     * the last state, so a tunnel never turns into "parked".
     */
    @Volatile
    var parked: Boolean? = null
        private set
    private var stillSince = 0L

    fun addListener(l: (RoadState) -> Unit) {
        listeners += l
        l(state)
    }

    fun removeListener(l: (RoadState) -> Unit) {
        listeners -= l
    }

    private var users = 0
    private var app: Context? = null
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    private val net = Executors.newSingleThreadExecutor()
    /** Three tiles load at once (a drive log showed ~45 s per tile one at a time). */
    private val tilePool = Executors.newFixedThreadPool(3)
    private val race = Executors.newCachedThreadPool()
    private var logger: DiagnosticLogger? = null

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var focus: AudioFocusRequest? = null

    // ---- road-alerts thread only ----
    private var roads: List<Road> = emptyList()
    private var osmCams: List<SpeedCamera> = emptyList()
    private var myCams = mutableListOf<SpeedCamera>()
    private var hidden = mutableSetOf<Long>()
    private var cameras: List<SpeedCamera> = emptyList()
    private var bumps: List<SpeedCamera> = emptyList()

    private var last: LatLon? = null
    private var lastFixAt = 0L
    private var speedKmh: Double? = null
    private var heading = Double.NaN

    private var roadId: Long? = null
    private var roadLimit: Int? = null
    private var roadEstimate: Int? = null
    private var road: Road? = null

    /** Limits the driver set by voice: per OSM way, and for named roads along the same name nearby. */
    private class UserLimit(val wayId: Long, val name: String?, val at: LatLon, val limit: Int)
    private var userLimits = mutableListOf<UserLimit>()
    private var candidate: Long? = null
    private var roadSeen = 0L

    private var next: CameraAhead? = null
    private var nextBump: CameraAhead? = null
    private var trip: Trip? = null
    private var tripCard: String? = null
    /** Road and limit of the last lower-limit-ahead warning, so it is said once. */
    private var aheadSaid: Pair<Long, Int>? = null
    private var speeding = false
    private var lastNag = 0L
    private var muted = false
    private var note: String? = null
    private var noteUntil = 0L

    // ---------- lifecycle ----------

    @Synchronized
    fun start(context: Context) {
        users++
        if (users > 1) return
        val ctx = context.applicationContext
        app = ctx
        logger = DiagnosticLogger(ctx, "road-alerts")
        val t = HandlerThread("road-alerts").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post {
            pending.clear() // a load cut off by the last stop() never cleared its key
            retryAt.clear()
            loadFixes()
            startGps()
            tick()
        }
        tts = TextToSpeech(ctx) { status -> h.post { onTtsInit(status) } }
        net.execute {
            pruneTileCache(ctx)
            if (RoadPackManager.update(ctx, force = false)) h.post { roadTiles.clear(); camAreas.clear() }
        }
        logger?.log("started")
    }

    @Synchronized
    fun stop() {
        if (users == 0) return
        users--
        if (users > 0) return
        parked = null
        stillSince = 0L
        handler?.post {
            finishTrip()
            stopGps()
            speeding = false
            next = null
            speedKmh = null
            publish()
        }
        thread?.quitSafely()
        thread = null
        handler = null
        tts?.shutdown()
        tts = null
        ttsReady = false
        runCatching { tone?.release() }
        tone = null
        logger?.log("stopped")
    }

    /** Car-screen actions (long-press / tap on the overlay, or said after tapping the sign). */
    fun addCameraHere(limit: Int? = null) {
        handler?.post {
            val at = last ?: return@post
            myCams += SpeedCamera(-System.currentTimeMillis(), at, limit, heading.takeUnless { it.isNaN() })
            saveFixes()
            mergeCams()
            showNote(R.string.road_note_camera_added)
        }
    }

    fun addBumpHere() {
        handler?.post {
            val at = last ?: return@post
            myCams += SpeedCamera(-System.currentTimeMillis(), at, null, null, bump = true)
            saveFixes()
            mergeCams()
            showNote(R.string.road_note_bump_added)
        }
    }

    fun removeNextCamera() {
        handler?.post {
            val c = next?.camera ?: return@post
            if (c.mine) {
                myCams.removeAll { it.id == c.id }
            } else {
                hidden += c.id
            }
            saveFixes()
            mergeCams()
            next = null
            showNote(R.string.road_note_camera_removed)
        }
    }

    /** From the voice prompt: [limit] for the road under the car, or null to forget the driver's limit there. */
    fun setUserLimit(limit: Int?) {
        handler?.post {
            val r = road
            val ctx = app ?: return@post
            if (r == null) {
                say(vs(R.string.road_voice_no_road))
                showNote(R.string.road_voice_no_road)
                return@post
            }
            userLimits.removeAll { it.wayId == r.id || (r.name != null && it.name == r.name && RoadMath.distanceM(it.at, r.pts.first()) < 30_000) }
            if (limit != null) userLimits += UserLimit(r.id, r.name, last ?: r.pts.first(), limit)
            saveFixes()
            speeding = false
            if (limit != null) {
                say(vs(R.string.road_voice_saved, limit))
                note = ctx.getString(R.string.road_voice_saved, limit)
            } else {
                say(vs(R.string.road_voice_cleared))
                note = ctx.getString(R.string.road_voice_cleared)
            }
            noteUntil = System.currentTimeMillis() + 3_000
            publish()
            handler?.postDelayed({ publish() }, 3_100)
        }
    }

    /** Shown while the voice prompt listens or when it didn't catch a number. */
    fun showVoiceNote(@StringRes res: Int) {
        handler?.post { showNote(res) }
    }

    fun toggleMute() {
        handler?.post {
            muted = !muted
            if (muted) runCatching { tts?.stop() }
            showNote(if (muted) R.string.road_note_muted else R.string.road_note_unmuted)
        }
    }

    // ---------- GPS ----------

    /** All four methods implemented: on Android 10 they are not default methods yet. */
    private val gps = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun startGps() {
        val ctx = app ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            logger?.log("no location permission -- alerts off")
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val looper = handler?.looper ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gps, looper)
        } catch (e: Exception) {
            logger?.log("GPS start failed: $e")
        }
    }

    private fun stopGps() {
        runCatching { app?.getSystemService(LocationManager::class.java)?.removeUpdates(gps) }
    }

    private fun onFix(loc: Location) {
        val ctx = app ?: return
        val at = LatLon(loc.latitude, loc.longitude)
        last = at
        lastFixAt = System.currentTimeMillis()
        speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else null
        updateParked(speedKmh)
        if (loc.hasBearing() && loc.speed > 2f) heading = loc.bearing.toDouble()
        trackTrip(at)

        ensureTiles(at)
        refreshPlace(at)

        matchRoad(at)
        val limit = currentLimit(ctx).limit
        checkSpeed(ctx, limit)
        checkLimitAhead(ctx, at, limit)
        checkCameras(ctx, at, limit)
        checkBumps(ctx, at)
        publish()
    }

    private fun updateParked(kmh: Double?) {
        if (kmh == null) return
        val now = System.currentTimeMillis()
        if (kmh < 3) {
            if (stillSince == 0L) stillSince = now
            if (now - stillSince >= 30_000 && parked != true) {
                parked = true
                logger?.log("parked")
            }
        } else {
            stillSince = 0L
            if (kmh > 8 && parked != false) {
                parked = false
                logger?.log("moving")
            }
        }
    }

    /** Republishes every 2 s so a lost GPS fix clears the speed on screen. */
    private fun tick() {
        val h = handler ?: return
        if (System.currentTimeMillis() - lastFixAt > 5_000 && speedKmh != null) {
            speedKmh = null
            speeding = false
        }
        publish()
        h.postDelayed({ tick() }, 2_000)
    }

    // ---------- limits ----------

    private class Limit(val limit: Int?, val fromRoad: Boolean, val estimated: Boolean, val fromUser: Boolean = false)

    /** The driver's own limit for [r]: this way, or a way of the same name within 30 km of where it was set. */
    private fun userLimitFor(r: Road): Int? {
        userLimits.lastOrNull { it.wayId == r.id }?.let { return it.limit }
        val n = r.name ?: return null
        return userLimits.lastOrNull { it.name == n && RoadMath.distanceM(it.at, r.pts.first()) < 30_000 }?.limit
    }

    /**
     * The road's signed limit, else the legal default for its kind of road,
     * else the driver's manual limit (null = off).
     */
    private fun currentLimit(ctx: Context): Limit {
        road?.let { r -> userLimitFor(r)?.let { return Limit(it, fromRoad = false, estimated = false, fromUser = true) } }
        if (AppSettings.roadUseOsmLimit(ctx)) {
            roadLimit?.let { return Limit(it, fromRoad = true, estimated = false) }
            roadEstimate?.let { return Limit(it, fromRoad = false, estimated = true) }
        }
        return Limit(AppSettings.roadManualLimit(ctx), fromRoad = false, estimated = false)
    }

    /** Only switches road after the new one wins twice, so a bridge or a side street can't flicker it. */
    private fun matchRoad(at: LatLon) {
        val m = RoadMath.matchRoad(roads, at, heading)
        val now = System.currentTimeMillis()
        if (m != null) {
            val current = roadId
            if (current != null && m.road.id != current && candidate != m.road.id) {
                candidate = m.road.id
                return
            }
            candidate = null
            roadId = m.road.id
            roadLimit = m.limit
            roadEstimate = m.road.estimate
            road = m.road
            roadSeen = now
        } else if (now - roadSeen > 15_000) {
            roadId = null
            roadLimit = null
            roadEstimate = null
            road = null
        }
    }

    private fun checkSpeed(ctx: Context, limit: Int?) {
        val kmh = speedKmh
        if (!AppSettings.roadSpeedAlerts(ctx) || kmh == null || limit == null) {
            speeding = false
            return
        }
        val over = limit + AppSettings.roadTolerance(ctx)
        val now = System.currentTimeMillis()
        if (kmh > over) {
            if (!speeding) {
                speeding = true
                trip?.overLimit()
                lastNag = now + 3_000 // let the sentence finish
                say(vs(R.string.road_say_speeding, kmh.roundToInt(), limit))
            } else if (now - lastNag >= 8_000) {
                lastNag = now
                beep()
            }
        } else if (kmh < over - 3) {
            speeding = false
        }
    }

    // ---------- cameras ----------

    private fun checkCameras(ctx: Context, at: LatLon, limit: Int?) {
        if (!AppSettings.roadCameraAlerts(ctx)) {
            next = null
            return
        }
        val kmh = speedKmh ?: 0.0
        val moving = kmh > 10 && !heading.isNaN()
        val hit = RoadMath.cameraAhead(cameras, at, heading, moving, roads)
        next = hit
        if (hit == null || !moving) return
        val c = hit.camera
        val camLimit = c.limit ?: limit
        if (!c.farSaid && hit.meters <= 600) {
            c.farSaid = true
            trip?.camera()
            val m = (hit.meters / 50).roundToInt() * 50
            say(
                if (camLimit != null) vs(R.string.road_say_camera_far, m, camLimit)
                else vs(R.string.road_say_camera_far_nolimit, m),
            )
        } else if (!c.nearSaid && hit.meters <= 200 && camLimit != null && kmh > camLimit) {
            c.nearSaid = true
            say(vs(R.string.road_say_camera_near, camLimit))
        }
    }

    // ---------- lower limit ahead ----------

    /**
     * Follows our road about 400 m ahead; where the next road has a lower
     * limit and we are faster than it, says so once ("الحد هيبقى 60 بعد 300 متر").
     */
    private fun checkLimitAhead(ctx: Context, at: LatLon, limit: Int?) {
        val r = road
        val kmh = speedKmh ?: return
        if (!AppSettings.roadLimitAheadAlerts(ctx) || r == null || limit == null || kmh < 25 || heading.isNaN()) return
        for ((p, dir) in RoadMath.pointsAhead(r, at, heading, listOf(100.0, 200.0, 300.0, 400.0))) {
            val m = RoadMath.matchRoad(roads, p, dir) ?: continue
            if (m.road.id == r.id) continue
            val next = userLimitFor(m.road)
                ?: (if (AppSettings.roadUseOsmLimit(ctx)) m.limit ?: m.road.estimate else null)
                ?: return
            if (next >= limit || kmh <= next + AppSettings.roadTolerance(ctx)) return
            val key = m.road.id to next
            if (key == aheadSaid) return
            aheadSaid = key
            val meters = ((RoadMath.distanceM(at, p) / 50).roundToInt() * 50).coerceAtLeast(50)
            say(vs(R.string.road_say_limit_ahead, next, meters))
            showNoteText(ctx.getString(R.string.road_note_limit_ahead, next, meters), 6_000)
            return
        }
    }

    // ---------- trip summary and parking spot ----------

    private fun trackTrip(at: LatLon) {
        val kmh = speedKmh ?: return
        val now = System.currentTimeMillis()
        if (trip == null && kmh > 8) {
            trip = Trip(now)
            tripCard = null
        }
        if (kmh >= 2) trip?.add(at, kmh, now)
        // Parked for two minutes, not just a long red light.
        if (parked == true && stillSince != 0L && now - stillSince >= 120_000) finishTrip()
    }

    /** Saves where the car stopped and shows / notifies the summary. Also runs when the car is switched off. */
    private fun finishTrip() {
        val t = trip ?: return
        trip = null
        val ctx = app ?: return
        val at = last
        if (at != null) TripNotifier.saveParking(ctx, at, System.currentTimeMillis())
        logger?.log("trip done: ${t.meters.roundToInt()} m, ${t.minutes} min, max ${t.maxKmh.roundToInt()}, over ${t.overCount}")
        if (!t.worthSummary || !AppSettings.roadTripSummary(ctx)) return
        val text = ctx.getString(
            R.string.road_trip_summary, Trip.km(t.meters), Trip.duration(t.minutes), t.maxKmh.roundToInt(), t.overCount,
        )
        tripCard = text
        runCatching { TripNotifier.notify(ctx, text, at) }.onFailure { logger?.log("trip notification failed: $it") }
        publish()
    }

    // ---------- speed bumps ----------

    private fun checkBumps(ctx: Context, at: LatLon) {
        val kmh = speedKmh ?: 0.0
        val moving = kmh > 15 && !heading.isNaN()
        if (!AppSettings.roadBumpAlerts(ctx) || !moving) {
            nextBump = null
            return
        }
        // About 7 seconds ahead, at least 60 m and at most 200 m.
        val warnM = (kmh / 3.6 * 7).coerceIn(60.0, 200.0)
        val hit = RoadMath.cameraAhead(bumps, at, heading, true, roads)?.takeIf { it.meters <= warnM }
        nextBump = hit
        val b = hit?.camera ?: return
        if (b.farSaid) return
        // Bumps often come in rows: one warning for those just after it.
        for (o in bumps) if (RoadMath.distanceM(o.at, b.at) <= 80) o.farSaid = true
        val m = ((hit.meters / 10).roundToInt() * 10).coerceAtLeast(10)
        say(vs(R.string.road_say_bump, m))
    }

    private fun mergeCams() {
        val old = (cameras + bumps).associateBy { it.id }
        val all = (osmCams + myCams).filter { it.id !in hidden }.map { old[it.id] ?: it }
        cameras = all.filter { !it.bump }
        bumps = all.filter { it.bump }
    }

    private fun loadFixes() {
        val p = app?.getSharedPreferences(FIXES, Context.MODE_PRIVATE) ?: return
        try {
            val mine = JSONArray(p.getString("my_cameras", "[]"))
            myCams = (0 until mine.length()).map { i ->
                val o = mine.getJSONObject(i)
                SpeedCamera(
                    o.getLong("id"),
                    LatLon(o.getDouble("lat"), o.getDouble("lon")),
                    if (o.has("limit")) o.getInt("limit") else null,
                    if (o.has("heading")) o.getDouble("heading") else null,
                    bump = o.optBoolean("bump", false),
                )
            }.toMutableList()
            val h = JSONArray(p.getString("hidden_cameras", "[]"))
            hidden = (0 until h.length()).map { h.getLong(it) }.toMutableSet()
            val u = JSONArray(p.getString("user_limits", "[]"))
            userLimits = (0 until u.length()).map { i ->
                val o = u.getJSONObject(i)
                UserLimit(
                    o.getLong("way"),
                    if (o.has("name")) o.getString("name") else null,
                    LatLon(o.getDouble("lat"), o.getDouble("lon")),
                    o.getInt("limit"),
                )
            }.toMutableList()
        } catch (e: Exception) {
            logger?.log("fixes unreadable: $e")
        }
        mergeCams()
    }

    private fun saveFixes() {
        val p = app?.getSharedPreferences(FIXES, Context.MODE_PRIVATE) ?: return
        val mine = JSONArray()
        for (c in myCams) {
            val o = JSONObject().put("id", c.id).put("lat", c.at.lat).put("lon", c.at.lon)
            c.limit?.let { o.put("limit", it) }
            c.heading?.let { o.put("heading", it) }
            if (c.bump) o.put("bump", true)
            mine.put(o)
        }
        val u = JSONArray()
        for (l in userLimits) {
            val o = JSONObject().put("way", l.wayId).put("lat", l.at.lat).put("lon", l.at.lon).put("limit", l.limit)
            l.name?.let { o.put("name", it) }
            u.put(o)
        }
        p.edit()
            .putString("user_limits", u.toString())
            .putString("my_cameras", mine.toString())
            .putString("hidden_cameras", JSONArray(hidden.toList()).toString())
            .apply()
    }

    // ---------- temperature and city ----------
    //
    // Every 10 minutes, or after 5 km: the current temperature from Open-Meteo
    // (free, no key) and the city name from the phone's geocoder, in the alert
    // language. Both off the network thread; failures just keep the last values.

    private var tempC: Int? = null
    private var city: String? = null
    private var placeAt: LatLon? = null
    private var placeTime = 0L
    private var placeLoading = false

    private fun refreshPlace(at: LatLon) {
        val ctx = app ?: return
        if (!AppSettings.roadShowWeather(ctx) || placeLoading) return
        val now = System.currentTimeMillis()
        val p = placeAt
        if (p != null && now - placeTime < 10 * 60_000 && RoadMath.distanceM(p, at) < 5_000) return
        val h = handler ?: return
        placeLoading = true
        placeAt = at
        placeTime = now
        val lang = voiceCtx?.resources?.configuration?.locales?.get(0) ?: ctx.resources.configuration.locales[0]
        net.execute {
            val t = runCatching {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m"
                    .format(Locale.US, at.lat, at.lon)
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", UA)
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                JSONObject(body).getJSONObject("current").getDouble("temperature_2m").roundToInt()
            }.onFailure { logger?.log("weather failed: $it") }.getOrNull()
            val name = runCatching {
                @Suppress("DEPRECATION")
                android.location.Geocoder(ctx, lang).getFromLocation(at.lat, at.lon, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.onFailure { logger?.log("geocoder failed: $it") }.getOrNull()
            h.post {
                placeLoading = false
                if (t != null) tempC = t
                if (name != null) city = name
                logger?.log("place: ${t ?: "-"}° ${name ?: "-"}")
                publish()
            }
        }
    }

    // ---------- OpenStreetMap tiles ----------
    //
    // Roads come in ~550 m tiles (3x3 around the car), cameras in one ~16 km
    // square around the car. Each answer is cached on disk, so a road driven
    // before works offline and Overpass sees few, small requests. One 1 km query
    // in central Cairo was big enough to time out, which left no road matched
    // and the limit falling back to the manual one.

    /** A road tile (row/col in 0.005 deg), or a camera area (rows/cols in 0.05 deg, r0..r1 x c0..c1). */
    private class Tile(
        val key: String, val query: String, val maxAgeMs: Long, val roads: Boolean,
        val r0: Int, val c0: Int, val r1: Int = r0, val c1: Int = c0, val center: LatLon,
    )

    private val roadTiles = LinkedHashMap<String, List<Road>>(32, 0.75f, true)
    private val camAreas = LinkedHashMap<String, List<SpeedCamera>>(8, 0.75f, true)
    private val pending = mutableSetOf<String>()
    private val retryAt = mutableMapOf<String, Long>()
    @Volatile private var busyUntil = 0L

    /** Drops cached tiles older than 30 days, then the oldest until the cache is under 50 MB. */
    private fun pruneTileCache(ctx: Context) {
        val files = File(ctx.cacheDir, "road-tiles").listFiles()?.toMutableList() ?: return
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600_000
        files.filter { it.lastModified() < cutoff }.forEach { it.delete(); files.remove(it) }
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= 50L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    private fun wantedTiles(at: LatLon): List<Tile> {
        val out = ArrayList<Tile>()
        val (ry, rx) = RoadMath.tileOf(at, RoadMath.ROAD_TILE)
        // The car's own tile first, then its neighbours.
        // The car's tile, then the tiles ahead in the direction of travel (two deep),
        // then the rest of the 3x3 -- so data is there before the car is.
        val around = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1)
        val order = if (heading.isNaN()) around else {
            val ay = Math.round(Math.cos(Math.toRadians(heading))).toInt()
            val ax = Math.round(Math.sin(Math.toRadians(heading))).toInt()
            (listOf(0 to 0, ay to ax, 2 * ay to 2 * ax) + around).distinct()
        }
        for ((dy, dx) in order) {
            val (s, w, n, e) = RoadMath.tileBox(ry + dy, rx + dx, RoadMath.ROAD_TILE)
            out += Tile(
                "r_${ry + dy}_${rx + dx}", RoadMath.roadsQuery(s, w, n, e), 14L * 24 * 3600_000, roads = true,
                r0 = ry + dy, c0 = rx + dx, center = LatLon((s + n) / 2, (w + e) / 2),
            )
        }
        val (cy, cx) = RoadMath.tileOf(at, RoadMath.CAMERA_TILE)
        val (s, w) = RoadMath.tileBox(cy - 1, cx - 1, RoadMath.CAMERA_TILE)
        val (_, _, n, e) = RoadMath.tileBox(cy + 1, cx + 1, RoadMath.CAMERA_TILE)
        out += Tile(
            "cb_${cy}_$cx", RoadMath.camerasQuery(s, w, n, e), 7L * 24 * 3600_000, roads = false,
            r0 = cy - 1, c0 = cx - 1, r1 = cy + 1, c1 = cx + 1, center = at,
        )
        return out
    }

    private fun ensureTiles(at: LatLon) {
        val now = System.currentTimeMillis()
        for (t in wantedTiles(at)) {
            val have = if (t.roads) roadTiles.containsKey(t.key) else camAreas.containsKey(t.key)
            if (have || t.key in pending || now < (retryAt[t.key] ?: 0L)) continue
            pending += t.key
            tilePool.execute { loadTile(t) }
        }
    }

    /** Network thread: disk cache, else Overpass (then cached). */
    private fun loadTile(t: Tile) {
        val started = System.currentTimeMillis()
        // The offline pack answers at once and is authoritative inside its area.
        val ctx = app
        if (ctx != null && RoadPackManager.covers(ctx, t.center)) {
            val fromPack = runCatching {
                if (t.roads) RoadPackManager.roads(ctx, t.r0, t.c0)?.let { it to emptyList<SpeedCamera>() }
                else RoadPackManager.cameras(ctx, t.r0, t.c0, t.r1, t.c1)?.let { emptyList<Road>() to it }
            }.onFailure { logger?.log("pack read failed for ${t.key}: $it") }.getOrNull()
            if (fromPack != null) {
                handler?.post { applyTile(t, fromPack, "pack", System.currentTimeMillis() - started) }
                return
            }
        }
        val file = app?.let { File(File(it.cacheDir, "road-tiles"), "${t.key}.json") }
        var body: String? = null
        var fromCache = false
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < t.maxAgeMs) {
            body = runCatching { file.readText() }.getOrNull()
            fromCache = body != null
        }
        var error: Exception? = null
        if (body == null) {
            val wait = busyUntil - System.currentTimeMillis()
            if (wait > 0) runCatching { Thread.sleep(wait) }
            try {
                val got = download(t.query)
                body = got
                if (file != null) runCatching { file.parentFile?.mkdirs(); file.writeText(got) }
            } catch (e: Exception) {
                error = e
            }
        }
        val parsed = body?.let { runCatching { parse(it) }.getOrNull() }
        handler?.post {
            pending -= t.key
            if (parsed == null) {
                retryAt[t.key] = System.currentTimeMillis() + 30_000
                logger?.log("tile ${t.key} failed: ${error ?: "unreadable"}")
                return@post
            }
            applyTile(t, parsed, if (fromCache) "cache" else "net", System.currentTimeMillis() - started)
        }
    }

    /** Road-alerts thread: stores a loaded tile and rebuilds the road and camera lists. */
    private fun applyTile(t: Tile, data: Pair<List<Road>, List<SpeedCamera>>, source: String, ms: Long) {
        pending -= t.key
        retryAt -= t.key
        if (t.roads) {
            roadTiles[t.key] = data.first
            while (roadTiles.size > 30) roadTiles.remove(roadTiles.keys.first())
        } else {
            camAreas[t.key] = data.second
            while (camAreas.size > 4) camAreas.remove(camAreas.keys.first())
        }
        // Overpass returns whole ways (the same way from several tiles); the pack
        // returns per-tile pieces of a way, which must all stay.
        roads = roadTiles.values.flatten().distinctBy { it.id to it.pts.first() }
        osmCams = camAreas.values.flatten().distinctBy { it.id }
        mergeCams()
        logger?.log("tile ${t.key} ($source): ${data.first.size} roads, ${data.second.size} cameras in $ms ms")
    }

    /** Asks every endpoint at once and returns the first good answer. */
    private fun download(query: String): String {
        val cs = ExecutorCompletionService<String>(race)
        val futures = ENDPOINTS.map { ep -> cs.submit(Callable { fetchFrom(ep, query) }) }
        var error: Exception? = null
        try {
            repeat(futures.size) {
                try {
                    return cs.take().get()
                } catch (e: ExecutionException) {
                    error = (e.cause as? Exception) ?: e
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        throw error ?: IOException("no endpoint")
    }

    private fun fetchFrom(endpoint: String, query: String): String {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 25_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = c.responseCode
            if (code == 429) busyUntil = System.currentTimeMillis() + 30_000
            if (code != 200) throw IOException("HTTP $code from $endpoint")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            // Overpass reports a timeout as HTTP 200 with partial data and a remark.
            if (body.contains("\"remark\"") && body.contains("runtime error")) throw IOException("Overpass timeout at $endpoint")
            return body
        } finally {
            c.disconnect()
        }
    }

    private fun parse(body: String): Pair<List<Road>, List<SpeedCamera>> {
        val els = JSONObject(body).getJSONArray("elements")
        val roads = ArrayList<Road>()
        val cams = ArrayList<SpeedCamera>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            fun tag(k: String): String? = if (tags.has(k)) tags.optString(k) else null
            when (e.optString("type")) {
                "node" -> cams += if (tag("highway") == "speed_camera") SpeedCamera(
                    e.getLong("id"),
                    LatLon(e.getDouble("lat"), e.getDouble("lon")),
                    RoadMath.parseMaxspeed(tag("maxspeed")),
                    RoadMath.parseDirection(tag("direction") ?: tag("camera:direction")),
                ) else if (tag("traffic_calming") in RoadMath.BUMPS) SpeedCamera(
                    e.getLong("id"), LatLon(e.getDouble("lat"), e.getDouble("lon")), null, null, bump = true,
                ) else continue
                "way" -> {
                    val g = e.optJSONArray("geometry") ?: continue
                    val pts = (0 until g.length()).mapNotNull { j ->
                        g.optJSONObject(j)?.let { LatLon(it.getDouble("lat"), it.getDouble("lon")) }
                    }
                    if (pts.size < 2) continue
                    val all = RoadMath.parseMaxspeed(tag("maxspeed"))
                    val oneway = tag("oneway") == "yes" || tag("junction") == "roundabout" || tag("highway") == "motorway"
                    val zone = listOf(tag("maxspeed"), tag("maxspeed:type"), tag("source:maxspeed"))
                        .firstOrNull { it != null && it.contains(':') }
                    roads += Road(
                        e.getLong("id"),
                        pts,
                        RoadMath.parseMaxspeed(tag("maxspeed:forward")) ?: all,
                        RoadMath.parseMaxspeed(tag("maxspeed:backward")) ?: all,
                        oneway,
                        RoadMath.estimateLimit(tag("highway"), zone, pts.first(), tag("name"), tag("name:ar"), tag("name:en"), tag("ref")),
                        (tag("name") ?: tag("name:ar") ?: tag("name:en"))?.let { RoadMath.normalizeName(it) },
                    )
                }
            }
        }
        return roads to cams
    }

    // ---------- sound ----------

    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        val ctx = app ?: return
        if (status != TextToSpeech.SUCCESS) {
            logger?.log("TTS init failed ($status) -- beeps only")
            return
        }
        // Speak in the alert language (Arabic by default, whatever the phone's language),
        // with the sentences from that language's strings. No Arabic voice installed:
        // English, and a note saying how to add the Arabic one.
        fun ok(r: Int) = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        var lang = when (AppSettings.roadVoiceLanguage(ctx)) {
            "en" -> Locale.ENGLISH
            "app" -> ctx.resources.configuration.locales[0]
            else -> Locale("ar", "EG")
        }
        var good = ok(t.setLanguage(lang))
        if (!good && lang.language == "ar") {
            lang = Locale("ar")
            good = ok(t.setLanguage(lang))
            if (!good) {
                logger?.log("TTS has no Arabic voice -- falling back to English")
                showNote(R.string.road_voice_arabic_missing)
                lang = Locale.ENGLISH
                good = ok(t.setLanguage(lang))
            }
        }
        ttsReady = good
        voiceCtx = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(lang) })
        if (!ttsReady) logger?.log("TTS has no voice for $lang -- beeps only")
        t.setAudioAttributes(navAudio)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = releaseFocus()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = releaseFocus()
        })
    }

    /** Strings in the alert language (see [onTtsInit]). */
    private var voiceCtx: Context? = null

    private fun vs(@StringRes res: Int, vararg args: Any): String =
        (voiceCtx ?: app)?.getString(res, *args) ?: ""

    private val navAudio: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun say(text: String) {
        val ctx = app ?: return
        if (muted) return
        when (AppSettings.roadAlertSound(ctx)) {
            "off" -> return
            "beep" -> beep()
            else -> {
                val t = tts
                if (t == null || !ttsReady) {
                    beep()
                    return
                }
                logger?.log("say: $text")
                requestFocus()
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "road-${System.currentTimeMillis()}")
            }
        }
    }

    private fun beep() {
        val ctx = app ?: return
        if (muted || AppSettings.roadAlertSound(ctx) == "off") return
        try {
            val g = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
            g.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        } catch (e: RuntimeException) {
            logger?.log("beep failed: $e")
        }
    }

    /** Lowers the music while a warning is spoken. */
    private fun requestFocus() {
        val am = app?.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(navAudio)
            .build()
        focus = req
        runCatching { am.requestAudioFocus(req) }
    }

    private fun releaseFocus() {
        val req = focus ?: return
        focus = null
        runCatching { app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req) }
    }

    // ---------- state ----------

    private fun showNote(@StringRes res: Int) {
        app?.getString(res)?.let { showNoteText(it, 3_000) }
    }

    private fun showNoteText(text: String, ms: Long) {
        note = text
        noteUntil = System.currentTimeMillis() + ms
        publish()
        handler?.postDelayed({ publish() }, ms + 100)
    }

    private fun publish() {
        val ctx = app ?: return
        val lim = currentLimit(ctx)
        val limit = lim.limit
        val kmh = speedKmh
        val cam = next?.takeIf { AppSettings.roadCameraAlerts(ctx) }
        val s = RoadState(
            speedKmh = kmh?.roundToInt(),
            limit = limit,
            limitFromRoad = lim.fromRoad,
            limitEstimated = lim.estimated,
            limitFromUser = lim.fromUser,
            onRoad = road != null,
            tempC = tempC.takeIf { AppSettings.roadShowWeather(ctx) },
            city = city.takeIf { AppSettings.roadShowWeather(ctx) },
            over = kmh != null && limit != null && kmh > limit + AppSettings.roadTolerance(ctx),
            wayOver = kmh != null && limit != null && kmh >= limit + maxOf(20.0, limit * 0.2),
            cameraM = cam?.meters?.roundToInt(),
            cameraLimit = cam?.camera?.limit ?: cam?.let { limit },
            bumpM = nextBump?.meters?.roundToInt(),
            trip = tripCard.takeIf { parked == true },
            muted = muted,
            note = note?.takeIf { System.currentTimeMillis() < noteUntil },
        )
        state = s
        for (l in listeners) runCatching { l(s) }
    }
}

package dev.caraalink.road

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** A speed camera: an OSM node (positive id) or one the driver added (negative id). */
class SpeedCamera(
    val id: Long,
    val at: LatLon,
    val limit: Int?,
    /** Direction of travel the camera watches, in degrees, when known. */
    val heading: Double?,
) {
    var farSaid = false
    var nearSaid = false
    val mine: Boolean get() = id < 0
}

/** An OSM way with its signed limit each way (null = unknown). */
class Road(
    val id: Long,
    val pts: List<LatLon>,
    val fwd: Int?,
    val back: Int?,
    val oneway: Boolean,
    /** When no limit is tagged: the named road's announced limit or the legal default (see [RoadMath.namedLimit], [RoadMath.defaultLimit]). */
    val estimate: Int? = null,
)

data class RoadMatch(val road: Road, val forward: Boolean) {
    val limit: Int? get() = if (forward) road.fwd else road.back
}

data class CameraAhead(val camera: SpeedCamera, val meters: Double)

/**
 * Geometry and matching for road limits and speed cameras. No Android
 * dependencies, so it is unit-tested on the JVM. Ported from Saga OBD's
 * road_service.dart.
 */
object RoadMath {
    private const val EARTH_M = 6_371_000.0

    /** Drivable roads within 1 km and cameras within 4 km, with geometry. */
    fun overpassQuery(at: LatLon): String =
        "[out:json][timeout:25];(" +
            "way(around:1000,${at.lat},${at.lon})[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|" +
            "unclassified|residential|living_street|service)(_link)?$\"];" +
            "node(around:4000,${at.lat},${at.lon})[\"highway\"=\"speed_camera\"];" +
            ");out geom tags;"

    /** "60", "60 km/h", "40 mph" → km/h; anything without a number ("EG:urban", "none") → null. */
    fun parseMaxspeed(v: String?): Int? {
        if (v == null) return null
        val m = Regex("""(\d+(?:\.\d+)?)""").find(v) ?: return null
        val n = m.groupValues[1].toDouble()
        val kmh = if (v.contains("mph")) n * 1.609 else n
        return kmh.roundToInt().takeIf { it in 5..200 }
    }

    /**
     * The legal limit for a road with no maxspeed tag, from its zone tag
     * ("EG:urban" in maxspeed, maxspeed:type or source:maxspeed) or else its
     * class. Egypt: 60 in built-up areas, 90 between cities, 100 on motorways
     * (some desert roads are signed higher -- those carry a maxspeed tag).
     */
    fun defaultLimit(highway: String?, zone: String?): Int? {
        val link = highway?.endsWith("_link") == true
        when (zone?.trim()?.uppercase()) {
            "EG:URBAN" -> return 60
            "EG:RURAL" -> return 90
            "EG:MOTORWAY", "EG:TRUNK" -> return 100
        }
        return when (highway?.removeSuffix("_link")) {
            "motorway" -> if (link) 60 else 100
            "trunk" -> if (link) 60 else 90
            "primary", "secondary", "tertiary", "unclassified", "residential" -> 60
            "living_street", "service" -> 40
            else -> null
        }
    }

    /** A named road and its announced limit: every word in [all] must appear, none of [not]. */
    private class Named(val limit: Int, val all: List<String>, val not: List<String> = emptyList())

    /**
     * Announced limits for major Egyptian roads that OpenStreetMap usually has no
     * maxspeed for (Ministry of Interior / General Traffic Department figures as
     * reported in 2024-2026). More specific entries first. Some roads drop lower
     * on signed sections; a maxspeed tag on the way always wins over this table.
     */
    private val NAMED = listOf(
        Named(120, listOf("دائري", "اقليمي")), Named(120, listOf("regional", "ring")),
        Named(120, listOf("دائري", "اوسطي")), Named(120, listOf("middle", "ring")),
        Named(90, listOf("دائري"), not = listOf("اقليمي", "اوسطي")),
        Named(90, listOf("ring road"), not = listOf("regional", "middle")),
        Named(90, listOf("26 يوليو")), Named(90, listOf("26th of july")), Named(90, listOf("26 july")),
        Named(90, listOf("محور", "الشهيد")),
        Named(90, listOf("المشير", "طنطاوي")), Named(90, listOf("tantawi")),
        Named(80, listOf("تيتو")), Named(80, listOf("tito")),
        Named(80, listOf("شينزو")), Named(80, listOf("shinzo")),
        Named(80, listOf("كفراوي")), Named(80, listOf("kafrawy")),
        Named(80, listOf("كمال عامر")),
        Named(80, listOf("جيهان السادات")),
        Named(80, listOf("ابو زيد خضر")),
        Named(80, listOf("محور", "جمال عبد الناصر")),
        Named(80, listOf("المليون شجره")),
        Named(80, listOf("امتداد", "رمسيس")),
        Named(80, listOf("صلاح سالم")), Named(80, listOf("salah salem")),
        Named(80, listOf("اوتوستراد")), Named(80, listOf("اتوستراد")), Named(80, listOf("autostrad")),
        Named(120, listOf("شبرا", "بنها", "الحر")),
        Named(120, listOf("محور", "الضبعه")),
        Named(120, listOf("حلوان", "الكريمات")),
        Named(90, listOf("الاسكندريه", "الزراعي")),
        Named(60, listOf("التسعين")),
        Named(60, listOf("كوبري", "اكتوبر")), Named(60, listOf("october bridge")),
        Named(50, listOf("نفق", "الازهر")), Named(50, listOf("azhar tunnel")),
    )

    /** Strips diacritics and folds alef/yeh/teh marbuta variants so OSM spellings match. */
    fun normalizeName(s: String): String = s.lowercase()
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace(Regex("[أإآ]"), "ا")
        .replace('ى', 'ي')
        .replace('ة', 'ه')
        .replace('ـ', ' ')
        .replace(Regex("\\s+"), " ")

    /** The announced limit for a well-known Egyptian road, matched on any of its names. */
    fun namedLimit(vararg names: String?): Int? {
        val text = names.filterNotNull().joinToString(" | ") { normalizeName(it) }
        if (text.isBlank()) return null
        for (n in NAMED) {
            val all = n.all.map { normalizeName(it) }
            val not = n.not.map { normalizeName(it) }
            if (all.all { text.contains(it) } && not.none { text.contains(it) }) return n.limit
        }
        return null
    }

    private val cardinal = mapOf(
        "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5, "E" to 90.0, "ESE" to 112.5,
        "SE" to 135.0, "SSE" to 157.5, "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
        "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
    )

    /** OSM `direction`: degrees ("90") or a compass point ("NE"); "forward"/"both" etc. → null. */
    fun parseDirection(v: String?): Double? {
        val s = v?.trim()?.uppercase() ?: return null
        return s.toDoubleOrNull()?.let { ((it % 360) + 360) % 360 } ?: cardinal[s]
    }

    fun distanceM(a: LatLon, b: LatLon): Double {
        val p1 = a.lat * PI / 180
        val p2 = b.lat * PI / 180
        val dp = p2 - p1
        val dl = (b.lon - a.lon) * PI / 180
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], 0..360 degrees. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = a.lat * PI / 180
        val p2 = b.lat * PI / 180
        val dl = (b.lon - a.lon) * PI / 180
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    /** Signed difference a - b, folded into -180..180. */
    fun angleDiff(a: Double, b: Double): Double = ((a - b + 540) % 360) - 180

    /** Metres from [p] to segment [a]-[b] (local flat approximation). */
    fun toSegmentM(p: LatLon, a: LatLon, b: LatLon): Double {
        val k = cos(p.lat * PI / 180)
        fun x(q: LatLon) = q.lon * PI / 180 * EARTH_M * k
        fun y(q: LatLon) = q.lat * PI / 180 * EARTH_M
        val px = x(p); val py = y(p)
        val ax = x(a); val ay = y(a)
        val dx = x(b) - ax; val dy = y(b) - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    /**
     * The road under the car: close to the centre line AND pointing the way
     * we drive, so a parallel service road or a crossing street doesn't win.
     * [heading] is NaN when unknown (stopped), and then distance alone decides.
     */
    fun matchRoad(roads: List<Road>, at: LatLon, heading: Double): RoadMatch? {
        val hasDir = !heading.isNaN()
        var best: RoadMatch? = null
        var bestScore = Double.MAX_VALUE
        for (r in roads) {
            for (i in 1 until r.pts.size) {
                val d = toSegmentM(at, r.pts[i - 1], r.pts[i])
                if (d > 30) continue // phone GPS in the city is often 10-25 m off
                var score = d
                var fwd = true
                if (hasDir) {
                    val diff = abs(angleDiff(heading, bearing(r.pts[i - 1], r.pts[i])))
                    fwd = diff <= 90
                    val off = if (fwd) diff else 180 - diff
                    if (off > 35) continue // crossing road, not ours
                    if (r.oneway && !fwd) continue // wrong way on a one-way
                    score += off * 0.4
                }
                if (score < bestScore) {
                    bestScore = score
                    best = RoadMatch(r, fwd)
                }
            }
        }
        return best
    }

    /**
     * Nearest camera within 1 km that is on the line we're driving: ahead,
     * at most ~35 m to the side, and (when its direction is known) watching
     * our direction of travel. Cameras more than 1 km away are re-armed so
     * they announce again next time.
     */
    /**
     * Direction a camera watches when OSM doesn't say: the direction of the
     * one-way carriageway it stands on. Divided roads are mapped as two one-way
     * ways, so this tells a camera on the other side of the road from ours.
     */
    fun inferHeading(c: SpeedCamera, roads: List<Road>): Double? {
        var best: Double? = null
        var bestD = 15.0
        for (r in roads) {
            if (!r.oneway) continue
            for (i in 1 until r.pts.size) {
                val d = toSegmentM(c.at, r.pts[i - 1], r.pts[i])
                if (d < bestD) {
                    bestD = d
                    best = bearing(r.pts[i - 1], r.pts[i])
                }
            }
        }
        return best
    }

    fun cameraAhead(
        cameras: List<SpeedCamera>,
        at: LatLon,
        heading: Double,
        moving: Boolean,
        roads: List<Road> = emptyList(),
    ): CameraAhead? {
        var best: CameraAhead? = null
        for (c in cameras) {
            val d = distanceM(at, c.at)
            if (d > 1000) {
                c.farSaid = false
                c.nearSaid = false
                continue
            }
            if (moving && !heading.isNaN()) {
                val diff = abs(angleDiff(bearing(at, c.at), heading))
                if (diff > 60) continue
                if (d * sin(diff * PI / 180) > 25) continue
                val ch = c.heading ?: inferHeading(c, roads)
                if (ch != null && abs(angleDiff(ch, heading)) > 60) continue
            }
            if (best == null || d < best.meters) best = CameraAhead(c, d)
        }
        return best
    }
}

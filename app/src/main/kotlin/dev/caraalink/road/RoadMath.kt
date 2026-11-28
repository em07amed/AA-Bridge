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
    /** A speed bump (OSM traffic_calming), not a camera: warned about by distance, no limit. */
    val bump: Boolean = false,
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
    /** Normalized name ([RoadMath.normalizeName]), so a limit the driver sets carries along the whole road. */
    val name: String? = null,
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

    /** Road tiles are ~550 m, camera tiles ~5.5 km (fetched 3x3 at a time). */
    const val ROAD_TILE = 0.005
    const val CAMERA_TILE = 0.05

    data class Box(val s: Double, val w: Double, val n: Double, val e: Double)

    /** (row, column) of the tile holding [at]. */
    fun tileOf(at: LatLon, size: Double): Pair<Int, Int> =
        Math.floor(at.lat / size).toInt() to Math.floor(at.lon / size).toInt()

    fun tileBox(row: Int, col: Int, size: Double): Box =
        Box(row * size, col * size, (row + 1) * size, (col + 1) * size)

    private fun bbox(s: Double, w: Double, n: Double, e: Double) =
        "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.US, s, w, n, e)

    /** Drivable roads in a box with geometry (no service roads: parking aisles and driveways). */
    fun roadsQuery(s: Double, w: Double, n: Double, e: Double): String =
        "[out:json][timeout:25];way(${bbox(s, w, n, e)})[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|" +
            "unclassified|residential|living_street)(_link)?$\"];out tags geom;"

    /** Speed cameras and speed bumps. */
    fun camerasQuery(s: Double, w: Double, n: Double, e: Double): String {
        val b = bbox(s, w, n, e)
        return "[out:json][timeout:25];(node($b)[\"highway\"=\"speed_camera\"];" +
            "node($b)[\"traffic_calming\"~\"^(${BUMPS.joinToString("|")})$\"];);out;"
    }

    /** OSM traffic_calming values a driver slows down for (not islands, chokers or chicanes). Same list as build_pack.py. */
    val BUMPS = setOf("yes", "bump", "hump", "table", "cushion", "dip", "rumble_strip", "speed_bump")

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
     * reported in 2024-2026, private cars). More specific entries first. Some roads drop lower
     * on signed sections; a maxspeed tag on the way always wins over this table.
     */
    private val NAMED = listOf(
        // Dakahlia (Dakahlia traffic department): Mansoura ring road 60 since 2025;
        // the governorate's main roads 90 for private cars.
        Named(60, listOf("دائري", "المنصوره")),
        Named(90, listOf("ميت غمر")), Named(90, listOf("السنبلاوين")), Named(90, listOf("جمصه")),
        Named(90, listOf("دكرنس")), Named(90, listOf("المنزله")), Named(90, listOf("منيه النصر")),
        Named(90, listOf("طناح")), Named(90, listOf("المحموديه")), Named(90, listOf("بنها", "المنصوره")),
        Named(90, listOf("دمياط", "الشرقي")),
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
        // Intercity and desert roads. Where reports disagree, the value most of them
        // give; roads split by kilometre (Suez, Ismailia) take the lower section.
        Named(120, listOf("سخنه")), Named(120, listOf("sokhna")),
        Named(120, listOf("الاسكندريه", "صحراوي"), not = listOf("مطروح")),
        Named(120, listOf("alexandria", "desert")),
        Named(90, listOf("الاسماعيليه", "صحراوي")), Named(90, listOf("ismailia", "desert")),
        Named(100, listOf("طريق", "السويس"), not = listOf("الاسماعيليه")), Named(100, listOf("suez road")),
        Named(100, listOf("الزعفرانه")), Named(100, listOf("غارب")), Named(100, listOf("zafarana")),
        Named(100, listOf("مطروح")), Named(100, listOf("matrouh")),
        Named(100, listOf("الفيوم", "صحراوي")),
        Named(100, listOf("بلبيس", "صحراوي")),
        Named(100, listOf("وادي النطرون", "العلمين")),
        Named(100, listOf("ساحلي")), Named(100, listOf("coastal road")),
        Named(90, listOf("الاسكندريه", "الزراعي")),
        Named(90, listOf("زراعي")),
        Named(60, listOf("التسعين")),
        Named(60, listOf("كوبري", "اكتوبر")), Named(60, listOf("october bridge")),
        Named(50, listOf("نفق", "الازهر")), Named(50, listOf("azhar tunnel")),
    )

    private val MAJOR = setOf("motorway", "trunk", "primary", "secondary")

    /**
     * Limit for a road with no maxspeed tag: the announced limit when it is a main
     * road with a known name (so a side street that shares a name doesn't match),
     * else the legal default for its zone or class.
     */
    fun estimateLimit(highway: String?, zone: String?, at: LatLon?, vararg names: String?): Int? {
        if (highway?.removeSuffix("_link") in MAJOR) namedLimit(*names)?.let { return it }
        if (at != null && highway?.removeSuffix("_link") in TOWN_ROADS) cityLimit(at)?.let { return it }
        return defaultLimit(highway, zone)
    }

    private class City(val center: LatLon, val radiusM: Double, val limit: Int)
    private val TOWN_ROADS = setOf("primary", "secondary", "tertiary", "unclassified", "residential")

    /** Cities with a lower limit on their own streets: Mansoura 40 (Dakahlia traffic department). */
    private val CITIES = listOf(
        City(LatLon(31.0409, 31.3785), 3_500.0, 40), // Mansoura
    )

    fun cityLimit(at: LatLon): Int? = CITIES.firstOrNull { distanceM(at, it.center) <= it.radiusM }?.limit

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

    private val TENS = mapOf(
        "عشرين" to 20, "تلاتين" to 30, "ثلاثين" to 30, "اربعين" to 40, "خمسين" to 50, "ستين" to 60,
        "سبعين" to 70, "تمانين" to 80, "ثمانين" to 80, "تسعين" to 90,
    )
    private val HUNDRED = listOf("ميه", "مايه", "مائه", "ميت")

    /**
     * A speed limit the driver said: "60", "٦٠", "ستين", "مية", "مية وعشرين",
     * "الحد تمانين". Only 20..140 is accepted; anything else is null.
     */
    fun parseSpokenLimit(text: String): Int? {
        val t = normalizeName(text).map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("")
        Regex("\\d{2,3}").findAll(t).map { it.value.toInt() }.firstOrNull { it in 20..140 }?.let { return it }
        val hundred = HUNDRED.any { t.contains(it) }
        val tens = TENS.entries.firstOrNull { t.contains(it.key) }?.value ?: 0
        val v = (if (hundred) 100 else 0) + tens
        return v.takeIf { it in 20..140 }
    }

    /** "رادار" / "كاميرا" (optionally with its limit, "رادار ستين"): a speed camera right here. */
    fun isCameraCommand(text: String): Boolean {
        val t = normalizeName(text)
        return listOf("رادار", "ردار", "كاميرا", "كاميره", "radar", "camera").any { t.contains(it) }
    }

    /** "مطب": a speed bump right here. */
    fun isBumpCommand(text: String): Boolean {
        val t = normalizeName(text)
        return listOf("مطب", "bump").any { t.contains(it) }
    }

    /** "امسح", "الغي", "clear": forget the driver's limit for this road. */
    fun isClearCommand(text: String): Boolean {
        val t = normalizeName(text)
        return listOf("امسح", "الغي", "الغاء", "شيل", "clear", "delete", "remove").any { t.contains(it) }
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

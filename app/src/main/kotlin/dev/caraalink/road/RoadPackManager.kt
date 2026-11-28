package dev.caraalink.road

import android.content.Context
import android.net.ConnectivityManager
import dev.caraalink.R
import dev.caraalink.diagnostics.DiagnosticLogger
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The offline Egypt roads pack: every drivable road with its limit tags and the
 * speed cameras, built weekly from OpenStreetMap by .github/workflows/roads-pack.yml
 * and published on the fork's "roads-egypt" release. With it, limits are instant
 * and work with no signal; without it (or outside its area) RoadAlerts falls back
 * to Overpass tiles.
 *
 * Checks for a new pack at most once a day; downloads only on Wi-Fi (an unmetered
 * network) unless the driver asks from Settings.
 */
object RoadPackManager {
    private const val BASE = "https://github.com/em07amed/AA-Bridge/releases/download/roads-egypt"
    private const val META_URL = "$BASE/egypt-roads.json"
    private const val ZIP_URL = "$BASE/egypt-roads.zip"
    private const val PREFS = "road_pack"

    class Info(val version: String, val bytes: Long, val roads: Int, val cameras: Int, val bounds: DoubleArray)

    @Volatile private var reader: RoadPackReader? = null
    @Volatile var info: Info? = null
        private set
    /** Download progress 0..100 while downloading, else null. */
    @Volatile var progress: Int? = null
        private set
    @Volatile var lastError: String? = null
        private set

    private val chunks = object : LinkedHashMap<Pair<Int, Int>, PackChunk>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, PackChunk>?) = size > 12
    }

    private fun file(ctx: Context) = File(File(ctx.filesDir, "roads"), "egypt-roads.zip")

    /** Opens the downloaded pack, if any. Cheap after the first call. */
    @Synchronized
    fun open(ctx: Context): RoadPackReader? {
        reader?.let { return it }
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            RoadPackReader(f).also { r ->
                reader = r
                info = r.metaText?.let { parseInfo(it, f.length()) }
            }
        } catch (e: Exception) {
            DiagnosticLogger(ctx, "road-pack").log("pack unreadable, removing it: $e")
            f.delete()
            null
        }
    }

    /** Whether the pack covers [at] (inside its bounds). */
    fun covers(ctx: Context, at: LatLon): Boolean {
        if (open(ctx) == null) return false
        val b = info?.bounds ?: return false
        return at.lat >= b[0] && at.lon >= b[1] && at.lat <= b[2] && at.lon <= b[3]
    }

    /** Roads of the app tile (row, col), or null when the pack isn't there. */
    fun roads(ctx: Context, row: Int, col: Int): List<Road>? {
        val c = chunk(ctx, Math.floorDiv(row, 10), Math.floorDiv(col, 10)) ?: return null
        return c.tiles[row to col] ?: emptyList()
    }

    /** Cameras of the 0.05-degree chunks rows r0..r1, columns c0..c1. */
    fun cameras(ctx: Context, r0: Int, c0: Int, r1: Int, c1: Int): List<SpeedCamera>? {
        val out = ArrayList<SpeedCamera>()
        for (r in r0..r1) for (c in c0..c1) out += chunk(ctx, r, c)?.cameras ?: return null
        return out
    }

    @Synchronized
    private fun chunk(ctx: Context, row: Int, col: Int): PackChunk? {
        val r = open(ctx) ?: return null
        chunks[row to col]?.let { return it }
        return r.chunk(row, col).also { chunks[row to col] = it }
    }

    /**
     * Network thread. Fetches the pack's description at most once a day (always
     * when [force]) and downloads a newer pack on Wi-Fi, or anywhere when [force].
     * Returns true when a new pack was installed.
     */
    fun update(ctx: Context, force: Boolean): Boolean {
        val log = DiagnosticLogger(ctx, "road-pack")
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checked", 0) < 24 * 3600_000L) return false
        if (progress != null) return false
        try {
            val meta = JSONObject(httpText(META_URL))
            prefs.edit().putLong("checked", now).apply()
            val version = meta.getString("version")
            open(ctx)
            // Same date and size: nothing new (a pack rebuilt the same day differs in size).
            if (info?.version == version && info?.bytes == meta.optLong("bytes", info?.bytes ?: 0) && file(ctx).exists()) {
                lastError = null
                return false
            }
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            if (!force && cm?.isActiveNetworkMetered != false) {
                log.log("pack $version available; waiting for Wi-Fi")
                return false
            }
            download(ctx, meta, log)
            log.log("pack $version installed")
            lastError = null
            return true
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            log.log("pack update failed: $e")
            return false
        } finally {
            progress = null
        }
    }

    private fun download(ctx: Context, meta: JSONObject, log: DiagnosticLogger) {
        val expectedBytes = meta.getLong("bytes")
        val expectedSha = meta.getString("sha256").lowercase()
        val dir = File(ctx.filesDir, "roads").apply { mkdirs() }
        val tmp = File(dir, "egypt-roads.zip.part")
        progress = 0
        val digest = MessageDigest.getInstance("SHA-256")
        val c = URL(ZIP_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        progress = ((done * 100) / expectedBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (tmp.length() != expectedBytes || sha != expectedSha) {
            tmp.delete()
            throw IOException("download damaged (size ${tmp.length()}/$expectedBytes)")
        }
        synchronized(this) {
            runCatching { reader?.close() }
            reader = null
            info = null
            chunks.clear()
            if (!tmp.renameTo(file(ctx))) throw IOException("could not install the pack")
            open(ctx)
        }
        log.log("pack downloaded: ${expectedBytes / 1024} KB")
    }

    /** Settings summary: version and size, download progress, or how to get it. */
    fun status(ctx: Context): String {
        progress?.let { return ctx.getString(R.string.road_pack_downloading, it) }
        open(ctx)
        val i = info
        val base = if (i != null) {
            ctx.getString(R.string.road_pack_installed, i.version, i.bytes / (1024 * 1024))
        } else {
            ctx.getString(R.string.road_pack_missing)
        }
        return lastError?.let { base + "\n" + ctx.getString(R.string.road_pack_error, it) } ?: base
    }

    private fun parseInfo(text: String, bytes: Long): Info {
        val o = JSONObject(text)
        val b = o.getJSONArray("bounds")
        return Info(
            o.getString("version"), bytes, o.optInt("roads"), o.optInt("cameras"),
            DoubleArray(4) { b.getDouble(it) },
        )
    }

    private fun httpText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

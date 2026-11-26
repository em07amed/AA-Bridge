package dev.caraalink.road

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** One ~5.5 km chunk of the roads pack: roads by app tile (row, column), and its cameras. */
class PackChunk(val tiles: Map<Pair<Int, Int>, List<Road>>, val cameras: List<SpeedCamera>)

/**
 * Reads the offline roads pack built by tools/roadpack/build_pack.py (format
 * documented there). Roads come with their raw tags and get their limit from
 * the same rules as Overpass data ([RoadMath.estimateLimit]). No Android
 * dependencies, so it is tested on the JVM against a pack the generator built.
 */
class RoadPackReader(file: File) : Closeable {
    private val zip = ZipFile(file)

    /** meta.json as text (version, bounds, counts); parsed by the caller. */
    val metaText: String? = zip.getEntry("meta.json")?.let { e ->
        zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** The chunk at (row, column) in 0.05-degree units; an absent entry means no roads there. */
    fun chunk(row: Int, col: Int): PackChunk {
        val e = zip.getEntry("c/${row}_$col.bin") ?: return PackChunk(emptyMap(), emptyList())
        return DataInputStream(BufferedInputStream(zip.getInputStream(e), 64 * 1024)).use { read(it) }
    }

    override fun close() = zip.close()

    private fun read(input: DataInputStream): PackChunk {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "DBR1") throw IOException("not a roads pack chunk")
        val strings = Array(input.readUnsignedShort()) { input.readUTF() }
        fun str(i: Int): String? = if (i == NONE) null else strings[i]

        val cameras = List(input.readInt()) {
            val id = input.readLong()
            val lat = input.readInt() / UNIT
            val lon = input.readInt() / UNIT
            val limit = input.readUnsignedByte()
            val heading = input.readShort().toInt()
            SpeedCamera(id, LatLon(lat, lon), limit.takeIf { it > 0 }, heading.takeIf { it >= 0 }?.toDouble())
        }

        // Many pieces share a road's tags: work its limit and name out once per chunk.
        val estimates = HashMap<List<String?>, Int?>()
        val names = HashMap<String, String>()
        val tiles = HashMap<Pair<Int, Int>, List<Road>>()
        repeat(input.readInt()) {
            val row = input.readInt()
            val col = input.readInt()
            val count = input.readInt()
            val roads = ArrayList<Road>(count)
            repeat(count) {
                val id = input.readLong()
                val oneway = input.readUnsignedByte() and 1 != 0
                val fwd = input.readUnsignedByte().takeIf { it > 0 }
                val back = input.readUnsignedByte().takeIf { it > 0 }
                val highway = str(input.readUnsignedShort())
                val zone = str(input.readUnsignedShort())
                val name = str(input.readUnsignedShort())
                val nameAr = str(input.readUnsignedShort())
                val nameEn = str(input.readUnsignedShort())
                val ref = str(input.readUnsignedShort())
                val n = input.readUnsignedShort()
                var lat = input.readInt()
                var lon = input.readInt()
                val pts = ArrayList<LatLon>(n)
                pts += LatLon(lat / UNIT, lon / UNIT)
                repeat(n - 1) {
                    lat += input.readShort()
                    lon += input.readShort()
                    pts += LatLon(lat / UNIT, lon / UNIT)
                }
                // City limits depend on where the road is: key on the ~5 km cell too.
                val key = listOf(highway, zone, name, nameAr, nameEn, ref, "${(lat / 5000)}_${(lon / 5000)}")
                val estimate = estimates.getOrPut(key) {
                    RoadMath.estimateLimit(highway, zone, pts.first(), name, nameAr, nameEn, ref)
                }
                val shown = (name ?: nameAr ?: nameEn)?.let { raw -> names.getOrPut(raw) { RoadMath.normalizeName(raw) } }
                roads += Road(id, pts, fwd, back, oneway, estimate, shown)
            }
            tiles[row to col] = roads
        }
        return PackChunk(tiles, cameras)
    }

    private companion object {
        const val NONE = 0xFFFF
        const val UNIT = 100_000.0
    }
}

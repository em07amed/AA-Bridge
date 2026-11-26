package dev.caraalink.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reads app/src/test/resources/roadpack/sample.zip, built by
 * tools/roadpack/build_pack.py from tools/roadpack/test/sample.osm
 * (a Mansoura road, a city street, a footway and a camera).
 */
class RoadPackTest {
    private fun sample(): File {
        val url = javaClass.classLoader!!.getResource("roadpack/sample.zip")
        return File(url.toURI())
    }

    @Test
    fun readsRoadsCamerasAndMeta() {
        RoadPackReader(sample()).use { pack ->
            assertTrue(pack.metaText!!.contains("\"roads\": 2"))
            val chunk = pack.chunk(620, 627)
            // The car on the Dekernes road at 31.0400, 31.3760 -> tile (6208, 6275).
            val roads = chunk.tiles[6208 to 6275]!!
            val dekernes = roads.first { it.id == 100L }
            assertTrue(dekernes.oneway)
            // oneway=-1: points come in travel order (westward), maxspeed:forward was for the other way.
            assertTrue(dekernes.pts.first().lon > dekernes.pts.last().lon)
            assertNull(dekernes.fwd)
            assertEquals(90, dekernes.estimate) // named Dakahlia road
            assertEquals(RoadMath.normalizeName("طريق المنصورة دكرنس"), dekernes.name)

            val street = chunk.tiles.values.flatten().first { it.id == 200L }
            assertEquals(40, street.estimate) // Mansoura city street

            assertTrue(chunk.tiles.values.flatten().none { it.id == 300L }) // footway left out
            val cam = chunk.cameras.single()
            assertEquals(60, cam.limit)
            assertEquals(90.0, cam.heading!!, 0.001)

            // Matching works on pack roads as on Overpass ones.
            val m = RoadMath.matchRoad(roads, LatLon(31.04003, 31.3760), 270.0)
            assertNotNull(m)
            assertEquals(100L, m!!.road.id)
        }
    }

    @Test
    fun emptyAreasAreEmptyNotErrors() {
        RoadPackReader(sample()).use { pack ->
            val c = pack.chunk(1, 1)
            assertTrue(c.tiles.isEmpty() && c.cameras.isEmpty())
        }
    }
}

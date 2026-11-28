package dev.caraalink.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadMathTest {

    /** A point [north] / [east] metres away from [o]. */
    private fun offset(o: LatLon, north: Double, east: Double): LatLon {
        val dLat = north / 111_320.0
        val dLon = east / (111_320.0 * Math.cos(Math.toRadians(o.lat)))
        return LatLon(o.lat + dLat, o.lon + dLon)
    }

    private val cairo = LatLon(30.0444, 31.2357)

    @Test
    fun parsesMaxspeedForms() {
        assertEquals(60, RoadMath.parseMaxspeed("60"))
        assertEquals(80, RoadMath.parseMaxspeed("80 km/h"))
        assertEquals(64, RoadMath.parseMaxspeed("40 mph"))
        assertNull(RoadMath.parseMaxspeed("EG:urban"))
        assertNull(RoadMath.parseMaxspeed("none"))
        assertNull(RoadMath.parseMaxspeed(null))
    }

    @Test
    fun parsesDirections() {
        assertEquals(90.0, RoadMath.parseDirection("90")!!, 0.001)
        assertEquals(45.0, RoadMath.parseDirection("ne")!!, 0.001)
        assertEquals(350.0, RoadMath.parseDirection("-10")!!, 0.001)
        assertNull(RoadMath.parseDirection("forward"))
    }

    @Test
    fun distanceAndBearing() {
        val north = offset(cairo, 1000.0, 0.0)
        assertEquals(1000.0, RoadMath.distanceM(cairo, north), 2.0)
        assertEquals(0.0, RoadMath.bearing(cairo, north), 0.5)
        assertEquals(90.0, RoadMath.bearing(cairo, offset(cairo, 0.0, 500.0)), 0.5)
        assertEquals(-20.0, RoadMath.angleDiff(350.0, 10.0), 0.001)
        assertEquals(20.0, RoadMath.angleDiff(10.0, 350.0), 0.001)
    }

    @Test
    fun picksRoadAlongOurDirectionNotTheCrossingOne() {
        // East-west road at 60 (two-way), north-south crossing road at 40.
        val ew = Road(1, listOf(offset(cairo, 0.0, -300.0), offset(cairo, 0.0, 300.0)), 60, 60, false)
        val ns = Road(2, listOf(offset(cairo, -300.0, 2.0), offset(cairo, 300.0, 2.0)), 40, 40, false)
        val at = offset(cairo, 3.0, 0.0)
        assertEquals(1L, RoadMath.matchRoad(listOf(ew, ns), at, 90.0)!!.road.id)
        assertEquals(2L, RoadMath.matchRoad(listOf(ew, ns), at, 0.0)!!.road.id)
    }

    @Test
    fun usesTheLimitForOurDirection() {
        val r = Road(1, listOf(offset(cairo, 0.0, -300.0), offset(cairo, 0.0, 300.0)), 80, 60, false)
        assertEquals(80, RoadMath.matchRoad(listOf(r), cairo, 90.0)!!.limit) // driving east = forward
        assertEquals(60, RoadMath.matchRoad(listOf(r), cairo, 270.0)!!.limit)
    }

    @Test
    fun ignoresOneWayRoadDrivenTheWrongWayAndFarRoads() {
        val oneway = Road(1, listOf(offset(cairo, 0.0, -300.0), offset(cairo, 0.0, 300.0)), 60, 60, true)
        assertNull(RoadMath.matchRoad(listOf(oneway), cairo, 270.0))
        assertNull(RoadMath.matchRoad(listOf(oneway), offset(cairo, 50.0, 0.0), 90.0))
    }

    @Test
    fun cameraAheadOnOurLineOnly() {
        val ahead = SpeedCamera(1, offset(cairo, 400.0, 0.0), 60, null)
        val behind = SpeedCamera(2, offset(cairo, -200.0, 0.0), 60, null)
        val parallel = SpeedCamera(3, offset(cairo, 300.0, 80.0), 60, null)
        val other = SpeedCamera(4, offset(cairo, 250.0, 0.0), 60, 180.0) // watches southbound traffic
        val hit = RoadMath.cameraAhead(listOf(ahead, behind, parallel, other), cairo, 0.0, moving = true)
        assertNotNull(hit)
        assertEquals(1L, hit!!.camera.id)
        assertEquals(400.0, hit.meters, 3.0)
    }

    @Test
    fun farCamerasReArm() {
        val far = SpeedCamera(1, offset(cairo, 2000.0, 0.0), null, null)
        far.farSaid = true
        far.nearSaid = true
        assertNull(RoadMath.cameraAhead(listOf(far), cairo, 0.0, moving = true))
        assertFalse(far.farSaid)
        assertFalse(far.nearSaid)
        assertTrue(SpeedCamera(-5, cairo, null, null).mine)
    }

    @Test
    fun egyptDefaultLimits() {
        assertEquals(60, RoadMath.defaultLimit("primary", "EG:urban"))
        assertEquals(90, RoadMath.defaultLimit("primary", "EG:rural"))
        assertEquals(100, RoadMath.defaultLimit("motorway", null))
        assertEquals(60, RoadMath.defaultLimit("motorway_link", null))
        assertEquals(90, RoadMath.defaultLimit("trunk", null))
        assertEquals(60, RoadMath.defaultLimit("residential", null))
        assertNull(RoadMath.defaultLimit("footway", null))
    }

    @Test
    fun cameraOnTheOtherCarriagewayIsSkipped() {
        // Divided road: northbound carriageway at x=0, southbound 18 m east of it.
        val north = Road(1, listOf(offset(cairo, -500.0, 0.0), offset(cairo, 500.0, 0.0)), 80, null, true)
        val south = Road(2, listOf(offset(cairo, 500.0, 18.0), offset(cairo, -500.0, 18.0)), 80, null, true)
        val theirs = SpeedCamera(1, offset(cairo, 300.0, 18.0), 80, null) // on the southbound side, no direction tag
        val ours = SpeedCamera(2, offset(cairo, 450.0, 0.0), 80, null)
        assertEquals(180.0, RoadMath.inferHeading(theirs, listOf(north, south))!!, 1.0)
        val hit = RoadMath.cameraAhead(listOf(theirs, ours), cairo, 0.0, moving = true, roads = listOf(north, south))
        assertEquals(2L, hit!!.camera.id)
    }

    @Test
    fun namedEgyptianRoads() {
        assertEquals(80, RoadMath.namedLimit("محور جوزيف تيتو"))
        assertEquals(80, RoadMath.namedLimit(null, null, "Shinzo Abe Axis"))
        assertEquals(90, RoadMath.namedLimit("الطريق الدائري"))
        assertEquals(120, RoadMath.namedLimit("الطريق الدائري الإقليمي"))
        assertEquals(120, RoadMath.namedLimit("الدائري الأوسطي"))
        assertEquals(80, RoadMath.namedLimit("شارع صلاح سالم"))
        assertEquals(80, RoadMath.namedLimit("طريق الأوتوستراد"))
        assertEquals(60, RoadMath.namedLimit("شارع التسعين الشمالي"))
        assertEquals(90, RoadMath.namedLimit("محور 26 يوليو"))
        assertNull(RoadMath.namedLimit("شارع جمال عبد الناصر")) // a common street name, not the New Cairo axis
        assertNull(RoadMath.namedLimit("شارع الهرم"))
        assertNull(RoadMath.namedLimit(null, null))
    }

    @Test
    fun governorateRoads() {
        assertEquals(120, RoadMath.namedLimit("طريق القطامية العين السخنة"))
        assertEquals(120, RoadMath.namedLimit("طريق القاهرة الإسكندرية الصحراوي"))
        assertEquals(100, RoadMath.namedLimit("طريق الإسكندرية مطروح"))
        assertEquals(90, RoadMath.namedLimit("طريق القاهرة الإسكندرية الزراعي"))
        assertEquals(90, RoadMath.namedLimit("طريق مصر الإسماعيلية الصحراوي"))
        assertEquals(100, RoadMath.namedLimit("طريق القاهرة السويس"))
        assertEquals(100, RoadMath.namedLimit("الطريق الساحلي الدولي"))
        assertEquals(90, RoadMath.namedLimit("طريق طنطا المحلة الزراعي"))
        assertEquals(100, RoadMath.namedLimit(null, null, "Cairo-Suez Road"))
    }

    @Test
    fun namesOnlyApplyToMainRoads() {
        assertEquals(100, RoadMath.estimateLimit("trunk", null, null, "طريق الإسكندرية مطروح"))
        assertEquals(60, RoadMath.estimateLimit("residential", null, null, "شارع مطروح")) // side street: legal default
        assertEquals(80, RoadMath.estimateLimit("primary_link", null, null, "محور جوزيف تيتو"))
        assertEquals(90, RoadMath.estimateLimit("trunk", null, null, "طريق بلا اسم معروف"))
    }

    @Test
    fun dakahlia() {
        val mansoura = LatLon(31.0409, 31.3785)
        assertEquals(60, RoadMath.namedLimit("الطريق الدائري بالمنصورة"))
        assertEquals(90, RoadMath.namedLimit("طريق المنصورة ميت غمر"))
        assertEquals(90, RoadMath.namedLimit("طريق المنصورة دكرنس المنزلة"))
        assertEquals(90, RoadMath.namedLimit("طريق منية النصر"))
        assertEquals(90, RoadMath.estimateLimit("primary", null, mansoura, "طريق المنصورة طناح"))
        assertEquals(40, RoadMath.estimateLimit("secondary", null, mansoura, "شارع الجمهورية")) // city street
        assertEquals(60, RoadMath.estimateLimit("secondary", null, LatLon(30.05, 31.24), "شارع الجمهورية"))
        assertEquals(90, RoadMath.namedLimit("الطريق الدائري")) // Cairo ring road unchanged
    }

    @Test
    fun tiles() {
        val (row, col) = RoadMath.tileOf(cairo, RoadMath.ROAD_TILE)
        val box = RoadMath.tileBox(row, col, RoadMath.ROAD_TILE)
        assertTrue(cairo.lat >= box.s && cairo.lat < box.n && cairo.lon >= box.w && cairo.lon < box.e)
        assertEquals(555.0, RoadMath.distanceM(LatLon(box.s, box.w), LatLon(box.n, box.w)), 5.0)
        assertTrue(RoadMath.roadsQuery(box.s, box.w, box.n, box.e).contains("way(30.04000,31.23500,30.04500,31.24000)"))
        assertTrue(RoadMath.camerasQuery(1.0, 2.0, 3.0, 4.0).contains("speed_camera"))
    }

    @Test
    fun spokenLimits() {
        assertEquals(60, RoadMath.parseSpokenLimit("ستين"))
        assertEquals(80, RoadMath.parseSpokenLimit("الحد تمانين"))
        assertEquals(80, RoadMath.parseSpokenLimit("ثمانين"))
        assertEquals(100, RoadMath.parseSpokenLimit("مية"))
        assertEquals(120, RoadMath.parseSpokenLimit("مية وعشرين"))
        assertEquals(120, RoadMath.parseSpokenLimit("مائة و عشرين"))
        assertEquals(90, RoadMath.parseSpokenLimit("90"))
        assertEquals(60, RoadMath.parseSpokenLimit("٦٠"))
        assertNull(RoadMath.parseSpokenLimit("مش عارف"))
        assertNull(RoadMath.parseSpokenLimit("5"))
        assertTrue(RoadMath.isClearCommand("امسح الحد"))
        assertFalse(RoadMath.isClearCommand("ستين"))
    }

    @Test
    fun voiceCommandsForCamerasAndBumps() {
        assertTrue(RoadMath.isCameraCommand("رادار"))
        assertTrue(RoadMath.isCameraCommand("فيه رادار هنا"))
        assertTrue(RoadMath.isCameraCommand("كاميرا"))
        assertEquals(60, RoadMath.parseSpokenLimit("رادار ستين"))
        assertNull(RoadMath.parseSpokenLimit("رادار"))
        assertTrue(RoadMath.isClearCommand("شيل الرادار"))
        assertTrue(RoadMath.isBumpCommand("مطب"))
        assertTrue(RoadMath.isBumpCommand("في مطب"))
        assertFalse(RoadMath.isBumpCommand("ستين"))
        assertFalse(RoadMath.isCameraCommand("مطب"))
    }

    @Test
    fun camerasQueryAsksForBumpsToo() {
        val q = RoadMath.camerasQuery(31.0, 31.3, 31.1, 31.4)
        assertTrue(q.contains("speed_camera"))
        assertTrue(q.contains("traffic_calming"))
        assertTrue(q.contains("hump"))
        assertFalse(q.contains("island"))
    }

    @Test
    fun bumpAheadOnOurLineOnly() {
        val at = LatLon(31.0400, 31.3700)
        // Heading east; one bump 100 m ahead on our line, one behind, one 100 m off to the side.
        val ahead = SpeedCamera(1, LatLon(31.0400, 31.37105), null, null, bump = true)
        val behind = SpeedCamera(2, LatLon(31.0400, 31.36895), null, null, bump = true)
        val side = SpeedCamera(3, LatLon(31.0409, 31.37105), null, null, bump = true)
        val hit = RoadMath.cameraAhead(listOf(behind, side, ahead), at, 90.0, true)
        assertEquals(1L, hit!!.camera.id)
        assertEquals(100.0, hit.meters, 5.0)
    }

    @Test
    fun tripCountsDistanceButNotJumps() {
        val t = Trip(0)
        t.add(LatLon(31.0400, 31.3700), 50.0, 1_000)
        t.add(LatLon(31.0400, 31.3710), 60.0, 7_000) // ~95 m in 6 s
        t.add(LatLon(31.1400, 31.3710), 60.0, 8_000) // 11 km in 1 s: a bad fix
        t.overLimit()
        assertEquals(95.0, t.meters, 5.0)
        assertEquals(60.0, t.maxKmh, 0.01)
        assertEquals(1, t.overCount)
        assertFalse(t.worthSummary)
        assertEquals("12.4", Trip.km(12_400.0))
        assertEquals("25", Trip.duration(25))
        assertEquals("1:05", Trip.duration(65))
    }
}

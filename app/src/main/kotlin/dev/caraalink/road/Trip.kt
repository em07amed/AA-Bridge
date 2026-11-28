package dev.caraalink.road

/**
 * One drive, from moving off to parking: what the summary on the car screen
 * and the phone notification show. Pure, so it is unit-tested on the JVM.
 */
class Trip(val startedAt: Long) {
    var meters = 0.0
        private set
    var maxKmh = 0.0
        private set
    /** Times the speed went over the limit (one per stretch, not per second). */
    var overCount = 0
        private set
    var cameras = 0
        private set
    var endedAt = startedAt
        private set
    private var prev: LatLon? = null

    /** A GPS fix while the trip runs. Jumps (a fix after a tunnel, a bad fix) are not counted as driven. */
    fun add(at: LatLon, kmh: Double, now: Long) {
        val p = prev
        if (p != null) {
            val d = RoadMath.distanceM(p, at)
            val secs = (now - endedAt) / 1000.0
            // At most ~200 km/h between fixes, plus a little GPS noise.
            if (d <= 15 + secs * 56) meters += d
        }
        prev = at
        endedAt = now
        if (kmh > maxKmh) maxKmh = kmh
    }

    fun overLimit() { overCount++ }

    fun camera() { cameras++ }

    val minutes: Int get() = ((endedAt - startedAt) / 60_000).toInt()

    /** Too short to be worth a summary: moving the car in a car park. */
    val worthSummary: Boolean get() = meters >= 500

    companion object {
        /** "12.4" km, one decimal under 100 km. */
        fun km(meters: Double): String {
            val km = meters / 1000
            return if (km < 100) String.format(java.util.Locale.US, "%.1f", km) else km.toInt().toString()
        }

        /** "25 min" or "1:05". */
        fun duration(minutes: Int): String =
            if (minutes < 60) minutes.toString() else String.format(java.util.Locale.US, "%d:%02d", minutes / 60, minutes % 60)
    }
}

package dev.caraalink.road

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.caraalink.R
import dev.caraalink.settings.AppSettings

/**
 * Drawn over Android Auto on the car screen: a limit sign (green ring, orange
 * when over the limit, red when well over), the next camera or speed bump, and a short note
 * after an action. It sits in the same view tree that is encoded to the car,
 * so it needs no help from Android Auto. Only the badges take touches -- tap
 * the sign and speak, long-press it to mute, long-press the camera banner
 * to remove it.
 * Everywhere else, touches fall through to Android Auto.
 */
@SuppressLint("ViewConstructor")
class RoadOverlay(context: Context) : FrameLayout(context) {

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val radar = label()
    private val bump = label()
    private val row = LinearLayout(context).apply {
        // Limit sign on top, speed under it.
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR // sign then speed, in any language
    }
    private val sign = label().apply { setTextColor(Color.BLACK) }
    private val speed = label()
    private val note = label()
    /** "24°  المنصورة" -- outside temperature and city. */
    private val place = label()

    private var unit = 0f
    private var last = RoadState()
    // Redraw only when something changed: the state is republished every couple of
    // seconds, and this view tree is re-encoded to the car on every frame.
    private val listener: (RoadState) -> Unit = { s -> post { if (s != last) render(s) } }

    init {
        val corner = AppSettings.roadOverlayCorner(context)
        val left = corner.endsWith("left")
        val vertical = if (corner.startsWith("bottom")) Gravity.BOTTOM else Gravity.TOP
        val horizontal = if (left) Gravity.LEFT else Gravity.RIGHT
        column.gravity = horizontal
        row.addView(sign)
        row.addView(speed)
        column.addView(place)
        column.addView(radar)
        column.addView(bump)
        column.addView(row)
        column.addView(note)
        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, vertical or horizontal))

        row.setOnClickListener { RoadAlerts.toggleMute() }
        row.setOnLongClickListener { RoadAlerts.addCameraHere(); true }
        // Tap the sign and say the limit (for roads with none, or a wrong one), "رادار" or "مطب".
        sign.setOnClickListener { VoiceLimit.listen(context) }
        // The speed is often hidden (Google Maps shows its own), so the sign mutes too.
        sign.setOnLongClickListener { RoadAlerts.toggleMute(); true }
        radar.setOnClickListener { RoadAlerts.toggleMute() }
        radar.setOnLongClickListener { RoadAlerts.removeNextCamera(); true }
        render(last)
    }

    private fun label() = TextView(context).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RoadAlerts.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        RoadAlerts.removeListener(listener)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Everything scales with the short side of the car screen (720 px on a 1920x720 panel).
        val u = minOf(w, h) / 100f
        if (u <= 0f || u == unit) return
        unit = u
        post { applySizes() }
    }

    private fun applySizes() {
        val u = unit
        val gap = (u * 1.5f).toInt()
        // Hug the screen edge; just enough to clear the rounded corner of the AA view.
        val m = (u * 0.8f).toInt().coerceAtLeast(2)
        (column.layoutParams as LayoutParams).setMargins(m, m, m, m)
        column.requestLayout()

        val d = (u * 9f).toInt()
        sign.layoutParams = LinearLayout.LayoutParams(d, d).apply { bottomMargin = gap }
        sign.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 4f)

        speed.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 4.2f)
        // Exactly the sign's width; a default child of the column would stretch to the widest row (the place chip).
        speed.layoutParams = LinearLayout.LayoutParams(d, LinearLayout.LayoutParams.WRAP_CONTENT)
        speed.minHeight = d
        speed.setPadding(0, gap / 2, 0, gap / 2)
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        // Same chip as the temperature and city.
        radar.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 4.5f)
        radar.setPadding(gap * 2, gap, gap * 2, gap)
        radar.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = gap }

        bump.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 4.5f)
        bump.setPadding(gap * 2, gap, gap * 2, gap)
        bump.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = gap }
        bump.background = rounded(DARK)

        place.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 4.5f)
        place.setPadding(gap * 2, gap, gap * 2, gap)
        place.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = gap }
        place.background = rounded(DARK)

        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 4.5f)
        note.setPadding(gap * 2, gap, gap * 2, gap)
        note.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = gap }
        note.background = rounded(0xCC111111.toInt())

        render(last)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = unit * 3f
        setColor(color)
    }

    private fun render(s: RoadState) {
        last = s
        val u = unit

        // Limit sign: white with a green ring; orange when over the limit, red when well over.
        // No limit known on a matched road: a "?" sign (grey ring) to tap and say it.
        val limit = s.limit
        val ask = limit == null && s.onRoad
        sign.visibility = if ((limit != null || ask) && s.speedKmh != null) View.VISIBLE else View.GONE
        if (limit != null || ask) {
            sign.text = limit?.toString() ?: "?"
            sign.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isNight()) NIGHT_SIGN else Color.WHITE)
                setStroke(
                    (u * 1.2f).toInt().coerceAtLeast(2),
                    when {
                        limit == null -> GREY
                        s.wayOver -> RED
                        s.over -> ORANGE
                        else -> GREEN
                    },
                )
            }
        }

        // No speed on the overlay (Google Maps shows it); this badge only says the alerts are muted.
        row.visibility = if (s.speedKmh != null) View.VISIBLE else View.GONE
        speed.visibility = if (s.speedKmh != null && s.muted) View.VISIBLE else View.GONE
        speed.text = "🔇"
        speed.background = rounded(DARK)

        val cam = s.cameraM
        radar.visibility = if (cam != null && cam <= 1000) View.VISIBLE else View.GONE
        if (cam != null) {
            val rounded10 = (cam + 5) / 10 * 10
            radar.text = context.getString(R.string.road_camera_banner, rounded10) +
                (s.cameraLimit?.let { "  •  $it" } ?: "")
            radar.background = rounded(DARK)
        }

        val b = s.bumpM
        bump.visibility = if (b != null) View.VISIBLE else View.GONE
        if (b != null) bump.text = context.getString(R.string.road_bump_banner, (b + 5) / 10 * 10)

        val placeText = listOfNotNull(s.tempC?.let { "$it°" }, s.city).joinToString("  ")
        place.visibility = if (placeText.isNotEmpty()) View.VISIBLE else View.GONE
        place.text = placeText

        // A note after an action wins; otherwise the summary of the drive while parked.
        val noteText = s.note ?: s.trip
        note.visibility = if (noteText != null) View.VISIBLE else View.GONE
        note.text = noteText ?: ""

        // At night everything is a little dimmer, and the sign is not glaring white.
        val night = isNight()
        column.alpha = if (night) 0.8f else 1f

        column.visibility =
            if (row.visibility == View.VISIBLE || radar.visibility == View.VISIBLE || bump.visibility == View.VISIBLE ||
                note.visibility == View.VISIBLE ||
                place.visibility == View.VISIBLE
            ) View.VISIBLE
            else View.GONE
    }

    private var nightAt = 0L
    private var night = false

    /** Same sun-based day/night as Android Auto's night mode; looked up at most once a minute. */
    private fun isNight(): Boolean {
        val now = System.currentTimeMillis()
        if (now - nightAt > 60_000) {
            nightAt = now
            night = runCatching { dev.caraalink.aa.NightMode.current(context) }.getOrNull() ?: false
        }
        return night
    }

    private companion object {
        val NIGHT_SIGN = 0xFFC8C8C8.toInt()
        val RED = 0xFFE53935.toInt()
        val GREY = 0xFF9E9E9E.toInt()
        val ORANGE = 0xFFFB8C00.toInt()
        val BLUE = 0xFF1E88E5.toInt()
        val GREEN = 0xFF43A047.toInt()
        val DARK = 0xCC111111.toInt()
    }
}

package dev.caraalink.road

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.caraalink.R

/** Where the car was last parked, and the phone notification with the drive's summary. */
object TripNotifier {
    private const val CHANNEL_ID = "trip_summary"
    private const val NOTIFICATION_ID = 4711
    private const val PREFS = "road_alerts"

    class Parking(val at: LatLon, val time: Long)

    fun saveParking(ctx: Context, at: LatLon, time: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("park_lat", at.lat.toString())
            .putString("park_lon", at.lon.toString())
            .putLong("park_at", time)
            .apply()
    }

    fun parking(ctx: Context): Parking? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getString("park_lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("park_lon", null)?.toDoubleOrNull() ?: return null
        return Parking(LatLon(lat, lon), p.getLong("park_at", 0))
    }

    /** Opens the spot in the maps app, with a pin. */
    fun mapsIntent(ctx: Context, at: LatLon): Intent {
        val label = Uri.encode(ctx.getString(R.string.road_park_here))
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:${at.lat},${at.lon}?q=${at.lat},${at.lon}($label)"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun notify(ctx: Context, summary: String, at: LatLon?) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, ctx.getString(R.string.road_trip_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(ctx.getString(R.string.road_trip_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
        if (at != null) {
            val open = PendingIntent.getActivity(
                ctx, 0, mapsIntent(ctx, at), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.setContentIntent(open)
            b.addAction(0, ctx.getString(R.string.road_park_here), open)
        }
        manager.notify(NOTIFICATION_ID, b.build())
    }
}

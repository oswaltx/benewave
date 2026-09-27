package dev.greenwave.maps

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.organicmaps.R
import java.util.Locale

/**
 * greenWave tracking mode: records the ride without a navigation, started and
 * stopped by the rider from the greenWave menu. Every GPS fix goes to
 * [GreenWaveController.onTrackLocation] (track file, stop detection, ride log).
 */
class GreenWaveTrackingService : Service() {

    private val listener = LocationListener { onLocation(it) }
    private var startedAt = 0L
    private var distanceMeters = 0.0
    private var last: Location? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "greenWave-Tracking braucht die Standort-Berechtigung.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        running = true
        startedAt = SystemClock.elapsedRealtime()
        GreenWaveController.startTracking(this)
        GreenWaveController.onTrackingEvent = { alert -> notifyEvent(alert) }
        requestUpdates()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun requestUpdates() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
    }

    private fun onLocation(location: Location) {
        last?.let { if (location.hasAccuracy() && location.accuracy < 30f) distanceMeters += it.distanceTo(location) }
        if (!location.hasAccuracy() || location.accuracy < 30f) last = location
        GreenWaveController.onTrackLocation(location)
        summary()
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotified >= 10_000L) {
            lastNotified = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    /** A stop ([alert]) or pass-through was registered: update right away; stops also alert and toast. */
    private fun notifyEvent(alert: Boolean) {
        if (!running) return
        lastNotified = SystemClock.elapsedRealtime()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(alert = alert))
        if (alert) GreenWaveController.trackingLastEvent?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        GreenWaveController.onTrackingEvent = null
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
        if (running) GreenWaveController.stopTracking()
        running = false
        summary = ""
        super.onDestroy()
    }

    private fun notification(alert: Boolean = false) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_logo_small)
        .setContentTitle(GreenWaveController.trackingLastEvent ?: "greenWave-Tracking läuft")
        .setContentText(summary())
        .setOngoing(true)
        .setOnlyAlertOnce(!alert)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            },
        )
        .addAction(
            0, "Beenden",
            PendingIntent.getService(
                this, 1, Intent(this, GreenWaveTrackingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun summary(): String {
        val minutes = if (startedAt == 0L) 0 else (SystemClock.elapsedRealtime() - startedAt) / 60_000
        val km = String.format(Locale.GERMAN, "%.1f", distanceMeters / 1000)
        return ("$minutes min · $km km · ${GreenWaveController.trackingStops} Stopps · " +
            "${GreenWaveController.trackingPasses} Grün · ${GreenWaveController.trackingLights} Ampeln")
            .also { summary = it }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "greenWave-Tracking", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "greenwave_tracking"
        private const val NOTIFICATION_ID = 0x6772
        private const val ACTION_STOP = "dev.greenwave.maps.STOP_TRACKING"

        @Volatile var running = false
            private set

        /** For the map overlay: "12 min · 3,4 km · 2 Stopps · 5 Grün · 6 Ampeln". */
        @Volatile var summary: String = ""
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GreenWaveTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GreenWaveTrackingService::class.java))
        }
    }
}

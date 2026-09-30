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
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.organicmaps.R
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.location.LocationUpdate
import dev.greenwave.shared.tracking.RideGuard
import java.util.Locale

/**
 * greenWave tracking mode: records the ride without a navigation, started and
 * stopped by the rider from the greenWave menu. Every GPS fix goes to
 * [GreenWaveController.onTrackLocation] (track file, stop detection, ride log).
 *
 * Saves battery with a [RideGuard]: GPS every second while riding, only every
 * [SLOW_INTERVAL_MS] without a fix (indoors), and off while standing still
 * until the significant-motion sensor reports movement. After 20 minutes of
 * standstill or without a fix the recording ends by itself.
 */
class GreenWaveTrackingService : Service() {

    private val listener = LocationListener { onLocation(it) }
    private var startedAt = 0L
    private var distanceMeters = 0.0
    private var last: Location? = null
    private var lastNotified = 0L
    private val guard = RideGuard()
    private var gps: RideGuard.Gps? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            apply(guard.onTick(System.currentTimeMillis() / 1000))
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }
    private val motion = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            val now = System.currentTimeMillis() / 1000
            guard.onMotion(now)
            apply(guard.onTick(now))
        }
    }

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
        setGps(RideGuard.Gps.FAST)
        handler.postDelayed(tick, TICK_MS)
        return START_NOT_STICKY
    }

    /** What the [guard] decided: GPS mode, and ending the recording after a long standstill or no fix. */
    private fun apply(decision: RideGuard.Decision) {
        if (!running) return
        state = decision.state
        if (decision.end) {
            val why = if (decision.state == RideGuard.State.STILL) "20 min Stillstand" else "20 min kein GPS"
            endedBecause = "greenWave-Tracking beendet ($why)"
            stopSelf()
            return
        }
        if (decision.gps != gps) setGps(decision.gps)
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun setGps(mode: RideGuard.Gps) {
        gps = mode
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val sensors = getSystemService(SensorManager::class.java)
        val trigger = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        manager.removeUpdates(listener)
        trigger?.let { sensors.cancelTriggerSensor(motion, it) }
        when (mode) {
            RideGuard.Gps.FAST ->
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            RideGuard.Gps.SLOW ->
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, SLOW_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            RideGuard.Gps.WAIT_FOR_MOTION ->
                if (trigger != null) {
                    sensors.requestTriggerSensor(motion, trigger)
                } else {
                    // No motion sensor: look now and then instead.
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, SLOW_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
                }
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun onLocation(location: Location) {
        apply(
            guard.onFix(
                LocationUpdate(
                    position = LatLng(location.latitude, location.longitude),
                    epochSec = location.time / 1000,
                    speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0,
                    bearingDegrees = if (location.hasBearing()) location.bearing else null,
                ),
            ),
        )
        if (!running) return
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
        handler.removeCallbacks(tick)
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
        val sensors = getSystemService(SensorManager::class.java)
        sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { sensors.cancelTriggerSensor(motion, it) }
        if (running) GreenWaveController.stopTracking()
        running = false
        summary = ""
        state = RideGuard.State.RIDING
        endedBecause?.let { text ->
            // The ongoing notification goes with the service; leave a short note why it ended.
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID + 1,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo_small)
                    .setContentTitle(text)
                    .setContentText("Im greenWave-Menü neu starten, wenn du weiterfährst.")
                    .setAutoCancel(true)
                    .build(),
            )
        }
        endedBecause = null
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
        val pause = when (state) {
            RideGuard.State.RIDING -> ""
            RideGuard.State.VEHICLE -> "Pause (zu schnell fürs Rad) · "
            RideGuard.State.STILL -> "Pause (Stillstand, GPS aus) · "
            RideGuard.State.NO_FIX -> "Kein GPS-Empfang · "
        }
        val minutes = if (startedAt == 0L) 0 else (SystemClock.elapsedRealtime() - startedAt) / 60_000
        val km = String.format(Locale.GERMAN, "%.1f", distanceMeters / 1000)
        return ("$pause$minutes min · $km km · ${GreenWaveController.trackingStops} Stopps · " +
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
        private const val TICK_MS = 30_000L
        private const val SLOW_INTERVAL_MS = 30_000L

        @Volatile var running = false
            private set

        /** Why learning is paused (vehicle, standstill, no fix), [RideGuard.State.RIDING] otherwise. */
        @Volatile var state: RideGuard.State = RideGuard.State.RIDING
            private set

        private var endedBecause: String? = null

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

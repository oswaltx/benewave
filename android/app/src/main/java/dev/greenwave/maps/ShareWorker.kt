package dev.greenwave.maps

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.greenwave.shared.sharing.ShareSchedule
import dev.greenwave.shared.sharing.SharedEvents
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Weekly exchange with the shared board: sends the queued, trimmed events
 * ([ShareQueue]) at the random moment [ShareSchedule] picked, then fetches what
 * others shared around the rider's own lights into [LightEventStore.SHARED_FILE].
 * Runs daily on unmetered networks and does nothing until the send is due;
 * "Jetzt senden" in the menu runs it at once. Off unless the rider enabled
 * sharing and a server address is set.
 */
internal class ShareWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val settings = GreenWaveSettings(context)
        val base = settings.shareServerUrl.trim().trimEnd('/')
        if (!settings.shareEnabled || base.isEmpty()) return Result.success()
        val now = System.currentTimeMillis() / 1000
        val offset = TimeZone.getDefault().getOffset(now * 1000) / 1000
        if (settings.shareNextAt == 0L) settings.shareNextAt = ShareSchedule.next(now, offset)
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!force && !ShareSchedule.due(settings.shareNextAt, now)) return Result.success()
        return try {
            val pending = ShareQueue.all(context)
            if (pending.isNotEmpty()) {
                val stored = ShareClient.upload(base, pending.shuffled())
                ShareQueue.removeFirst(context, pending.size)
                Log.i(TAG, "shared ${pending.size} light events, $stored new on the board")
            }
            fetch(context, base, settings, now)
            settings.shareLastSent = now
            settings.shareNextAt = ShareSchedule.next(now, offset)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "board exchange failed, retrying later", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun fetch(context: Context, base: String, settings: GreenWaveSettings, now: Long) {
        val own = LightEventStore(context).all()
        val box = SharedEvents.region(own) ?: return
        val since = maxOf(settings.shareLastFetch, now - SharedEvents.MAX_AGE_SEC)
        val downloaded = ShareClient.download(base, ShareClient.Box(box.minLat, box.minLon, box.maxLat, box.maxLon), since)
        val shared = LightEventStore(context, LightEventStore.SHARED_FILE)
        shared.replaceAll(SharedEvents.merge(shared.all(), downloaded, own, now))
        settings.shareLastFetch = now
        Log.i(TAG, "fetched ${downloaded.size} shared light events")
    }

    companion object {
        private const val TAG = "GreenWave"
        private const val KEY_FORCE = "force"
        private const val MAX_ATTEMPTS = 5
        private const val PERIODIC = "greenwave-share"
        private const val NOW = "greenwave-share-now"

        /** Daily check on unmetered networks; idempotent, called at app start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ShareWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Send and fetch now, on any network (menu "Jetzt senden"). */
        fun sendNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ShareWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_FORCE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

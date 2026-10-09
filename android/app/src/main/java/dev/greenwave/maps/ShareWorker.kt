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
    override fun doWork(): Result = synchronized(RUN_LOCK) { exchange() }

    /** One worker at a time (daily run and "Jetzt senden" may overlap), so the queue and the shared file stay consistent. */
    private fun exchange(): Result {
        val context = applicationContext
        val settings = GreenWaveSettings(context)
        if (settings.runsRed) {
            ShareQueue.clear(context)
            return Result.success()
        }
        val base = settings.shareServerUrl.trim().trimEnd('/')
        // https only, also for an address saved before the dialog enforced it.
        if (!settings.shareEnabled || !base.startsWith("https://")) return Result.success()
        val now = System.currentTimeMillis() / 1000
        val offset = TimeZone.getDefault().getOffset(now * 1000) / 1000
        if (settings.shareNextAt == 0L) settings.shareNextAt = ShareSchedule.next(now, offset)
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!force && !ShareSchedule.due(settings.shareNextAt, now)) return Result.success()

        val sent = runCatching { send(context, base, now) }
            .onFailure { Log.w(TAG, "sending to the board failed, retrying later", it) }
            .isSuccess
        // Fetching does not depend on sending: a failed upload must not cut the rider off from others' data.
        val fetched = runCatching { fetch(context, base, now) }
            .onFailure { Log.w(TAG, "fetching from the board failed, retrying later", it) }
            .isSuccess
        if (sent) {
            // The weekly rhythm follows the send; a failed fetch alone must not turn it into daily sends.
            settings.shareLastSent = now
            settings.shareNextAt = ShareSchedule.next(now, offset)
        }
        if (sent && fetched) return Result.success()
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    /**
     * Sends the queue in requests of [ShareClient.MAX_PER_POST]. Events the board
     * would not take (too old, clock wrong) are dropped first; a request the board
     * rejects (4xx) is dropped too, so one bad row never blocks the queue.
     */
    private fun send(context: Context, base: String, now: Long) {
        val pending = ShareQueue.all(context)
        if (pending.isEmpty()) return
        val uploadable = SharedEvents.uploadable(pending, now)
        ShareQueue.remove(context, pending - uploadable.toSet())
        for (chunk in uploadable.shuffled().chunked(ShareClient.MAX_PER_POST)) {
            try {
                val stored = ShareClient.upload(base, chunk)
                Log.i(TAG, "shared ${chunk.size} light events, $stored new on the board")
            } catch (e: ShareClient.Rejected) {
                Log.w(TAG, "board rejected ${chunk.size} light events, dropping them", e)
            }
            ShareQueue.remove(context, chunk)
        }
    }

    private fun fetch(context: Context, base: String, now: Long) {
        val own = LightEventStore(context).all()
        val box = SharedEvents.region(own) ?: return
        // Always the whole window: others upload weekly and late, and merge removes what is known already.
        val since = now - SharedEvents.MAX_AGE_SEC
        val downloaded = ShareClient.download(base, ShareClient.Box(box.minLat, box.minLon, box.maxLat, box.maxLon), since)
        val shared = LightEventStore(context, LightEventStore.SHARED_FILE)
        shared.replaceAll(SharedEvents.merge(shared.all(), downloaded, own, now))
        GreenWaveSettings(context).shareLastFetch = now
        Log.i(TAG, "fetched ${downloaded.size} shared light events")
    }

    companion object {
        private const val TAG = "GreenWave"
        private val RUN_LOCK = Any()
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

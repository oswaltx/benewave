package dev.greenwave.maps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 * Weekly exchange with the shared board, sending and fetching at two separate
 * random moments ([ShareSchedule], at least [ShareSchedule.MIN_GAP_SEC] apart),
 * so the board cannot tie a download area to an upload:
 *
 * - send: the queued, trimmed events ([ShareQueue]), minus implausible ones;
 * - fetch: what others shared in the fixed tiles around the rider's own lights
 *   ([SharedEvents.tiles]), only lights reported by several sources
 *   ([SharedEvents.credible]), into [LightEventStore.SHARED_FILE].
 *
 * Checks hourly on unmetered networks and acts only when a moment is due, the
 * local time is inside the sending window (a moment missed at night moves to
 * the next window) and the network is still unmetered when it runs. "Jetzt
 * senden" in the menu does both at once on any network. Off unless the rider
 * enabled sharing and a server address is set.
 */
internal class ShareWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = synchronized(RUN_LOCK) { exchange() }

    /** One worker at a time (hourly run and "Jetzt senden" may overlap), so the queue and the shared file stay consistent. */
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
        if (settings.shareFetchAt == 0L) settings.shareFetchAt = ShareSchedule.nextApart(now, offset, settings.shareNextAt)
        val force = inputData.getBoolean(KEY_FORCE, false)

        var sendDue = force || ShareSchedule.due(settings.shareNextAt, now)
        var fetchDue = force || ShareSchedule.due(settings.shareFetchAt, now)
        if (!sendDue && !fetchDue) return Result.success()
        if (!force) {
            // The constraint holds when the run starts; a metered hotspot that calls itself WLAN or a switch since must not send.
            if (!unmetered(context)) return Result.success()
            if (!ShareSchedule.inWindow(now, offset)) {
                if (sendDue) settings.shareNextAt = ShareSchedule.postpone(now, offset, apartFrom = settings.shareFetchAt)
                if (fetchDue) settings.shareFetchAt = ShareSchedule.postpone(now, offset, apartFrom = settings.shareNextAt)
                return Result.success()
            }
            // Both due (phone was off): send now, fetch later, never both in one go.
            if (sendDue && fetchDue) {
                settings.shareFetchAt = ShareSchedule.postpone(now, offset, apartFrom = now)
                fetchDue = false
            }
        }

        var failed = false
        if (sendDue) {
            runCatching { send(context, settings, base, now) }
                .onSuccess {
                    settings.shareLastSent = now
                    settings.shareNextAt = ShareSchedule.nextApart(now, offset, settings.shareFetchAt)
                }
                .onFailure { failed = true; Log.w(TAG, "sending to the board failed, retrying later", it) }
        }
        // Fetching does not depend on sending: a failed upload must not cut the rider off from others' data.
        if (fetchDue) {
            runCatching { fetch(context, base, now) }
                .onSuccess {
                    settings.shareLastFetch = now
                    settings.shareFetchAt = ShareSchedule.nextApart(now, offset, settings.shareNextAt)
                }
                .onFailure { failed = true; Log.w(TAG, "fetching from the board failed, retrying later", it) }
        }
        if (!failed) return Result.success()
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private fun unmetered(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Sends the queue in requests of [ShareClient.MAX_PER_POST]. Events the board
     * would not take (too old, clock wrong, too fast from the previous one) are
     * dropped first; a request the board rejects (400/413) is dropped too, so one
     * bad row never blocks the queue. Registers the device first if needed; an
     * unknown token is forgotten, so the retry registers anew.
     */
    private fun send(context: Context, settings: GreenWaveSettings, base: String, now: Long) {
        val pending = ShareQueue.all(context)
        if (pending.isEmpty()) return
        val uploadable = SharedEvents.plausible(SharedEvents.uploadable(pending, now))
        ShareQueue.remove(context, pending - uploadable.toSet())
        if (uploadable.isEmpty()) return
        if (settings.shareDevice.isEmpty()) settings.shareDevice = ShareClient.register(base)
        for (chunk in uploadable.shuffled().chunked(ShareClient.MAX_PER_POST)) {
            try {
                val stored = ShareClient.upload(base, settings.shareDevice, chunk)
                Log.i(TAG, "shared ${chunk.size} light events, $stored new on the board")
            } catch (e: ShareClient.Rejected) {
                Log.w(TAG, "board rejected ${chunk.size} light events, dropping them", e)
            } catch (e: ShareClient.Unauthorized) {
                settings.shareDevice = ""
                throw e
            }
            ShareQueue.remove(context, chunk)
        }
    }

    /**
     * Replaces the shared events of the fetched tiles by what the board has
     * there now (always the whole window: others upload weekly and late);
     * shared events elsewhere (an earlier trip) stay until they age out.
     */
    private fun fetch(context: Context, base: String, now: Long) {
        val own = LightEventStore(context).all()
        val tiles = SharedEvents.tiles(own)
        if (tiles.isEmpty()) return
        val since = now - SharedEvents.MAX_AGE_SEC
        val downloaded = tiles.flatMap { ShareClient.download(base, it, since) }
        val credible = SharedEvents.credible(downloaded)
        val shared = LightEventStore(context, LightEventStore.SHARED_FILE)
        val elsewhere = shared.all().filter { SharedEvents.tileOf(it.light) !in tiles }
        shared.replaceAll(SharedEvents.merge(elsewhere, credible, own, now))
        Log.i(TAG, "fetched ${downloaded.size} shared light events in ${tiles.size} tiles, ${credible.size} credible")
    }

    companion object {
        private const val TAG = "GreenWave"
        private val RUN_LOCK = Any()
        private const val KEY_FORCE = "force"
        private const val MAX_ATTEMPTS = 5
        private const val PERIODIC = "greenwave-share"
        private const val NOW = "greenwave-share-now"

        /** Hourly check on unmetered networks (cheap until a moment is due); idempotent, called at app start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ShareWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            // UPDATE: installs from before ran daily; the new period takes over without losing the slot.
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
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

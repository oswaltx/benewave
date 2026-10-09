package dev.greenwave.maps

import android.content.Context
import android.util.Log
import dev.greenwave.shared.tracking.LightEvent
import org.json.JSONArray
import java.io.File

/**
 * Events waiting for the next weekly send to the shared board (see
 * [ShareWorker]): already trimmed around start and end of each ride
 * ([dev.greenwave.shared.sharing.ShareTrim]), without track or rider id.
 * Written by the controller's worker, read and emptied by [ShareWorker] on
 * another thread, hence every access goes through the file under one lock.
 */
internal object ShareQueue {
    private const val FILE = "greenwave_share_queue.json"
    /** About ten weeks of daily riding; older events are of little use for the board anyway. */
    private const val MAX_EVENTS = 5_000

    private val lock = Any()

    fun add(context: Context, events: Collection<LightEvent>) {
        if (events.isEmpty()) return
        synchronized(lock) {
            val list = read(context) + events
            write(context, list.takeLast(MAX_EVENTS))
        }
    }

    fun all(context: Context): List<LightEvent> = synchronized(lock) { read(context) }

    fun size(context: Context): Int = all(context).size

    /** After a send: drop exactly [events] (one occurrence each); events queued meanwhile stay. */
    fun remove(context: Context, events: Collection<LightEvent>) {
        if (events.isEmpty()) return
        synchronized(lock) {
            val pending = events.groupingBy { it }.eachCount().toMutableMap()
            val kept = read(context).filter { e ->
                val n = pending[e] ?: 0
                if (n > 0) pending[e] = n - 1
                n == 0
            }
            write(context, kept)
        }
    }

    /** Sharing switched off or the rider runs red: nothing queued may leave the device any more. */
    fun clear(context: Context) {
        synchronized(lock) { file(context).delete() }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun read(context: Context): List<LightEvent> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val rows = JSONArray(f.readText())
            (0 until rows.length()).map { LightEventStore.fromRow(rows.getJSONArray(it)) }
        } catch (e: Exception) {
            Log.w("GreenWave", "share queue unreadable, starting fresh", e)
            emptyList()
        }
    }

    private fun write(context: Context, events: List<LightEvent>) {
        val rows = JSONArray()
        events.forEach { rows.put(LightEventStore.toRow(it)) }
        val f = file(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(rows.toString())
        tmp.renameTo(f)
    }
}

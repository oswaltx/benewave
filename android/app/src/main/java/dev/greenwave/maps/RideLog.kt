package dev.greenwave.maps

import android.content.Context
import android.util.Log
import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.geo.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One finished bicycle navigation, as kept on the device. */
internal data class Ride(
    val startEpochSec: Long,
    val endEpochSec: Long,
    /** Speeds while moving (km/h, one per fix, >= [RideLog.MOVING_KMH]), for the rider profile. */
    val movingSpeedsKmh: List<Double>,
    /** Lights passed, in order, with their distance from the ride start. */
    val passedLights: List<PassedLight>,
    /** Passes through a light with a learned program while it should have been red. */
    val redPasses: Int,
    /** Rider's own "I often cross on red" flag at the time of the ride. */
    val riderRunsRed: Boolean,
    /** 1–5 stars, null until rated (or if skipped). */
    val rating: Int? = null,
    /** Lights the rider marked as particularly wrong. */
    val wrongLights: List<LatLng> = emptyList(),
)

internal data class PassedLight(val position: LatLng, val atMeters: Double, val learned: Boolean)

/**
 * Finished rides as one JSON file in private storage (capped at [MAX_RIDES]).
 * Not thread-safe — only touch it from GreenWaveController's worker.
 */
internal class RideLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "greenwave_rides.json")
    private var cache: MutableList<Ride>? = null

    fun all(): List<Ride> = load()

    fun add(ride: Ride) {
        val rides = load()
        rides.add(ride)
        while (rides.size > MAX_RIDES) rides.removeAt(0)
        save(rides)
    }

    fun rate(startEpochSec: Long, rating: Int?, wrongLights: List<LatLng>) {
        val rides = load()
        val i = rides.indexOfFirst { it.startEpochSec == startEpochSec }
        if (i < 0) return
        rides[i] = rides[i].copy(rating = rating, wrongLights = wrongLights)
        save(rides)
    }

    /**
     * Speed range from the last rides: 20th to 80th percentile of moving
     * speed, at least [MIN_WIDTH_KMH] wide. Null until there is enough data.
     */
    fun profileRange(): SpeedRangeKmh? {
        val speeds = load().takeLast(PROFILE_RIDES).flatMap { it.movingSpeedsKmh }.sorted()
        if (speeds.size < MIN_PROFILE_SAMPLES) return null
        val low = speeds[(speeds.size * 0.2).toInt()]
        val high = speeds[min(speeds.lastIndex, (speeds.size * 0.8).toInt())]
        val mid = (low + high) / 2
        val half = max((high - low) / 2, MIN_WIDTH_KMH / 2)
        return SpeedRangeKmh(
            min = max(MOVING_KMH, (mid - half).roundToInt().toDouble()),
            max = min(40.0, (mid + half).roundToInt().toDouble()),
        )
    }

    private fun load(): MutableList<Ride> {
        cache?.let { return it }
        val rides = mutableListOf<Ride>()
        if (file.exists()) {
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) rides.add(array.getJSONObject(i).toRide())
            } catch (e: Exception) {
                Log.w("GreenWave", "ride log unreadable, starting fresh", e)
            }
        }
        cache = rides
        return rides
    }

    private fun save(rides: List<Ride>) {
        val array = JSONArray()
        rides.forEach { array.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun Ride.toJson() = JSONObject()
        .put("start", startEpochSec)
        .put("end", endEpochSec)
        .put("speeds", JSONArray(movingSpeedsKmh.map { (it * 10).roundToInt() / 10.0 }))
        .put("lights", JSONArray(passedLights.map {
            JSONObject().put("lat", it.position.latitude).put("lon", it.position.longitude)
                .put("at", it.atMeters.roundToInt()).put("learned", it.learned)
        }))
        .put("redPasses", redPasses)
        .put("runsRed", riderRunsRed)
        .put("rating", rating ?: JSONObject.NULL)
        .put("wrong", JSONArray(wrongLights.map { JSONObject().put("lat", it.latitude).put("lon", it.longitude) }))

    private fun JSONObject.toRide(): Ride {
        val speeds = getJSONArray("speeds")
        val lights = getJSONArray("lights")
        val wrong = getJSONArray("wrong")
        return Ride(
            startEpochSec = getLong("start"),
            endEpochSec = getLong("end"),
            movingSpeedsKmh = List(speeds.length()) { speeds.getDouble(it) },
            passedLights = List(lights.length()) {
                val l = lights.getJSONObject(it)
                PassedLight(LatLng(l.getDouble("lat"), l.getDouble("lon")), l.getDouble("at"), l.getBoolean("learned"))
            },
            redPasses = getInt("redPasses"),
            riderRunsRed = getBoolean("runsRed"),
            rating = if (isNull("rating")) null else getInt("rating"),
            wrongLights = List(wrong.length()) {
                val w = wrong.getJSONObject(it)
                LatLng(w.getDouble("lat"), w.getDouble("lon"))
            },
        )
    }

    companion object {
        const val MOVING_KMH = 8.0
        private const val MAX_RIDES = 200
        private const val PROFILE_RIDES = 10
        private const val MIN_PROFILE_SAMPLES = 300
        private const val MIN_WIDTH_KMH = 6.0
    }
}

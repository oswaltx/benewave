package dev.greenwave.maps

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.live.LaneConnection
import dev.greenwave.shared.live.SignalObservation
import dev.greenwave.shared.live.SignalState
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/**
 * Live signal states from Hamburg's open traffic light data (OGC SensorThings,
 * https://tld.iot.hamburg.de, Datenlizenz Deutschland Namensnennung 2.0, source:
 * Freie und Hansestadt Hamburg, LGV/LSBG). Blocking — call off the main thread.
 */
internal object HamburgSignalsClient {
    private const val BASE = "https://tld.iot.hamburg.de/v1.1/Things"
    private const val USER_AGENT = "greenWave Maps/dev (privates Hobbyprojekt)"

    /** Box around each light that is searched for lane connections. */
    private const val BOX_DEG_LAT = 0.0006
    private const val BOX_DEG_LON = 0.0010

    /** Observations this far back are enough for a few cycles. */
    private const val HISTORY_SEC = 20 * 60L

    /** Rough bounds of the city; outside them no request is made. */
    fun covers(position: LatLng): Boolean =
        position.latitude in 53.39..53.75 && position.longitude in 9.70..10.34

    /** Lane connections with their recent primary-signal changes around [lights] (max ~8 per call). */
    fun laneConnections(lights: List<LatLng>, nowEpochSec: Long): List<LaneConnection> {
        if (lights.isEmpty()) return emptyList()
        val boxes = lights.joinToString(" or ") { p ->
            val s = p.latitude - BOX_DEG_LAT / 2
            val n = p.latitude + BOX_DEG_LAT / 2
            val w = p.longitude - BOX_DEG_LON / 2
            val e = p.longitude + BOX_DEG_LON / 2
            String.format(
                Locale.ROOT,
                "geo.intersects(Locations/location, geography'POLYGON((%.6f %.6f, %.6f %.6f, %.6f %.6f, %.6f %.6f, %.6f %.6f))')",
                w, s, e, s, e, n, w, n, w, s,
            )
        }
        val since = Instant.ofEpochSecond(nowEpochSec - HISTORY_SEC).toString()
        val params = linkedMapOf(
            "\$filter" to "Datastreams/properties/serviceName eq 'HH_STA_traffic_lights' and ($boxes)",
            "\$select" to "name,properties",
            "\$top" to "200",
            "\$expand" to "Locations(\$select=location)," +
                "Datastreams(\$filter=properties/layerName eq 'primary_signal' or properties/layerName eq 'cycle_second';" +
                "\$select=name;" +
                "\$expand=Observations(\$filter=phenomenonTime gt $since;\$orderby=phenomenonTime asc;" +
                "\$select=phenomenonTime,result;\$top=150))",
        )
        val query = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        val body = get("$BASE?$query")
        return parse(JSONObject(body))
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("Hamburg TLD HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(root: JSONObject): List<LaneConnection> {
        val things = root.optJSONArray("value") ?: return emptyList()
        val result = mutableListOf<LaneConnection>()
        for (i in 0 until things.length()) {
            val thing = things.getJSONObject(i)
            val lines = thing.optJSONArray("Locations")?.optJSONObject(0)
                ?.optJSONObject("location")?.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            // [ingress lane, connection through the junction, egress lane]
            if (lines.length() != 3) continue
            val through = lines.getJSONArray(1)
            if (through.length() < 2) continue
            fun point(j: Int) = through.getJSONArray(j).let { LatLng(latitude = it.getDouble(1), longitude = it.getDouble(0)) }
            val observations = mutableListOf<SignalObservation>()
            val cycleStarts = mutableListOf<Long>()
            val streams = thing.optJSONArray("Datastreams")
            for (s in 0 until (streams?.length() ?: 0)) {
                val stream = streams!!.getJSONObject(s)
                // "Cycle second at …" reports the cycle second resetting to 0; the rest is the primary signal.
                val isCycle = stream.optString("name").startsWith("Cycle second", ignoreCase = true)
                val obs = stream.optJSONArray("Observations") ?: continue
                for (o in 0 until obs.length()) {
                    val ob = obs.getJSONObject(o)
                    val time = runCatching { Instant.parse(ob.getString("phenomenonTime")).epochSecond }.getOrNull() ?: continue
                    if (isCycle) {
                        if (ob.optInt("result", -1) == 0) cycleStarts.add(time)
                    } else {
                        observations.add(SignalObservation(time, state(ob.optInt("result", -1))))
                    }
                }
            }
            result.add(
                LaneConnection(
                    id = thing.optString("name"),
                    laneType = thing.optJSONObject("properties")?.optString("laneType").orEmpty(),
                    stopLine = point(0),
                    exit = point(through.length() - 1),
                    observations = observations,
                    cycleStarts = cycleStarts,
                ),
            )
        }
        return result
    }

    // Hamburg TLD usage guide: 1 red, 2 amber, 3 green, 4 red-amber.
    private fun state(code: Int) = when (code) {
        1 -> SignalState.RED
        2 -> SignalState.AMBER
        3 -> SignalState.GREEN
        4 -> SignalState.RED_AMBER
        else -> SignalState.OTHER
    }
}

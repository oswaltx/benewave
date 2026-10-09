package dev.greenwave.maps

import dev.greenwave.shared.tracking.LightEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The shared board ("Pinnwand", a separate server):
 *
 * - `POST {base}/v1/events`, body `{"v":1,"events":[row, …]}` with at most
 *   [MAX_PER_POST] rows in [LightEventStore]'s row format; answer 200
 *   `{"stored":n,"duplicates":m}`, 400 `{"error":"…"}` for invalid rows,
 *   413 when too large.
 * - `GET {base}/v1/events?bbox=minLat,minLon,maxLat,maxLon&since=epochSec`,
 *   box at most 1° per side; answer 200
 *   `{"v":1,"events":[row, …],"truncated":bool}`, newest first.
 *
 * Nothing identifies the rider: no account, no device id, no track; the
 * server keeps no IP and no access log. Called from [ShareWorker] only.
 */
internal object ShareClient {
    const val MAX_PER_POST = 2_000
    private const val USER_AGENT = "greenWave Maps/dev"

    data class Box(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    /** The board refused the request itself (HTTP 4xx): sending the same again will not help. */
    class Rejected(message: String) : Exception(message)

    /**
     * Sends one request of at most [MAX_PER_POST] events; returns how many the
     * board stored (duplicates excluded). Throws [Rejected] on 4xx, other
     * exceptions on network or server trouble.
     */
    fun upload(base: String, events: List<LightEvent>): Int {
        require(events.size <= MAX_PER_POST) { "at most $MAX_PER_POST events per request" }
        val rows = JSONArray().apply { events.forEach { put(LightEventStore.toRow(it)) } }
        val body = JSONObject().put("v", 1).put("events", rows).toString()
        return request("$base/v1/events", "POST", body).optInt("stored", 0)
    }

    /** Events in [box] since [sinceEpochSec]; malformed rows are skipped. Throws on any failure. */
    fun download(base: String, box: Box, sinceEpochSec: Long): List<LightEvent> {
        val bbox = String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", box.minLat, box.minLon, box.maxLat, box.maxLon)
        val answer = request("$base/v1/events?bbox=$bbox&since=$sinceEpochSec", "GET", null)
        val rows = answer.getJSONArray("events")
        return (0 until rows.length()).mapNotNull { i -> runCatching { LightEventStore.fromRow(rows.getJSONArray(i)) }.getOrNull() }
    }

    private fun request(url: String, method: String, body: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                if (code in 400..499) throw Rejected("board HTTP $code: $detail")
                error("board HTTP $code: $detail")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

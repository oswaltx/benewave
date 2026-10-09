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
 *   box at most [MAX_BOX_DEG] per side; answer 200
 *   `{"v":1,"events":[row, …],"truncated":bool}`.
 *
 * Nothing identifies the rider: no account, no device id, no track; the
 * server keeps no IP and no access log. Called from [ShareWorker] only.
 */
internal object ShareClient {
    const val MAX_PER_POST = 2_000
    const val MAX_BOX_DEG = 1.0
    private const val USER_AGENT = "greenWave Maps/dev"

    data class Box(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    /** Sends [events] in chunks; returns how many the server stored (duplicates excluded). Throws on any failure. */
    fun upload(base: String, events: List<LightEvent>): Int {
        var stored = 0
        for (chunk in events.chunked(MAX_PER_POST)) {
            val rows = JSONArray().apply { chunk.forEach { put(LightEventStore.toRow(it)) } }
            val body = JSONObject().put("v", 1).put("events", rows).toString()
            val answer = request("$base/v1/events", "POST", body)
            stored += answer.optInt("stored", 0)
        }
        return stored
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
                error("board HTTP $code: $detail")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

package dev.greenwave.maps

import android.util.Log
import dev.greenwave.shared.sharing.SourcedEvent
import dev.greenwave.shared.sharing.Tile
import dev.greenwave.shared.tracking.LightEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The shared board ("Pinnwand", a separate server):
 *
 * - `POST {base}/v1/device`: answer 200 `{"device":"id-issued.sig"}`, a random
 *   token with its issue time, signed by the board; 429 when too many devices
 *   registered lately. The board shows a token's events only once it is a
 *   week old.
 * - `POST {base}/v1/events`, body `{"v":1,"device":"id.sig","events":[row, …]}`
 *   with at most [MAX_PER_POST] rows in [LightEventStore]'s row format; answer
 *   200 `{"stored":n,"duplicates":m}`, 400 `{"error":"…"}` for invalid or
 *   implausible rows, 401 for an unknown token, 413 when too large, 429 over
 *   the limit per device.
 * - `GET {base}/v1/events?tile=latIdx,lonIdx&since=epochSec`, one fixed tile of
 *   [SharedEvents.TILE_DEG]; answer 200 `{"v":1,"events":[row + [src, cell], …],"truncated":bool}`,
 *   newest first; src names the source (one device), renamed per answer and
 *   ~100 m cell, so it counts devices at a junction without linking ways.
 *
 * Nothing identifies the rider: no account, no track; the token is random and
 * forgotten when sharing is switched off; the server keeps no IP and no access
 * log. Called from [ShareWorker] only.
 */
internal object ShareClient {
    const val MAX_PER_POST = 2_000
    private const val USER_AGENT = "greenWave Maps/dev"

    /** The board refused the content itself (HTTP 400 invalid rows, 413 too large): sending the same again will not help. */
    class Rejected(message: String) : Exception(message)

    /** The board does not know the device token (HTTP 401): register again. */
    class Unauthorized(message: String) : Exception(message)

    /** A new device token from the board. Throws on any failure. */
    fun register(base: String): String {
        val token = request("$base/v1/device", "POST", "").getString("device")
        require(Regex("[0-9a-f]{32}-[0-9]{1,12}\\.[0-9a-f]{32}").matches(token)) { "unexpected device token" }
        return token
    }

    /**
     * Sends one request of at most [MAX_PER_POST] events; returns how many the
     * board stored (duplicates excluded). Throws [Rejected] on 400/413, other
     * exceptions on network or server trouble.
     */
    fun upload(base: String, device: String, events: List<LightEvent>): Int {
        require(events.size <= MAX_PER_POST) { "at most $MAX_PER_POST events per request" }
        val rows = JSONArray().apply { events.forEach { put(LightEventStore.toRow(it)) } }
        val body = JSONObject().put("v", 1).put("device", device).put("events", rows).toString()
        return request("$base/v1/events", "POST", body).optInt("stored", 0)
    }

    /**
     * Events in [tile] since [sinceEpochSec] with their source (names hold only
     * within one answer, so the tile is their scope);
     * malformed rows and rows without a source are skipped. Throws on any failure.
     */
    fun download(base: String, tile: Tile, sinceEpochSec: Long): List<SourcedEvent> {
        val answer = request("$base/v1/events?tile=${tile.latIdx},${tile.lonIdx}&since=$sinceEpochSec", "GET", null)
        val rows = answer.getJSONArray("events")
        if (answer.optBoolean("truncated")) Log.w("GreenWave", "board answer for tile $tile truncated, older events missing")
        return (0 until rows.length()).mapNotNull { i ->
            runCatching {
                val row = rows.getJSONArray(i)
                SourcedEvent(LightEventStore.fromRow(row), source = row.getString(7), scope = "${tile.latIdx},${tile.lonIdx}:${row.getString(8)}")
            }.getOrNull()
        }
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
                // Only the board's own content errors; 401/403/404/408/429 (proxy, wrong address) keep the queue for a retry.
                if (code == 400 || code == 413) throw Rejected("board HTTP $code: $detail")
                if (code == 401) throw Unauthorized("board HTTP $code: $detail")
                error("board HTTP $code: $detail")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

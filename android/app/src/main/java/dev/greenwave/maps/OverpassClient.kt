package dev.greenwave.maps

import dev.greenwave.shared.geo.LatLng
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Overpass client for `highway=traffic_signals` nodes. Blocking —
 * call it off the main thread. Throws on network or HTTP errors.
 */
internal object OverpassClient {
    // The main instance answers 504 when busy; retry once, then try mirrors.
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    // Overpass' usage policy asks clients to identify themselves; a generic
    // library User-Agent also gets 406 from the public instance.
    private const val USER_AGENT = "greenWave Maps/dev (privates Hobbyprojekt)"

    fun trafficSignals(query: String): List<LatLng> {
        var lastError: Exception? = null
        for ((attempt, endpoint) in ENDPOINTS.withIndex()) {
            if (attempt > 0) Thread.sleep(2_000L * attempt)
            try {
                return request(endpoint, query)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("no Overpass endpoint")
    }

    private fun request(endpoint: String, query: String): List<LatLng> {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use {
                it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray())
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("Overpass HTTP $code from $endpoint")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val elements = JSONObject(body).getJSONArray("elements")
            return List(elements.length()) { i ->
                val e = elements.getJSONObject(i)
                LatLng(latitude = e.getDouble("lat"), longitude = e.getDouble("lon"))
            }
        } finally {
            connection.disconnect()
        }
    }
}

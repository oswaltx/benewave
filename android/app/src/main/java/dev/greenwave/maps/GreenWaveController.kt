package dev.greenwave.maps

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.routing.RoutingController
import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.data.RandomTrafficTimingGenerator
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.geo.distanceToPath
import dev.greenwave.shared.guidance.RideGuidance
import dev.greenwave.shared.guidance.RideGuidanceCalculator
import dev.greenwave.shared.location.LocationUpdate
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.routing.TrafficLightsAlongRoute
import java.util.concurrent.Executors

/**
 * Glue between CoMaps' routing and the greenWave logic: whenever a bicycle
 * route is built it looks up the traffic lights along it (Overpass), and on
 * every location fix it computes the speed advice for the navigation panel.
 *
 * Signal timings are not known yet: until measured rhythms exist every light
 * gets a deterministic placeholder program ([RandomTrafficTimingGenerator]),
 * exactly like the standalone greenWave app.
 */
object GreenWaveController {
    private const val TAG = "GreenWave"

    /** Only lights this far ahead count for the advice; farther ones are too uncertain. */
    private const val LOOKAHEAD_METERS = 2_000.0

    /** Re-query Overpass only if this share of a new route leaves the area already covered. */
    private const val UNCOVERED_SHARE_FOR_REQUERY = 0.15

    enum class LightsStatus { NO_ROUTE, LOADING, READY, FAILED }

    data class State(val status: LightsStatus, val lightCount: Int, val guidance: RideGuidance?)

    @Volatile private var route: Route? = null
    @Volatile var lightsStatus: LightsStatus = LightsStatus.NO_ROUTE
        private set

    // Everything Overpass returned so far and the paths those queries covered,
    // so a reroute near the old route doesn't hit the public server again.
    private val knownSignals = LinkedHashSet<LatLng>()
    private val coveredPaths = mutableListOf<List<LatLng>>()
    private var requestedRevision = -1

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false

    var speedRange = SpeedRangeKmh(min = 10.0, max = 25.0)

    fun init() {
        if (initialized) return
        initialized = true
        RoutingController.get().addRouteChangedListener { onRouteChanged() }
        onRouteChanged()
    }

    private fun onRouteChanged() {
        val controller = RoutingController.get()
        val geometry = controller.routeGeometry
        if (geometry.pointCount < 2 || controller.lastRouterType != Router.Bicycle) {
            route = null
            lightsStatus = LightsStatus.NO_ROUTE
            return
        }
        if (geometry.mRevision == requestedRevision) return
        requestedRevision = geometry.mRevision
        val path = geometry.mJunctions.map { LatLng(latitude = it.mLat, longitude = it.mLon) }
        route = Route(path = path, trafficLights = emptyList())
        lightsStatus = LightsStatus.LOADING
        val revision = geometry.mRevision
        worker.execute { loadLights(path, revision) }
    }

    /** Runs on [worker]. */
    private fun loadLights(path: List<LatLng>, revision: Int) {
        val status = try {
            if (needsQuery(path)) {
                val signals = OverpassClient.trafficSignals(TrafficLightsAlongRoute.overpassQuery(path))
                knownSignals.addAll(signals)
                coveredPaths.add(path)
            }
            val lights = TrafficLightsAlongRoute.select(path, knownSignals.toList())
                .map { RandomTrafficTimingGenerator.deterministic(it) }
            main.post {
                if (revision == requestedRevision) route = Route(path = path, trafficLights = lights)
            }
            Log.i(TAG, "route rev $revision: ${lights.size} traffic lights")
            LightsStatus.READY
        } catch (e: Exception) {
            Log.w(TAG, "traffic light lookup failed", e)
            LightsStatus.FAILED
        }
        main.post { if (revision == requestedRevision) lightsStatus = status }
    }

    private fun needsQuery(path: List<LatLng>): Boolean {
        if (coveredPaths.isEmpty()) return true
        val step = maxOf(1, path.size / 100)
        val sample = path.filterIndexed { i, _ -> i % step == 0 }
        val uncovered = sample.count { p ->
            coveredPaths.none { distanceToPath(it, p) < TrafficLightsAlongRoute.MAX_DISTANCE_TO_ROUTE_METERS }
        }
        return uncovered > sample.size * UNCOVERED_SHARE_FOR_REQUERY
    }

    /** Current advice for [location]; call on the main thread. */
    fun state(location: Location?): State {
        val current = route ?: return State(LightsStatus.NO_ROUTE, 0, null)
        if (location == null || lightsStatus != LightsStatus.READY) {
            return State(lightsStatus, current.trafficLights.size, null)
        }
        val update = LocationUpdate(
            position = LatLng(latitude = location.latitude, longitude = location.longitude),
            epochSec = location.time / 1000,
            speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
        )
        val progress = distanceAlongPath(current.path, update.position)
        val ahead = current.trafficLights.filter {
            val d = distanceAlongPath(current.path, it.position) - progress
            d > 0 && d <= LOOKAHEAD_METERS
        }
        val guidance = RideGuidanceCalculator.compute(current.copy(trafficLights = ahead), update, speedRange)
        return State(LightsStatus.READY, current.trafficLights.size, guidance)
    }
}

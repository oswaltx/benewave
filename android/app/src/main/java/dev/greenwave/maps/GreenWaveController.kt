package dev.greenwave.maps

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import dev.greenwave.shared.model.TrafficLight
import dev.greenwave.shared.routing.TrafficLightsAlongRoute
import dev.greenwave.shared.tracking.StopDetector
import dev.greenwave.shared.tracking.StopEventMatcher
import dev.greenwave.shared.tracking.TrafficLightRhythmEstimator
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Glue between CoMaps' routing and the greenWave logic: whenever a bicycle
 * route is built it looks up the traffic lights along it (Overpass), and on
 * every location fix it computes the speed advice for the navigation panel.
 *
 * While navigating, every fix also goes through a [StopDetector]; detected stops
 * are logged on the device and, once a light has enough of them, its measured
 * cycle ([TrafficLightRhythmEstimator]) replaces the placeholder program
 * ([RandomTrafficTimingGenerator]) that every light starts with.
 */
object GreenWaveController {
    private const val TAG = "GreenWave"

    /** Only lights this far ahead count for the advice; farther ones are too uncertain. */
    private const val LOOKAHEAD_METERS = 2_000.0

    /** Re-query Overpass only if this share of a new route leaves the area already covered. */
    private const val UNCOVERED_SHARE_FOR_REQUERY = 0.15

    /** Measured rhythms below this confidence are not trusted over the placeholder. */
    private const val MIN_CONFIDENCE = 0.5

    enum class LightsStatus { NO_ROUTE, LOADING, READY, FAILED }

    data class State(
        val status: LightsStatus,
        val lightCount: Int,
        val learnedCount: Int,
        val guidance: RideGuidance?,
    )

    @Volatile private var route: Route? = null
    @Volatile private var learnedCount = 0
    @Volatile var lightsStatus: LightsStatus = LightsStatus.NO_ROUTE
        private set

    // Everything Overpass returned so far and the paths those queries covered,
    // so a reroute near the old route doesn't hit the public server again.
    private val knownSignals = LinkedHashSet<LatLng>()
    private val coveredPaths = mutableListOf<List<LatLng>>()
    private var requestedRevision = -1

    // Worker-only state for learning.
    private var store: StopEventStore? = null
    private val stopDetector = StopDetector()
    private var lightPositions: List<LatLng> = emptyList()
    private var lightsPath: List<LatLng> = emptyList()
    private var lights: List<TrafficLight> = emptyList()
    private var learnedPositions: Set<LatLng> = emptySet()
    private var rideLog: RideLog? = null
    private var ride: RideBuilder? = null

    /** Worker-only accumulator for the bicycle navigation in progress. */
    private class RideBuilder(val startEpochSec: Long) {
        var lastEpochSec = startEpochSec
        val movingSpeeds = mutableListOf<Double>()
        val passed = mutableListOf<PassedLight>()
        var redPasses = 0
        var lastProgress: Double? = null
    }

    /** A ride shorter than this is not logged or rated. */
    private const val MIN_RIDE_SEC = 120L

    private var settings: GreenWaveSettings? = null
    @Volatile private var profileRange: SpeedRangeKmh? = null
    private var host: WeakReference<Context>? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false

    /** Manual range, or the rider profile if enabled and there is enough data. */
    val speedRange: SpeedRangeKmh
        get() {
            val s = settings ?: return SpeedRangeKmh(min = 10.0, max = 25.0)
            return (if (s.autoProfile) profileRange else null) ?: s.manualRange
        }

    internal fun settings(): GreenWaveSettings? = settings

    fun init(context: Context) {
        host = WeakReference(context)
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        settings = GreenWaveSettings(appContext)
        worker.execute {
            store = StopEventStore(appContext)
            rideLog = RideLog(appContext).also { profileRange = it.profileRange() }
        }
        RoutingController.get().addRouteChangedListener { onRouteChanged() }
        onRouteChanged()
    }

    private fun onRouteChanged() {
        val controller = RoutingController.get()
        val geometry = controller.routeGeometry
        if (geometry.pointCount < 2 || controller.lastRouterType != Router.Bicycle) {
            val wasActive = route != null
            route = null
            lightsStatus = LightsStatus.NO_ROUTE
            requestedRevision = -1
            if (wasActive) worker.execute { finishRide() }
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
        lightPositions = emptyList()
        val status = try {
            if (needsQuery(path)) {
                val signals = OverpassClient.trafficSignals(TrafficLightsAlongRoute.overpassQuery(path))
                knownSignals.addAll(signals)
                coveredPaths.add(path)
            }
            lightsPath = path
            lightPositions = TrafficLightsAlongRoute.select(path, knownSignals.toList())
            publishRoute(revision)
            Log.i(TAG, "route rev $revision: ${lightPositions.size} traffic lights")
            LightsStatus.READY
        } catch (e: Exception) {
            Log.w(TAG, "traffic light lookup failed", e)
            LightsStatus.FAILED
        }
        main.post { if (revision == requestedRevision) lightsStatus = status }
    }

    /** Worker: combine current light positions with what has been learned so far. */
    private fun publishRoute(revision: Int) {
        val events = store?.all().orEmpty()
        val learnedSet = mutableSetOf<LatLng>()
        val lights = lightPositions.map { position ->
            val stops = events.filter { StopEventMatcher.match(it.position, lightPositions) == position }
            val estimate = TrafficLightRhythmEstimator.estimate(stops)
            if (estimate != null && estimate.confidence >= MIN_CONFIDENCE) {
                learnedSet.add(position)
                TrafficLight(
                    position = position,
                    cycleLengthSec = estimate.cycleLengthSec,
                    greenStartOffsetSec = estimate.greenStartOffsetSec,
                    greenDurationSec = estimate.greenDurationSec,
                )
            } else {
                RandomTrafficTimingGenerator.deterministic(position)
            }
        }
        this.lights = lights
        learnedPositions = learnedSet
        val path = lightsPath
        val count = learnedSet.size
        main.post {
            if (revision == requestedRevision) {
                route = Route(path = path, trafficLights = lights)
                learnedCount = count
            }
        }
    }

    /**
     * Every fix while navigating (from NavigationService, so also with the
     * screen off). Feeds the stop detector on bicycle routes only.
     */
    fun onRideLocation(location: Location) {
        if (route == null) return
        val update = location.toUpdate()
        val revision = requestedRevision
        worker.execute {
            trackRide(update)
            val event = stopDetector.onUpdate(update) ?: return@execute
            val log = store ?: return@execute
            log.append(event)
            Log.i(TAG, "stop ${event.durationSec}s at ${event.position}")
            if (lightPositions.isNotEmpty()) publishRoute(revision)
        }
    }

    /** Worker: rider profile samples, lights passed, pass-throughs on predicted red. */
    private fun trackRide(update: LocationUpdate) {
        val current = ride ?: RideBuilder(update.epochSec).also { ride = it }
        current.lastEpochSec = update.epochSec
        if (update.speedKmh >= RideLog.MOVING_KMH) current.movingSpeeds.add(update.speedKmh)
        if (lightsPath.size < 2) return
        val progress = distanceAlongPath(lightsPath, update.position)
        val previous = current.lastProgress
        current.lastProgress = progress
        if (previous == null || progress <= previous) return
        for (light in lights) {
            val at = distanceAlongPath(lightsPath, light.position)
            if (at <= previous || at > progress) continue
            if (current.passed.any { it.position == light.position }) continue
            val learned = light.position in learnedPositions
            current.passed.add(PassedLight(light.position, at, learned))
            if (learned && update.speedKmh > RED_PASS_MIN_KMH && !isGreen(light, update.epochSec)) {
                current.redPasses++
            }
        }
    }

    private const val RED_PASS_MIN_KMH = 10.0

    private fun isGreen(light: TrafficLight, epochSec: Long): Boolean {
        val c = light.cycleLengthSec
        val pos = (((epochSec - light.greenStartOffsetSec) % c) + c) % c
        return pos < light.greenDurationSec
    }

    /** Worker: navigation ended — log the ride and ask for a rating. */
    private fun finishRide() {
        val done = ride ?: return
        ride = null
        lights = emptyList()
        lightsPath = emptyList()
        if (done.lastEpochSec - done.startEpochSec < MIN_RIDE_SEC) return
        val entry = Ride(
            startEpochSec = done.startEpochSec,
            endEpochSec = done.lastEpochSec,
            movingSpeedsKmh = done.movingSpeeds,
            passedLights = done.passed,
            redPasses = done.redPasses,
            riderRunsRed = settings?.runsRed ?: false,
        )
        val log = rideLog ?: return
        log.add(entry)
        profileRange = log.profileRange()
        Log.i(TAG, "ride logged: ${entry.passedLights.size} lights, ${entry.redPasses} red passes")
        main.post { hostActivity()?.let { GreenWaveDialogs.showRating(it, entry) } }
    }

    /** Worker-side persistence for the rating dialog. */
    internal fun saveRating(ride: Ride, rating: Int?, wrongLights: List<LatLng>) {
        worker.execute { rideLog?.rate(ride.startEpochSec, rating, wrongLights) }
    }

    private fun hostActivity(): Activity? {
        var c = host?.get()
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val activity = c as? Activity ?: return null
        return if (activity.isFinishing || activity.isDestroyed) null else activity
    }

    private fun Location.toUpdate() = LocationUpdate(
        position = LatLng(latitude = latitude, longitude = longitude),
        epochSec = time / 1000,
        speedKmh = if (hasSpeed()) speed * 3.6 else 0.0,
        bearingDegrees = if (hasBearing()) bearing else null,
    )

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
        val current = route ?: return State(LightsStatus.NO_ROUTE, 0, 0, null)
        if (location == null || lightsStatus != LightsStatus.READY) {
            return State(lightsStatus, current.trafficLights.size, learnedCount, null)
        }
        val update = location.toUpdate()
        val progress = distanceAlongPath(current.path, update.position)
        val ahead = current.trafficLights.filter {
            val d = distanceAlongPath(current.path, it.position) - progress
            d > 0 && d <= LOOKAHEAD_METERS
        }
        val guidance = RideGuidanceCalculator.compute(current.copy(trafficLights = ahead), update, speedRange)
        return State(LightsStatus.READY, current.trafficLights.size, learnedCount, guidance)
    }
}

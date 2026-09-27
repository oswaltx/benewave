package dev.greenwave.maps

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.routing.RoutingController
import dev.greenwave.shared.algorithm.SpeedOptions
import dev.greenwave.shared.algorithm.SpeedOptionsCalculator
import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.data.RandomTrafficTimingGenerator
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.geo.distanceToPath
import dev.greenwave.shared.live.LaneConnectionMatcher
import dev.greenwave.shared.live.LiveSignalCycle
import dev.greenwave.shared.location.LocationUpdate
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import dev.greenwave.shared.routing.TrafficLightsAlongRoute
import dev.greenwave.shared.tracking.StopDetector
import dev.greenwave.shared.tracking.StopEvent
import dev.greenwave.shared.tracking.StopEventMatcher
import dev.greenwave.shared.tracking.TrafficLightRhythmEstimator
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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

    /** Waits before re-trying a failed Overpass lookup on the same route. */
    private val RETRY_DELAYS_MS = longArrayOf(30_000, 60_000, 120_000, 300_000)

    enum class LightsStatus { NO_ROUTE, LOADING, READY, FAILED }

    data class State(
        val status: LightsStatus,
        val lightCount: Int,
        val learnedCount: Int,
        val advice: Advice?,
        /** Of [learnedCount], lights whose timing comes from a live source (Hamburg). */
        val liveCount: Int = 0,
    )

    /**
     * What the panel and notification show for the next light. Without a learned
     * cycle for it ([nextLightKnown] false) there is deliberately no speed:
     * the placeholder timing would only be a guess.
     */
    data class Advice(
        val nextLightDistanceMeters: Double?,
        val nextLightKnown: Boolean,
        /** Null if there is no light ahead or it is not known. */
        val options: SpeedOptions?,
    )

    @Volatile private var route: Route? = null
    @Volatile private var learnedCount = 0
    @Volatile private var liveCount = 0
    /** Worker-only: timing from a live source per light position, refreshed every [LIVE_REFRESH_MS]. */
    private var liveLights: Map<LatLng, TrafficLight> = emptyMap()
    private const val LIVE_REFRESH_MS = 5 * 60_000L
    private const val LIVE_BATCH = 8
    /** Learned lights of [route] (main thread copy of [learnedPositions]). */
    @Volatile private var routeLearned: Set<LatLng> = emptySet()
    @Volatile var lightsStatus: LightsStatus = LightsStatus.NO_ROUTE
        private set

    // Everything Overpass returned so far and the paths those queries covered,
    // so a reroute near the old route doesn't hit the public server again.
    private val knownSignals = LinkedHashSet<LatLng>()
    private val coveredPaths = mutableListOf<List<LatLng>>()
    private var requestedRevision = -1
    private var failedAttempts = 0

    // Worker-only state for learning.
    private var store: StopEventStore? = null
    private val stopDetector = StopDetector()
    private var lightPositions: List<LatLng> = emptyList()
    private var lightsPath: List<LatLng> = emptyList()
    private var lights: List<TrafficLight> = emptyList()
    private var learnedPositions: Set<LatLng> = emptySet()
    private var rideLog: RideLog? = null
    private var knownLights: KnownLights? = null
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

    // Tracking mode (GreenWaveTrackingService): worker-only except the counters.
    private var track: TrackFile? = null
    /** True if [track] was opened by a navigation (no tracking mode running). */
    private var trackByNav = false
    /** Latest suggested bicycle route, written next to the track once one is open. */
    private var suggestedRoute: JSONObject? = null
    private var appContext: Context? = null
    @Volatile var trackingStops = 0
        private set
    /** Lights at which a stop was registered during this tracking. */
    private val trackingLightSet = mutableSetOf<LatLng>()
    @Volatile var trackingLights = 0
        private set
    /** Short line about the last stop, e.g. "Ampel registriert · 3 Stopps dort". */
    @Volatile var trackingLastEvent: String? = null
        private set
    /** Set by [GreenWaveTrackingService]; runs on main after each stop. */
    @Volatile var onTrackingEvent: (() -> Unit)? = null
    @Volatile private var profileRange: SpeedRangeKmh? = null

    /** For the settings dialog: how much has been logged on this device. */
    @Volatile var loggedStops = 0
        private set
    @Volatile var loggedRides = 0
        private set
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
        this.appContext = appContext
        settings = GreenWaveSettings(appContext)
        worker.execute {
            store = StopEventStore(appContext).also { loggedStops = it.all().size }
            knownLights = KnownLights(appContext)
            rideLog = RideLog(appContext).also {
                profileRange = it.profileRange()
                loggedRides = it.all().size
            }
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
            if (wasActive) Framework.nativeClearGreenWaveMarks()
            lightsStatus = LightsStatus.NO_ROUTE
            requestedRevision = -1
            if (wasActive) worker.execute { suggestedRoute = null; finishRide() }
            return
        }
        if (geometry.mRevision == requestedRevision) return
        requestedRevision = geometry.mRevision
        failedAttempts = 0
        val path = geometry.mJunctions.map { LatLng(latitude = it.mLat, longitude = it.mLon) }
        route = Route(path = path, trafficLights = emptyList())
        lightsStatus = LightsStatus.LOADING
        val revision = geometry.mRevision
        val suggestion = suggestedRouteJson(path, controller.cachedRoutingInfo?.totalTimeInSeconds)
        worker.execute {
            suggestedRoute = suggestion
            track?.addRoute(suggestion)
            loadLights(path, revision)
        }
    }

    /** The route as the router suggested it: when, its ETA and length, and the path. */
    private fun suggestedRouteJson(path: List<LatLng>, etaSec: Int?): JSONObject {
        val coords = JSONArray()
        path.forEach { coords.put(it.latitude).put(it.longitude) }
        var length = 0.0
        for (i in 1 until path.size) length += distanceMeters(path[i - 1], path[i])
        return JSONObject()
            .put("epoch_s", System.currentTimeMillis() / 1000)
            .put("eta_s", etaSec ?: JSONObject.NULL)
            .put("length_m", length.roundToInt())
            .put("path_latlon", coords)
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
            knownLights?.addAll(lightPositions)
            liveLights = emptyMap()
            publishRoute(revision)
            Log.i(TAG, "route rev $revision: ${lightPositions.size} traffic lights")
            refreshLive(revision)
            LightsStatus.READY
        } catch (e: Exception) {
            Log.w(TAG, "traffic light lookup failed", e)
            LightsStatus.FAILED
        }
        main.post {
            if (revision != requestedRevision) return@post
            lightsStatus = status
            if (status == LightsStatus.FAILED) scheduleRetry(path, revision)
        }
    }

    /** Main thread: retry a failed lookup while the same route is still active. */
    private fun scheduleRetry(path: List<LatLng>, revision: Int) {
        val delay = RETRY_DELAYS_MS.getOrNull(failedAttempts) ?: RETRY_DELAYS_MS.last()
        failedAttempts++
        Log.i(TAG, "retry traffic light lookup in ${delay / 1000}s (attempt $failedAttempts)")
        main.postDelayed({
            if (revision == requestedRevision && lightsStatus == LightsStatus.FAILED) {
                lightsStatus = LightsStatus.LOADING
                worker.execute { loadLights(path, revision) }
            }
        }, delay)
    }

    /**
     * Worker: timing from Hamburg's live signal data for the route's lights in
     * Hamburg, then again every [LIVE_REFRESH_MS] while the route is active.
     * A failed request keeps the previous values.
     */
    private fun refreshLive(revision: Int) {
        val positions = lightPositions.filter { HamburgSignalsClient.covers(it) }
        if (positions.isEmpty()) return
        val path = lightsPath
        val now = System.currentTimeMillis() / 1000
        val found = mutableMapOf<LatLng, TrafficLight>()
        try {
            for (batch in positions.chunked(LIVE_BATCH)) {
                val connections = HamburgSignalsClient.laneConnections(batch, now)
                for (position in batch) {
                    val lane = LaneConnectionMatcher.match(path, position, connections) ?: continue
                    LiveSignalCycle.estimate(position, lane.observations, now, lane.cycleStarts)?.let { found[position] = it }
                }
            }
            liveLights = found
            Log.i(TAG, "live timing for ${found.size} of ${positions.size} Hamburg lights")
            publishRoute(revision)
        } catch (e: Exception) {
            Log.w(TAG, "Hamburg live signals failed", e)
        }
        main.postDelayed({
            if (revision == requestedRevision) worker.execute { if (revision == requestedRevision) refreshLive(revision) }
        }, LIVE_REFRESH_MS)
    }

    /** Worker: combine current light positions with what has been learned so far. */
    private fun publishRoute(revision: Int) {
        val events = store?.all().orEmpty()
        val learnedSet = mutableSetOf<LatLng>()
        val live = liveLights
        val lights = lightPositions.map { position ->
            live[position]?.let {
                learnedSet.add(position)
                return@map it
            }
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
        val liveNow = lightPositions.count { it in live }
        main.post {
            if (revision == requestedRevision) {
                route = Route(path = path, trafficLights = lights)
                routeLearned = learnedSet
                learnedCount = count
                liveCount = liveNow
                showMarks(lights, learnedSet)
            }
        }
    }

    /**
     * Every fix while navigating (from NavigationService, so also with the
     * screen off). Feeds the stop detector on bicycle routes only.
     */
    fun onRideLocation(location: Location) {
        if (route == null || settings?.loggingEnabled == false) return
        val update = location.toUpdate()
        val revision = requestedRevision
        val fix = Location(location)
        worker.execute {
            if (track == null) openNavTrack(update.epochSec)
            if (trackByNav) track?.append(fix)
            trackRide(update)
            val event = stopDetector.onUpdate(update) ?: return@execute
            val log = store ?: return@execute
            log.append(event)
            loggedStops = log.all().size
            Log.i(TAG, "stop ${event.durationSec}s at ${event.position}")
            if (lightPositions.isNotEmpty()) publishRoute(revision)
            val light = StopEventMatcher.match(event.position, lightPositions) ?: lookUpLightNear(event.position)
            val text = describeStop(event, light)
            navEvent = text to System.currentTimeMillis()
            main.post { hostActivity()?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() } }
        }
    }

    /** Worker: navigation without tracking mode also records its track, next to the suggested route. */
    private fun openNavTrack(epochSec: Long) {
        val context = appContext ?: return
        track = TrackFile(context, epochSec).also { t -> suggestedRoute?.let { t.addRoute(it) } }
        trackByNav = true
        Log.i(TAG, "navigation track started: ${track?.file?.name}")
    }

    /** Last stop during navigation and when it happened; shown in the notification for a while. */
    @Volatile private var navEvent: Pair<String, Long>? = null
    private const val NAV_EVENT_SHOW_MS = 20_000L

    /** Worker: one line for a registered stop, e.g. "Ampel registriert (35 s) · 3 Stopps dort". */
    private fun describeStop(event: StopEvent, light: LatLng?): String {
        if (light == null) return "Stopp ohne Ampel (${event.durationSec} s)"
        val stopsThere = store?.all().orEmpty().count { StopEventMatcher.match(it.position, listOf(light)) == light }
        return "Ampel registriert (${event.durationSec} s) · $stopsThere Stopps dort"
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
    private fun finishRide(askRating: Boolean = true) {
        if (trackByNav) {
            track?.close()
            track = null
            trackByNav = false
        }
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
        loggedRides = log.all().size
        profileRange = log.profileRange()
        Log.i(TAG, "ride logged: ${entry.passedLights.size} lights, ${entry.redPasses} red passes")
        if (askRating) main.post { hostActivity()?.let { GreenWaveDialogs.showRating(it, entry) } }
    }

    /** One row of the "Ampeldaten" list. */
    data class LightStats(
        val position: LatLng,
        val stops: Int,
        val cycleSec: Int?,
        val greenSec: Int?,
        val confidence: Double?,
        val trusted: Boolean,
    )

    /** All lights ever on a route with what has been learned about them; [onResult] runs on main. */
    fun lightStats(onResult: (List<LightStats>) -> Unit) {
        worker.execute {
            val positions = knownLights?.all().orEmpty()
            val events = store?.all().orEmpty()
            val grouped = events.groupBy { StopEventMatcher.match(it.position, positions) }
            val stats = positions.map { position ->
                val stops = grouped[position].orEmpty()
                val estimate = TrafficLightRhythmEstimator.estimate(stops)
                LightStats(
                    position = position,
                    stops = stops.size,
                    cycleSec = estimate?.cycleLengthSec,
                    greenSec = estimate?.greenDurationSec,
                    confidence = estimate?.confidence,
                    trusted = estimate != null && estimate.confidence >= MIN_CONFIDENCE,
                )
            }.sortedWith(compareByDescending<LightStats> { it.trusted }.thenByDescending { it.stops })
            main.post { onResult(stats) }
        }
    }

    /** Tracking mode started (from [GreenWaveTrackingService], main thread). */
    fun startTracking(context: Context) {
        init(context)
        trackingStops = 0
        trackingLights = 0
        trackingLastEvent = null
        val appContext = context.applicationContext
        worker.execute {
            track?.close()
            trackingLightSet.clear()
            trackByNav = false
            track = TrackFile(appContext, System.currentTimeMillis() / 1000)
                .also { t -> if (route != null) suggestedRoute?.let { t.addRoute(it) } }
            Log.i(TAG, "tracking started: ${track?.file?.name}")
        }
    }

    /**
     * A fix in tracking mode. While a bicycle navigation runs, NavigationService
     * already feeds [onRideLocation]; here only the raw track is written then.
     */
    fun onTrackLocation(location: Location) {
        val navigating = route != null
        val update = location.toUpdate()
        worker.execute {
            track?.append(location)
            if (navigating) return@execute
            trackRide(update)
            val event = stopDetector.onUpdate(update) ?: return@execute
            store?.append(event)
            loggedStops = store?.all()?.size ?: loggedStops
            trackingStops++
            Log.i(TAG, "tracking stop ${event.durationSec}s at ${event.position}")
            val light = lookUpLightNear(event.position)
            if (light != null) {
                trackingLightSet.add(light)
                trackingLights = trackingLightSet.size
            }
            trackingLastEvent = describeStop(event, light)
            main.post { onTrackingEvent?.invoke() }
        }
    }

    /** Tracking mode ended: close the track, log the ride (no rating, there was no advice). */
    fun stopTracking() {
        worker.execute {
            track?.close()
            Log.i(TAG, "tracking stopped: ${track?.file?.name}")
            track = null
            if (route == null) finishRide(askRating = false)
        }
    }

    /**
     * Worker: without a route nobody asked Overpass about this spot, so look for
     * a signal next to the stop and remember it; then the stop counts for that
     * light in the "Ampeldaten" list and on later routes.
     */
    private fun lookUpLightNear(position: LatLng): LatLng? {
        val known = knownLights ?: return null
        nearest(known.all(), position)?.let { return it }
        try {
            val signals = OverpassClient.trafficSignals(TrafficLightsAlongRoute.overpassQuery(listOf(position, position)))
            known.addAll(signals)
        } catch (e: Exception) {
            Log.w(TAG, "traffic light lookup at stop failed", e)
        }
        return nearest(known.all(), position)
    }

    private fun nearest(lights: List<LatLng>, position: LatLng): LatLng? = lights
        .map { it to distanceMeters(it, position) }
        .filter { it.second < TrafficLightsAlongRoute.MAX_DISTANCE_TO_ROUTE_METERS }
        .minByOrNull { it.second }?.first

    /** "Meine Fahrten": every recorded track, newest first; [onResult] runs on main. */
    internal fun rideSummaries(onResult: (List<RideSummary>) -> Unit) {
        val context = appContext ?: return
        worker.execute {
            val list = runCatching {
                RideHistory.summaries(
                    context, store?.all().orEmpty(), rideLog?.all().orEmpty(), knownLights?.all().orEmpty(),
                )
            }.onFailure { Log.w(TAG, "reading rides failed", it) }.getOrDefault(emptyList())
            main.post { onResult(list) }
        }
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

    /**
     * Adds the speed advice to CoMaps' navigation notification (header line and
     * colour), so it is visible on the lock screen and over other apps.
     * [defaultColor] restores CoMaps' colour when there is no advice.
     */
    fun decorateNotification(builder: NotificationCompat.Builder, location: Location, defaultColor: Int) {
        navEvent?.let { (text, at) ->
            if (System.currentTimeMillis() - at < NAV_EVENT_SHOW_MS) {
                builder.setSubText(text).setColor(NOTIFICATION_GREEN)
                return
            }
        }
        val advice = state(location).advice
        val distance = advice?.nextLightDistanceMeters ?: run {
            builder.setSubText(null).setColor(defaultColor)
            return
        }
        val where = "Ampel ${(distance / 10).roundToInt() * 10} m"
        val options = advice.options
        when {
            !advice.nextLightKnown -> builder.setSubText("? $where unbekannt").setColor(defaultColor)
            options?.holdKmh != null -> builder.setSubText("✓ halten · $where").setColor(NOTIFICATION_GREEN)
            options != null && (options.slowerKmh != null || options.fasterKmh != null) ->
                builder.setSubText("${speedPair(options)} · $where").setColor(NOTIFICATION_AMBER)
            else -> builder.setSubText("– kein Grün erreichbar · $where").setColor(defaultColor)
        }
    }

    /** "▼ 16 | ▲ 20 km/h", leaving out a missing side. */
    internal fun speedPair(options: SpeedOptions): String = listOfNotNull(
        options.slowerKmh?.let { "▼ ${it.roundToInt()}" },
        options.fasterKmh?.let { "▲ ${it.roundToInt()}" },
    ).joinToString(" | ") + " km/h"

    private val NOTIFICATION_GREEN = 0xFF2E7D32.toInt()
    private val NOTIFICATION_AMBER = 0xFFE08E0B.toInt()

    private fun Location.toUpdate() = LocationUpdate(
        position = LatLng(latitude = latitude, longitude = longitude),
        epochSec = time / 1000,
        speedKmh = if (hasSpeed()) speed * 3.6 else 0.0,
        bearingDegrees = if (hasBearing()) bearing else null,
    )

    /** Main thread: coloured dots for the route's lights (green = learned cycle). */
    private fun showMarks(lights: List<TrafficLight>, learned: Set<LatLng>) {
        val coords = DoubleArray(lights.size * 2)
        lights.forEachIndexed { i, light ->
            coords[2 * i] = light.position.latitude
            coords[2 * i + 1] = light.position.longitude
        }
        Framework.nativeSetGreenWaveMarks(coords, BooleanArray(lights.size) { lights[it].position in learned })
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
        val current = route ?: return State(LightsStatus.NO_ROUTE, 0, 0, null)
        if (location == null || lightsStatus != LightsStatus.READY) {
            return State(lightsStatus, current.trafficLights.size, learnedCount, null, liveCount)
        }
        val update = location.toUpdate()
        val progress = distanceAlongPath(current.path, update.position)
        val ahead = current.trafficLights
            .map { it to distanceAlongPath(current.path, it.position) - progress }
            .filter { it.second > 0 && it.second <= LOOKAHEAD_METERS }
            .sortedBy { it.second }
        val next = ahead.firstOrNull()
            ?: return State(LightsStatus.READY, current.trafficLights.size, learnedCount, Advice(null, false, null), liveCount)
        val known = next.first.position in routeLearned
        // Only learned lights take part; an unknown one further on must not steer the speed either.
        val learnedAhead = ahead.map { it.first }.takeWhile { it.position in routeLearned }
        val options = if (!known) null else SpeedOptionsCalculator.compute(
            route = current.copy(trafficLights = learnedAhead),
            currentPosition = update.position,
            nowEpochSec = update.epochSec,
            currentSpeedKmh = update.speedKmh,
            speedRange = speedRange,
        )
        return State(LightsStatus.READY, current.trafficLights.size, learnedCount, Advice(next.second, known, options), liveCount)
    }
}

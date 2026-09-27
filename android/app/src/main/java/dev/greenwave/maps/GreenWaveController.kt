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
import dev.greenwave.shared.algorithm.Intersections
import dev.greenwave.shared.algorithm.SpeedOptions
import dev.greenwave.shared.algorithm.SpeedOptionsCalculator
import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.data.RandomTrafficTimingGenerator
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.geo.pointAtDistanceAlongPath
import dev.greenwave.shared.geo.distanceToPath
import dev.greenwave.shared.live.LaneConnectionMatcher
import dev.greenwave.shared.live.LiveSignalCycle
import dev.greenwave.shared.location.LocationUpdate
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import dev.greenwave.shared.routing.TrafficLightsAlongRoute
import dev.greenwave.shared.tracking.LightEvent
import dev.greenwave.shared.tracking.ObservationCycleEstimator
import dev.greenwave.shared.tracking.PassDetector
import dev.greenwave.shared.tracking.RhythmEstimate
import dev.greenwave.shared.tracking.StopDetector
import dev.greenwave.shared.tracking.StopEvent
import dev.greenwave.shared.tracking.StopEventMatcher
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
 * While riding (navigation or tracking mode), every fix also goes through a
 * [StopDetector] and a [PassDetector]; stops and pass-throughs are logged per
 * light and direction ([LightEventStore]). Once a light's program can be
 * learned from them ([ObservationCycleEstimator]) or comes from a live source
 * (Hamburg), it replaces the placeholder ([RandomTrafficTimingGenerator]),
 * which is never used for advice.
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
    @Volatile private var requestedRevision = -1
    private var failedAttempts = 0

    // Worker-only state for learning.
    private var store: StopEventStore? = null
    private var lightEvents: LightEventStore? = null
    private val stopDetector = StopDetector()
    private val passDetector = PassDetector()
    /** Recent (epochSec, bearing) of moving fixes, for the approach direction of a stop. */
    private val recentBearings = ArrayDeque<Pair<Long, Float>>()
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
    /** Set by [GreenWaveTrackingService]; runs on main after each stop (alert = true) or pass-through. */
    @Volatile var onTrackingEvent: ((alert: Boolean) -> Unit)? = null
    /** Tracking mode: when the last stop or pass was registered (for the map overlay's flash). */
    @Volatile var trackingLastEventAt = 0L
        private set
    @Volatile var trackingPasses = 0
        private set

    // Tracking mode: all traffic lights around the rider (Overpass), shown on the map
    // with their learning state and used for pass/stop detection. Worker-only.
    private var areaLights: List<LatLng> = emptyList()
    private var areaCenter: LatLng? = null
    private var areaFailedAt = 0L
    /** Worker-only: an area request is on [net]; don't start a second one. */
    private var areaLoading = false
    private const val AREA_RADIUS_METERS = 3_000
    private const val AREA_REFRESH_METERS = 1_500.0
    @Volatile private var profileRange: SpeedRangeKmh? = null

    /** For the settings dialog: how much has been logged on this device. */
    @Volatile var loggedStops = 0
        private set
    @Volatile var loggedRides = 0
        private set
    private var host: WeakReference<Context>? = null

    private val worker = Executors.newSingleThreadExecutor()
    /**
     * Network requests only (Overpass, Hamburg live data). A slow server can take
     * minutes across retries and mirrors; on [worker] that would stall every fix,
     * and on one shared thread a hanging stop lookup would hold up the live data.
     * Results are handed back to [worker], which owns all state.
     */
    private val net = Executors.newCachedThreadPool()
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
            lightEvents = LightEventStore(appContext)
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
            if (wasActive) worker.execute { suggestedRoute = null; finishRide(); refreshAreaMarks() }
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

    /**
     * Runs on [worker]; the lookups run on [net]: the downloaded map first, Overpass
     * only where the map has no signals (not downloaded) and the path isn't covered yet.
     */
    private fun loadLights(path: List<LatLng>, revision: Int) {
        lightPositions = emptyList()
        val query = needsQuery(path)
        net.execute {
            if (revision != requestedRevision) return@execute
            val offline = offlineSignals { OfflineSignals.alongPath(path) }
            val signals = when {
                offline.isNotEmpty() -> offline
                !query -> emptyList()
                else -> try {
                    OverpassClient.trafficSignals(TrafficLightsAlongRoute.overpassQuery(path))
                } catch (e: Exception) {
                    Log.w(TAG, "traffic light lookup failed", e)
                    null
                }
            }
            Log.i(TAG, "route rev $revision: ${signals?.size ?: 0} signals from " + if (offline.isNotEmpty()) "map" else "Overpass")
            worker.execute {
                if (revision != requestedRevision) return@execute
                if (signals == null) {
                    postLightsStatus(path, revision, LightsStatus.FAILED)
                    return@execute
                }
                knownSignals.addAll(signals)
                coveredPaths.add(path)
                selectLights(path, revision)
            }
        }
    }

    /** Worker: the route's lights from the signals known so far. */
    private fun selectLights(path: List<LatLng>, revision: Int) {
        val status = try {
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
        postLightsStatus(path, revision, status)
    }

    private fun postLightsStatus(path: List<LatLng>, revision: Int, status: LightsStatus) {
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
     * A failed request keeps the previous values. The requests run on [net].
     */
    private fun refreshLive(revision: Int) {
        val positions = lightPositions.filter { HamburgSignalsClient.covers(it) }
        if (positions.isEmpty()) return
        val path = lightsPath
        net.execute {
            if (revision != requestedRevision) return@execute
            val now = System.currentTimeMillis() / 1000
            val batches = try {
                positions.chunked(LIVE_BATCH).map { it to HamburgSignalsClient.laneConnections(it, now) }
            } catch (e: Exception) {
                Log.w(TAG, "Hamburg live signals failed", e)
                null
            }
            worker.execute {
                if (revision != requestedRevision) return@execute
                if (batches != null) {
                    val found = mutableMapOf<LatLng, TrafficLight>()
                    for ((batch, connections) in batches) {
                        for (position in batch) {
                            val lane = LaneConnectionMatcher.match(path, position, connections) ?: continue
                            LiveSignalCycle.estimate(position, lane.observations, now, lane.cycleStarts)?.let { found[position] = it }
                        }
                    }
                    liveLights = found
                    Log.i(TAG, "live timing for ${found.size} of ${positions.size} Hamburg lights: " +
                        found.keys.joinToString { "%.5f,%.5f".format(java.util.Locale.ROOT, it.latitude, it.longitude) })
                    publishRoute(revision)
                }
                main.postDelayed({
                    if (revision == requestedRevision) worker.execute { if (revision == requestedRevision) refreshLive(revision) }
                }, LIVE_REFRESH_MS)
            }
        }
    }

    /** Worker: combine current light positions with what has been learned so far. */
    private fun publishRoute(revision: Int) {
        val path = lightsPath
        val now = System.currentTimeMillis() / 1000
        val learnedSet = mutableSetOf<LatLng>()
        val live = liveLights
        val lights = lightPositions.map { position ->
            live[position]?.let {
                learnedSet.add(position)
                return@map it
            }
            val estimate = learnedProgram(position, approachBearing(path, position), now)
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
        val count = learnedSet.size
        val liveNow = lightPositions.count { it in live }
        val withData = lightsWithData()
        val positions = lightPositions
        main.post {
            if (revision == requestedRevision) {
                route = Route(path = path, trafficLights = lights)
                routeLearned = learnedSet
                learnedCount = count
                liveCount = liveNow
                showMarks(positions.map { it to markState(it, learnedSet, live.keys, withData) })
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
            observeLights(update)
            val event = stopDetector.onUpdate(update) ?: return@execute
            val log = store ?: return@execute
            log.append(event)
            loggedStops = log.all().size
            Log.i(TAG, "stop ${event.durationSec}s at ${event.position}")
            withLightNear(event.position, StopEventMatcher.match(event.position, lightPositions)) { light ->
                recordStop(event, light)
                if (lightPositions.isNotEmpty()) publishRoute(revision)
                val text = describeStop(event, light)
                navEvent = text to System.currentTimeMillis()
                main.post { hostActivity()?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() } }
            }
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

    /** Worker: remember the heading of moving fixes and log pass-throughs at nearby lights. */
    private fun observeLights(update: LocationUpdate) {
        val bearing = update.bearingDegrees
        if (bearing != null && update.speedKmh >= RideLog.MOVING_KMH) {
            recentBearings.addLast(update.epochSec to bearing)
            while (recentBearings.size > 180) recentBearings.removeFirst()
        }
        val candidates = (lightPositions + areaLights + knownLights?.all().orEmpty())
            .filter { distanceMeters(it, update.position) < 100.0 }
            .distinct()
        val passes = passDetector.onUpdate(update, candidates)
        if (passes.isNotEmpty()) {
            lightEvents?.addAll(passes)
            Log.i(TAG, "passed on green: ${passes.size}")
            if (GreenWaveTrackingService.running && route == null) {
                trackingPasses += passes.size
                passes.forEach { trackingLightSet.add(it.light) }
                trackingLights = trackingLightSet.size
                val there = lightEvents?.at(passes.last().light).orEmpty().size
                trackingLastEvent = "Grün-Durchfahrt registriert · $there Daten dort"
                trackingLastEventAt = System.currentTimeMillis()
                refreshAreaMarks()
                main.post { onTrackingEvent?.invoke(false) }
            }
        }
    }

    /** Worker: a stop at [light] as learning events, with the direction the rider came from. */
    private fun recordStop(event: StopEvent, light: LatLng?) {
        if (light == null) return
        val heading = recentBearings.lastOrNull { it.first <= event.startEpochSec }?.second?.roundToInt()
        lightEvents?.addAll(LightEvent.fromStop(event, light, heading))
    }

    /** Worker: the learned program for [position] when approached with [headingDeg], or null. */
    private fun learnedProgram(position: LatLng, headingDeg: Double?, nowEpochSec: Long): RhythmEstimate? {
        val events = lightEvents?.at(position).orEmpty().filter { it.sameApproach(headingDeg) }
        // Stops logged before light events existed count too (direction unknown).
        val since = lightEvents?.all()?.firstOrNull()?.epochSec ?: Long.MAX_VALUE
        val legacy = store?.all().orEmpty()
            .filter { it.endEpochSec < since && StopEventMatcher.match(it.position, listOf(position)) == position }
            .flatMap { LightEvent.fromStop(it, position, null) }
        return ObservationCycleEstimator.estimate(events + legacy, nowEpochSec)
    }

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
        val passes: Int = 0,
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
            val now = System.currentTimeMillis() / 1000
            val stats = positions.map { position ->
                val stops = grouped[position].orEmpty()
                val estimate = learnedProgram(position, null, now)
                LightStats(
                    position = position,
                    stops = stops.size,
                    passes = lightEvents?.at(position).orEmpty().count { it.kind == LightEvent.Kind.PASS_GREEN },
                    cycleSec = estimate?.cycleLengthSec,
                    greenSec = estimate?.greenDurationSec,
                    confidence = estimate?.confidence,
                    trusted = estimate != null && estimate.confidence >= MIN_CONFIDENCE,
                )
            }.sortedWith(compareByDescending<LightStats> { it.trusted }.thenByDescending { it.stops + it.passes })
            main.post { onResult(stats) }
        }
    }

    /** Tracking mode started (from [GreenWaveTrackingService], main thread). */
    fun startTracking(context: Context) {
        init(context)
        trackingStops = 0
        trackingLights = 0
        trackingLastEvent = null
        trackingLastEventAt = 0L
        trackingPasses = 0
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
            updateArea(update.position)
            trackRide(update)
            observeLights(update)
            val event = stopDetector.onUpdate(update) ?: return@execute
            store?.append(event)
            loggedStops = store?.all()?.size ?: loggedStops
            trackingStops++
            Log.i(TAG, "tracking stop ${event.durationSec}s at ${event.position}")
            withLightNear(event.position, nearest(areaLights, event.position)) { light ->
                recordStop(event, light)
                if (light != null) {
                    trackingLightSet.add(light)
                    trackingLights = trackingLightSet.size
                }
                trackingLastEvent = describeStop(event, light)
                trackingLastEventAt = System.currentTimeMillis()
                refreshAreaMarks()
                main.post { onTrackingEvent?.invoke(true) }
            }
        }
    }

    /** Tracking mode ended: close the track, log the ride (no rating, there was no advice). */
    fun stopTracking() {
        worker.execute {
            track?.close()
            Log.i(TAG, "tracking stopped: ${track?.file?.name}")
            track = null
            areaCenter = null
            areaLights = emptyList()
            if (route == null) {
                finishRide(askRating = false)
                main.post { if (route == null) Framework.nativeClearGreenWaveMarks() }
            }
        }
    }

    /** Worker: (re)load the lights around [position] when the rider has moved far enough. */
    private fun updateArea(position: LatLng) {
        if (areaLoading) return
        val center = areaCenter
        if (center != null && distanceMeters(center, position) < AREA_REFRESH_METERS) return
        if (System.currentTimeMillis() - areaFailedAt < 60_000L) return
        val query = String.format(
            java.util.Locale.ROOT,
            "[out:json][timeout:25];node[\"highway\"=\"traffic_signals\"](around:%d,%.6f,%.6f);out;",
            AREA_RADIUS_METERS, position.latitude, position.longitude,
        )
        areaLoading = true
        net.execute {
            val lights = offlineSignals { OfflineSignals.around(position, AREA_RADIUS_METERS.toDouble()) }.ifEmpty {
                try {
                    OverpassClient.trafficSignals(query)
                } catch (e: Exception) {
                    Log.w(TAG, "tracking area lookup failed", e)
                    null
                }
            }
            worker.execute {
                areaLoading = false
                // Tracking ended while the request ran: stopTracking() already cleared the area.
                if (!GreenWaveTrackingService.running) return@execute
                if (lights == null) {
                    areaFailedAt = System.currentTimeMillis()
                    return@execute
                }
                areaLights = lights
                areaCenter = position
                Log.i(TAG, "tracking area: ${areaLights.size} traffic lights within $AREA_RADIUS_METERS m")
                refreshAreaMarks()
            }
        }
    }

    /**
     * Worker: dots for all lights around the rider in tracking mode — grey no
     * data yet, amber some data, green learned — so uncertain ones can be ridden
     * on purpose. Only while no route is shown (the route's own dots win).
     */
    private fun refreshAreaMarks() {
        if (!GreenWaveTrackingService.running) return
        val lights = areaLights
        if (lights.isEmpty()) return
        val byLight = lightEvents?.all().orEmpty().groupBy { it.light }
        val now = System.currentTimeMillis() / 1000
        val marks = lights.map { position ->
            val events = byLight[position].orEmpty()
            val state = when {
                events.isEmpty() -> MARK_NO_DATA
                events.size >= ObservationCycleEstimator.MIN_EVENTS && learnedProgram(position, null, now) != null -> MARK_LEARNED
                else -> MARK_LEARNING
            }
            position to state
        }
        main.post { if (route == null && GreenWaveTrackingService.running) showMarks(marks) }
    }

    /**
     * Worker: without a route nobody asked Overpass about this spot, so look for
     * a signal next to the stop and remember it; then the stop counts for that
     * light in the "Ampeldaten" list and on later routes. [then] runs on the
     * worker, right away when [match] or a known light fits, otherwise after the
     * request on [net].
     */
    private fun withLightNear(position: LatLng, match: LatLng?, then: (LatLng?) -> Unit) {
        val known = knownLights
        val light = match ?: known?.let { nearest(it.all(), position) }
        if (light != null || known == null) {
            then(light)
            return
        }
        net.execute {
            val signals = offlineSignals {
                OfflineSignals.around(position, TrafficLightsAlongRoute.MAX_DISTANCE_TO_ROUTE_METERS * 2)
            }.ifEmpty {
                try {
                    OverpassClient.trafficSignals(TrafficLightsAlongRoute.overpassQuery(listOf(position, position)))
                } catch (e: Exception) {
                    Log.w(TAG, "traffic light lookup at stop failed", e)
                    emptyList()
                }
            }
            worker.execute {
                known.addAll(signals)
                then(nearest(known.all(), position))
            }
        }
    }

    /** [net]: signals from the downloaded map, empty if that fails (e.g. no map there). */
    private fun offlineSignals(lookup: () -> List<LatLng>): List<LatLng> = try {
        lookup()
    } catch (e: Throwable) {
        Log.w(TAG, "map traffic signals failed", e)
        emptyList()
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

    /** Map dot colours, see GreenWaveMarks.cpp. */
    private const val MARK_NO_DATA = 0
    private const val MARK_LEARNING = 1
    private const val MARK_LEARNED = 2
    private const val MARK_LIVE = 3

    private fun markState(position: LatLng, learned: Set<LatLng>, live: Set<LatLng>, withData: Set<LatLng>) = when (position) {
        in live -> MARK_LIVE
        in learned -> MARK_LEARNED
        in withData -> MARK_LEARNING
        else -> MARK_NO_DATA
    }

    /** Worker: lights with at least one logged event (stop or pass). */
    private fun lightsWithData(): Set<LatLng> = lightEvents?.all().orEmpty().mapTo(HashSet()) { it.light }

    /** Main thread: coloured dots on the map. */
    private fun showMarks(marks: List<Pair<LatLng, Int>>) {
        val coords = DoubleArray(marks.size * 2)
        marks.forEachIndexed { i, (position, _) ->
            coords[2 * i] = position.latitude
            coords[2 * i + 1] = position.longitude
        }
        Framework.nativeSetGreenWaveMarks(coords, IntArray(marks.size) { marks[it].second })
    }

    /** Direction of travel on [path] at [position] (degrees, 0 = north), from 10 m before to 10 m after it. */
    private fun approachBearing(path: List<LatLng>, position: LatLng): Double? {
        if (path.size < 2) return null
        val at = distanceAlongPath(path, position)
        val a = pointAtDistanceAlongPath(path, maxOf(0.0, at - 10))
        val b = pointAtDistanceAlongPath(path, at + 10)
        if (a == b) return null
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
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
        val learned = routeLearned
        // One light per intersection, the known one if there is any (see Intersections).
        val ahead = Intersections.representatives(
            current.trafficLights
                .map { it to distanceAlongPath(current.path, it.position) - progress }
                .filter { it.second > 0 && it.second <= LOOKAHEAD_METERS }
                .sortedBy { it.second },
            isKnown = { it.position in learned },
        )
        val next = ahead.firstOrNull()
            ?: return State(LightsStatus.READY, current.trafficLights.size, learnedCount, Advice(null, false, null), liveCount)
        val known = next.first.position in learned
        // Only learned lights take part; an unknown one further on must not steer the speed either.
        val learnedAhead = ahead.map { it.first }.takeWhile { it.position in learned }
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

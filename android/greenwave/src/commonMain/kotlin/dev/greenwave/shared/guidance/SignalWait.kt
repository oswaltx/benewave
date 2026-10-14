package dev.greenwave.shared.guidance

import dev.greenwave.shared.algorithm.Intersections
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight

/**
 * Expected waiting time at the traffic lights of a route, for an honest arrival time
 * (David 14.10.2026). Kept apart from the route modes, whose weights may exaggerate on purpose.
 *
 * A known light (live or learned, see [RouteAdvice]) reached at a random moment is red with
 * probability red/cycle and then makes the rider wait red/2 on average: red²/(2·cycle).
 * An unknown light counts [DEFAULT_WAIT_SEC], never its placeholder program.
 */
object SignalWait {
    /** Unknown light: a bit above red²/(2·cycle) of typical city programs (90 s cycle, 50 s red: 14 s). */
    const val DEFAULT_WAIT_SEC = 20.0

    /** Wait at one intersection, [alongMeters] from the start of the route. */
    data class Wait(val alongMeters: Double, val waitSec: Double, val known: Boolean)

    /**
     * Waits still ahead: their sum, how many intersections and how many of them are known.
     * The router's bicycle time holds no signal waits (GetRoadPenaltyEtaFactor in
     * libs/routing/bicycle_route_profile.hpp), so [waitSec] is added as it is.
     */
    data class Summary(val waitSec: Double, val lights: Int, val known: Int)

    fun expectedWaitSec(light: TrafficLight): Double {
        val cycle = light.cycleLengthSec
        if (cycle <= 0) return DEFAULT_WAIT_SEC
        val red = (cycle - light.greenDurationSec).coerceIn(0, cycle).toDouble()
        return red * red / (2.0 * cycle)
    }

    /** One wait per intersection of [route] in route order; lights in [known] are live or learned. */
    fun along(route: Route, known: Set<LatLng>): List<Wait> {
        val sorted = route.trafficLights
            .map { it to distanceAlongPath(route.path, it.position) }
            .sortedBy { it.second }
        return Intersections.representatives(sorted, isKnown = { it.position in known }).map { (light, along) ->
            val isKnown = light.position in known
            Wait(along, if (isKnown) expectedWaitSec(light) else DEFAULT_WAIT_SEC, isKnown)
        }
    }

    /** The [waits] not yet passed at [progressMeters] along the route. */
    fun ahead(waits: List<Wait>, progressMeters: Double): Summary {
        val rest = waits.filter { it.alongMeters > progressMeters }
        return Summary(rest.sumOf { it.waitSec }, rest.size, rest.count { it.known })
    }
}

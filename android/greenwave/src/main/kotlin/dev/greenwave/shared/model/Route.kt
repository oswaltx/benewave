package dev.greenwave.shared.model

import dev.greenwave.shared.geo.LatLng

/**
 * @param path the route's shape, in travel order — used to measure progress
 *   along the route (see `distanceAlongPath`).
 * @param trafficLights the route's traffic lights, in travel order.
 */

data class Route(
    val path: List<LatLng>,
    val trafficLights: List<TrafficLight>,
)

package dev.greenwave.shared.matching

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.angleDifference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MapMatcherTest {
    private val origin = LatLng(50.93, 6.96)

    /** [north]/[east] metres from the origin. */
    private fun at(north: Double, east: Double) = LatLng(
        origin.latitude + north / 111_320.0,
        origin.longitude + east / (111_320.0 * cos(origin.latitude * PI / 180.0)),
    )

    private fun line(vararg pts: Pair<Double, Double>) = pts.map { at(it.first, it.second) }

    /** A track along [path] every [step] m with ±[noise] m GPS noise (seeded). */
    private fun track(path: List<LatLng>, step: Double = 5.0, noise: Double = 3.0, seed: Int = 1): List<LatLng> {
        val rnd = Random(seed)
        val out = mutableListOf<LatLng>()
        for ((a, b) in path.zipWithNext()) {
            val len = dev.greenwave.shared.geo.distanceMeters(a, b)
            val n = (len / step).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val f = i.toDouble() / n
                val p = LatLng(a.latitude + f * (b.latitude - a.latitude), a.longitude + f * (b.longitude - a.longitude))
                val dn = (rnd.nextDouble() * 2 - 1) * noise
                val de = (rnd.nextDouble() * 2 - 1) * noise
                out.add(LatLng(p.latitude + dn / 111_320.0, p.longitude + de / (111_320.0 * cos(p.latitude * PI / 180.0))))
            }
        }
        out.add(path.last())
        return out
    }

    @Test
    fun cyclewayNextToTheRoadIsMatchedAndTheRoadSignalIsNotOnTheRoute() {
        val road = Way(1, line(0.0 to 0.0, 400.0 to 0.0), WayKind.CARRIAGEWAY)
        val cycleway = Way(2, line(0.0 to 10.0, 400.0 to 10.0), WayKind.CYCLEWAY)
        val matcher = MapMatcher(RoadNetwork(listOf(road, cycleway)))
        // Riding the cycleway, GPS drifting ±4 m.
        val match = assertNotNull(matcher.match(track(cycleway.points, noise = 4.0)))
        assertTrue(match.edges.all { it.way.kind == WayKind.CYCLEWAY }, "ways: ${match.edges.map { it.way.kind }}")
        val roadSignal = at(200.0, 0.0)
        assertTrue(match.distanceTo(roadSignal) > 8.0, "road signal is ${match.distanceTo(roadSignal)} m off the route")
        // A signal on the cycleway itself is on the route.
        assertTrue(match.distanceTo(at(200.0, 10.0)) < 1.0)
    }

    @Test
    fun curvedRoadWithoutJunctionIsStraightOn() {
        // Quarter-ish arc: heading turns by ~70° over ~120 m, one way, no junction.
        val arc = (0..12).map { i ->
            val a = i / 12.0 * 70.0 * PI / 180.0
            at(100 * sin(a), 100 - 100 * cos(a))
        }
        val way = Way(1, line(-100.0 to 0.0) + arc + listOf(at(100 * sin(70 * PI / 180) + 80 * cos(70 * PI / 180), 100 - 100 * cos(70 * PI / 180) + 80 * sin(70 * PI / 180))), WayKind.CARRIAGEWAY)
        val match = assertNotNull(MapMatcher(RoadNetwork(listOf(way))).match(track(way.points)))
        val passage = assertNotNull(match.passage(arc[6]))
        assertTrue(passage.sameWay, "no junction, the rider stays on the way")
    }

    @Test
    fun turningRightAtAJunctionIsARightTurn() {
        val northSouth = Way(1, line(-200.0 to 0.0, 0.0 to 0.0, 200.0 to 0.0), WayKind.CARRIAGEWAY)
        val east = Way(2, line(0.0 to 0.0, 0.0 to 200.0), WayKind.CARRIAGEWAY)
        val ridden = line(-200.0 to 0.0, 0.0 to 0.0, 0.0 to 200.0)
        val match = assertNotNull(MapMatcher(RoadNetwork(listOf(northSouth, east))).match(track(ridden)))
        val passage = assertNotNull(match.passage(at(-5.0, 0.0)))
        assertTrue(!passage.sameWay)
        val turn = angleDifference(passage.inBearing!!, passage.outBearing!!)
        assertTrue(turn in 60.0..120.0, "turn $turn")
    }

    @Test
    fun straightThroughAJunctionStaysOnTheWay() {
        val northSouth = Way(1, line(-200.0 to 0.0, 0.0 to 0.0, 200.0 to 0.0), WayKind.CARRIAGEWAY)
        val east = Way(2, line(0.0 to 0.0, 0.0 to 200.0), WayKind.CARRIAGEWAY)
        val match = assertNotNull(MapMatcher(RoadNetwork(listOf(northSouth, east))).match(track(northSouth.points)))
        val passage = assertNotNull(match.passage(at(-5.0, 0.0)))
        assertTrue(passage.sameWay)
        assertEquals(0.0, angleDifference(passage.inBearing!!, passage.outBearing!!), 10.0)
    }

    @Test
    fun waysMeetingAtTheSameVertexAreConnected() {
        val a = Way(1, line(0.0 to 0.0, 100.0 to 0.0), WayKind.CARRIAGEWAY)
        val b = Way(2, line(100.0 to 0.0, 100.0 to 100.0), WayKind.CYCLEWAY)
        val net = RoadNetwork(listOf(a, b))
        assertEquals(3, net.nodes.size)
        val start = net.candidates(at(10.0, 0.0), 5.0, 1).single()
        val end = net.candidates(at(100.0, 90.0), 5.0, 1).single()
        val (d, _) = assertNotNull(net.route(net.reach(start, 1_000.0), end))
        assertEquals(180.0, d, 2.0)
    }
}

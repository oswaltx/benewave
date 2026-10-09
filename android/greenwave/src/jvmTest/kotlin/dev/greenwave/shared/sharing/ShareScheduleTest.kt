package dev.greenwave.shared.sharing

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareScheduleTest {
    private val cest = 2 * 3_600

    private fun local(epochSec: Long) = Instant.ofEpochSecond(epochSec).atZone(ZoneOffset.ofTotalSeconds(cest))

    @Test
    fun nextSendIsInTheFollowingCalendarWeekDuringTheDay() {
        // Friday 09.10.2026 03:00 CEST.
        val now = ZonedDateTime.of(2026, 10, 9, 3, 0, 0, 0, ZoneOffset.ofTotalSeconds(cest)).toEpochSecond()
        val days = mutableSetOf<DayOfWeek>()
        repeat(200) { seed ->
            val at = local(ShareSchedule.next(now, cest, Random(seed)))
            // Week of Monday 12.10. to Sunday 18.10.
            assertTrue(at.toLocalDate() >= java.time.LocalDate.of(2026, 10, 12), "$at")
            assertTrue(at.toLocalDate() <= java.time.LocalDate.of(2026, 10, 18), "$at")
            assertTrue(at.hour in 8..20, "$at")
            days.add(at.dayOfWeek)
        }
        assertEquals(7, days.size)
    }

    @Test
    fun sundayNightPlansTheNextWeekNotTheSameDay() {
        val sunday = ZonedDateTime.of(2026, 10, 11, 23, 30, 0, 0, ZoneOffset.ofTotalSeconds(cest)).toEpochSecond()
        repeat(50) { seed ->
            val at = local(ShareSchedule.next(sunday, cest, Random(seed)))
            assertTrue(at.toLocalDate() in java.time.LocalDate.of(2026, 10, 12)..java.time.LocalDate.of(2026, 10, 18), "$at")
        }
    }

    @Test
    fun due() {
        assertFalse(ShareSchedule.due(0, 100))
        assertFalse(ShareSchedule.due(200, 100))
        assertTrue(ShareSchedule.due(100, 100))
        assertTrue(ShareSchedule.due(50, 100))
    }

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZoneOffset.ofTotalSeconds(cest)).toEpochSecond()

    @Test
    fun windowIsDaytimeLocal() {
        assertFalse(ShareSchedule.inWindow(at(9, 7, 59), cest))
        assertTrue(ShareSchedule.inWindow(at(9, 8, 0), cest))
        assertTrue(ShareSchedule.inWindow(at(9, 20, 59), cest))
        assertFalse(ShareSchedule.inWindow(at(9, 21, 0), cest))
        assertFalse(ShareSchedule.inWindow(at(9, 3, 0), cest))
    }

    @Test
    fun postponeGoesToTheNextWindowNeverAtNight() {
        repeat(100) { seed ->
            val early = local(ShareSchedule.postpone(at(9, 5, 0), cest, Random(seed)))
            assertEquals(9, early.dayOfMonth, "$early")
            assertTrue(early.hour in 8..20, "$early")
            val late = local(ShareSchedule.postpone(at(9, 22, 30), cest, Random(seed)))
            assertEquals(10, late.dayOfMonth, "$late")
            assertTrue(late.hour in 8..20, "$late")
            // Inside the window a missed exchange waits for tomorrow's window rather than right away (not tied to waking up).
            val day = local(ShareSchedule.postpone(at(9, 12, 0), cest, Random(seed)))
            assertEquals(10, day.dayOfMonth, "$day")
        }
    }

    @Test
    fun postponedFetchStaysApartFromTheSend() {
        repeat(100) { seed ->
            val send = at(10, 9, 0)
            val fetch = ShareSchedule.postpone(at(9, 22, 0), cest, Random(seed), apartFrom = send)
            assertTrue(kotlin.math.abs(fetch - send) >= ShareSchedule.MIN_GAP_SEC, "${local(fetch)}")
            assertTrue(local(fetch).hour in 8..20)
        }
    }

    @Test
    fun fetchIsPlannedApartFromTheSend() {
        val now = at(9, 3)
        repeat(200) { seed ->
            val random = Random(seed)
            val send = ShareSchedule.next(now, cest, random)
            val fetch = ShareSchedule.nextApart(now, cest, send, random)
            assertTrue(kotlin.math.abs(fetch - send) >= ShareSchedule.MIN_GAP_SEC, "send ${local(send)} fetch ${local(fetch)}")
            assertTrue(local(fetch).hour in 8..20)
        }
    }

    private val gap = ShareSchedule.MIN_GAP_SEC

    private fun decide(state: ShareSchedule.State, now: Long, force: Boolean = false, unmetered: Boolean = true, seed: Int = 1) =
        ShareSchedule.decide(state, now, cest, force, unmetered, Random(seed))

    @Test
    fun decideActsOnlyWhenDueOnWlanInTheWindow() {
        val sendAt = at(14, 10)
        val fetchAt = at(16, 15)
        val state = ShareSchedule.State(sendAt, fetchAt, 0, 0)
        assertEquals(ShareSchedule.Decision(false, false, sendAt, fetchAt), decide(state, at(13, 12)))
        assertEquals(ShareSchedule.Decision(false, false, sendAt, fetchAt), decide(state, at(14, 11), unmetered = false))
        assertEquals(ShareSchedule.Decision(true, false, sendAt, fetchAt), decide(state, at(14, 11)))
    }

    @Test
    fun decideMovesAMomentMissedAtNightToTheNextWindow() {
        val state = ShareSchedule.State(at(14, 10), at(16, 15), 0, 0)
        repeat(50) { seed ->
            val d = decide(state, at(14, 22, 30), seed = seed)
            assertFalse(d.send || d.fetch)
            val moved = local(d.sendAt)
            assertEquals(15, moved.dayOfMonth, "$moved")
            assertTrue(moved.hour in 8..20)
        }
    }

    @Test
    fun decideKeepsSendAndFetchApartAlsoWhenOneRanLate() {
        // The send was due Wednesday but ran only Thursday 09:00; the fetch planned Thursday 15:00 must wait.
        val lastSent = at(15, 9)
        repeat(50) { seed ->
            val d = decide(ShareSchedule.State(at(21, 10), at(15, 14), lastSent, 0), at(15, 15), seed = seed)
            assertFalse(d.fetch)
            assertTrue(d.fetchAt - lastSent >= gap, "${local(d.fetchAt)}")
            assertTrue(local(d.fetchAt).hour in 8..20)
        }
        // And the other way round.
        val lastFetch = at(15, 9)
        val d = decide(ShareSchedule.State(at(15, 14), at(22, 10), 0, lastFetch), at(15, 15))
        assertFalse(d.send)
        assertTrue(d.sendAt - lastFetch >= gap)
    }

    @Test
    fun decideNeverSendsAndFetchesInOneGo() {
        repeat(50) { seed ->
            val d = decide(ShareSchedule.State(at(12, 10), at(13, 10), 0, 0), at(15, 11), seed = seed)
            assertTrue(d.send)
            assertFalse(d.fetch)
            assertTrue(d.fetchAt - at(15, 11) >= gap)
        }
    }

    @Test
    fun forceSendsOnlyAndMovesAFetchThatWouldComeTooClose() {
        repeat(50) { seed ->
            val now = at(15, 23)
            val d = decide(ShareSchedule.State(at(20, 10), at(16, 9), 0, 0), now, force = true, unmetered = false, seed = seed)
            assertTrue(d.send)
            assertFalse(d.fetch)
            assertTrue(d.fetchAt - now >= gap, "${local(d.fetchAt)}")
        }
        val far = at(18, 12)
        assertEquals(far, decide(ShareSchedule.State(at(20, 10), far, 0, 0), at(15, 12), force = true).fetchAt)
    }

    @Test
    fun decidePlansBothApartOnFirstRun() {
        repeat(50) { seed ->
            val d = decide(ShareSchedule.State(0, 0, 0, 0), at(9, 12), seed = seed)
            assertFalse(d.send || d.fetch)
            assertTrue(kotlin.math.abs(d.sendAt - d.fetchAt) >= gap)
        }
    }
}

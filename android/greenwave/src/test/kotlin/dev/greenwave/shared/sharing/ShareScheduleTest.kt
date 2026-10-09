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
}

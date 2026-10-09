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
}

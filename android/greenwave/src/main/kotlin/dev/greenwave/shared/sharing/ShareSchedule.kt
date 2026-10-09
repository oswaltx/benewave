package dev.greenwave.shared.sharing

import kotlin.random.Random

/**
 * When collected events leave the device: once per calendar week, at a random
 * moment of a random day (David, 02.10.2026). Sending is decoupled from riding,
 * so the server cannot tell a ride's time from the time of the upload.
 */
object ShareSchedule {
    private const val DAY_SEC = 86_400L
    private const val WEEK_SEC = 7 * DAY_SEC

    /** Earliest and latest local time of day for a send (no uploads at night). */
    const val FROM_SEC_OF_DAY = 8 * 3_600L
    const val UNTIL_SEC_OF_DAY = 21 * 3_600L

    /**
     * The next send: a random moment between [FROM_SEC_OF_DAY] and
     * [UNTIL_SEC_OF_DAY] on a random day of the calendar week (Monday to
     * Sunday, local time) after the one [nowEpochSec] falls in.
     * [utcOffsetSec] is the local offset from UTC.
     */
    fun next(nowEpochSec: Long, utcOffsetSec: Int, random: Random = Random.Default): Long {
        val local = nowEpochSec + utcOffsetSec
        val day = Math.floorDiv(local, DAY_SEC)
        // 1970-01-01 was a Thursday: day 0 has weekday index 3 (Monday = 0).
        val weekday = Math.floorMod(day + 3, 7L)
        val nextMonday = (day - weekday) * DAY_SEC + WEEK_SEC
        val at = nextMonday + random.nextLong(7) * DAY_SEC + random.nextLong(FROM_SEC_OF_DAY, UNTIL_SEC_OF_DAY)
        return at - utcOffsetSec
    }

    /** Whether a send planned for [plannedEpochSec] is due ([plannedEpochSec] 0 = never planned, then plan first). */
    fun due(plannedEpochSec: Long, nowEpochSec: Long): Boolean = plannedEpochSec in 1..nowEpochSec
}

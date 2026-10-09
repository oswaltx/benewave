package dev.greenwave.shared.sharing

import kotlin.math.abs
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

    /** Fetching and sending at least this far apart, so the board cannot tie a download area to an upload. */
    const val MIN_GAP_SEC = 12 * 3_600L

    /**
     * Like [next], but at least [MIN_GAP_SEC] away from [otherEpochSec] (the
     * planned send, when planning the fetch). Drawn again until it fits; the
     * week has room for that, so the fallback is never reached in practice.
     */
    fun nextApart(nowEpochSec: Long, utcOffsetSec: Int, otherEpochSec: Long, random: Random = Random.Default): Long {
        var at = next(nowEpochSec, utcOffsetSec, random)
        repeat(50) {
            if (abs(at - otherEpochSec) >= MIN_GAP_SEC) return at
            at = next(nowEpochSec, utcOffsetSec, random)
        }
        return at
    }

    /** Whether [nowEpochSec] lies in the local sending window ([FROM_SEC_OF_DAY] to [UNTIL_SEC_OF_DAY]). */
    fun inWindow(nowEpochSec: Long, utcOffsetSec: Int): Boolean =
        Math.floorMod(nowEpochSec + utcOffsetSec, DAY_SEC) in FROM_SEC_OF_DAY until UNTIL_SEC_OF_DAY

    /**
     * A due exchange the phone missed (off, no WLAN) and that would now fall
     * outside the window: a new random moment in the next window (later today
     * if it has not begun yet, else tomorrow), never at night. With
     * [apartFrom] (the other planned exchange) at least [MIN_GAP_SEC] away
     * from it, on a later day if need be.
     */
    fun postpone(nowEpochSec: Long, utcOffsetSec: Int, random: Random = Random.Default, apartFrom: Long? = null): Long {
        val local = nowEpochSec + utcOffsetSec
        val day = Math.floorDiv(local, DAY_SEC)
        val first = if (Math.floorMod(local, DAY_SEC) < FROM_SEC_OF_DAY) day else day + 1
        var at = 0L
        for (d in first..first + 2) repeat(20) {
            at = d * DAY_SEC + random.nextLong(FROM_SEC_OF_DAY, UNTIL_SEC_OF_DAY) - utcOffsetSec
            if (apartFrom == null || abs(at - apartFrom) >= MIN_GAP_SEC) return at
        }
        return at
    }

    /** Whether a send planned for [plannedEpochSec] is due ([plannedEpochSec] 0 = never planned, then plan first). */
    fun due(plannedEpochSec: Long, nowEpochSec: Long): Boolean = plannedEpochSec in 1..nowEpochSec
}

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
        val day = local.floorDiv(DAY_SEC)
        // 1970-01-01 was a Thursday: day 0 has weekday index 3 (Monday = 0).
        val weekday = (day + 3).mod(7L)
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
        (nowEpochSec + utcOffsetSec).mod(DAY_SEC) in FROM_SEC_OF_DAY until UNTIL_SEC_OF_DAY

    /**
     * A due exchange the phone missed (off, no WLAN) and that would now fall
     * outside the window: a new random moment in the next window (later today
     * if it has not begun yet, else tomorrow), never at night. With
     * [apartFrom] (the other planned exchange) at least [MIN_GAP_SEC] away
     * from it, on a later day if need be.
     */
    fun postpone(nowEpochSec: Long, utcOffsetSec: Int, random: Random = Random.Default, apartFrom: Long? = null): Long {
        val local = nowEpochSec + utcOffsetSec
        val day = local.floorDiv(DAY_SEC)
        val first = if (local.mod(DAY_SEC) < FROM_SEC_OF_DAY) day else day + 1
        var at = 0L
        for (d in first..first + 2) repeat(20) {
            at = d * DAY_SEC + random.nextLong(FROM_SEC_OF_DAY, UNTIL_SEC_OF_DAY) - utcOffsetSec
            if (apartFrom == null || abs(at - apartFrom) >= MIN_GAP_SEC) return at
        }
        return at
    }

    /** Whether a send planned for [plannedEpochSec] is due ([plannedEpochSec] 0 = never planned, then plan first). */
    fun due(plannedEpochSec: Long, nowEpochSec: Long): Boolean = plannedEpochSec in 1..nowEpochSec

    /** What the worker knows: planned moments (0 = not planned yet) and when it last sent or fetched (0 = never). */
    data class State(val sendAt: Long, val fetchAt: Long, val lastSent: Long, val lastFetch: Long)

    /** What the worker does now, and the plan to keep. */
    data class Decision(val send: Boolean, val fetch: Boolean, val sendAt: Long, val fetchAt: Long)

    /**
     * The worker's decision at [nowEpochSec]. Acts only on unmetered networks
     * inside the window; a moment due outside the window moves to the next one.
     * Sending and fetching never happen within [MIN_GAP_SEC] of each other, also
     * when one of them ran late, so the board cannot tie a download area to an
     * upload. [force] ("Jetzt senden") sends at once on any network and moves
     * a fetch that would come too close.
     */
    fun decide(
        state: State,
        nowEpochSec: Long,
        utcOffsetSec: Int,
        force: Boolean,
        unmetered: Boolean,
        random: Random = Random.Default,
    ): Decision {
        val now = nowEpochSec
        var sendAt = if (state.sendAt != 0L) state.sendAt else next(now, utcOffsetSec, random)
        var fetchAt = if (state.fetchAt != 0L) state.fetchAt else nextApart(now, utcOffsetSec, sendAt, random)
        fun close(a: Long, b: Long) = b != 0L && abs(a - b) < MIN_GAP_SEC
        if (force) {
            if (close(fetchAt, now) || fetchAt <= now) fetchAt = postpone(now, utcOffsetSec, random, apartFrom = now)
            return Decision(send = true, fetch = false, sendAt = sendAt, fetchAt = fetchAt)
        }
        var send = due(sendAt, now)
        var fetch = due(fetchAt, now)
        if (!send && !fetch || !unmetered) return Decision(false, false, sendAt, fetchAt)
        if (!inWindow(now, utcOffsetSec)) {
            if (send) sendAt = postpone(now, utcOffsetSec, random, apartFrom = fetchAt)
            if (fetch) fetchAt = postpone(now, utcOffsetSec, random, apartFrom = sendAt)
            return Decision(false, false, sendAt, fetchAt)
        }
        if (send && close(now, state.lastFetch)) {
            sendAt = postpone(now, utcOffsetSec, random, apartFrom = state.lastFetch)
            send = false
        }
        if (fetch && close(now, state.lastSent)) {
            fetchAt = postpone(now, utcOffsetSec, random, apartFrom = state.lastSent)
            fetch = false
        }
        // Both due (phone was off): send now, fetch later.
        if (send && fetch) {
            fetchAt = postpone(now, utcOffsetSec, random, apartFrom = now)
            fetch = false
        }
        return Decision(send, fetch, sendAt, fetchAt)
    }
}

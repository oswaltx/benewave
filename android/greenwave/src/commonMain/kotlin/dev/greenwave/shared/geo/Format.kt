package dev.greenwave.shared.geo

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [value] with exactly six decimals and a dot, like "%.6f" in Locale.ROOT (common code has no String.format).
 * Rounds the binary value, so on exact decimal ties it can differ from Java by 1e-6 (about 0.1 m); used for
 * Overpass queries only. Not for NaN or infinite values.
 */
fun sixDecimals(value: Double): String {
    val scaled = (abs(value) * 1_000_000).roundToLong()
    val sign = if (value < 0 || 1.0 / value < 0) "-" else "" // like Java: keeps "-" for -0.0 and tiny negatives
    return "$sign${scaled / 1_000_000}.${(scaled % 1_000_000).toString().padStart(6, '0')}"
}

/** Degrees to radians, bit-identical to Math.toRadians (JVM-only). */
fun degToRad(degrees: Double): Double = degrees * (kotlin.math.PI / 180.0)

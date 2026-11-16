package com.fuzzymistborn.cubby

import java.time.Instant
import java.time.ZoneId

/** Pure helpers for "allow this hidden app for a while": expiry times are wall-clock epoch millis. */
object TempAccess {
    const val HOUR_MS = 60 * 60 * 1000L

    /** The next local midnight after [now]. */
    fun endOfDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Apps whose allowance has run out at [now]. */
    fun expired(allowed: Map<String, Long>, now: Long): Set<String> =
        allowed.filterValues { it <= now }.keys

    /** When the next allowance ends, or null if there are none. */
    fun nextExpiry(allowed: Map<String, Long>): Long? = allowed.values.minOrNull()

    fun encode(allowed: Map<String, Long>): Set<String> = allowed.mapTo(HashSet()) { (pkg, at) -> "$pkg|$at" }

    fun decode(raw: Set<String>): Map<String, Long> = raw.mapNotNull { entry ->
        val i = entry.lastIndexOf('|')
        val at = if (i > 0) entry.substring(i + 1).toLongOrNull() else null
        at?.let { entry.substring(0, i) to it }
    }.toMap()
}

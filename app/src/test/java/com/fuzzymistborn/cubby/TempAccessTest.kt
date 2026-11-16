package com.fuzzymistborn.cubby

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class TempAccessTest {
    private val zone = ZoneId.of("America/New_York")

    @Test
    fun endOfDayIsTheNextLocalMidnight() {
        val now = ZonedDateTime.of(2026, 11, 16, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
        val want = ZonedDateTime.of(2026, 11, 17, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(want, TempAccess.endOfDay(now, zone))
    }

    @Test
    fun expiredOnlyReturnsFinishedAllowances() {
        val allowed = mapOf("a" to 100L, "b" to 200L, "c" to 300L)
        assertEquals(setOf("a", "b"), TempAccess.expired(allowed, 200L))
        assertEquals(100L, TempAccess.nextExpiry(allowed))
        assertNull(TempAccess.nextExpiry(emptyMap()))
    }

    @Test
    fun encodeDecodeRoundTripsAndSkipsGarbage() {
        val allowed = mapOf("com.x.y" to 12345L, "z" to 9L)
        assertEquals(allowed, TempAccess.decode(TempAccess.encode(allowed)))
        assertEquals(emptyMap<String, Long>(), TempAccess.decode(setOf("nobar", "p|notanumber", "|5")))
    }
}

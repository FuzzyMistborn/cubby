package com.fuzzymistborn.cubby

import org.junit.Assert.assertEquals
import org.junit.Test

class NewAppsTest {
    @Test
    fun firstRunHidesNothing() {
        val r = foldNewApps(listOf("a", "b"), emptySet(), emptySet(), emptySet(), hideNew = true)
        assertEquals(NewApps(setOf("a", "b"), emptySet(), emptySet()), r)
    }

    @Test
    fun newAppIsHiddenAndPendingWhenEnabled() {
        val r = foldNewApps(listOf("a", "b"), setOf("a"), emptySet(), emptySet(), hideNew = true)
        assertEquals(NewApps(setOf("a", "b"), setOf("b"), setOf("b")), r)
    }

    @Test
    fun newAppStaysVisibleWhenDisabled() {
        val r = foldNewApps(listOf("a", "b"), setOf("a"), emptySet(), emptySet(), hideNew = false)
        assertEquals(NewApps(setOf("a", "b"), emptySet(), emptySet()), r)
    }

    @Test
    fun uninstalledPendingAppsAreDropped() {
        val r = foldNewApps(listOf("a"), setOf("a", "b"), setOf("b"), setOf("b"), hideNew = true)
        assertEquals(emptySet<String>(), r.pending)
    }

    @Test
    fun reviewedAppIsNotHiddenAgain() {
        val r = foldNewApps(listOf("a", "b"), setOf("a", "b"), emptySet(), emptySet(), hideNew = true)
        assertEquals(emptySet<String>(), r.hidden)
    }
}

package com.fuzzymistborn.cubby

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTest {
    private val layout = Layout.stock(listOf("a", "b"))

    @Test
    fun backgroundSurvivesARoundTrip() {
        val b = Backup(layout, setOf("x"), false, false, false, background = "OCEAN")
        assertEquals(b, Backup.fromJson(b.toJson()))
    }

    @Test
    fun olderBackupsWithoutABackgroundLoadAsSystem() {
        val json = org.json.JSONObject(Backup(layout, emptySet(), false, false, false).toJson())
        json.remove("background")
        assertEquals("SYSTEM", Backup.fromJson(json.toString())?.background)
    }

    @Test
    fun unknownBackgroundNamesFallBackToSystem() {
        assertEquals(HomeBackground.SYSTEM, HomeBackground.fromName("NOPE"))
    }

    @Test
    fun wifiOnlyAppsSurviveARoundTrip() {
        val b = Backup(layout, emptySet(), false, false, false, wifiOnly = setOf("a"))
        assertEquals(b, Backup.fromJson(b.toJson()))
    }

    @Test
    fun olderBackupsWithoutWifiOnlyLoadEmpty() {
        val json = org.json.JSONObject(Backup(layout, emptySet(), false, false, false).toJson())
        json.remove("wifiOnly")
        assertEquals(emptySet<String>(), Backup.fromJson(json.toString())?.wifiOnly)
    }
}

package com.fuzzymistborn.cubby

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    private val sample = Backup(
        layout = Layout(
            listOf(listOf(Item.App("a"), Item.Folder("f1", "Games", listOf("b", "c"))), listOf(Item.App("d"))),
            favorites = listOf("e"),
        ),
        hidden = setOf("x", "y"),
        layoutLocked = true,
        favoritesEnabled = true,
        hideNewApps = true,
        searchEnabled = false,
        tileColor = "SKY",
    )

    @Test
    fun oldBackupsGetDefaults() {
        val old = JSONObject(sample.toJson())
            .apply { remove("searchEnabled"); remove("tileColor") }
            .toString()
        val b = Backup.fromJson(old)!!
        assertEquals(true, b.searchEnabled)
        assertEquals("WHITE", b.tileColor)
    }

    @Test
    fun roundTrips() {
        assertEquals(sample, Backup.fromJson(sample.toJson()))
    }

    @Test
    fun doesNotMentionPin() {
        assertEquals(false, sample.toJson().contains("pin", ignoreCase = true))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Backup.fromJson("not json"))
        assertNull(Backup.fromJson("{}"))
        assertNull(Backup.fromJson("""{"app":"other","version":1}"""))
    }

    @Test
    fun rejectsNewerVersion() {
        val newer = sample.toJson().replace("\"version\": 1", "\"version\": 99")
        assertNull(Backup.fromJson(newer))
    }
}

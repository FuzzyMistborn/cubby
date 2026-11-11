package com.fuzzymistborn.cubby

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Everything a parent can carry to another install. Never includes the PIN. */
data class Backup(
    val layout: Layout,
    val hidden: Set<String>,
    val layoutLocked: Boolean,
    val favoritesEnabled: Boolean,
    val hideNewApps: Boolean,
    val searchEnabled: Boolean = true,
    val tileColor: String = "WHITE",
) {
    fun toJson(): String = JSONObject()
        .put("app", "cubby")
        .put("version", VERSION)
        .put("layout", JSONObject(layout.toJson()))
        .put("hidden", JSONArray(hidden.sorted()))
        .put("layoutLocked", layoutLocked)
        .put("favoritesEnabled", favoritesEnabled)
        .put("hideNewApps", hideNewApps)
        .put("searchEnabled", searchEnabled)
        .put("tileColor", tileColor)
        .toString(2)

    companion object {
        const val VERSION = 1

        /** Null if [text] isn't a Cubby backup this version can read. */
        fun fromJson(text: String): Backup? = try {
            val o = JSONObject(text)
            if (o.optString("app") != "cubby" || o.getInt("version") > VERSION) {
                null
            } else {
                val h = o.getJSONArray("hidden")
                Backup(
                    layout = Layout.fromJson(o.getJSONObject("layout").toString()) ?: return null,
                    hidden = List(h.length()) { h.getString(it) }.toSet(),
                    layoutLocked = o.optBoolean("layoutLocked", false),
                    favoritesEnabled = o.optBoolean("favoritesEnabled", false),
                    hideNewApps = o.optBoolean("hideNewApps", false),
                    searchEnabled = o.optBoolean("searchEnabled", true),
                    tileColor = o.optString("tileColor", "WHITE"),
                )
            }
        } catch (e: JSONException) {
            null
        }
    }
}

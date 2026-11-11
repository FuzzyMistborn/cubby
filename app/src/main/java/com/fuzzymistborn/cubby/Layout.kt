package com.fuzzymistborn.cubby

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

const val FOLDER_PREFIX = "folder:"
const val DEFAULT_FOLDER_NAME = "Folder"
const val MAX_FAVORITES = 6

/** How many uninstalled or disabled apps keep a remembered spot before the oldest are forgotten. */
const val MAX_MISSING_STASH = 64

/** Something on a home page: a single app or a folder of apps. */
sealed interface Item {
    /** Stable id: the package name for an app, "folder:<id>" for a folder. */
    val key: String

    data class App(val pkg: String) : Item {
        override val key: String get() = pkg
    }

    data class Folder(val id: String, val name: String, val apps: List<String>) : Item {
        override val key: String get() = FOLDER_PREFIX + id
    }
}

/** Where an item can be dropped. Indexes are into the list after the item has been removed. */
sealed interface Dest {
    data class Page(val page: Int, val index: Int) : Dest
    data class Favorites(val index: Int) : Dest
}

/**
 * Where an app used to be, so it goes back there when it returns: page -1 means the favorites row.
 * [hidden] apps are still installed (a parent hid them); the rest are missing or disabled.
 */
data class Spot(val page: Int, val index: Int, val folderId: String? = null, val hidden: Boolean = true)

/**
 * The home screen arrangement: pages of items plus an optional favorites row.
 * Favorites hold apps only; an app is in exactly one place. Always has at least one page.
 */
data class Layout(
    val pages: List<List<Item>> = listOf(emptyList()),
    val favorites: List<String> = emptyList(),
    val stash: Map<String, Spot> = emptyMap(),
) {
    fun item(key: String): Item? =
        if (key in favorites) Item.App(key)
        else pages.firstNotNullOfOrNull { page -> page.firstOrNull { it.key == key } }

    /** Index of the page holding [key], or -1 (also -1 for favorites). */
    fun pageOf(key: String): Int = pages.indexOfFirst { page -> page.any { it.key == key } }

    private fun without(key: String): Layout = copy(
        pages = pages.map { page -> page.filterNot { it.key == key } },
        favorites = favorites - key,
    )

    /** Moves an item to [dest]. Returns this unchanged if the move isn't allowed. */
    fun move(key: String, dest: Dest): Layout {
        val item = item(key) ?: return this
        val base = without(key)
        return when (dest) {
            is Dest.Favorites -> {
                if (item !is Item.App || base.favorites.size >= MAX_FAVORITES) return this
                val favs = base.favorites.toMutableList()
                favs.add(dest.index.coerceIn(0, favs.size), key)
                base.copy(favorites = favs)
            }

            is Dest.Page -> {
                val pages = base.pages.toMutableList()
                val page = dest.page.coerceAtLeast(0)
                while (pages.size <= page) pages.add(emptyList())
                val items = pages[page].toMutableList()
                items.add(dest.index.coerceIn(0, items.size), item)
                pages[page] = items
                base.copy(pages = pages)
            }
        }
    }

    /**
     * Drops the app [dragKey] onto [targetKey]: onto an app it makes a new folder holding both,
     * onto a folder it joins that folder. Only page items can be targets.
     */
    fun mergeInto(dragKey: String, targetKey: String, newId: () -> String = ::newFolderId): Layout {
        if (dragKey == targetKey) return this
        val dragged = item(dragKey) as? Item.App ?: return this
        val base = without(dragKey)
        var merged = false
        val pages = base.pages.map { page ->
            page.map { item ->
                if (item.key != targetKey) {
                    item
                } else {
                    when (item) {
                        is Item.App -> Item.Folder(newId(), DEFAULT_FOLDER_NAME, listOf(item.pkg, dragged.pkg))
                        is Item.Folder -> item.copy(apps = item.apps + dragged.pkg)
                    }.also { merged = true }
                }
            }
        }
        return if (merged) base.copy(pages = pages) else this
    }

    /** Takes [pkg] out of a folder and puts it next to the folder. */
    fun removeFromFolder(folderKey: String, pkg: String): Layout = copy(
        pages = pages.map { page ->
            page.flatMap { item ->
                if (item is Item.Folder && item.key == folderKey && pkg in item.apps) {
                    listOf(item.copy(apps = item.apps - pkg), Item.App(pkg))
                } else {
                    listOf(item)
                }
            }
        },
    ).normalize()

    fun renameFolder(folderKey: String, name: String): Layout {
        val clean = name.trim().ifEmpty { DEFAULT_FOLDER_NAME }
        return copy(
            pages = pages.map { page ->
                page.map { if (it is Item.Folder && it.key == folderKey) it.copy(name = clean) else it }
            },
        )
    }

    /**
     * Dissolves folders that hold fewer than two apps and drops empty pages (keeping one). A
     * one-app folder survives while a hidden app is stashed against it, so unhiding restores it.
     */
    fun normalize(): Layout {
        val stashed = stash.values.filter { it.hidden }.mapNotNullTo(HashSet()) { it.folderId }
        val cleaned = pages.map { page ->
            page.flatMap { item ->
                when {
                    item is Item.Folder && item.apps.isEmpty() -> emptyList()
                    item is Item.Folder && item.apps.size == 1 && item.id !in stashed ->
                        listOf(Item.App(item.apps[0]))
                    else -> listOf(item)
                }
            }
        }
        return copy(pages = cleaned.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) })
    }

    /**
     * Makes every page hold at most [capacity] items by pushing the overflow onto the front of the
     * next page (creating one at the end if needed), so nothing needs scrolling.
     */
    fun paginate(capacity: Int): Layout {
        if (capacity < 1 || pages.all { it.size <= capacity }) return this
        val out = ArrayList<List<Item>>()
        var carry = emptyList<Item>()
        pages.forEach { page ->
            val all = carry + page
            out.add(all.take(capacity))
            carry = all.drop(capacity)
        }
        while (carry.isNotEmpty()) {
            out.add(carry.take(capacity))
            carry = carry.drop(capacity)
        }
        return copy(pages = out)
    }

    /**
     * Undoes [paginate] when capacity grows from [from] to [to] (rotation, dock off): a page that
     * held exactly [from] items was filled by a spill, so it pulls items forward from the pages
     * after it until it holds [to]. Pages that weren't full (a child's deliberate pages) keep
     * their items, though a full page may still draw from them.
     */
    fun expand(from: Int, to: Int): Layout {
        if (from < 1 || to <= from) return this
        val wasFull = pages.map { it.size == from }
        val out = pages.map { it.toMutableList() }
        for (i in out.indices) {
            if (!wasFull[i]) continue
            var j = i + 1
            while (out[i].size < to && j < out.size) {
                val take = minOf(to - out[i].size, out[j].size)
                out[i].addAll(out[j].subList(0, take))
                repeat(take) { out[j].removeAt(0) }
                j++
            }
        }
        return copy(pages = out.filter { it.isNotEmpty() }.ifEmpty { listOf(mutableListOf()) })
    }

    /**
     * Brings the layout in line with the installed apps: forgets uninstalled ones, collapses
     * duplicates, and puts apps it hasn't seen back where they were (hidden apps are stashed with
     * their spot) or on the last page. [installed] is every launchable app in display order;
     * [hidden] ones are taken off the screen but remembered.
     */
    fun reconcile(installed: List<String>, hidden: Set<String> = emptySet()): Layout {
        val visible = installed.filter { it !in hidden }
        val valid = visible.toSet()
        val seen = HashSet<String>()
        fun keep(pkg: String) = pkg in valid && seen.add(pkg)

        // Remember where apps were while they are hidden or missing (an update, a disable, a storage
        // hiccup), so an app that comes back isn't dumped on the last page.
        val installedSet = installed.toSet()
        val newStash = LinkedHashMap<String, Spot>()
        // Keep hidden apps' spots even while they are missing too (hidden and briefly unqueryable).
        stash.forEach { (pkg, spot) -> if (pkg !in installedSet || pkg in hidden) newStash[pkg] = spot }
        installed.filter { it in hidden }.forEach { pkg -> spotOf(pkg)?.let { newStash[pkg] = it } }
        layoutPackages().filter { it !in installedSet }.forEach { pkg ->
            spotOf(pkg)?.let {
                newStash.remove(pkg)
                newStash[pkg] = it.copy(hidden = false)
            }
        }
        newStash.keys.filter { it !in installedSet }.dropLast(MAX_MISSING_STASH).forEach { newStash.remove(it) }

        val favs = favorites.filter(::keep)
        val kept = pages.map { page ->
            page.mapNotNull { item ->
                when (item) {
                    is Item.App -> item.takeIf { keep(it.pkg) }
                    is Item.Folder -> item.copy(apps = item.apps.filter(::keep))
                }
            }
        }.ifEmpty { listOf(emptyList()) }

        var result = Layout(kept, favs, newStash)
        val fresh = visible.filter { it !in seen }
        // In spot order, so apps returning together (Show all) don't shift each other's indexes.
        fresh.filter { it in stash }
            .sortedWith(compareBy({ stash.getValue(it).page }, { stash.getValue(it).index }))
            .forEach { result = result.restore(it, stash.getValue(it)) }
        val rest = fresh.filter { it !in stash }.map { Item.App(it) }
        val pages = result.pages.dropLast(1) + listOf(result.pages.last() + rest)
        return result.copy(pages = pages).normalize()
    }

    private fun layoutPackages(): List<String> =
        favorites + pages.flatMap { page ->
            page.flatMap { if (it is Item.App) listOf(it.pkg) else (it as Item.Folder).apps }
        }

    private fun spotOf(pkg: String): Spot? {
        val fav = favorites.indexOf(pkg)
        if (fav >= 0) return Spot(-1, fav)
        pages.forEachIndexed { p, page ->
            page.forEachIndexed { i, item ->
                if (item is Item.App && item.pkg == pkg) return Spot(p, i)
                if (item is Item.Folder && pkg in item.apps) return Spot(p, i, item.id)
            }
        }
        return null
    }

    /** Puts [pkg] back at [spot]; if its folder is gone, at the same position on that page. */
    private fun restore(pkg: String, spot: Spot): Layout {
        if (spot.folderId != null && pages.any { page -> page.any { it.key == FOLDER_PREFIX + spot.folderId } }) {
            return copy(
                pages = pages.map { page ->
                    page.map { if (it is Item.Folder && it.id == spot.folderId) it.copy(apps = it.apps + pkg) else it }
                },
            )
        }
        if (spot.page < 0 && favorites.size < MAX_FAVORITES) {
            return copy(favorites = favorites.toMutableList().apply { add(spot.index.coerceIn(0, size), pkg) })
        }
        val page = spot.page.coerceIn(0, pages.lastIndex)
        val items = pages[page].toMutableList().apply { add(spot.index.coerceIn(0, size), Item.App(pkg)) }
        return copy(pages = pages.toMutableList().also { it[page] = items })
    }

    /** Moves the favorites row onto the first page, for when favorites get switched off. */
    fun foldFavorites(): Layout =
        if (favorites.isEmpty()) this
        else copy(
            pages = listOf(pages[0] + favorites.map { Item.App(it) }) + pages.drop(1),
            favorites = emptyList(),
        )

    fun toJson(): String = JSONObject().apply {
        put("pages", JSONArray(pages.map { page -> JSONArray(page.map { it.toJson() }) }))
        put("fav", JSONArray(favorites))
        put(
            "stash",
            JSONObject().also { o ->
                stash.forEach { (pkg, spot) ->
                    o.put(
                        pkg,
                        JSONObject().put("p", spot.page).put("i", spot.index)
                            .put("f", spot.folderId ?: JSONObject.NULL).put("h", spot.hidden),
                    )
                }
            },
        )
    }.toString()

    companion object {
        /** Every app on one page, in the order given (alphabetical from the loader), no folders. */
        fun stock(installed: List<String>) = Layout(listOf(installed.map { Item.App(it) }))

        fun fromJson(text: String): Layout? = try {
            val root = JSONObject(text)
            val pages = root.getJSONArray("pages").let { arr ->
                List(arr.length()) { i ->
                    arr.getJSONArray(i).let { page ->
                        List(page.length()) { j -> itemFromJson(page.getJSONObject(j)) }
                    }
                }
            }
            val favs = root.getJSONArray("fav").let { arr -> List(arr.length()) { arr.getString(it) } }
            val stash = root.optJSONObject("stash")?.let { o ->
                o.keys().asSequence().associateWith { pkg ->
                    o.getJSONObject(pkg).let {
                        Spot(
                            it.getInt("p"),
                            it.getInt("i"),
                            it.optString("f").takeIf { f -> f.isNotEmpty() && f != "null" },
                            it.optBoolean("h", true),
                        )
                    }
                }
            } ?: emptyMap()
            Layout(pages.ifEmpty { listOf(emptyList()) }, favs, stash)
        } catch (e: JSONException) {
            null
        }

        private fun itemFromJson(o: JSONObject): Item = when (o.getString("t")) {
            "a" -> Item.App(o.getString("p"))
            "f" -> Item.Folder(
                id = o.getString("id"),
                name = o.getString("n"),
                apps = o.getJSONArray("a").let { arr -> List(arr.length()) { arr.getString(it) } },
            )

            else -> throw JSONException("unknown item type")
        }
    }
}

private fun Item.toJson(): JSONObject = when (this) {
    is Item.App -> JSONObject().put("t", "a").put("p", pkg)
    is Item.Folder -> JSONObject().put("t", "f").put("id", id).put("n", name).put("a", JSONArray(apps))
}

private fun newFolderId(): String = UUID.randomUUID().toString().take(8)

/** Result of [foldNewApps]: which packages the launcher has seen, hides, and is waiting on a parent for. */
data class NewApps(val known: Set<String>, val hidden: Set<String>, val pending: Set<String>)

/**
 * Notices packages that appeared since the last load. With [hideNew] on they start hidden and are
 * marked pending until a parent reviews them. An empty [known] means first run, so nothing already
 * installed is hidden. Never call with an empty [installed] list (a failed query would forget everything).
 */
fun foldNewApps(
    installed: List<String>,
    known: Set<String>,
    hidden: Set<String>,
    pending: Set<String>,
    hideNew: Boolean,
): NewApps {
    val fresh = if (known.isEmpty()) emptySet() else installed.filter { it !in known }.toSet()
    val auto = if (hideNew) fresh else emptySet()
    return NewApps(
        known = known + installed,
        hidden = hidden + auto,
        pending = (pending + auto).intersect(installed.toSet()),
    )
}

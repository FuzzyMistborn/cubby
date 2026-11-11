package com.fuzzymistborn.cubby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutTest {
    private val abc = listOf("a", "b", "c")

    private fun apps(vararg pkgs: String) = pkgs.map { Item.App(it) }

    @Test
    fun stockIsOnePageInOrder() {
        assertEquals(Layout(listOf(apps("a", "b", "c"))), Layout.stock(abc))
    }

    @Test
    fun moveReordersWithinPage() {
        val l = Layout.stock(abc).move("a", Dest.Page(0, 2))
        assertEquals(apps("b", "c", "a"), l.pages[0])
    }

    @Test
    fun moveToNewPageCreatesIt() {
        val l = Layout.stock(abc).move("c", Dest.Page(1, 0))
        assertEquals(listOf(apps("a", "b"), apps("c")), l.pages)
    }

    @Test
    fun moveToFavoritesAndBack() {
        val fav = Layout.stock(abc).move("b", Dest.Favorites(0))
        assertEquals(listOf("b"), fav.favorites)
        assertEquals(apps("a", "c"), fav.pages[0])
        val back = fav.move("b", Dest.Page(0, 1))
        assertEquals(emptyList<String>(), back.favorites)
        assertEquals(apps("a", "b", "c"), back.pages[0])
    }

    @Test
    fun favoritesAreCappedAndAppsOnly() {
        val many = (1..8).map { "p$it" }
        var l = Layout.stock(many)
        many.forEach { l = l.move(it, Dest.Favorites(l.favorites.size)) }
        assertEquals(MAX_FAVORITES, l.favorites.size)

        val folder = Layout.stock(abc).mergeInto("a", "b") { "f1" }
        assertEquals(folder, folder.move(FOLDER_PREFIX + "f1", Dest.Favorites(0)))
    }

    @Test
    fun mergeMakesAndGrowsFolders() {
        val l = Layout.stock(abc).mergeInto("a", "b") { "f1" }
        assertEquals(listOf(Item.Folder("f1", DEFAULT_FOLDER_NAME, listOf("b", "a")), Item.App("c")), l.pages[0])

        val joined = l.mergeInto("c", FOLDER_PREFIX + "f1")
        assertEquals(listOf(Item.Folder("f1", DEFAULT_FOLDER_NAME, listOf("b", "a", "c"))), joined.pages[0])
    }

    @Test
    fun mergeIgnoresFoldersAsDragSourceAndFavoriteTargets() {
        val folder = Layout.stock(abc).mergeInto("a", "b") { "f1" }
        assertEquals(folder, folder.mergeInto(FOLDER_PREFIX + "f1", "c"))
        val fav = Layout.stock(abc).move("a", Dest.Favorites(0))
        assertEquals(fav, fav.mergeInto("b", "a"))
    }

    @Test
    fun removingFromFolderDissolvesSmallFolders() {
        val l = Layout.stock(abc).mergeInto("a", "b") { "f1" }.removeFromFolder(FOLDER_PREFIX + "f1", "a")
        assertEquals(apps("b", "a", "c"), l.pages[0])
    }

    @Test
    fun renameFallsBackToDefault() {
        val l = Layout.stock(abc).mergeInto("a", "b") { "f1" }
        assertEquals("Games", (l.renameFolder(FOLDER_PREFIX + "f1", " Games ").pages[0][0] as Item.Folder).name)
        assertEquals(DEFAULT_FOLDER_NAME, (l.renameFolder(FOLDER_PREFIX + "f1", "  ").pages[0][0] as Item.Folder).name)
    }

    @Test
    fun normalizeDropsEmptyPagesButKeepsOne() {
        val l = Layout(listOf(apps("a"), emptyList(), apps("b"))).normalize()
        assertEquals(listOf(apps("a"), apps("b")), l.pages)
        assertEquals(listOf(emptyList<Item>()), Layout(listOf(emptyList(), emptyList())).normalize().pages)
    }

    @Test
    fun reconcileDropsMissingAddsNewAndDedupes() {
        val old = Layout(
            pages = listOf(
                listOf(Item.App("gone"), Item.Folder("f1", "Games", listOf("a", "gone", "b")), Item.App("a")),
                apps("c"),
            ),
            favorites = listOf("c", "gone"),
        )
        val l = old.reconcile(listOf("a", "b", "c", "d"))
        assertEquals(listOf(Item.Folder("f1", "Games", listOf("a", "b"))), l.pages[0])
        assertEquals(apps("d"), l.pages[1])
        assertEquals(listOf("c"), l.favorites)
    }

    @Test
    fun reconcileOfEmptyLayoutIsStock() {
        assertEquals(Layout.stock(abc), Layout().reconcile(abc))
    }

    @Test
    fun foldFavoritesPutsThemOnFirstPage() {
        val l = Layout.stock(abc).move("b", Dest.Favorites(0)).foldFavorites()
        assertEquals(apps("a", "c", "b"), l.pages[0])
        assertEquals(emptyList<String>(), l.favorites)
    }

    @Test
    fun jsonRoundTrips() {
        val l = Layout.stock(listOf("a", "b", "c", "d"))
            .mergeInto("a", "b") { "f1" }
            .renameFolder(FOLDER_PREFIX + "f1", "Fun")
            .move("d", Dest.Favorites(0))
            .move("c", Dest.Page(1, 0))
        assertEquals(l, Layout.fromJson(l.toJson()))
    }

    @Test
    fun badJsonIsNull() {
        assertNull(Layout.fromJson("not json"))
        assertNull(Layout.fromJson("""{"pages":[[{"t":"x"}]],"fav":[]}"""))
    }

    @Test
    fun hidingAppsRemembersTheirSpotAndUnhidingRestoresIt() {
        val l = Layout.stock(listOf("a", "b", "c", "d"))
            .mergeInto("a", "b") { "f1" }
            .move("d", Dest.Favorites(0))
        val all = listOf("a", "b", "c", "d")

        val hidden = l.reconcile(all, hidden = setOf("a", "d"))
        assertEquals(listOf(Item.Folder("f1", DEFAULT_FOLDER_NAME, listOf("b")), Item.App("c")), hidden.pages[0])
        assertEquals(emptyList<String>(), hidden.favorites)

        val back = hidden.reconcile(all)
        assertEquals(
            listOf(Item.Folder("f1", DEFAULT_FOLDER_NAME, listOf("b", "a")), Item.App("c")),
            back.pages[0],
        )
        assertEquals(listOf("d"), back.favorites)
        assertEquals(emptyMap<String, Spot>(), back.stash)
    }

    @Test
    fun stashSurvivesJson() {
        val l = Layout.stock(listOf("a", "b", "c"))
            .reconcile(listOf("a", "b", "c"), setOf("b"))
            .reconcile(listOf("a", "b"), setOf("b"))
        assertEquals(setOf("b", "c"), l.stash.keys)
        assertEquals(l, Layout.fromJson(l.toJson()))
    }

    @Test
    fun anAppThatBrieflyDisappearsComesBackToItsSpot() {
        val l = Layout.stock(listOf("a", "b", "c")).move("c", Dest.Page(0, 0)) // c, a, b
        val gone = l.reconcile(listOf("a", "b"))
        assertEquals(apps("a", "b"), gone.pages[0])
        assertEquals(false, gone.stash.getValue("c").hidden)
        assertEquals(apps("c", "a", "b"), gone.reconcile(listOf("a", "b", "c")).pages[0])
    }

    @Test
    fun aFolderMemberThatDisappearsReturnsToItsFolderIfItStillExists() {
        val l = Layout.stock(listOf("a", "b", "c", "d")).mergeInto("a", "b") { "f1" }.mergeInto("c", FOLDER_PREFIX + "f1")
        val gone = l.reconcile(listOf("a", "b", "d"))
        assertEquals(Item.Folder("f1", DEFAULT_FOLDER_NAME, listOf("b", "a")), gone.pages[0][0])
        val back = gone.reconcile(listOf("a", "b", "c", "d"))
        assertEquals(Item.Folder("f1", DEFAULT_FOLDER_NAME, listOf("b", "a", "c")), back.pages[0][0])
    }

    @Test
    fun missingAppStashIsCapped() {
        val many = (1..MAX_MISSING_STASH + 20).map { "p$it" }
        val l = Layout.stock(many).reconcile(listOf("p1"))
        assertEquals(MAX_MISSING_STASH, l.stash.size)
    }

    @Test
    fun unhidingToAFolderThatNoLongerExistsReturnsToItsOldPosition() {
        val l = Layout.stock(listOf("a", "b", "c")).mergeInto("a", "b") { "f1" }
        val hidden = l.reconcile(listOf("a", "b", "c"), setOf("a"))
        val without = hidden.copy(pages = listOf(listOf(Item.App("c"))))
        val back = without.reconcile(listOf("a", "b", "c"))
        assertEquals(listOf("a", "c", "b"), back.pages[0].map { it.key })
    }

    @Test
    fun aFolderKeptAliveByAHiddenMemberDissolvesOnceItIsUninstalled() {
        val l = Layout.stock(listOf("a", "b", "c")).mergeInto("a", "b") { "f1" }
        val hidden = l.reconcile(listOf("a", "b", "c"), setOf("a"))
        assertEquals(Item.Folder("f1", DEFAULT_FOLDER_NAME, listOf("b")), hidden.pages[0][0])
        // a (hidden) is uninstalled for good: it no longer keeps the folder alive
        val gone = hidden.copy(stash = hidden.stash.mapValues { it.value.copy(hidden = false) }).reconcile(listOf("b", "c"))
        assertEquals(listOf(Item.App("b"), Item.App("c")), gone.pages[0])
    }

    @Test
    fun paginateSpillsOverflowOntoTheNextPage() {
        val l = Layout(listOf(apps("a", "b", "c", "d", "e"), apps("f"))).paginate(2)
        assertEquals(listOf(apps("a", "b"), apps("c", "d"), apps("e", "f")), l.pages)
    }

    @Test
    fun paginateLeavesFittingPagesAlone() {
        val l = Layout(listOf(apps("a", "b"), apps("c")))
        assertEquals(l, l.paginate(2))
        assertEquals(l, l.paginate(0))
    }

    @Test
    fun paginateAddsPagesAtTheEnd() {
        assertEquals(listOf(apps("a", "b"), apps("c")), Layout.stock(abc).paginate(2).pages)
    }

    @Test
    fun expandUndoesAPaginateSpill() {
        val original = Layout(listOf(apps("a", "b", "c", "d", "e"), apps("f")))
        assertEquals(original, original.paginate(3).expand(3, 5))
    }

    @Test
    fun expandCascadesAcrossManySpilledPages() {
        val l = Layout(listOf(apps("a", "b"), apps("c", "d"), apps("e", "f"), apps("g"))).expand(2, 5)
        assertEquals(listOf(apps("a", "b", "c", "d", "e"), apps("f", "g")), l.pages)
    }

    @Test
    fun expandLeavesPagesThatWereNotFull() {
        val l = Layout(listOf(apps("a", "b"), apps("c")))
        assertEquals(l, l.expand(3, 6))
    }

    @Test
    fun expandIgnoresShrinkingOrUnknownCapacity() {
        val l = Layout(listOf(apps("a", "b"), apps("c")))
        assertEquals(l, l.expand(2, 2))
        assertEquals(l, l.expand(0, 6))
    }

    @Test
    fun aHiddenAppThatGoesMissingKeepsItsSpot() {
        val l = Layout(listOf(apps("a", "b", "c")))
        val hidden = l.reconcile(listOf("a", "b", "c"), setOf("b"))
        // "b" is hidden and then briefly unqueryable.
        val missing = hidden.reconcile(listOf("a", "c"), setOf("b"))
        assertEquals(Spot(0, 1), missing.stash.getValue("b").copy(hidden = true))
        val back = missing.reconcile(listOf("a", "b", "c"))
        assertEquals(apps("a", "b", "c"), back.pages.single())
    }

    @Test
    fun appsReturningTogetherKeepTheirOrder() {
        val l = Layout(listOf(apps("a", "b", "c", "d", "e", "f")))
        val hiddenSet = setOf("c", "d", "e")
        val all = listOf("a", "b", "c", "d", "e", "f")
        val hidden = l.reconcile(all, hiddenSet)
        // Installed order differs from spot order, so restoring in installed order would misplace them.
        val back = hidden.reconcile(listOf("e", "d", "c", "a", "b", "f"))
        assertEquals(apps("a", "b", "c", "d", "e", "f"), back.pages.single())
    }
}

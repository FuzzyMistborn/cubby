package com.fuzzymistborn.cubby

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Tracked outside the pager (favorites, zones): always a hit candidate. */
private const val NO_PAGE = -1

/** Touch size of one dock icon. */
private val DOCK_SLOT = 88.dp

/** Fixed tile size: icon, two label lines and padding. Rows and columns are however many fit. */
private val MIN_TILE_HEIGHT = 172.dp
private val TILE_ICON = 88.dp
private val MIN_TILE_WIDTH = 150.dp
private val TILE_GAP = 12.dp
private val GRID_TOP_PADDING = 12.dp
private val GRID_BOTTOM_PADDING = 4.dp

/** Height of the bottom bar, fixed so entering edit mode never changes how many tiles fit. */
private val BOTTOM_BAR_HEIGHT = 56.dp

private const val ZONE_PAGES = "zone:pages"
private const val ZONE_FAVORITES = "zone:favorites"

// The edge zone must sit inside the grid's side padding, or holding a tile in the outer column
// would count as holding at the edge.
private val GRID_SIDE_PADDING = 32.dp
private val EDGE_ZONE = 28.dp
private const val EDGE_FLIP_MS = 700L
private const val MERGE_DWELL_MS = 450L
private const val MERGE_HINT_MS = 150L
private const val MOVE_COOLDOWN_MS = 150L
private const val REORDER_DWELL_MS = 350L

/** On-screen rectangle (window coordinates) of a tile or drop zone; [owner] guards stale removal. */
private class Tracked(val owner: Any, val rect: Rect, val page: Int)

private class LayoutHolder(var value: Layout) {
    var fromParent: Layout = value
}

/** How a page's grid is laid out: tiles per row, tile height, and how many fit. */
private class GridSpec(val columns: Int, val rows: Int, val tileHeight: Dp) {
    val capacity get() = columns * rows
}

/** Fits as many fixed-size tiles as the pager has room for (at least one); spare room stays empty. */
private fun gridSpec(size: IntSize, density: Density): GridSpec? {
    if (size.width <= 0 || size.height <= 0) return null
    with(density) {
        val gap = TILE_GAP.toPx()
        val width = size.width - 2 * GRID_SIDE_PADDING.toPx()
        val height = size.height - GRID_TOP_PADDING.toPx() - GRID_BOTTOM_PADDING.toPx()
        val columns = ((width + gap) / (MIN_TILE_WIDTH.toPx() + gap)).toInt().coerceAtLeast(1)
        val rows = ((height + gap) / (MIN_TILE_HEIGHT.toPx() + gap)).toInt().coerceAtLeast(1)
        return GridSpec(columns, rows, MIN_TILE_HEIGHT)
    }
}

private data class DragState(
    val key: String,
    val pointer: Offset,
    val grab: Offset,
    val size: Size,
)

private fun destOf(key: String, layout: Layout): Dest? {
    val fav = layout.favorites.indexOf(key)
    if (fav >= 0) return Dest.Favorites(fav)
    val page = layout.pageOf(key)
    if (page < 0) return null
    return Dest.Page(page, layout.pages[page].indexOfFirst { it.key == key })
}

@Composable
private fun trackBounds(map: HashMap<String, Tracked>, key: String, page: Int = NO_PAGE): Modifier {
    val owner = remember { Any() }
    DisposableEffect(key) {
        onDispose { if (map[key]?.owner === owner) map.remove(key) }
    }
    return Modifier.onGloballyPositioned { map[key] = Tracked(owner, it.boundsInWindow(), page) }
}

/**
 * The kids' home screen: pages of apps and folders, an optional favorites row, and (unless the
 * layout is locked) long-press drag to reorder, make folders, move between pages and favorites.
 */
@Composable
fun HomeScreen(
    apps: List<AppEntry>,
    layout: Layout,
    favoritesEnabled: Boolean,
    locked: Boolean,
    tileColor: TileColor,
    searchEnabled: Boolean,
    resetKey: Int,
    onLayoutChange: (Layout) -> Unit,
    onResetLayout: () -> Unit,
    onLaunch: (String) -> Unit,
    onParent: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val byPkg = remember(apps) { apps.associateBy { it.packageName } }
    val currentOnChange by rememberUpdatedState(onLayoutChange)
    // The newest layout including changes made this frame; a plain parameter lags a frame behind,
    // which would let a drop or the next tick work from (and re-save) a stale layout.
    val latest = remember { LayoutHolder(layout) }
    SideEffect {
        if (layout != latest.fromParent) {
            latest.fromParent = layout
            latest.value = layout
        }
    }
    // Pages never scroll: the grid is sized from the room the pager has, and what doesn't fit
    // spills onto the next page.
    var pagerSize by remember { mutableStateOf(IntSize.Zero) }
    val grid = remember(pagerSize, density) { gridSpec(pagerSize, density) }
    val currentCapacity by rememberUpdatedState(grid?.capacity ?: 0)
    fun applyLayout(next: Layout) {
        val paged = next.paginate(currentCapacity)
        latest.value = paged
        currentOnChange(paged)
    }
    // Capacity that grew (rotation, dock off) pulls a stranded last page back. Saved across the
    // activity recreation a rotation causes; a fresh launch (0) never merges, or a child's
    // deliberate page would vanish on every launch.
    var lastCapacity by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(grid?.capacity, layout) {
        val capacity = grid?.capacity ?: 0
        var next = latest.value
        if (lastCapacity in 1 until capacity) next = next.expand(lastCapacity, capacity)
        if (capacity > 0) lastCapacity = capacity
        val paged = next.paginate(capacity)
        if (paged != latest.value) applyLayout(paged)
    }

    var editing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<DragState?>(null) }
    var mergeTarget by remember { mutableStateOf<String?>(null) }
    var dockActive by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val tracked = remember { HashMap<String, Tracked>() }
    val pager = rememberPagerState { layout.pages.size }

    BackHandler(enabled = editing) { editing = false }

    // Leaving the launcher ends edit mode and closes whatever was open.
    LaunchedEffect(resetKey) {
        editing = false
        searching = false
        drag = null
        dockActive = false
        mergeTarget = null
        openFolder = null
        confirmReset = false
    }

    // Only tiles on the page being shown (or outside the pager) count, so a neighbouring page that
    // is still composed mid-scroll can't be picked by mistake.
    fun hit(p: Offset, exclude: String?): String? =
        tracked.entries.firstOrNull { (k, v) ->
            k != exclude && !k.startsWith("zone:") && v.rect.contains(p) &&
                (v.page == NO_PAGE || v.page == pager.targetPage)
        }?.key

    // While a tile is held, a ticker reacts to where the finger is: edge dwell flips pages,
    // dwell over a tile's middle makes a folder, a pass over its rim reorders.
    val draggedKey = drag?.key
    LaunchedEffect(draggedKey) {
        if (draggedKey == null) return@LaunchedEffect
        val edgePx = with(density) { EDGE_ZONE.toPx() }
        val unblockPx = with(density) { 140.dp.toPx() }
        var hoverKey: String? = null
        var hoverSince = 0L
        // A tile we just swapped with can't trigger another swap until the finger leaves it,
        // otherwise the grid animation slides it back under the finger and the two bounce forever.
        var blockKey: String? = null
        var blockPoint = Offset.Zero
        var lastMove = 0L
        var edgeDir = 0
        var edgeSince = 0L

        while (true) {
            delay(40)
            val d = drag ?: break
            val l = latest.value
            val dragged = l.item(d.key) ?: break
            val now = System.currentTimeMillis()
            val p = d.pointer
            val cur = pager.currentPage.coerceIn(l.pages.indices)
            dockActive = tracked[ZONE_FAVORITES]?.rect?.contains(p) == true

            fun change(next: Layout) {
                if (next != l) {
                    applyLayout(next)
                    lastMove = now
                }
            }

            val dir = when {
                hit(p, d.key) != null -> 0
                p.x < origin.x + edgePx -> -1
                p.x > origin.x + boxSize.width - edgePx -> 1
                else -> 0
            }
            if (dir != edgeDir) {
                edgeDir = dir
                edgeSince = now
            }
            if (dir != 0 && now - edgeSince > EDGE_FLIP_MS) {
                val next = cur + dir
                val pageHasOthers = l.pages[cur].any { it.key != d.key }
                if (next in l.pages.indices || (next == l.pages.size && pageHasOthers)) {
                    val dest = Dest.Page(next, if (dir < 0) l.pages[next].size else 0)
                    change(l.move(d.key, dest))
                    mergeTarget = null
                    hoverKey = null
                    // Let the new page exist before scrolling to it.
                    withFrameNanos { }
                    withFrameNanos { }
                    pager.animateScrollToPage(next)
                }
                edgeSince = System.currentTimeMillis()
                continue
            }

            val target = hit(p, d.key)
            if (blockKey != null && (target == null || target != blockKey) &&
                (target != null || (p - blockPoint).getDistance() > unblockPx)
            ) {
                blockKey = null
            }
            if (target == null) {
                hoverKey = null
                mergeTarget = null
                if (now - lastMove > MOVE_COOLDOWN_MS) {
                    val inFavorites = dockActive
                    val inPages = tracked[ZONE_PAGES]?.rect?.contains(p) == true
                    // A full page would just push the item back out again, so only move onto room.
                    val room = currentCapacity < 1 || l.pages[cur].size < currentCapacity
                    if (inFavorites) {
                        if (dragged is Item.App && d.key !in l.favorites) {
                            change(l.move(d.key, Dest.Favorites(l.favorites.size)))
                        }
                    } else if (inPages && room && l.pageOf(d.key) != cur) {
                        change(l.move(d.key, Dest.Page(cur, l.pages[cur].size)))
                    }
                }
                continue
            }

            val rect = tracked.getValue(target).rect
            val middle = rect.deflate(minOf(rect.width, rect.height) * 0.25f)
            val canMerge = dragged is Item.App && l.pageOf(target) >= 0
            if (canMerge && middle.contains(p)) {
                if (target != hoverKey) {
                    hoverKey = target
                    hoverSince = now
                    mergeTarget = null
                } else if (now - hoverSince > MERGE_DWELL_MS) {
                    change(l.mergeInto(d.key, target))
                    mergeTarget = null
                    hoverKey = null
                } else if (now - hoverSince > MERGE_HINT_MS) {
                    mergeTarget = target
                }
            } else {
                // Rim (or a tile that can't be merged into): reorder, but only once the finger
                // has rested here, so crossing a rim on the way to a tile's middle doesn't swap.
                mergeTarget = null
                if (target != hoverKey) {
                    hoverKey = target
                    hoverSince = now
                } else if (target != blockKey && now - hoverSince > REORDER_DWELL_MS &&
                    now - lastMove > MOVE_COOLDOWN_MS
                ) {
                    val dest = destOf(target, l)
                    if (dest != null && dest != destOf(d.key, l)) {
                        change(l.move(d.key, dest))
                        blockKey = target
                        blockPoint = p
                        hoverKey = null
                    }
                }
            }
        }
    }

    val onTap: (Item) -> Unit = { item ->
        when {
            item is Item.Folder -> openFolder = item.key
            editing -> Unit
            item is Item.App -> onLaunch(item.pkg)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .onGloballyPositioned {
                origin = it.positionInWindow()
                boxSize = it.size
            }
            .pointerInput(locked, longPressMs) {
                if (locked) return@pointerInput
                // Watch at the Initial pass so a long-press drag isn't stolen by the pager,
                // but never consume until the long press fires: taps and swipes pass through.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val startKey = hit(down.position + origin, null) ?: return@awaitEachGesture
                    val startRect = tracked.getValue(startKey).rect
                    val slop = viewConfiguration.touchSlop
                    val moved = withTimeoutOrNull(longPressMs) {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial)
                                .changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull true
                            if (change.changedToUp() || (change.position - down.position).getDistance() > slop) {
                                return@withTimeoutOrNull true
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    }
                    if (moved != null) return@awaitEachGesture

                    editing = true
                    val start = down.position + origin
                    drag = DragState(startKey, start, start - startRect.topLeft, startRect.size)
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial)
                            .changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (change.changedToUp() || !change.pressed) break
                        drag = drag?.copy(pointer = change.position + origin)
                    }
                    drag = null
                    dockActive = false
                    mergeTarget = null
                    val settled = latest.value.normalize().paginate(currentCapacity)
                    if (settled != latest.value) applyLayout(settled)
                    // Dropping can remove an emptied page; follow the dropped item to its new index.
                    val page = settled.pageOf(startKey)
                    if (page >= 0) {
                        scope.launch {
                            withFrameNanos { }
                            withFrameNanos { }
                            pager.scrollToPage(page)
                        }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = drag == null,
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { pagerSize = it }
                    .then(trackBounds(tracked, ZONE_PAGES)),
            ) { page ->
                val items = layout.pages.getOrElse(page) { emptyList() }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(grid?.columns ?: 1),
                    userScrollEnabled = false,
                    contentPadding = PaddingValues(
                        start = GRID_SIDE_PADDING,
                        end = GRID_SIDE_PADDING,
                        top = GRID_TOP_PADDING,
                        bottom = GRID_BOTTOM_PADDING,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                    verticalArrangement = Arrangement.spacedBy(TILE_GAP),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.key }) { item ->
                        ItemTile(
                            item = item,
                            byPkg = byPkg,
                            iconSize = TILE_ICON,
                            highlight = item.key == mergeTarget,
                            tileColor = tileColor.color,
                            modifier = Modifier
                                .animateItem()
                                .height(grid?.tileHeight ?: MIN_TILE_HEIGHT)
                                .then(trackBounds(tracked, item.key, page))
                                .alpha(if (item.key == draggedKey) 0f else 1f),
                            onClick = { onTap(item) },
                        )
                    }
                }
            }

            if (favoritesEnabled) {
                // A centered pill of bare icons. Its slot is always reserved (even while empty), so
                // editing doesn't change how much room the pages have.
                Box(
                    Modifier.fillMaxWidth().height(DOCK_SLOT + 24.dp).padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (layout.favorites.isNotEmpty() || editing) Row(
                        Modifier
                            .then(trackBounds(tracked, ZONE_FAVORITES))
                            .clip(RoundedCornerShape(36.dp))
                            .background(Color.White.copy(alpha = if (dockActive) 0.55f else if (editing) 0.32f else 0.22f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .heightIn(min = DOCK_SLOT),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (layout.favorites.isEmpty()) {
                            Text(
                                "Drag favorite apps here",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        layout.favorites.forEach { pkg ->
                            key(pkg) {
                                byPkg[pkg]?.let { app ->
                                    DockIcon(
                                        app = app,
                                        modifier = Modifier
                                            .then(trackBounds(tracked, pkg))
                                            .alpha(if (pkg == draggedKey) 0f else 1f),
                                        onClick = { onTap(Item.App(pkg)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(BOTTOM_BAR_HEIGHT).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editing) {
                    Text(
                        "Drag apps to move them. Drop one on another to make a folder.",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                    )
                    PageDots(count = layout.pages.size, current = pager.currentPage)
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { confirmReset = true }) { Text("Reset", color = Color.White) }
                        Button(onClick = { editing = false }) { Text("Done") }
                    }
                } else {
                    Box(Modifier.weight(1f))
                    PageDots(count = layout.pages.size, current = pager.currentPage)
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val ctx = LocalContext.current
                        if (searchEnabled) {
                            OutlinedButton(onClick = { searching = true }) { Text("Search", color = Color.White) }
                        }
                        Text(
                            "Parents",
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = {
                                        Toast.makeText(ctx, "Parents: press and hold", Toast.LENGTH_SHORT).show()
                                    },
                                    onLongClick = onParent,
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        drag?.let { d ->
            val item = layout.item(d.key)
            val dockApp = if (d.key in layout.favorites) byPkg[d.key] else null
            if (dockApp != null) {
                // Held in the dock: show the bare icon centred under the finger.
                val half = with(density) { (DOCK_SLOT / 2).toPx() }
                DockIcon(
                    app = dockApp,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (d.pointer.x - half - origin.x).roundToInt(),
                                (d.pointer.y - half - origin.y).roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            scaleX = 1.15f
                            scaleY = 1.15f
                        },
                    onClick = {},
                )
            } else if (item != null) {
                ItemTile(
                    item = item,
                    byPkg = byPkg,
                    iconSize = TILE_ICON,
                    highlight = false,
                    tileColor = tileColor.color,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (d.pointer.x - d.grab.x - origin.x).roundToInt(),
                                (d.pointer.y - d.grab.y - origin.y).roundToInt(),
                            )
                        }
                        .size(with(density) { d.size.width.toDp() }, with(density) { d.size.height.toDp() })
                        .graphicsLayer {
                            scaleX = 1.08f
                            scaleY = 1.08f
                            alpha = 0.92f
                        },
                    onClick = {},
                )
            }
        }
    }

    openFolder?.let { folderKey ->
        val folder = layout.item(folderKey) as? Item.Folder
        if (folder == null) {
            // The folder dissolved or vanished under the dialog; close it outside composition.
            LaunchedEffect(folderKey) { openFolder = null }
        } else {
            FolderDialog(
                folder = folder,
                byPkg = byPkg,
                canEdit = editing,
                onRename = { applyLayout(latest.value.renameFolder(folder.key, it)) },
                onMoveOut = { applyLayout(latest.value.removeFromFolder(folder.key, it)) },
                onLaunch = {
                    openFolder = null
                    onLaunch(it)
                },
                onDismiss = { openFolder = null },
            )
        }
    }

    if (searching) {
        SearchDialog(
            apps = apps,
            onLaunch = {
                searching = false
                onLaunch(it)
            },
            onDismiss = { searching = false },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the home screen?") },
            text = {
                Text("Every app goes back to one page in alphabetical order. Folders and favorites are removed.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetLayout()
                    scope.launch { pager.scrollToPage(0) }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    if (count < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == current) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 1f else 0.5f)),
            )
        }
    }
}

@Composable
private fun ItemTile(
    item: Item,
    byPkg: Map<String, AppEntry>,
    iconSize: Dp,
    highlight: Boolean,
    tileColor: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    when (item) {
        is Item.App -> {
            val app = byPkg[item.pkg] ?: return
            TileBody(app.label, highlight, tileColor, modifier, onClick) { AppIcon(app, iconSize) }
        }

        is Item.Folder -> TileBody(item.name, highlight, tileColor, modifier, onClick) {
            FolderIcon(item, byPkg, iconSize)
        }
    }
}

@Composable
private fun TileBody(
    label: String,
    highlight: Boolean,
    tileColor: Color,
    modifier: Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(if (highlight) Color(0xFFFFE08A) else tileColor.copy(alpha = 0.88f))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Color.Black,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A bare app icon for the dock; the label is for screen readers only. */
@Composable
private fun DockIcon(app: AppEntry, modifier: Modifier, onClick: () -> Unit) {
    Image(
        bitmap = app.icon,
        contentDescription = app.label,
        modifier = modifier
            .size(DOCK_SLOT)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}

@Composable
private fun AppIcon(app: AppEntry, size: Dp) {
    Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(size))
}

/** A rounded square showing up to four of the folder's app icons. */
@Composable
private fun FolderIcon(folder: Item.Folder, byPkg: Map<String, AppEntry>, size: Dp) {
    val inner = size / 2 - 4.dp
    Column(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Color(0xFFD9DEE8))
            .padding(4.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        folder.apps.take(4).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { pkg -> byPkg[pkg]?.let { AppIcon(it, inner) } }
            }
        }
    }
}

@Composable
private fun FolderDialog(
    folder: Item.Folder,
    byPkg: Map<String, AppEntry>,
    canEdit: Boolean,
    onRename: (String) -> Unit,
    onMoveOut: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(folder.key) { mutableStateOf(folder.name) }
    val close = {
        if (canEdit && name != folder.name) onRename(name)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = close,
        title = {
            if (canEdit) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(20) }, singleLine = true)
            } else {
                Text(folder.name)
            }
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(folder.apps.mapNotNull { byPkg[it] }, key = { it.packageName }) { app ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(enabled = !canEdit) { onLaunch(app.packageName) }
                                .padding(8.dp),
                        ) {
                            AppIcon(app, 64.dp)
                            Text(
                                app.label,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (canEdit) {
                            TextButton(onClick = {
                                // Moving out can dissolve the folder, so keep a rename typed so far.
                                if (name != folder.name) onRename(name)
                                onMoveOut(app.packageName)
                            }) { Text("Move out") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done") } },
    )
}

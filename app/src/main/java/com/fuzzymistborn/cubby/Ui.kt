package com.fuzzymistborn.cubby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PinDialogMode { SET_FIRST, VERIFY, CHANGE }

/** Package broadcasts come in bursts (an update is several); wait for quiet before reloading. */
private const val RELOAD_DEBOUNCE_MS = 400L

@Composable
fun LauncherRoot(
    prefs: Prefs,
    screen: Screen,
    onScreenChange: (Screen) -> Unit,
    leaveCount: Int,
    onPickerOpen: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var hidden by remember { mutableStateOf(prefs.hiddenApps) }
    var layoutLocked by remember { mutableStateOf(prefs.layoutLocked) }
    var favoritesEnabled by remember { mutableStateOf(prefs.favoritesEnabled) }
    var hideNewApps by remember { mutableStateOf(prefs.hideNewApps) }
    var searchEnabled by remember { mutableStateOf(prefs.searchEnabled) }
    var tileColor by remember { mutableStateOf(TileColor.fromName(prefs.tileColor)) }
    var parentTimeout by remember { mutableIntStateOf(prefs.parentTimeoutMinutes) }
    var onboarding by remember { mutableStateOf(!prefs.onboardingDone) }
    var pending by remember { mutableStateOf(prefs.pendingApps) }
    var layout by remember { mutableStateOf(prefs.layout ?: Layout()) }
    var pinDialog by remember { mutableStateOf<PinDialogMode?>(null) }
    var hasPin by remember { mutableStateOf(prefs.hasPin()) }
    var pinBusy by remember { mutableStateOf(false) }
    // Read by PIN coroutines that may outlive the screen they started on.
    val currentLeave by rememberUpdatedState(leaveCount)

    val allApps = apps.orEmpty()
    val visibleApps = remember(apps, hidden) { allApps.filter { it.packageName !in hidden } }
    val allPkgs = remember(apps) { allApps.map { it.packageName } }
    val visiblePkgs = remember(visibleApps) { visibleApps.map { it.packageName } }

    fun saveLayout(next: Layout) {
        layout = next
        prefs.layout = next
    }

    var pendingImport by remember { mutableStateOf<Backup?>(null) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        onPickerOpen(false)
        if (uri == null) return@rememberLauncherForActivityResult
        val json = Backup(
            layout, hidden, layoutLocked, favoritesEnabled, hideNewApps,
            searchEnabled, tileColor.name,
        ).toJson()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "Backup saved" else "Couldn't save the backup", Toast.LENGTH_SHORT).show()
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPickerOpen(false)
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val backup = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                }.getOrNull()?.let(Backup::fromJson)
            }
            if (backup == null) {
                Toast.makeText(context, "That isn't a Cubby backup", Toast.LENGTH_SHORT).show()
            } else {
                pendingImport = backup
            }
        }
    }

    // Query the package manager (labels, icons) off the main thread.
    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) delay(RELOAD_DEBOUNCE_MS)
        var loaded = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        // A transient empty answer (package manager busy) must not blank the home screen: retry
        // on the first load, and keep the current list on later ones.
        var tries = 0
        while (loaded.isEmpty() && apps == null && tries++ < 3) {
            delay(1000)
            loaded = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        }
        if (loaded.isEmpty() && apps != null) return@LaunchedEffect
        // Decide what is new before anything renders, so a new app never flashes onto the home screen.
        if (loaded.isNotEmpty()) {
            val r = foldNewApps(
                loaded.map { it.packageName }, prefs.knownApps, prefs.hiddenApps, prefs.pendingApps, prefs.hideNewApps,
            )
            prefs.knownApps = r.known
            prefs.hiddenApps = r.hidden
            prefs.pendingApps = r.pending
            hidden = r.hidden
            pending = r.pending
        }
        apps = loaded
    }

    // Fold newly installed, removed, hidden or unhidden apps into the saved arrangement.
    LaunchedEffect(allPkgs, hidden, favoritesEnabled) {
        // Nothing loaded yet (or an implausible empty list): don't reconcile against it.
        if (allPkgs.isEmpty()) return@LaunchedEffect
        var next = layout.reconcile(allPkgs, hidden)
        if (!favoritesEnabled) next = next.foldFavorites()
        if (next != layout) saveLayout(next)
    }

    // Leaving the launcher closes any half-finished PIN dialog.
    LaunchedEffect(leaveCount) {
        pinDialog = null
        pendingImport = null
    }

    // Keep the grid current when apps are installed, removed or updated.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // An update sends REMOVED then ADDED; reloading in between would briefly lose the
                // app and make the layout forget where it was.
                if (i.action == Intent.ACTION_PACKAGE_REMOVED &&
                    i.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                ) return
                // A real uninstall forgets the app, so reinstalling it counts as new again.
                if (i.action == Intent.ACTION_PACKAGE_REMOVED) {
                    i.data?.schemeSpecificPart?.let { pkg ->
                        prefs.knownApps = prefs.knownApps - pkg
                        prefs.pendingApps = prefs.pendingApps - pkg
                    }
                }
                reloadTick++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // As a launcher, Back must not exit; from the parent screen it returns to the kid view.
    BackHandler(enabled = true) {
        if (screen == Screen.PARENT) onScreenChange(Screen.HOME)
    }

    if (apps != null) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                apps = visibleApps,
                layout = layout,
                favoritesEnabled = favoritesEnabled,
                locked = layoutLocked,
                tileColor = tileColor,
                searchEnabled = searchEnabled,
                resetKey = leaveCount,
                onLayoutChange = ::saveLayout,
                onResetLayout = { saveLayout(Layout.stock(visiblePkgs)) },
                onLaunch = { launchApp(context, it) },
                onParent = {
                    if (prefs.parentUnlocked()) onScreenChange(Screen.PARENT) else pinDialog = PinDialogMode.VERIFY
                },
            )

            Screen.PARENT -> ParentScreen(
                apps = allApps,
                hidden = hidden,
                pending = pending,
                searchEnabled = searchEnabled,
                onSearchEnabledChange = {
                    searchEnabled = it
                    prefs.searchEnabled = it
                },
                tileColor = tileColor,
                onTileColorChange = {
                    tileColor = it
                    prefs.tileColor = it.name
                },
                parentTimeout = parentTimeout,
                onParentTimeoutChange = {
                    parentTimeout = it
                    prefs.parentTimeoutMinutes = it
                },
                hideNewApps = hideNewApps,
                onHideNewAppsChange = {
                    hideNewApps = it
                    prefs.hideNewApps = it
                },
                layoutLocked = layoutLocked,
                onLayoutLockedChange = {
                    layoutLocked = it
                    prefs.layoutLocked = it
                },
                favoritesEnabled = favoritesEnabled,
                onFavoritesChange = {
                    favoritesEnabled = it
                    prefs.favoritesEnabled = it
                },
                onResetLayout = { saveLayout(Layout.stock(visiblePkgs)) },
                onExport = {
                    onPickerOpen(true)
                    exportPicker.launch("cubby-backup.json")
                },
                onImport = {
                    onPickerOpen(true)
                    importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                },
                onSetVisible = { pkgs, visible ->
                    hidden = if (visible) hidden - pkgs else hidden + pkgs
                    pending = pending - pkgs
                    prefs.hiddenApps = hidden
                    prefs.pendingApps = pending
                },
                onChangePin = { pinDialog = PinDialogMode.CHANGE },
                onSwitchLauncher = {
                    context.startActivity(
                        Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                onDone = { onScreenChange(Screen.HOME) },
            )
        }
    }

    // Only ever shown on the parent screen, never over the kid view.
    pendingImport?.takeIf { screen == Screen.PARENT }?.let { b ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Restore this backup?") },
            text = { Text("This replaces the current layout, hidden apps and settings. The PIN is not changed.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    hidden = b.hidden
                    layoutLocked = b.layoutLocked
                    favoritesEnabled = b.favoritesEnabled
                    hideNewApps = b.hideNewApps
                    searchEnabled = b.searchEnabled
                    tileColor = TileColor.fromName(b.tileColor)
                    pending = emptySet()
                    prefs.hiddenApps = hidden
                    prefs.layoutLocked = layoutLocked
                    prefs.favoritesEnabled = favoritesEnabled
                    prefs.hideNewApps = hideNewApps
                    prefs.searchEnabled = searchEnabled
                    prefs.tileColor = tileColor.name
                    prefs.pendingApps = pending
                    // Apps in the backup that aren't installed here wait in the stash.
                    var next = b.layout.reconcile(allPkgs, b.hidden)
                    if (!b.favoritesEnabled) next = next.foldFavorites()
                    saveLayout(next)
                    Toast.makeText(context, "Backup restored", Toast.LENGTH_SHORT).show()
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }

    if (onboarding && hasPin && screen == Screen.PARENT) {
        val finish = {
            onboarding = false
            prefs.onboardingDone = true
        }
        AlertDialog(
            onDismissRequest = finish,
            title = { Text("Welcome to Cubby") },
            text = {
                Text(
                    "1. Make Cubby the home app so kids land here (Switch launcher).\n" +
                        "2. Choose which apps the kids can see in the list on this screen.\n" +
                        "3. Back on the home screen, press and hold the faint \"Parents\" text " +
                        "(bottom right) to open this screen again.\n" +
                        "4. Press and hold an app on the home screen to rearrange.",
                )
            },
            confirmButton = { TextButton(onClick = finish) { Text("Got it") } },
            dismissButton = {
                TextButton(onClick = {
                    finish()
                    context.startActivity(
                        Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Switch launcher") }
            },
        )
    }

    // First run (or unreadable PIN data): a parent has to set a PIN before the launcher is usable,
    // so a child can never be the one who creates it.
    val activeMode = if (!hasPin) PinDialogMode.SET_FIRST else pinDialog
    activeMode?.let { mode ->
        var error by remember(mode) { mutableStateOf<String?>(null) }
        PinDialog(
            title = when (mode) {
                PinDialogMode.SET_FIRST -> "Create a parent PIN to finish setup"
                PinDialogMode.VERIFY -> "Enter parent PIN"
                PinDialogMode.CHANGE -> "Choose a new PIN"
            },
            error = error,
            cancellable = mode != PinDialogMode.SET_FIRST,
            onDismiss = { pinDialog = null },
            onSubmit = submit@{ pin ->
                if (pinBusy) return@submit
                when (mode) {
                    PinDialogMode.VERIFY -> {
                        pinBusy = true
                        val startLeave = currentLeave
                        scope.launch {
                            val ok = withContext(Dispatchers.Default) { prefs.verifyPin(pin) }
                            pinBusy = false
                            // Left the launcher while verifying: don't open the parent screen behind it.
                            if (currentLeave != startLeave) return@launch
                            if (ok) {
                                pinDialog = null
                                prefs.extendParentUnlock()
                                onScreenChange(Screen.PARENT)
                            } else {
                                val wait = prefs.lockoutRemainingMs()
                                error = if (wait > 0) {
                                    "Too many tries. Wait ${(wait + 999) / 1000}s."
                                } else {
                                    "Wrong PIN."
                                }
                            }
                        }
                    }

                    PinDialogMode.SET_FIRST, PinDialogMode.CHANGE -> {
                        if (pin.length !in 4..8 || !pin.all { it.isDigit() }) {
                            error = "Use 4 to 8 digits."
                        } else if (mode == PinDialogMode.CHANGE && screen != Screen.PARENT) {
                            // Only reachable from the parent screen; never from a stale dialog.
                            pinDialog = null
                        } else {
                            pinBusy = true
                            val startLeave = currentLeave
                            scope.launch {
                                withContext(Dispatchers.Default) { prefs.setPin(pin) }
                                pinBusy = false
                                hasPin = true
                                pinDialog = null
                                if (currentLeave == startLeave) onScreenChange(Screen.PARENT)
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ParentScreen(
    apps: List<AppEntry>,
    hidden: Set<String>,
    pending: Set<String>,
    searchEnabled: Boolean,
    onSearchEnabledChange: (Boolean) -> Unit,
    tileColor: TileColor,
    onTileColorChange: (TileColor) -> Unit,
    parentTimeout: Int,
    onParentTimeoutChange: (Int) -> Unit,
    hideNewApps: Boolean,
    onHideNewAppsChange: (Boolean) -> Unit,
    layoutLocked: Boolean,
    onLayoutLockedChange: (Boolean) -> Unit,
    favoritesEnabled: Boolean,
    onFavoritesChange: (Boolean) -> Unit,
    onResetLayout: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSetVisible: (Set<String>, Boolean) -> Unit,
    onChangePin: () -> Unit,
    onSwitchLauncher: () -> Unit,
    onDone: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        LazyVerticalGrid(
            columns = if (landscape) GridCells.Adaptive(300.dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Parent settings", style = MaterialTheme.typography.headlineSmall)
                    Row(
                        Modifier.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onDone) { Text("Done") }
                        OutlinedButton(onClick = onChangePin) { Text("Change PIN") }
                        OutlinedButton(onClick = onSwitchLauncher) { Text("Switch launcher") }
                    }
                    SettingSwitch("Lock layout (kids can't rearrange)", layoutLocked, onLayoutLockedChange)
                    SettingSwitch("Show a dock of favorite apps", favoritesEnabled, onFavoritesChange)
                    SettingSwitch("Hide newly installed apps until I approve them", hideNewApps, onHideNewAppsChange)
                    SettingSwitch("Show a Search button", searchEnabled, onSearchEnabledChange)
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Skip the PIN for", modifier = Modifier.padding(end = 4.dp))
                        listOf(0 to "Never", 1 to "1 min", 5 to "5 min", 15 to "15 min").forEach { (minutes, label) ->
                            if (minutes == parentTimeout) {
                                Button(onClick = { onParentTimeoutChange(minutes) }) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { onParentTimeoutChange(minutes) }) { Text(label) }
                            }
                        }
                    }
                    Row(
                        Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Tile color", modifier = Modifier.padding(end = 4.dp))
                        TileColor.entries.forEach { option ->
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(option.color)
                                    .border(
                                        width = if (option == tileColor) 3.dp else 1.dp,
                                        color = if (option == tileColor) MaterialTheme.colorScheme.primary else Color.Gray,
                                        shape = CircleShape,
                                    )
                                    .clickable(onClickLabel = option.label) { onTileColorChange(option) },
                            )
                        }
                    }
                    Row(
                        Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { confirmReset = true }) { Text("Reset layout to default") }
                        OutlinedButton(onClick = onExport) { Text("Back up") }
                        OutlinedButton(onClick = onImport) { Text("Restore") }
                    }
                    Text("Show these apps to the kids:", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Row(
                        Modifier.padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val pkgs = shown.map { it.packageName }.toSet()
                        OutlinedButton(onClick = { onSetVisible(pkgs, true) }) { Text("Show all") }
                        OutlinedButton(onClick = { onSetVisible(pkgs, false) }) { Text("Hide all") }
                    }
                }
            }
            items(shown, key = { it.packageName }) { app ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                    Text(app.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (app.packageName in pending) {
                        Text("New", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                    Switch(
                        checked = app.packageName !in hidden,
                        onCheckedChange = { onSetVisible(setOf(app.packageName), it) },
                    )
                }
            }
        }
        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Reset the layout?") },
                text = {
                    Text("Every app goes back to one page in alphabetical order. Folders and favorites are removed.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmReset = false
                        onResetLayout()
                    }) { Text("Reset") }
                },
                dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PinDialog(
    title: String,
    error: String?,
    cancellable: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (cancellable) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = cancellable, dismissOnClickOutside = cancellable),
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(pin); pin = "" }) { Text("OK") }
        },
        dismissButton = if (cancellable) {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else {
            null
        },
    )
}

# Notes for AI agents

Cubby: a Compose home-screen launcher for kids' tablets (package `com.fuzzymistborn.cubby`).
Family Link does enforcement; this app is only the friendly layer. See README.md for features.

## Layout of the code
- `Layout.kt`: pure-Kotlin model (pages of `Item.App`/`Item.Folder`, favorites row), JSON
  (de)serialization, `move`/`mergeInto`/`reconcile`/`normalize`. No Android UI types; unit tested in
  `app/src/test/.../LayoutTest.kt`. Put layout rules here, not in composables.
  `reconcile(installed, hidden)` keeps a `stash` of where hidden *and missing* apps were (capped at
  `MAX_MISSING_STASH`), so an app that is unhidden, updated or briefly unqueryable returns to its
  page/folder/favorites spot instead of the end of the last page. Only stash entries with
  `hidden = true` keep a one-app folder alive.
- `Home.kt`: kid home screen: pager of grids, long-press drag, folder dialog, favorites row.
- `Ui.kt`: `LauncherRoot` (state + wiring), parent screen, PIN dialog. Apps load on `Dispatchers.IO`
  (debounced 400ms after package broadcasts); nothing renders or reconciles until the first load, and
  an empty list is never reconciled against.
- `Prefs.kt`: SharedPreferences (hidden apps, layout JSON, lock/favorites flags, PIN hash).
- `Apps.kt`: loads launchable apps (blocking, call off the main thread; icons are pre-rendered to
  `ImageBitmap` once), launches them with a guard against disabled/suspended apps.
- `Backup.kt`: pure-Kotlin export/import of layout, hidden apps and settings (never the PIN). Parent
  screen uses the Storage Access Framework; restore re-runs `reconcile` against installed apps. New
  fields need defaults in `fromJson` so older backups still load.
- `HomeExtras.kt`: `SearchDialog` and the `TileColor` presets (a clock/date was tried and removed on
  purpose; don't re-add it). `foldNewApps` (in `Layout.kt`) implements "hide new apps
  until approved" using `Prefs.knownApps`/`pendingApps`.
- `Background.kt`: `HomeBackground` (system wallpaper, gradient presets, `PHOTO`) and the photo store
  (`filesDir/home_background.jpg`, downscaled to 2048px). Backups carry only the name; a restored `PHOTO`
  with no file falls back to the system wallpaper.
- `TempAccess.kt`: pure helpers for timed allowances of hidden apps (`Prefs.tempAllowed`, package to
  wall-clock expiry; not in backups). `LauncherRoot` re-hides expired ones on every `leaveCount` bump and
  on a 30s poll. A manual show/hide on the parent screen cancels the timer. Wall clock, so a changed
  date can extend an allowance (Family Link normally locks the clock).
- `Network.kt`: Wi-Fi state (`wifiConnected`, `watchWifi`). `Prefs.wifiOnlyApps` (in backups) are hidden while
  off Wi-Fi: `LauncherRoot` uses `effectiveHidden = hidden + wifiOnly` for visible apps and `reconcile` (so
  search and the stash behave as for hidden apps), while the parent screen keeps showing `hidden`. Going
  offline is debounced 3s; coming back is immediate. Wi-Fi only, not mobile data or validated internet.
- `MainActivity.kt`: starts the parent grace window in `changeScreen` (the moment the parent leaves, not from a
  composition effect, which can be deferred while hidden). Skips the stop-reset while a backup/restore file
  picker is open (`pickerOpen`), so those flows stay on the parent screen. Bumps `leaveCount` on stop/new intent; `LauncherRoot` and `HomeScreen` use it to
  close PIN dialogs, edit mode and folder dialogs when the launcher is left.

## Build and test
- `./gradlew testDebugUnitTest assembleDebug assembleRelease --warning-mode all` should finish with
  no Gradle deprecation warnings. Keep it that way.
- Versions: Gradle 9.8.2, AGP 9.5.1 (built-in Kotlin, so no `kotlin.android` plugin and no
  `kotlinOptions`), Kotlin 2.4.21 (compose plugin), compileSdk 37, targetSdk 35, minSdk 29.
- New Compose libraries can force a higher `compileSdk`; AGP auto-downloads the platform if the
  license is accepted. Raise `compileSdk` without raising `targetSdk` (behavior changes).
- Unit tests need `org.json:json` as a test dependency, because the Android stub jar's org.json
  throws "not mocked".
- When removing "unused" imports, keep `androidx.compose.runtime.getValue`/`setValue`: they are used
  implicitly by `by` delegates, so a text search for the name finds nothing.

## Dev environment (this host)
- Fedora with Android Studio as a **Flatpak** (`com.google.AndroidStudio`). Open the project with
  `flatpak run com.google.AndroidStudio <path>`. Studio's config, AVDs and logs live under
  `~/.var/app/com.google.AndroidStudio/` (AVDs: `config/.android/avd`, log:
  `cache/Google/AndroidStudio*/log/idea.log`).
- SDK is `~/Android/Sdk` (no cmdline-tools, so no `sdkmanager`; install SDK parts from Studio's SDK
  Manager). Host `adb` is at `/home/linuxbrew/.linuxbrew/bin/adb`; java is Homebrew JDK 17.
- **The emulator crashes if Studio launches it** (Qt "xcb" plugin / no display inside the sandbox,
  exit 134). Launch it from the host instead:
  `ANDROID_AVD_HOME=~/.var/app/com.google.AndroidStudio/config/.android/avd ~/Android/Sdk/emulator/emulator -avd Pixel_Tablet -gpu host`
- A corrupted SDK package (e.g. an empty `build-tools/34.0.0`) shows up as "Installed Build Tools
  revision X is corrupted": delete that folder and reinstall it from the SDK Manager.
- Debug APKs from Studio and from `./gradlew` can be signed with different keys; `adb install -r`
  then fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. Uninstall first (this wipes app data).
- Emulator is 2560x1600 at density 2.0. Screenshots shown to you may be scaled; multiply by the
  stated factor to get device pixels.

## Testing on the emulator
- Install: `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`, then
  `adb shell am start -n com.fuzzymistborn.cubby/.MainActivity`.
- Drive touches with `adb shell input motionevent DOWN|MOVE|UP x y` (long-press = DOWN, sleep ~1s,
  then MOVE in steps, sleep to dwell, UP). `input swipe`/`draganddrop` don't hold long enough.
- In zsh, wrap adb in a **function** (`emu() { adb -s emulator-5554 "$@"; }`). A string variable
  (`A="adb ..."; $A ...`) isn't word-split and silently fails; `e` is already an alias.
- If a screenshot looks unchanged, check the commands actually ran before concluding anything. The
  user may also be using the emulator by hand; unexpected dialogs or PINs mean they are.
- The test device's parent PIN is 1234 (set it again after any `pm clear`; the first-run dialog
  appears, its OK button sits further right, about x=1586 at 2560 wide, than the verify dialog's
  at about x=1500). Typing right after a dialog appears can garble `input text`; wait about a second
  and screenshot before pressing OK when a PIN matters. Debug builds allow `run-as com.fuzzymistborn.cubby` to edit
  `shared_prefs/cubby.xml` (e.g. add `<boolean name="favorites_enabled" value="true" />`).
- After a `Reset layout`, the layout is one alphabetical page with no folders or favorites.

## Drag-and-drop design (Home.kt) and pitfalls
- The gesture handler on the root box watches the **Initial** pointer pass and only consumes once a
  long press fires, so taps and pager swipes still work. The pager must not own the drag, or it
  steals horizontal moves.
- Tiles register window-coordinate bounds in a map (with an owner token so a disposed tile can't
  remove its replacement). A 40ms ticker coroutine reads the finger position and applies rules:
  edge hold flips/creates pages, middle hold (450ms) makes a folder, rim hold (350ms) reorders.
- The edge-flip zone (`EDGE_ZONE`) must stay narrower than `GRID_SIDE_PADDING`, and only counts when
  no tile is under the finger, or holding a tile in the outer column flips pages.
- Every layout change goes through `applyLayout`, which updates a `LayoutHolder` synchronously;
  read `latest.value`, not the `layout` parameter (it lags a frame). After a drop, `normalize()` can
  remove an emptied page, so the pager scrolls to the dropped item's page.
- **Do not reorder on instant rim contact.** It swaps while the finger is still on its way to a
  tile's middle, then the grid animation slides the other tile back under the finger and the two
  swap forever. Keep the rim dwell and the "block the tile we just swapped with until the finger
  leaves it" rule.
- Pages never scroll. `gridSpec` sizes tiles into the pager's measured size: landscape always gets
  `LANDSCAPE_ROWS` (3) rows, shrinking the tile (and icon) down to `MIN_TILE_HEIGHT` to fit whatever
  else is on screen (search bar, dock); portrait fits as many `MAX_TILE_HEIGHT` tiles as there is room
  for. Tiles never grow past `MAX_TILE_HEIGHT` (kids found stretched tall tiles ugly). `Layout.paginate(capacity)` spills
  overflow onto the front of the next page. `applyLayout` always paginates. Shrinking capacity
  pushes items forward; when it grows (rotation, dock off) `Layout.expand(from, to)` undoes that: only
  pages holding exactly the old capacity (spill-filled) pull items forward, so a child's deliberate
  short page stays put. `HomeScreen` keeps the last capacity in `rememberSaveable` (a rotation
  recreates the activity) and never expands on a fresh launch (last capacity 0).
- The edit toolbar (hint, Reset, Done) lives in the fixed-height bottom bar and the search slot is
  reserved while the search bar is on, so entering edit mode never changes the capacity. The dock slot is
  always reserved when favorites are on (the empty dock only shows in edit mode). Hiding an empty
  dock and toggling favorites with a star badge on each tile were both tried and rejected; keep the
  dock always showing and favorites added by dragging. Keep capacity stable, or items would reshuffle
  under the child's finger. Dragging onto a full page is refused (`room` check in the ticker) because paginate would push the item straight back out.
- Locked folders (`Item.Folder.locked`, saved in layout JSON and so in backups): tapping one asks for the
  parent PIN (`PinDialogMode.ACTION`, a one-off check that does not start the parent grace window).
  Lock is set from the folder dialog in edit mode (PIN first); "Remove lock" shows once it is open.
  Apps in locked folders are filtered out of search, nothing can be dropped into one, a locked
  one-app folder is not dissolved, and the kid-side Reset button is hidden while any exist.
- Pinned apps (`Layout.pinned`, max `MAX_PINNED`, in layout JSON so in backups): `enforcePinned()` keeps them
  first on page 1 and runs in `reconcile` and `applyLayout` (before `paginate`). Kids can't start a drag on
  one or merge into/out of one. Parent "Arrange home screen" is `HomeScreen(parentMode = true)` shown
  from `LauncherRoot` while on the parent screen; there `enforcePinned(reorder = true)` lets a drag
  among pinned apps set their order. Reset keeps pins (`resetKeepingPins`).
- Folders hold apps only (no nesting), favorites hold apps only (max 6). One-app folders dissolve
  and empty pages are removed on drop (`normalize`).

## PIN and security rules
- A parent PIN must exist before the launcher is usable: with no (or unreadable) PIN, a
  non-dismissable "Create a parent PIN to finish setup" dialog shows, so a child can't create it.
- PIN hashing (PBKDF2) runs on `Dispatchers.Default`, never the main thread. CHANGE only applies
  while on the parent screen, and PIN dialogs are cleared when the launcher is left.
- Parent-screen grace window (`Prefs.parentTimeoutMinutes`, default 5, 0 = always ask): after a PIN
  entry, re-opening the parent screen skips the PIN until that many minutes after the parent last
  *left* the parent screen. The deadline is in memory only (monotonic clock), so a reboot or restart
  always brings the PIN back, and `setPin` clears it. It is deliberately not part of backups.
- Lockout uses `SystemClock.elapsedRealtime` plus the boot count, so changing the clock can't
  shorten it and a reboot restarts the full delay.

## Product decisions
- Kids can rearrange by default; a parent switch locks the layout. Layout is per tablet, no sync.
- The favorites "dock" is optional and off by default; turning it off folds favorites onto page 1.
  It is a compact centered pill of bare icons (`DockIcon`, `DOCK_SLOT` 88dp, labels only as
  content descriptions), not a full-width row: a full-width labeled row took ~170dp and squeezed
  the grid. Keep it small. Its bounds are the `ZONE_FAVORITES` drop zone; `dockActive` brightens
  it while a drag is over it.- Search is a wide pill (`SearchPill`) in a slot above the grid, not a bottom-bar button: the bottom
  right is the hardest corner for small children to reach. Drawn with a Canvas magnifier; the project
  has no icons library.
- Prefer a short dwell and clear visual feedback (yellow highlight) over precise gestures; users are
  small children.

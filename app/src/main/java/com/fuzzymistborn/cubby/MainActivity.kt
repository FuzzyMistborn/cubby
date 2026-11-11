package com.fuzzymistborn.cubby

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { HOME, PARENT }

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private var screen by mutableStateOf(Screen.HOME)

    // True while a system file picker (backup, restore) is open from the parent screen, so the
    // stop it causes doesn't throw the parent back to the kid view.
    private var pickerOpen = false

    // Bumped whenever the launcher is left, so open dialogs and edit mode don't linger behind
    // another app and greet the next person who comes back.
    private var leaveCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        setContent {
            MaterialTheme {
                LauncherRoot(
                    prefs = prefs,
                    screen = screen,
                    onScreenChange = ::changeScreen,
                    leaveCount = leaveCount,
                    onPickerOpen = { pickerOpen = it },
                )
            }
        }
    }

    // The PIN-free grace window counts from the moment the parent leaves the parent screen. Starting
    // it here (not from a composition effect, which can be deferred while the window is hidden)
    // means it can't begin late, at the next resume.
    private fun changeScreen(next: Screen) {
        if (screen == Screen.PARENT && next != Screen.PARENT) prefs.extendParentUnlock()
        screen = next
    }

    // Pressing the Home button re-delivers the intent (singleTask): always land on the kid view.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pickerOpen = false
        changeScreen(Screen.HOME)
        leaveCount++
    }

    // Never leave the parent screen open behind a different app.
    override fun onStop() {
        super.onStop()
        if (pickerOpen) return
        changeScreen(Screen.HOME)
        leaveCount++
    }
}

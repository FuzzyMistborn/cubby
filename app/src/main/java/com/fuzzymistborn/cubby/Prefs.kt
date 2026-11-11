package com.fuzzymistborn.cubby

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local settings: hidden apps, home layout and the parent PIN.
 *
 * The PIN is stored only as a salted PBKDF2 hash, and wrong guesses trigger an
 * escalating lockout. The prefs file is app-private, so a child can't read it
 * without root; the lockout slows down on-device guessing.
 */
class Prefs(context: Context) {
    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("cubby", Context.MODE_PRIVATE)

    var hiddenApps: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_HIDDEN, value).apply()

    /** Home screen arrangement for this tablet, or null to start from the stock layout. */
    var layout: Layout?
        get() = sp.getString(KEY_LAYOUT, null)?.let(Layout::fromJson)
        set(value) = sp.edit().putString(KEY_LAYOUT, value?.toJson()).apply()

    /** When true, kids can't rearrange the home screen. */
    var layoutLocked: Boolean
        get() = sp.getBoolean(KEY_LAYOUT_LOCKED, false)
        set(value) = sp.edit().putBoolean(KEY_LAYOUT_LOCKED, value).apply()

    var favoritesEnabled: Boolean
        get() = sp.getBoolean(KEY_FAVORITES, false)
        set(value) = sp.edit().putBoolean(KEY_FAVORITES, value).apply()

    /** When true, apps installed from now on start hidden until a parent reviews them. */
    var hideNewApps: Boolean
        get() = sp.getBoolean(KEY_HIDE_NEW, false)
        set(value) = sp.edit().putBoolean(KEY_HIDE_NEW, value).apply()

    var searchEnabled: Boolean
        get() = sp.getBoolean(KEY_SEARCH, true)
        set(value) = sp.edit().putBoolean(KEY_SEARCH, value).apply()

    /** Name of a [TileColor]; unknown or missing falls back to white. */
    var tileColor: String
        get() = sp.getString(KEY_TILE_COLOR, null) ?: TileColor.WHITE.name
        set(value) = sp.edit().putString(KEY_TILE_COLOR, value).apply()

    /** False until a parent has seen the welcome note on the parent screen. */
    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDED, value).apply()

    /** Every package the launcher has already seen, so only genuinely new installs count as new. */
    var knownApps: Set<String>
        get() = sp.getStringSet(KEY_KNOWN, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_KNOWN, value).apply()

    /** Auto-hidden new apps a parent hasn't looked at yet. */
    var pendingApps: Set<String>
        get() = sp.getStringSet(KEY_PENDING, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_PENDING, value).apply()

    /** Minutes the parent screen stays open to re-entry without the PIN after a parent leaves it; 0 = always ask. */
    var parentTimeoutMinutes: Int
        get() = sp.getInt(KEY_PARENT_TIMEOUT, DEFAULT_PARENT_TIMEOUT_MIN)
        set(value) {
            sp.edit().putInt(KEY_PARENT_TIMEOUT, value).apply()
            if (value <= 0) unlockedUntil = 0L
        }

    /**
     * True while the grace window from a recent PIN entry is open. Kept in memory only, on the
     * monotonic clock, so a reboot, app restart or clock change always brings the PIN back.
     */
    fun parentUnlocked(): Boolean = SystemClock.elapsedRealtime() < unlockedUntil

    /** Opens (or extends) the grace window; does nothing when the timeout is off. */
    fun extendParentUnlock() {
        val minutes = parentTimeoutMinutes
        unlockedUntil = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
    }

    /** True only if a usable PIN is stored; unreadable data counts as "none", forcing a fresh setup. */
    fun hasPin(): Boolean = storedPin() != null

    private fun storedPin(): Pair<ByteArray, ByteArray>? {
        val salt = sp.getString(KEY_PIN_SALT, null) ?: return null
        val hash = sp.getString(KEY_PIN_HASH, null) ?: return null
        return try {
            Base64.decode(salt, Base64.NO_WRAP) to Base64.decode(hash, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
        unlockedUntil = 0L
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /**
     * Milliseconds remaining on a lockout, or 0 if guessing is allowed. Uses the monotonic clock so
     * changing the date or time can't shorten or stretch it; a reboot restarts the full delay.
     */
    fun lockoutRemainingMs(): Long {
        val until = sp.getLong(KEY_LOCK_UNTIL, 0L)
        if (until == 0L) return 0L
        val delay = sp.getLong(KEY_LOCK_DELAY, 0L)
        val now = SystemClock.elapsedRealtime()
        val boot = bootCount()
        if (sp.getInt(KEY_LOCK_BOOT, -1) != boot) {
            sp.edit().putLong(KEY_LOCK_UNTIL, now + delay).putInt(KEY_LOCK_BOOT, boot).apply()
            return delay
        }
        val left = until - now
        if (left <= 0L) {
            // Spent: forget it, or a later reboot would re-arm a delay that had already run out.
            sp.edit().putLong(KEY_LOCK_UNTIL, 0L).apply()
            return 0L
        }
        return left.coerceAtMost(delay)
    }

    /** Does PBKDF2 work: call off the main thread. */
    fun verifyPin(pin: String): Boolean {
        if (lockoutRemainingMs() > 0) return false
        val (salt, expected) = storedPin() ?: return false
        val actual = hash(pin, salt)
        val ok = MessageDigest.isEqual(expected, actual)
        if (ok) {
            sp.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
        } else {
            val fails = sp.getInt(KEY_FAILS, 0) + 1
            val editor = sp.edit().putInt(KEY_FAILS, fails)
            if (fails >= FREE_ATTEMPTS) {
                val step = (fails - FREE_ATTEMPTS).coerceAtMost(5)
                val delay = (30_000L shl step).coerceAtMost(MAX_LOCK_MS)
                editor.putLong(KEY_LOCK_UNTIL, SystemClock.elapsedRealtime() + delay)
                    .putLong(KEY_LOCK_DELAY, delay)
                    .putInt(KEY_LOCK_BOOT, bootCount())
            }
            editor.apply()
        }
        return ok
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private companion object {
        @Volatile var unlockedUntil = 0L
        const val KEY_PARENT_TIMEOUT = "parent_timeout_min"
        const val DEFAULT_PARENT_TIMEOUT_MIN = 5
        const val KEY_HIDDEN = "hidden_apps"
        const val KEY_LAYOUT = "layout"
        const val KEY_LAYOUT_LOCKED = "layout_locked"
        const val KEY_FAVORITES = "favorites_enabled"
        const val KEY_SEARCH = "search_enabled"
        const val KEY_TILE_COLOR = "tile_color"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_HIDE_NEW = "hide_new_apps"
        const val KEY_KNOWN = "known_apps"
        const val KEY_PENDING = "pending_apps"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_FAILS = "pin_fails"
        const val KEY_LOCK_UNTIL = "pin_lock_until"
        const val KEY_LOCK_DELAY = "pin_lock_delay"
        const val KEY_LOCK_BOOT = "pin_lock_boot"
        const val ITERATIONS = 120_000
        const val FREE_ATTEMPTS = 5
        const val MAX_LOCK_MS = 15 * 60 * 1000L
    }
}

package com.soloit.doubleentry.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

data class Appearance(
    val themeMode: ThemeMode = ThemeMode.System,
    /** Material You wallpaper colors (Android 12+) instead of the emerald brand palette. */
    val dynamicColor: Boolean = false,
)

data class ReminderPrefs(
    val enabled: Boolean = false,
    /** Remind about unpaid invoices due within this many days (overdue ones are always included). */
    val daysBefore: Int = 3,
)

/** Device-local preferences (not synced): appearance, invoice reminders and the app lock. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val device = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(read())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private val _reminders = MutableStateFlow(
        ReminderPrefs(device.getBoolean("remindersEnabled", false), device.getInt("reminderDaysBefore", 3)),
    )
    val reminders: StateFlow<ReminderPrefs> = _reminders.asStateFlow()

    private val _appLock = MutableStateFlow(device.getBoolean("appLock", false))

    /** Require the screen lock or a fingerprint to open the app. */
    val appLock: StateFlow<Boolean> = _appLock.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    fun setReminders(value: ReminderPrefs) {
        device.edit {
            putBoolean("remindersEnabled", value.enabled)
            putInt("reminderDaysBefore", value.daysBefore)
        }
        _reminders.value = value
    }

    fun setAppLock(enabled: Boolean) {
        device.edit { putBoolean("appLock", enabled) }
        _appLock.value = enabled
    }

    private fun update(transform: (Appearance) -> Appearance) {
        val next = transform(_appearance.value)
        prefs.edit {
            putString("themeMode", next.themeMode.name)
            putBoolean("dynamicColor", next.dynamicColor)
        }
        _appearance.value = next
    }

    private fun read() = Appearance(
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", null) ?: "") }.getOrDefault(ThemeMode.System),
        dynamicColor = prefs.getBoolean("dynamicColor", false),
    )
}

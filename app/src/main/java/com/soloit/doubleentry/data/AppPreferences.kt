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

/** Device-local UI preferences (not synced). */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(read())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

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

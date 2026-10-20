package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.StatementColumns
import com.soloit.doubleentry.platform.KeyValueStores
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

/** Device-local preferences (not synced): appearance, invoice reminders, the app lock and statement import columns. */
class AppPreferences(stores: KeyValueStores) {
    private val prefs = stores.open("appearance")
    private val device = stores.open("device")
    private val statements = stores.open("statementImport")

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

    /** The columns last used to import a statement into [accountId], if that file had the same header [signature]. */
    fun statementColumns(accountId: String, signature: String): StatementColumns? {
        if (statements.getString("$accountId.signature") != signature) return null
        val v = statements.getString("$accountId.columns")?.split(',') ?: return null
        if (v.size != 7) return null
        fun index(i: Int) = v[i].toIntOrNull()
        return StatementColumns(index(0), index(1), index(2), index(3), index(4), index(5), v[6] == "1")
    }

    fun setStatementColumns(accountId: String, signature: String, columns: StatementColumns) {
        val v = with(columns) { listOf(date, text, amount, moneyOut, moneyIn, balance).joinToString(",") { it?.toString().orEmpty() } }
        statements.edit {
            putString("$accountId.signature", signature)
            putString("$accountId.columns", "$v,${if (columns.reversed) 1 else 0}")
        }
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
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode") ?: "") }.getOrDefault(ThemeMode.System),
        dynamicColor = prefs.getBoolean("dynamicColor", false),
    )
}

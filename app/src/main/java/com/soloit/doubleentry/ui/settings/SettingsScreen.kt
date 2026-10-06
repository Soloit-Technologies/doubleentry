package com.soloit.doubleentry.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.BuildConfig
import com.soloit.doubleentry.data.ReminderPrefs
import com.soloit.doubleentry.data.ThemeMode
import com.soloit.doubleentry.system.AppLock
import com.soloit.doubleentry.system.InvoiceReminders
import java.net.URLEncoder
import com.soloit.doubleentry.ui.LocalAppContainer
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.IconAvatar
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.SegmentedChoice
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCloudSync: () -> Unit,
    onDataManagement: () -> Unit,
    onBalanceSheet: () -> Unit,
    onIncomeStatement: () -> Unit,
    onCashflow: () -> Unit,
    onInvoices: () -> Unit,
    onRecurring: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val hostActivity = LocalActivity.current
    val appearance by container.preferences.appearance.collectAsStateWithLifecycle()
    val reminders by container.preferences.reminders.collectAsStateWithLifecycle()
    val appLock by container.preferences.appLock.collectAsStateWithLifecycle()
    val sync by container.sync.status.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    fun openLink(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure { notice = "There's no browser on this device to open $url" }
    }

    fun setReminders(value: ReminderPrefs) {
        container.preferences.setReminders(value)
        InvoiceReminders.schedule(context, value)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            setReminders(reminders.copy(enabled = true))
        } else {
            notice = "Allow notifications for DoubleEntry in the system settings to get invoice reminders."
        }
    }
    fun toggleReminders(on: Boolean) {
        // canNotify() is only false on Android 13+, where the permission can be requested.
        if (on && !InvoiceReminders.canNotify(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            setReminders(reminders.copy(enabled = on))
        }
    }
    fun toggleLock(on: Boolean) {
        val activity = hostActivity as? FragmentActivity ?: return
        when {
            !on -> container.appLock.setEnabled(false)
            !AppLock.isAvailable(context) -> notice = "Set up a screen lock (PIN, pattern, password or fingerprint) on this device first."
            // Confirm once, so the lock can't be switched on with a screen lock the user can't pass.
            else -> AppLock.authenticate(activity, "Turn on app lock") { error -> if (error == null) container.appLock.setEnabled(true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item { SectionHeader("Appearance") }
            item {
                val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 2 else 1
                Column {
                    GroupedItem(0, count) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingTitle(Icons.Rounded.Palette, "Theme")
                            SegmentedChoice(ThemeMode.entries, appearance.themeMode, container.preferences::setThemeMode, { it.name })
                        }
                    }
                    if (count == 2) {
                        GroupedItem(1, count, onClick = { container.preferences.setDynamicColor(!appearance.dynamicColor) }) {
                            SettingRow(
                                icon = Icons.Rounded.Wallpaper,
                                title = "Wallpaper colors",
                                subtitle = "Use Material You colors instead of the DoubleEntry green",
                                trailing = { Switch(checked = appearance.dynamicColor, onCheckedChange = container.preferences::setDynamicColor) },
                            )
                        }
                    }
                }
            }

            if (sync.featureEnabled) {
                item { SectionHeader("Cloud") }
                item {
                    GroupedItem(0, 1, onClick = onCloudSync) {
                        val subtitle = when {
                            !sync.configured -> "Not set up in this build"
                            !sync.isSignedIn -> "Sign in to use your ledger on all your devices"
                            sync.attention != null -> sync.attentionText()!!
                            !sync.enabled -> "Paused · ${sync.signedInEmail}"
                            sync.lastError != null -> sync.lastError!!
                            sync.lastSyncAtMillis != null -> "Synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sync.lastSyncAtMillis!!))} · ${sync.signedInEmail}"
                            else -> "On · ${sync.signedInEmail}"
                        }
                        SettingRow(if (sync.isActive) Icons.Rounded.Cloud else Icons.Rounded.CloudOff, "Cloud sync", subtitle, chevron = true)
                    }
                }
            }

            item { SectionHeader("Automation") }
            item {
                Column {
                    GroupedItem(0, 2, onClick = onRecurring) {
                        SettingRow(Icons.Rounded.EventRepeat, "Recurring transactions", "Rent, salary and subscriptions that post themselves", chevron = true)
                    }
                    GroupedItem(1, 2, onClick = { toggleReminders(!reminders.enabled) }) {
                        Column {
                            SettingRow(
                                icon = Icons.Rounded.NotificationsActive,
                                title = "Invoice reminders",
                                subtitle = if (reminders.enabled) {
                                    "A notification each morning for unpaid invoices due within ${reminders.daysBefore} day${if (reminders.daysBefore == 1) "" else "s"}"
                                } else {
                                    "Get notified before invoices are due"
                                },
                                trailing = { Switch(checked = reminders.enabled, onCheckedChange = ::toggleReminders) },
                            )
                            if (reminders.enabled) {
                                Row(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(1, 3, 7).forEach { days ->
                                        FilterChip(
                                            selected = reminders.daysBefore == days,
                                            onClick = { setReminders(reminders.copy(daysBefore = days)) },
                                            label = { Text(if (days == 1) "1 day" else "$days days") },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Security") }
            item {
                GroupedItem(0, 1, onClick = { toggleLock(!appLock) }) {
                    SettingRow(
                        icon = Icons.Rounded.Fingerprint,
                        title = "App lock",
                        subtitle = "Ask for your fingerprint or screen lock when opening the app. Hides the widget amount.",
                        trailing = { Switch(checked = appLock, onCheckedChange = ::toggleLock) },
                    )
                }
            }

            item { SectionHeader("Reports") }
            item {
                Column {
                    GroupedItem(0, 3, onClick = onBalanceSheet) {
                        SettingRow(Icons.Rounded.AccountBalance, "Balance sheet", "Asset and liability groups", chevron = true)
                    }
                    GroupedItem(1, 3, onClick = onIncomeStatement) {
                        SettingRow(Icons.AutoMirrored.Rounded.TrendingUp, "Income statement & cost trends", "Revenue and expense groups, tax, depreciation", chevron = true)
                    }
                    GroupedItem(2, 3, onClick = onCashflow) {
                        SettingRow(Icons.Rounded.Payments, "Cash flow", "Which accounts count as cash", chevron = true)
                    }
                }
            }

            item { SectionHeader("Invoices") }
            item {
                GroupedItem(0, 1, onClick = onInvoices) {
                    SettingRow(Icons.AutoMirrored.Rounded.ReceiptLong, "Invoice defaults", "Credit account and payment accounts", chevron = true)
                }
            }

            item { SectionHeader("Data") }
            item {
                GroupedItem(0, 1, onClick = onDataManagement) {
                    SettingRow(Icons.Rounded.Storage, "Data management", "Backups, CSV export, repair and delete", chevron = true)
                }
            }

            item { SectionHeader("Support") }
            item {
                Column {
                    GroupedItem(0, 3, onClick = { openLink(bugReportUrl()) }) {
                        SettingRow(Icons.Rounded.BugReport, "Report a problem", "Opens a bug report on GitHub with your app and Android version filled in", trailing = { ExternalLinkIcon() })
                    }
                    GroupedItem(1, 3, onClick = { openLink("$SUPPORT_URL/issues/new/choose") }) {
                        SettingRow(Icons.AutoMirrored.Rounded.HelpOutline, "Ask a question or suggest a feature", "On GitHub, where you can also follow known issues", trailing = { ExternalLinkIcon() })
                    }
                    GroupedItem(2, 3, onClick = { openLink(PRIVACY_POLICY_URL) }) {
                        SettingRow(Icons.Rounded.PrivacyTip, "Privacy policy", "Your ledger stays on this device", trailing = { ExternalLinkIcon() })
                    }
                }
            }

            item { SectionHeader("About") }
            item {
                GroupedItem(0, 1) {
                    SettingRow(Icons.Rounded.Info, "DoubleEntry ${BuildConfig.VERSION_NAME}", "Personal double-entry bookkeeping")
                }
            }
        }
    }
    notice?.let { MessageDialog("Not available", it) { notice = null } }
}

/** Public repository for support: issues are the help channel, and it hosts the privacy policy. */
private const val SUPPORT_URL = "https://github.com/Soloit-Technologies/doubleentry-app"
private const val PRIVACY_POLICY_URL = "$SUPPORT_URL/blob/main/PRIVACY.md"

/** The bug report form, with the fields of `.github/ISSUE_TEMPLATE/bug_report.yml` in that repository prefilled. */
private fun bugReportUrl(): String {
    fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    val fields = mapOf(
        "app-version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "android-version" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
    )
    return "$SUPPORT_URL/issues/new?template=bug_report.yml&" +
        fields.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
}

@Composable
private fun ExternalLinkIcon() = Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Opens in browser")

@Composable
private fun SettingTitle(icon: ImageVector, title: String) {
    // A plain row: the surrounding column already provides the padding a ListItem would add.
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAvatar(icon, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 2) } },
        leadingContent = { IconAvatar(icon, MaterialTheme.colorScheme.primary) },
        trailingContent = trailing ?: if (chevron) ({ Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

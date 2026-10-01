package com.soloit.doubleentry.ui.settings

import android.os.Build
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Wallpaper
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.BuildConfig
import com.soloit.doubleentry.data.ThemeMode
import com.soloit.doubleentry.ui.LocalAppContainer
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.IconAvatar
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
) {
    val container = LocalAppContainer.current
    val appearance by container.preferences.appearance.collectAsStateWithLifecycle()
    val sync by container.sync.status.collectAsStateWithLifecycle()

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

            if (sync.available) {
                item { SectionHeader("Cloud") }
                item {
                    GroupedItem(0, 1, onClick = onCloudSync) {
                        val subtitle = when {
                            !sync.isSignedIn -> "Sign in to sync between devices"
                            !sync.enabled -> "Paused · ${sync.signedInEmail}"
                            sync.lastError != null -> sync.lastError!!
                            sync.lastSyncAtMillis != null -> "Synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sync.lastSyncAtMillis!!))} · ${sync.signedInEmail}"
                            else -> "On · ${sync.signedInEmail}"
                        }
                        SettingRow(if (sync.isActive) Icons.Rounded.Cloud else Icons.Rounded.CloudOff, "Cloud sync", subtitle, chevron = true)
                    }
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
                    SettingRow(Icons.Rounded.Storage, "Data management", "Export, import, repair and delete", chevron = true)
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
}

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

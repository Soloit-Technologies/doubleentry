package com.soloit.doubleentry.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.soloit.doubleentry.DoubleEntryApp
import com.soloit.doubleentry.MainActivity
import com.soloit.doubleentry.R
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.Money
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Home-screen widget: today's net worth (assets − liabilities) and a button to record a
 * transaction. While the app lock is on, the amount is hidden.
 */
class NetWorthWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as DoubleEntryApp
        app.container.appScope.launch {
            try {
                update(context, manager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Refreshes every placed widget; cheap no-op when there are none. */
        suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NetWorthWidget::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val container = (context.applicationContext as DoubleEntryApp).container
            val text = if (container.preferences.appLock.value) {
                "••••••"
            } else {
                val balances = container.ledger.getBalances(LocalDate.now())
                val accounts = container.ledger.getAccounts()
                fun total(type: AccountType) = accounts.filter { it.type == type }.sumOf { balances[it.id] ?: 0L }
                Money.format(total(AccountType.Asset) - total(AccountType.Liability))
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val add = PendingIntent.getActivity(
                context,
                1,
                Launch.intent(context, LaunchTarget.NewTransaction),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_net_worth).apply {
                setTextViewText(R.id.widget_amount, text)
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_add, add)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}

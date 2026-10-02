package com.soloit.doubleentry.system

import android.content.Context
import android.content.Intent
import com.soloit.doubleentry.MainActivity

/** Where an outside entry point (launcher shortcut, notification, widget) wants the app to open. */
enum class LaunchTarget { NewTransaction, NewInvoice, Invoices, Invoice }

data class LaunchRequest(val target: LaunchTarget, val id: String? = null)

object Launch {
    private const val ACTION_PREFIX = "com.soloit.doubleentry.action."
    private const val EXTRA_ID = "com.soloit.doubleentry.extra.ID"

    /** An intent that opens [target]; the action is distinct per target so PendingIntents don't merge. */
    fun intent(context: Context, target: LaunchTarget, id: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_PREFIX + target.name)
            .putExtra(EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun parse(intent: Intent?): LaunchRequest? {
        val action = intent?.action?.takeIf { it.startsWith(ACTION_PREFIX) } ?: return null
        val target = LaunchTarget.entries.firstOrNull { it.name == action.removePrefix(ACTION_PREFIX) } ?: return null
        return LaunchRequest(target, intent.getStringExtra(EXTRA_ID))
    }
}

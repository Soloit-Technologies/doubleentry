package com.soloit.doubleentry.system

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.soloit.doubleentry.R

/**
 * Long-press launcher shortcuts. Published at runtime (not from XML) so they target whichever
 * application id is installed, including the `.dev` debug build.
 */
object Shortcuts {
    fun publish(context: Context) {
        val shortcuts = listOf(
            shortcut(context, "new_transaction", "New transaction", "Transaction", R.drawable.ic_shortcut_add, LaunchTarget.NewTransaction),
            shortcut(context, "new_invoice", "New invoice", "Invoice", R.drawable.ic_shortcut_invoice, LaunchTarget.NewInvoice),
        )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun shortcut(context: Context, id: String, long: String, short: String, icon: Int, target: LaunchTarget) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Launch.intent(context, target))
            .build()
}

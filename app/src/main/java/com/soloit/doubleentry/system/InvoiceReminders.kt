package com.soloit.doubleentry.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.soloit.doubleentry.DoubleEntryApp
import com.soloit.doubleentry.R
import com.soloit.doubleentry.data.ReminderPrefs
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.formatMedium
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Runs once a day and posts one summary notification about unpaid invoices that need attention. */
class InvoiceReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as DoubleEntryApp).container
        val prefs = container.preferences.reminders.value
        if (!prefs.enabled) return Result.success()
        val ledger = container.ledger
        val due = InvoiceReminders.select(container.invoices.liveInvoices(), LocalDate.now(), prefs.daysBefore).map { invoice ->
            val title = ledger.getTransaction(invoice.bookingTransactionId)?.title?.takeIf { it.isNotBlank() } ?: "Invoice ${invoice.invoiceDate}"
            title to invoice
        }
        InvoiceReminders.show(applicationContext, due, LocalDate.now())
        return Result.success()
    }
}

object InvoiceReminders {
    const val CHANNEL_ID = "invoice_reminders"
    private const val WORK_NAME = "invoice-reminders"
    private const val NOTIFICATION_ID = 1001
    private val NOTIFY_AT: LocalTime = LocalTime.of(9, 0)

    /** Unpaid invoices due within [daysBefore] days of [today] (overdue ones included), earliest first. */
    fun select(invoices: List<Invoice>, today: LocalDate, daysBefore: Int): List<Invoice> =
        invoices.filter { !it.isPaid && it.remaining > 0 && it.dueDate <= today.plusDays(daysBefore.toLong()) }.sortedBy { it.dueDate }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Invoice reminders", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Unpaid invoices that are due soon or overdue" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Starts (or stops) the daily check according to [prefs]. */
    fun schedule(context: Context, prefs: ReminderPrefs) {
        val work = WorkManager.getInstance(context)
        if (!prefs.enabled) {
            work.cancelUniqueWork(WORK_NAME)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        val now = LocalDateTime.now()
        val next = now.toLocalDate().atTime(NOTIFY_AT).let { if (it.isAfter(now)) it else it.plusDays(1) }
        val request = PeriodicWorkRequestBuilder<InvoiceReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Shows (or clears) the summary for [due] (title to invoice). */
    fun show(context: Context, due: List<Pair<String, Invoice>>, today: LocalDate) {
        val manager = NotificationManagerCompat.from(context)
        if (due.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!canNotify(context)) return

        val overdue = due.count { it.second.dueDate < today }
        val title = when {
            overdue == due.size -> "${due.size} overdue invoice${plural(due.size)}"
            overdue > 0 -> "${due.size} invoice${plural(due.size)} to pay · $overdue overdue"
            else -> "${due.size} invoice${plural(due.size)} due soon"
        }
        val lines = due.map { (name, invoice) -> "$name · ${Money.format(invoice.remaining)} · ${dueText(invoice.dueDate, today)}" }
        val target = if (due.size == 1) Launch.intent(context, LaunchTarget.Invoice, due[0].second.id) else Launch.intent(context, LaunchTarget.Invoices)
        val tap = PendingIntent.getActivity(context, 0, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.take(6).forEach(style::addLine) })
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        @Suppress("MissingPermission") // checked by canNotify
        manager.notify(NOTIFICATION_ID, notification)
    }

    internal fun dueText(dueDate: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, dueDate)
        return when {
            days < -1 -> "${-days} days overdue"
            days == -1L -> "due yesterday"
            days == 0L -> "due today"
            days == 1L -> "due tomorrow"
            else -> "due ${dueDate.formatMedium()}"
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}

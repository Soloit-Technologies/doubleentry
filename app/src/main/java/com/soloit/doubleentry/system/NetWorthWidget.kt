package com.soloit.doubleentry.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.createBitmap
import com.soloit.doubleentry.DoubleEntryApp
import com.soloit.doubleentry.MainActivity
import com.soloit.doubleentry.R
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.reports.NetWorthTrend
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Home-screen widget: today's net worth (assets − liabilities) and a button to record a
 * transaction. From two rows tall it also shows the change over 12 months and a sparkline. While
 * the app lock is on, the amount is hidden and the trend is left out.
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

    // Resizing changes which layout fits and how wide the sparkline is drawn.
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        onUpdate(context, manager, intArrayOf(appWidgetId))

    companion object {
        /** Below this height (dp) only the compact one-row layout fits. */
        private const val TREND_MIN_HEIGHT_DP = 100f
        private const val MIN_WIDTH_DP = 180f

        /** Refreshes every placed widget; cheap no-op when there are none. */
        suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NetWorthWidget::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val container = (context.applicationContext as DoubleEntryApp).container
            val trend = if (container.preferences.appLock.value) null else container.ledger.getNetWorthTrend(LocalDate.now())
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
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }?.toFloat() ?: 250f
                val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }?.toFloat() ?: 110f
                fun views(layout: Int) = RemoteViews(context.packageName, layout).apply {
                    setTextViewText(R.id.widget_amount, if (trend == null) "••••••" else Money.format(trend.latest?.netWorth ?: 0L))
                    setOnClickPendingIntent(R.id.widget_root, open)
                    setOnClickPendingIntent(R.id.widget_add, add)
                    if (layout == R.layout.widget_net_worth_trend && trend != null) showTrend(context, trend, widthDp, heightDp)
                }
                val compact = views(R.layout.widget_net_worth)
                val tall = views(R.layout.widget_net_worth_trend)
                val chosen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The launcher picks the largest layout that fits, also while the widget is being resized.
                    RemoteViews(mapOf(SizeF(MIN_WIDTH_DP, 40f) to compact, SizeF(MIN_WIDTH_DP, TREND_MIN_HEIGHT_DP) to tall))
                } else {
                    if (heightDp >= TREND_MIN_HEIGHT_DP) tall else compact
                }
                manager.updateAppWidget(id, chosen)
            }
        }

        private fun RemoteViews.showTrend(context: Context, trend: NetWorthTrend, widthDp: Float, heightDp: Float) {
            val change = trend.change
            val arrow = if (change >= 0) "▲" else "▼"
            // The arrow carries the direction, so the amount goes without a sign.
            setTextViewText(R.id.widget_change, "$arrow " + context.getString(R.string.widget_change, Money.format(abs(change))))
            setViewVisibility(R.id.widget_change, View.VISIBLE)
            if (trend.points.size < 2) return
            val density = context.resources.displayMetrics.density
            val fontScale = context.resources.configuration.fontScale
            // Width minus the padding; height minus the padding and the text above (which grows with the font size).
            val widthPx = ((widthDp - 32f) * density).roundToInt().coerceAtLeast(1)
            val heightPx = ((heightDp - 32f - 8f - 66f * fontScale) * density).roundToInt().coerceAtLeast((16 * density).roundToInt())
            setImageViewBitmap(R.id.widget_sparkline, sparkline(trend.points.map { it.netWorth }, widthPx, heightPx, density))
            setViewVisibility(R.id.widget_sparkline, View.VISIBLE)
        }

        /** A white line with a fading fill under it; the layout tints it to the accent colour. */
        private fun sparkline(values: List<Long>, width: Int, height: Int, density: Float): Bitmap {
            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)
            val stroke = 2.5f * density
            val dot = 3.5f * density
            val left = dot
            val right = width - dot
            val top = dot
            val bottom = height - stroke
            val min = values.min()
            val max = values.max()
            val step = (right - left) / (values.size - 1)
            fun x(i: Int) = left + i * step
            // A flat series sits in the middle rather than on the floor.
            fun y(v: Long) = if (max == min) (top + bottom) / 2 else bottom - (v - min).toFloat() / (max - min) * (bottom - top)

            val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            val fill = Path(line).apply {
                lineTo(x(values.lastIndex), height.toFloat())
                lineTo(x(0), height.toFloat())
                close()
            }
            canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, top, 0f, height.toFloat(), Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            })
            canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            })
            canvas.drawCircle(x(values.lastIndex), y(values.last()), dot, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            return bitmap
        }
    }
}

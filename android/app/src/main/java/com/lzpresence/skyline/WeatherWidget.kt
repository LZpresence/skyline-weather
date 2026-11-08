package com.lzpresence.skyline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.WorkManager

class WeatherWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        WidgetWorker.schedule(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Keep the periodic job queued first: WorkManager toggles a component when its queue
        // empties, which re-broadcasts APPWIDGET_UPDATE and would loop.
        WidgetWorker.schedule(context)
        val prefs = context.getSharedPreferences("skyline", Context.MODE_PRIVATE)
        val last = prefs.getLong("lastRun", 0L)
        if (System.currentTimeMillis() - last > 5 * 60_000) WidgetWorker.runNow(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WidgetWorker.PERIODIC)
    }

    companion object {
        private fun baseViews(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.root, open)
            return v
        }

        private fun push(ctx: Context, v: RemoteViews) {
            val m = AppWidgetManager.getInstance(ctx)
            m.updateAppWidget(ComponentName(ctx, WeatherWidget::class.java), v)
        }

        fun render(ctx: Context, wx: Wx) {
            val v = baseViews(ctx)
            // °F or °C, following the switch in the app
            val metric = ctx.getSharedPreferences("skyline", Context.MODE_PRIVATE).getString("units", "us") == "metric"
            fun t(f: Int?): Int? = f?.let { if (metric) Math.round((it - 32) * 5 / 9.0).toInt() else it }
            v.setTextViewText(R.id.temp, "${t(wx.temp) ?: "--"}°")
            val hilo = listOfNotNull(t(wx.hi)?.let { "H $it°" }, t(wx.lo)?.let { "L $it°" }).joinToString("  ")
            v.setTextViewText(R.id.hilo, hilo)
            v.setTextViewText(R.id.cond, wx.text)
            v.setTextViewText(R.id.place, wx.place)
            v.setImageViewBitmap(R.id.kikker, Kikker.bitmap(wx.keys, wx.scene, 220))
            push(ctx, v)
        }

        fun renderMessage(ctx: Context, msg: String) {
            val v = baseViews(ctx)
            v.setTextViewText(R.id.cond, msg)
            v.setTextViewText(R.id.place, "Tap to open Kikker Forecast")
            push(ctx, v)
        }
    }
}

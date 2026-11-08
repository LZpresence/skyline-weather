package com.lzpresence.skyline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Fetches the forecast and redraws the widget. Runs every 30 minutes and on demand. */
class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        ctx.getSharedPreferences("skyline", Context.MODE_PRIVATE).edit()
            .putLong("lastRun", System.currentTimeMillis()).apply()
        try {
            WeatherWidget.render(ctx, Weather.load(ctx))
            ctx.getSharedPreferences("skyline", Context.MODE_PRIVATE).edit()
                .putLong("lastOk", System.currentTimeMillis()).apply()
        } catch (e: NoLocationException) {
            WeatherWidget.renderMessage(ctx, e.message ?: "Location needed")
        } catch (e: Exception) {
            // Keep the last good reading on screen; only show an error if we never had one.
            val lastOk = ctx.getSharedPreferences("skyline", Context.MODE_PRIVATE).getLong("lastOk", 0L)
            if (lastOk == 0L) WeatherWidget.renderMessage(ctx, "Weather service unavailable")
        }
        Result.success()
    }

    companion object {
        const val PERIODIC = "skyline-periodic"
        private const val NOW = "skyline-now"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<WidgetWorker>(30, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<WidgetWorker>().setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

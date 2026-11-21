package com.signalbot.app.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.signalbot.app.MainActivity
import com.signalbot.app.data.ScanResult
import com.signalbot.app.data.Store
import com.signalbot.app.scan.Scanner
import com.signalbot.engine.strategy.formatPrice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Background scan. Safe to run any number of times: a signal is only ever notified once. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val result = Scanner(applicationContext).run()
            Notifier.notifyNew(applicationContext, result)
            val allFailed = result.assets.isNotEmpty() && result.assets.all { it.error != null }
            if (allFailed && runAttemptCount < 3) Result.retry() else Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

object ScanScheduler {
    /**
     * Two background jobs:
     *  - daily, aimed at 00:10 UTC (05:40 IST), just after the daily candle closes;
     *  - a catch-up every 3 hours, in case Android delayed or skipped the daily one.
     * Both need internet. KEEP means opening the app never resets them.
     */
    fun ensureScheduled(context: Context) {
        val wm = WorkManager.getInstance(context)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val daily = PeriodicWorkRequestBuilder<ScanWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayToNextDailyRun(System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setConstraints(net)
            .build()
        wm.enqueueUniquePeriodicWork("daily-scan", ExistingPeriodicWorkPolicy.KEEP, daily)
        val catchUp = PeriodicWorkRequestBuilder<ScanWorker>(3, TimeUnit.HOURS).setConstraints(net).build()
        wm.enqueueUniquePeriodicWork("catch-up-scan", ExistingPeriodicWorkPolicy.KEEP, catchUp)
    }

    /** Milliseconds until the next 00:10 UTC. */
    fun delayToNextDailyRun(now: Long): Long {
        val day = 86_400_000L
        val today = now - Math.floorMod(now, day) + 10 * 60_000L
        return (if (today > now) today else today + day) - now
    }
}

object Notifier {
    const val CHANNEL_SIGNALS = "signals"
    const val CHANNEL_STATUS = "status"
    private const val ID_SIGNALS = 1
    private const val ID_PROBLEM = 2

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SIGNALS, "Trade signals", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Legs to enter or exit at the next open"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Scanner problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When price data couldn't be loaded"
        })
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Notifies actions not notified before. Returns how many were new. */
    @SuppressLint("MissingPermission")
    fun notifyNew(context: Context, result: ScanResult): Int {
        val store = Store(context)
        val seen = store.notifiedKeys()
        val fresh = result.assets.flatMap { it.actions }.filter { it.key !in seen }
        if (!canNotify(context)) return 0
        val nm = NotificationManagerCompat.from(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (fresh.isNotEmpty()) {
            val lines = fresh.map { a ->
                val verb = if (a.action == "ENTER") "ENTER ${a.direction}" else "EXIT ${a.direction}"
                "${a.assetId}: $verb ${a.leg}d leg" + if (a.action == "ENTER") ", stop ${formatPrice(a.stop)}" else ""
            }
            val style = NotificationCompat.InboxStyle()
            lines.take(7).forEach { style.addLine(it) }
            if (lines.size > 7) style.setSummaryText("+${lines.size - 7} more")
            val n = NotificationCompat.Builder(context, CHANNEL_SIGNALS)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("${fresh.size} action${if (fresh.size > 1) "s" else ""} for the next open")
                .setContentText(lines.first())
                .setStyle(style)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(ID_SIGNALS, n)
            store.markNotified(fresh.map { it.key })
        }
        val failed = result.assets.filter { it.error != null }
        if (result.assets.isNotEmpty() && failed.size == result.assets.size) {
            val n = NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Couldn't load prices")
                .setContentText(failed.first().error ?: "Network problem")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(ID_PROBLEM, n)
        }
        return fresh.size
    }
}

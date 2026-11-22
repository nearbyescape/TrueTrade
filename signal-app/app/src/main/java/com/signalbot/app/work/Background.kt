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
import com.signalbot.app.data.ActionView
import com.signalbot.app.data.AppSettings
import com.signalbot.app.data.ScanResult
import com.signalbot.app.data.SetupResult
import com.signalbot.app.data.Store
import com.signalbot.app.scan.Scanner
import com.signalbot.app.scan.SetupScanner
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
            val setup = runCatching { SetupScanner(applicationContext).run() }.getOrNull()
            setup?.let { Notifier.notifySetup(applicationContext, it) }
            val allFailed = result.assets.isNotEmpty() && result.assets.all { it.error != null }
            if (allFailed && runAttemptCount < 3) Result.retry() else Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** Hourly check for the break-and-retest setup when it uses 4-hour or 1-hour candles. */
class SetupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val r = SetupScanner(applicationContext).run()
            Notifier.notifySetup(applicationContext, r)
            if (r.assets.isNotEmpty() && r.assets.all { it.error != null } && runAttemptCount < 2) Result.retry() else Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
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
        if (AppSettings(context).setupTimeframe == "1d") {
            wm.cancelUniqueWork("setup-hourly")
        } else {
            // Starts a few minutes after the next hour, when the hourly (and 4-hourly) candle has closed.
            val hour = 3_600_000L
            val now = System.currentTimeMillis()
            val delay = (now - Math.floorMod(now, hour) + hour + 3 * 60_000L) - now
            val hourly = PeriodicWorkRequestBuilder<SetupWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS).setConstraints(net).build()
            wm.enqueueUniquePeriodicWork("setup-hourly", ExistingPeriodicWorkPolicy.KEEP, hourly)
        }
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
    private const val ID_SETUP = 3
    private const val ID_SETUP_STEPS = 4
    const val CHANNEL_STEPS = "setup_steps"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SIGNALS, "Trade signals", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Legs to enter or exit at the next open"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STEPS, "Break and retest steps", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A trendline broke, or price came back to retest it. The entry itself comes as a trade signal"
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

    /**
     * Break-and-retest alerts. The ENTRY (all three checks passed) is the signal and goes on the loud channel
     * with stop moves and exits; the break and the retest go on the quieter steps channel. Each once only.
     */
    @SuppressLint("MissingPermission")
    fun notifySetup(context: Context, result: SetupResult): Int {
        val store = Store(context)
        val seen = store.notifiedKeys()
        val fresh = result.assets.flatMap { it.actions }.filter { it.key !in seen }
        if (fresh.isEmpty() || !canNotify(context)) return 0
        val nm = NotificationManagerCompat.from(context)
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val (steps, orders) = fresh.partition { it.action == "BREAK" || it.action == "RETEST" }
        fun line(a: ActionView) = when (a.action) {
            "ENTER" -> "${a.assetName}: ${if (a.direction == "LONG") "BUY" else "SELL SHORT"} at the next open, stop ${formatPrice(a.stop)}"
            "MOVE_STOP" -> "${a.assetName}: move the stop to ${formatPrice(a.stop)}"
            "EXIT" -> "${a.assetName}: ${a.reasons.firstOrNull() ?: "trade closed"}"
            "BREAK" -> "${a.assetName}: trendline broken, wait for the retest"
            else -> "${a.assetName}: retested the broken line"
        }
        fun post(list: List<ActionView>, channel: String, id: Int, title: String) {
            if (list.isEmpty()) return
            val lines = list.map(::line)
            val style = NotificationCompat.InboxStyle()
            lines.take(7).forEach { style.addLine(it) }
            nm.notify(id, NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title)
                .setContentText(lines.first())
                .setStyle(style)
                .setPriority(if (channel == CHANNEL_SIGNALS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
        val entries = orders.count { it.action == "ENTER" }
        post(orders, CHANNEL_SIGNALS, ID_SETUP,
            if (entries > 0) "Break and retest: $entries entry signal${if (entries > 1) "s" else ""}" else "Break and retest: manage your trade")
        post(steps, CHANNEL_STEPS, ID_SETUP_STEPS, "Break and retest: ${steps.size} step${if (steps.size > 1) "s" else ""} to watch")
        store.markNotified(fresh.map { it.key })
        return fresh.size
    }
}

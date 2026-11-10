package app.fluff.work

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
import app.fluff.FluffApp
import java.util.concurrent.TimeUnit

/** Pulls wearable data in the background so readiness is ready when the user opens the app. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as FluffApp).container
        if (!c.prefs.currentProfile().onboarded) return Result.success()
        val error = c.sync.sync()
        runCatching { c.engine.refreshReadiness() }
        // Transient network failures are retried with backoff; anything else waits for the next window.
        return if (error != null && runAttemptCount < 3 && error.contains("connection", ignoreCase = true)) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "fluff-sync"
        private const val NOW = "fluff-sync-now"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runOnce(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

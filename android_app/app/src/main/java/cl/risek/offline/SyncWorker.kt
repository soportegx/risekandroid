package cl.risek.offline

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters): CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        ServiceLocator.nvRepository.syncPendingOnce()
        Result.success()
    } catch (e: Exception) { Result.retry() }

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("risek-sync-now", ExistingWorkPolicy.KEEP, req)
        }
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("risek-sync-periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

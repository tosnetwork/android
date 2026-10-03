package network.tos.wallet.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import network.tos.wallet.api.API

/** Retains the historical worker class so queued pre-upgrade requests terminate.
 * V1 distributes updates externally and has no downloader, installer, notification
 * or foreground-service permissions. A persisted request cannot revive that flow. */
class ApkDownloadWorker(
    context: Context,
    workParam: WorkerParameters,
    api: API,
) : CoroutineWorker(context, workParam) {
    override suspend fun doWork(): Result = Result.failure()
}

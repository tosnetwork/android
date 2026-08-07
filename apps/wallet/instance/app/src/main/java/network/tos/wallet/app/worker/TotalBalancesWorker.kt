package network.tos.wallet.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Operation
import androidx.work.WorkerParameters
import network.tos.extensions.CrashReporter
import network.tos.wallet.app.extensions.workManager
import network.tos.wallet.app.manager.assets.AssetsManager
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.settings.SettingsRepository

class TotalBalancesWorker(
    context: Context,
    workParam: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val assetsManager: AssetsManager,
    private val settingsRepository: SettingsRepository,
): CoroutineWorker(context, workParam) {

    override suspend fun doWork(): Result {
        try {
            val wallets = accountRepository.getWallets()
            for (wallet in wallets) {
                assetsManager.requestTotalBalance(
                    wallet = wallet,
                    currency = settingsRepository.currency,
                    sorted = true,
                    refresh = true,
                )
            }
            return Result.success()
        } catch (e: Exception) {
            CrashReporter.recordException(e)
            return Result.failure()
        }
    }

    companion object {

        fun run(context: Context): Operation {
            return context.workManager.oneTime<TotalBalancesWorker>()
        }

    }
}
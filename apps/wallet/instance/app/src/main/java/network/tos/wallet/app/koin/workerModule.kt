package network.tos.wallet.app.koin

import network.tos.wallet.app.worker.ApkDownloadWorker
import network.tos.wallet.app.worker.DAppPushToggleWorker
import network.tos.wallet.app.worker.PushToggleWorker
import network.tos.wallet.app.worker.WidgetUpdaterWorker
import org.koin.androidx.workmanager.dsl.worker
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.module

val workerModule = module {
    workerOf(::DAppPushToggleWorker)
    workerOf(::PushToggleWorker)
    workerOf(::WidgetUpdaterWorker)
    workerOf(::ApkDownloadWorker)
}
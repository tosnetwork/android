package network.tos.wallet.app

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.StrictMode
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import network.tos.extensions.setLocales
import network.tos.icu.CurrencyFormatter
import network.tos.wallet.app.koin.koinModel
import network.tos.wallet.app.koin.viewModelWalletModule
import network.tos.wallet.app.koin.workerModule
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.api.apiModule
import network.tos.wallet.data.account.accountModule
import network.tos.wallet.data.rates.ratesModule
import network.tos.wallet.data.token.tokenModule
import network.tos.wallet.data.swap.swapModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import network.tos.wallet.data.backup.backupModule
import network.tos.wallet.data.battery.batteryModule
import network.tos.wallet.data.browser.browserModule
import network.tos.wallet.data.collectibles.collectiblesModule
import network.tos.wallet.data.plugins.pluginsModule
import network.tos.wallet.data.contacts.contactsModule
import network.tos.wallet.data.core.Theme
import network.tos.wallet.data.core.dataModule
import network.tos.wallet.data.dapps.dAppsModule
import network.tos.wallet.data.events.eventsModule
import network.tos.wallet.data.passcode.passcodeModule
import network.tos.wallet.data.purchase.purchaseModule
import network.tos.wallet.data.rn.rnLegacyModule
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.staking.stakingModule
import network.tos.wallet.localization.Localization
import org.koin.core.component.KoinComponent
import org.koin.android.ext.android.inject
import org.koin.androidx.workmanager.koin.workManagerFactory
import java.util.concurrent.Executors

class App: Application(), CameraXConfig.Provider, KoinComponent {

    companion object {

        lateinit var instance: App

        fun applyConfiguration(newConfig: Configuration) {
            CurrencyFormatter.onConfigurationChanged(newConfig)
        }
    }

    private val settingsRepository: SettingsRepository by inject()

    override fun onCreate() {
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                .penaltyLog()
                .detectAll()
                .penaltyListener(Executors.newSingleThreadExecutor()) {
                    Log.e("TosWalletStrictModeLog", "StrictMode.VmPolicy: $it", it.cause)
                }.build())
        }

        super.onCreate()
        updateThemes()


        instance = this

        startKoin {
            androidContext(this@App)
            modules(koinModel, contactsModule, workerModule, dAppsModule, viewModelWalletModule, purchaseModule, batteryModule, stakingModule, passcodeModule, rnLegacyModule, swapModule, backupModule, dataModule, browserModule, apiModule, accountModule, ratesModule, tokenModule, eventsModule, collectiblesModule, pluginsModule)
            workManagerFactory()
        }
        setLocales(settingsRepository.localeList)
    }

    fun updateThemes() {
        Theme.clear()
        Theme.add("blue", uikit.R.style.Theme_App_Blue, title = getString(Localization.theme_deep_blue))
        Theme.add("dark", uikit.R.style.Theme_App_Dark, title = getString(Localization.theme_dark))
        Theme.add("light", uikit.R.style.Theme_App_Light, true, title = getString(Localization.theme_light))
        Theme.add("system", 0, title = getString(Localization.system))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyConfiguration(newConfig)
    }

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder
            .fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR).build()
    }
}
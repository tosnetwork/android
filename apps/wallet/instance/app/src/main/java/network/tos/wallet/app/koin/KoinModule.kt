package network.tos.wallet.app.koin

import network.tos.network.NetworkMonitor
import network.tos.wallet.app.Environment
import network.tos.wallet.app.RemoteConfig
import network.tos.wallet.app.billing.BillingManager
import network.tos.wallet.app.client.safemode.SafeModeClient
import network.tos.wallet.app.core.AnalyticsHelper
import network.tos.wallet.app.manager.assets.AssetsManager
import network.tos.wallet.app.manager.tx.TransactionManager
import network.tos.wallet.app.core.history.HistoryHelper
import network.tos.wallet.app.helper.CacheHelper
import network.tos.wallet.app.helper.ReferrerClientHelper
import network.tos.wallet.app.manager.apk.APKManager
import network.tos.wallet.app.manager.push.PushManager
import network.tos.wallet.app.ui.screen.main.MainViewModel
import network.tos.wallet.app.ui.screen.root.RootViewModel
import network.tos.wallet.app.manager.tonconnect.TonConnectManager
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.picker.currency.CurrencyPickerViewModel
import network.tos.wallet.app.ui.screen.add.AddWalletViewModel
import network.tos.wallet.app.ui.screen.battery.BatteryViewModel
import network.tos.wallet.app.ui.screen.browser.main.BrowserMainViewModel
import network.tos.wallet.app.ui.screen.browser.search.BrowserSearchViewModel
import network.tos.wallet.app.ui.screen.country.CountryPickerViewModel
import network.tos.wallet.app.ui.screen.dev.DevViewModel
import network.tos.wallet.app.ui.screen.settings.currency.CurrencyViewModel
import network.tos.wallet.app.ui.screen.init.InitViewModel
import network.tos.wallet.app.ui.screen.ledger.steps.LedgerConnectionViewModel
import network.tos.wallet.app.ui.screen.migration.MigrationViewModel
import network.tos.wallet.app.ui.screen.settings.language.LanguageViewModel
import network.tos.wallet.app.ui.screen.name.base.NameViewModel
import network.tos.wallet.app.ui.screen.wallet.picker.PickerViewModel
import network.tos.wallet.app.ui.screen.settings.passcode.ChangePasscodeViewModel
import network.tos.wallet.app.ui.screen.settings.security.SecurityViewModel
import network.tos.wallet.app.ui.screen.settings.theme.ThemeViewModel
import network.tos.wallet.app.ui.screen.stories.w5.W5StoriesViewModel
import network.tos.wallet.app.ui.screen.tonconnect.TonConnectViewModel
import network.tos.wallet.app.usecase.emulation.EmulationUseCase
import network.tos.wallet.app.usecase.sign.SignUseCase
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val koinModel = module {
    factory { Dispatchers.Default }

    single(createdAtStart = true) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    singleOf(::Environment)
    singleOf(::RemoteConfig)

    singleOf(::SettingsRepository)
    singleOf(::NetworkMonitor)
    singleOf(::HistoryHelper)
    singleOf(::AssetsManager)
    singleOf(::BillingManager)
    singleOf(::TransactionManager)
    singleOf(::TonConnectManager)
    singleOf(::PushManager)
    singleOf(::SafeModeClient)
    singleOf(::APKManager)
    singleOf(::CacheHelper)
    singleOf(::ReferrerClientHelper)
    singleOf(::AnalyticsHelper)

    factoryOf(::SignUseCase)
    factoryOf(::EmulationUseCase)

    viewModelOf(::DevViewModel)
    viewModelOf(::ChangePasscodeViewModel)
    viewModelOf(::CountryPickerViewModel)
    viewModelOf(::CurrencyViewModel)
    viewModelOf(::ThemeViewModel)
    viewModelOf(::LanguageViewModel)
    viewModelOf(::SecurityViewModel)
    viewModelOf(::BrowserMainViewModel)
    viewModelOf(::BrowserSearchViewModel)
    viewModelOf(::MigrationViewModel)

    viewModelOf(::NameViewModel)
    viewModelOf(::InitViewModel)
    viewModelOf(::MainViewModel)
    viewModelOf(::RootViewModel)
    viewModelOf(::PickerViewModel)
    viewModelOf(::TonConnectViewModel)
    viewModelOf(::CurrencyPickerViewModel)

    viewModelOf(::LedgerConnectionViewModel)
    viewModelOf(::W5StoriesViewModel)
    viewModelOf(::AddWalletViewModel)
    viewModelOf(::BatteryViewModel)
    viewModelOf(BaseWalletVM::EmptyViewViewModel)
}
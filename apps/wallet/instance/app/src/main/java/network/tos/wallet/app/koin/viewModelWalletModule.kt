package network.tos.wallet.app.koin

import org.koin.dsl.module
import network.tos.wallet.app.ui.screen.wallet.main.WalletViewModel
import network.tos.wallet.app.ui.screen.settings.main.SettingsViewModel
import network.tos.wallet.app.ui.screen.name.edit.EditNameViewModel
import network.tos.wallet.app.ui.screen.events.main.EventsViewModel
import network.tos.wallet.app.ui.screen.collectibles.main.CollectiblesViewModel
import network.tos.wallet.app.ui.screen.browser.dapp.DAppViewModel
import network.tos.wallet.app.ui.screen.notifications.NotificationsManageViewModel
import network.tos.wallet.app.ui.screen.token.viewer.TokenViewModel
import network.tos.wallet.app.ui.screen.backup.main.BackupViewModel
import network.tos.wallet.app.ui.screen.backup.check.BackupCheckViewModel
import network.tos.wallet.app.ui.screen.wallet.manage.TokensManageViewModel
import network.tos.wallet.app.ui.screen.send.main.SendViewModel
import network.tos.wallet.app.ui.screen.token.picker.TokenPickerViewModel
import network.tos.wallet.app.ui.screen.battery.settings.BatterySettingsViewModel
import network.tos.wallet.app.ui.screen.battery.refill.BatteryRefillViewModel
import network.tos.wallet.app.ui.screen.battery.recharge.BatteryRechargeViewModel
import network.tos.wallet.app.ui.screen.browser.base.BrowserBaseViewModel
import network.tos.wallet.app.ui.screen.browser.more.BrowserMoreViewModel
import network.tos.wallet.app.ui.screen.card.CardViewModel
import network.tos.wallet.app.ui.screen.collectibles.manage.CollectiblesManageViewModel
import network.tos.wallet.app.ui.screen.dns.renew.DNSRenewViewModel
import network.tos.wallet.app.ui.screen.events.compose.details.TxDetailsViewModel
import network.tos.wallet.app.ui.screen.events.spam.SpamEventsViewModel
import network.tos.wallet.app.ui.screen.events.compose.history.TxEventsViewModel
import network.tos.wallet.app.ui.screen.send.contacts.main.SendContactsViewModel
import network.tos.wallet.app.ui.screen.purchase.PurchaseViewModel
import network.tos.wallet.app.ui.screen.nft.NftViewModel
import network.tos.wallet.app.ui.screen.onramp.main.OnRampViewModel
import network.tos.wallet.app.ui.screen.onramp.picker.currency.OnRampPickerViewModel
import network.tos.wallet.app.ui.screen.onramp.picker.provider.OnRampProviderPickerViewModel
import network.tos.wallet.app.ui.screen.qr.QRViewModel
import network.tos.wallet.app.ui.screen.send.contacts.add.AddContactViewModel
import network.tos.wallet.app.ui.screen.send.contacts.edit.EditContactViewModel
import network.tos.wallet.app.ui.screen.staking.viewer.StakeViewerViewModel
import network.tos.wallet.app.ui.screen.staking.unstake.UnStakeViewModel
import network.tos.wallet.app.ui.screen.staking.stake.StakingViewModel
import network.tos.wallet.app.ui.screen.send.transaction.SendTransactionViewModel
import network.tos.wallet.app.ui.screen.settings.apps.AppsViewModel
import network.tos.wallet.app.ui.screen.settings.extensions.ExtensionsViewModel
import network.tos.wallet.app.ui.screen.send.boc.RemoveExtensionViewModel
import network.tos.wallet.app.ui.screen.sign.SignDataViewModel
import network.tos.wallet.app.ui.screen.staking.withdraw.StakeWithdrawViewModel
import network.tos.wallet.app.ui.screen.swap.omniston.OmnistonViewModel
import network.tos.wallet.app.ui.screen.swap.picker.SwapPickerViewModel
import network.tos.wallet.app.ui.screen.transaction.TransactionViewModel
import org.koin.core.module.dsl.viewModelOf

val viewModelWalletModule = module {
    viewModelOf(::WalletViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::EditNameViewModel)
    viewModelOf(::EventsViewModel)
    viewModelOf(::CollectiblesViewModel)
    viewModelOf(::DAppViewModel)
    viewModelOf(::NotificationsManageViewModel)
    viewModelOf(::TokenViewModel)
    viewModelOf(::BackupViewModel)
    viewModelOf(::BackupCheckViewModel)
    viewModelOf(::TokensManageViewModel)
    viewModelOf(::SendViewModel)
    viewModelOf(::TokenPickerViewModel)
    viewModelOf(::BatterySettingsViewModel)
    viewModelOf(::BatteryRefillViewModel)
    viewModelOf(::BatteryRechargeViewModel)
    viewModelOf(::SendContactsViewModel)
    viewModelOf(::PurchaseViewModel)
    viewModelOf(::NftViewModel)
    viewModelOf(::StakeViewerViewModel)
    viewModelOf(::UnStakeViewModel)
    viewModelOf(::StakingViewModel)
    viewModelOf(::SendTransactionViewModel)
    viewModelOf(::RemoveExtensionViewModel)
    viewModelOf(::StakeWithdrawViewModel)
    viewModelOf(::AddContactViewModel)
    viewModelOf(::EditContactViewModel)
    viewModelOf(::AppsViewModel)
    viewModelOf(::ExtensionsViewModel)
    viewModelOf(::CollectiblesManageViewModel)
    viewModelOf(::CardViewModel)
    viewModelOf(::QRViewModel)
    viewModelOf(::TransactionViewModel)
    viewModelOf(::BrowserMoreViewModel)
    viewModelOf(::BrowserBaseViewModel)
    viewModelOf(::SpamEventsViewModel)
    viewModelOf(::SignDataViewModel)
    viewModelOf(::OnRampViewModel)
    viewModelOf(::OnRampProviderPickerViewModel)
    viewModelOf(::OnRampPickerViewModel)
    viewModelOf(::OmnistonViewModel)
    viewModelOf(::SwapPickerViewModel)
    viewModelOf(::DNSRenewViewModel)
    viewModelOf(::TxEventsViewModel)
    viewModelOf(::TxDetailsViewModel)
}
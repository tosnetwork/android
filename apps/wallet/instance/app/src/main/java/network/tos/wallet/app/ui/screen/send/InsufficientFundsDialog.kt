package network.tos.wallet.app.ui.screen.send

import android.text.SpannableStringBuilder
import android.view.View
import android.widget.Button
import androidx.appcompat.widget.AppCompatImageView
import network.tos.icu.Coins
import network.tos.icu.CurrencyFormatter
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import network.tos.wallet.app.core.Amount
import network.tos.wallet.app.core.InsufficientFundsException
import network.tos.wallet.app.extensions.getTitle
import network.tos.wallet.app.koin.api
import network.tos.wallet.app.ui.screen.battery.BatteryScreen
import network.tos.wallet.app.ui.screen.browser.more.BrowserMoreScreen
import network.tos.wallet.app.ui.screen.onramp.main.OnRampScreen
import network.tos.wallet.app.ui.screen.purchase.PurchaseScreen
import network.tos.wallet.app.ui.screen.send.main.helper.InsufficientBalanceType
import network.tos.wallet.app.view.BatteryView
import network.tos.wallet.app.R
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.localization.Localization
import uikit.base.BaseFragment
import uikit.dialog.modal.ModalDialog
import uikit.navigation.Navigation
import uikit.widget.TextHeaderView

class InsufficientFundsDialog(private val fragment: BaseFragment) : ModalDialog(fragment.requireContext(), R.layout.dialog_insufficient_funds) {

    private val navigation: Navigation?
        get() = Navigation.from(context)

    private val textView = findViewById<TextHeaderView>(R.id.text)!!
    private val iconView = findViewById<AppCompatImageView>(R.id.icon)!!
    private val batteryView = findViewById<BatteryView>(R.id.battery_view)!!
    private val batteryButton = findViewById<Button>(R.id.battery)!!
    private val tonButton = findViewById<Button>(R.id.ton)!!

    init {
        findViewById<View>(R.id.close)!!.setOnClickListener { dismiss() }
    }

    fun show(
        wallet: WalletEntity,
        e: InsufficientFundsException
    ) {
        super.show()
        applyWalletTitle(wallet.label, e.singleWallet, e.type)
        applyDescription(e.available, e.required, e.currency, e.withRechargeBattery, e.type)
        batteryButton.visibility = if (e.withRechargeBattery) View.VISIBLE else View.GONE

        val isBattery = e.type == InsufficientBalanceType.InsufficientBatteryChargesForFee

        tonButton.visibility =
            if (isBattery) View.GONE else View.VISIBLE
        iconView.visibility = if (isBattery) View.GONE else View.VISIBLE
        batteryView.setBatteryLevel(BatteryView.MIN_LEVEL)
        batteryView.visibility = if (isBattery) View.VISIBLE else View.GONE

        tonButton.text = context.getString(Localization.buy_ton).replace("TON", e.currency.code)
        tonButton.setOnClickListener {
            if (e.currency == WalletCurrency.TON) {
                navigation?.add(OnRampScreen.newInstance(context, wallet, "insufficientFunds"))
            } else {
                fragment.finish()
                navigation?.add(BrowserMoreScreen.newInstance(wallet, "defi"))
            }
            dismiss()
        }

        batteryButton.setOnClickListener {
            navigation?.add(BatteryScreen.newInstance(wallet, from = "insufficient_funds"))
            dismiss()
        }
    }

    fun show(
        wallet: WalletEntity,
        balance: Amount,
        required: Amount,
        withRechargeBattery: Boolean,
        singleWallet: Boolean,
        type: InsufficientBalanceType
    ) {
        super.show()
        applyWalletTitle(wallet.label, singleWallet, type)
        applyDescription(balance, required, withRechargeBattery, type)
        val isBatteryDisabled = context.api?.config?.flags?.disableBattery ?: false
        batteryButton.visibility = if (withRechargeBattery && !isBatteryDisabled) View.VISIBLE else View.GONE

        val isBattery = type == InsufficientBalanceType.InsufficientBatteryChargesForFee

        tonButton.visibility =
            if (isBattery) View.GONE else View.VISIBLE
        iconView.visibility = if (isBattery) View.GONE else View.VISIBLE
        batteryView.setBatteryLevel(BatteryView.MIN_LEVEL)
        batteryView.visibility = if (isBattery) View.VISIBLE else View.GONE

        tonButton.text = context.getString(Localization.buy_ton).replace("TON", required.symbol)
        tonButton.setOnClickListener {
            if (required.isTon) {
                navigation?.add(OnRampScreen.newInstance(context, wallet, "insufficientFunds"))
            } else {
                fragment.finish()
                navigation?.add(BrowserMoreScreen.newInstance(wallet, "defi"))
            }
            dismiss()
        }

        batteryButton.setOnClickListener {
            navigation?.add(BatteryScreen.newInstance(wallet, from = "insufficient_funds"))
            dismiss()
        }
    }

    private fun applyWalletTitle(
        label: Wallet.Label,
        singleWallet: Boolean,
        type: InsufficientBalanceType
    ) {
        if (type == InsufficientBalanceType.InsufficientBatteryChargesForFee) {
            textView.titleView.setText(Localization.insufficient_battery_charges)
        } else if (!singleWallet) {
            val walletTitle = label.getTitle(context, textView.titleView, 16)
            val spannable =
                SpannableStringBuilder(context.getString(Localization.insufficient_balance_in_wallet))
            spannable.append(" ")
            spannable.append(walletTitle)

            textView.titleView.text = spannable
        } else {
            textView.titleView.setText(Localization.insufficient_balance_title)
        }
    }

    private fun applyDescription(
        balance: Amount,
        required: Amount,
        withRechargeBattery: Boolean,
        type: InsufficientBalanceType
    ) {
        if (type == InsufficientBalanceType.InsufficientBatteryChargesForFee) {
            textView.descriptionView.text =
                context.getString(
                    Localization.insufficient_balance_charges,
                    CurrencyFormatter.format(value = required.value),
                    CurrencyFormatter.format(value = balance.value)
                )
            return
        } else {
            val balanceFormat =
                CurrencyFormatter.formatFull(balance.symbol, balance.value, balance.decimals)
                    .withCustomSymbol(context)
            val requiredFormat =
                CurrencyFormatter.formatFull(required.symbol, required.value, required.decimals)
                    .withCustomSymbol(context)

            val resId =
                if (withRechargeBattery || type == InsufficientBalanceType.InsufficientBalanceForFee) Localization.insufficient_balance_fees else Localization.insufficient_balance_default
            textView.descriptionView.text = context.getString(resId, requiredFormat, balanceFormat)
        }
    }

    private fun applyDescription(
        balance: Coins,
        required: Coins,
        currency: WalletCurrency,
        withRechargeBattery: Boolean,
        type: InsufficientBalanceType
    ) {
        if (type == InsufficientBalanceType.InsufficientBatteryChargesForFee) {
            textView.descriptionView.text =
                context.getString(
                    Localization.insufficient_balance_charges,
                    CurrencyFormatter.format(value = required.value),
                    CurrencyFormatter.format(value = balance.value)
                )
            return
        } else {
            val balanceFormat =
                CurrencyFormatter.format(currency.code, balance.value)
                    .withCustomSymbol(context)
            val requiredFormat =
                CurrencyFormatter.format(currency.code, required.value)
                    .withCustomSymbol(context)

            val resId =
                if (withRechargeBattery || type == InsufficientBalanceType.InsufficientBalanceForFee) Localization.insufficient_balance_fees else Localization.insufficient_balance_default
            textView.descriptionView.text = context.getString(resId, requiredFormat, balanceFormat)
        }
    }
}
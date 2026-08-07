package network.tos.wallet.data.core

import android.content.Context
import androidx.biometric.BiometricManager
import network.tos.extensions.CrashReporter
import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.wallet.data.core.currency.WalletCurrency

fun List<WalletCurrency>.query(value: String) = firstOrNull {
    it.address.equalsAddress(value)
}

fun accountId(accountId: String, testnet: Boolean): String {
    if (testnet) {
        return "testnet:$accountId"
    }
    return accountId
}

fun isAvailableBiometric(
    context: Context,
    authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_STRONG
): Boolean {
    val authStatus = BiometricManager.from(context).canAuthenticate(authenticators)
    return authStatus == BiometricManager.BIOMETRIC_SUCCESS
}

fun recordException(e: Throwable) {
    CrashReporter.recordException(e)
}
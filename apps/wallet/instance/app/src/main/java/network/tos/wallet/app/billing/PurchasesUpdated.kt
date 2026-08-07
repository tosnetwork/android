package network.tos.wallet.app.billing

import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase

data class PurchasesUpdated(
    val result: BillingResult,
    val purchases: List<Purchase>
)
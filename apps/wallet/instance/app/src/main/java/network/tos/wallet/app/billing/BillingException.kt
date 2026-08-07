package network.tos.wallet.app.billing

import com.android.billingclient.api.BillingResult

class BillingException(val result: BillingResult): Exception(result.debugMessage)
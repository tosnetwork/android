package network.tos.wallet.app.core.entities

import android.net.Uri
import android.os.Parcelable
import android.util.Log
import network.tos.wallet.api.entity.ConfigEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.purchase.entity.PurchaseMethodEntity
import network.tos.wallet.data.purchase.entity.PurchaseMethodEntity.SuccessUrlPattern
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.ton.crypto.digest.sha512
import org.ton.crypto.hex
import java.util.UUID

@Parcelize
data class WalletPurchaseMethodEntity(
    val method: PurchaseMethodEntity,
    val wallet: WalletEntity,
    val currency: String,
    val config: ConfigEntity,
): Parcelable {

    @IgnoredOnParcel
    val successUrlPattern: SuccessUrlPattern?
        get() = method.successUrlPattern

    @IgnoredOnParcel
    val uri: Uri by lazy {
        val address = wallet.address
        val url = method.actionButton.url
        Uri.parse(replaceUrl(url, address, currency, config))
    }

    private companion object {

        fun replaceUrl(
            url: String,
            address: String,
            currency: String,
            config: ConfigEntity
        ): String {
            var replacedUrl = url.replace("{ADDRESS}", address)
            replacedUrl = replacedUrl.replace("{CUR_FROM}", currency)
            replacedUrl = replacedUrl.replace("{CUR_TO}", "TON")

            if (replacedUrl.contains("TX_ID")) {
                val mercuryoSecret = config.mercuryoSecret
                val signature = hex(sha512((address+mercuryoSecret).toByteArray()))
                val tx = "mercuryo_" + UUID.randomUUID().toString()
                replacedUrl = replacedUrl.replace("{TX_ID}", tx)
                replacedUrl = replacedUrl.replace("=TON&", "=TONCOIN&")
                replacedUrl += "&signature=$signature"
            }
            return replacedUrl
        }
    }
}
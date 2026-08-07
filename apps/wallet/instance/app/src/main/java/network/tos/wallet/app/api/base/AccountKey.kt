package network.tos.wallet.app.api.base

class AccountKey(
    private val accountId: String,
    val testnet: Boolean
) {

    override fun toString(): String {
        if (testnet) {
            return "testnet:$accountId"
        }
        return accountId
    }
}

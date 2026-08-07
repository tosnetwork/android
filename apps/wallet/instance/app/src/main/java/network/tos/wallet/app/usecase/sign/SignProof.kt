package network.tos.wallet.app.usecase.sign

import network.tos.base64.encodeBase64
import network.tos.blockchain.ton.connect.TONProof
import network.tos.blockchain.ton.connect.TONProof.Request
import network.tos.wallet.app.extensions.requestPrivateKey
import network.tos.wallet.app.ui.screen.external.qr.keystone.sign.KeystoneSignScreen
import network.tos.wallet.app.ui.screen.ledger.proof.LedgerProofScreen
import network.tos.wallet.app.ui.screen.send.main.SendException
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.rn.RNLegacy
import network.tos.wallet.localization.Localization
import org.ton.crypto.hex
import uikit.extensions.addForResult
import uikit.navigation.NavigationActivity
import java.util.concurrent.CancellationException
import network.tos.blockchain.ton.connect.TCDomain
import network.tos.blockchain.ton.connect.TCAddress

class SignProof(
    private val accountRepository: AccountRepository,
    private val passcodeManager: PasscodeManager,
    private val rnLegacy: RNLegacy,
) {

    suspend fun ledger(
        activity: NavigationActivity,
        wallet: WalletEntity,
        payload: String,
        domain: String,
    ): TONProof.Result {
        val timestamp = System.currentTimeMillis() / 1000L
        val fragment = LedgerProofScreen.newInstance(
            domain = domain,
            timestamp = timestamp.toBigInteger(),
            payload = payload,
            walletId = wallet.id
        )
        val result = activity.addForResult(fragment)
        val signature = result.getByteArray(LedgerProofScreen.SIGNED_PROOF)
        if (signature == null || signature.isEmpty()) {
            throw CancellationException("Ledger cancelled")
        }

        return TONProof.Result(
            timestamp = timestamp,
            domain = TCDomain(domain),
            payload = payload,
            signature = signature.encodeBase64()
        )
    }

    suspend fun keystone(
        activity: NavigationActivity,
        wallet: WalletEntity,
        payload: String,
        domain: String
    ): TONProof.Result {
        val request = Request(
            payload = payload,
            domain = TCDomain(domain),
            address = TCAddress(wallet.contract.address)
        )

        val fragment = KeystoneSignScreen.newInstance(
            unsignedBody = hex(request.message),
            isTransaction = false,
            address = wallet.address,
            keystone = wallet.keystone ?: throw IllegalArgumentException("Keystone is not set")
        )
        val result = activity.addForResult(fragment)
        val signature = fragment.contract.parseResult(result)

        return TONProof.Result(
            timestamp = request.timestamp,
            domain = request.domain,
            payload = request.payload,
            signature = signature.toByteArray().encodeBase64()
        )
    }

    suspend fun default(
        activity: NavigationActivity,
        wallet: WalletEntity,
        payload: String,
        domain: String
    ): TONProof.Result {
        if (!domain.contains(".")) {
            throw IllegalArgumentException("Invalid domain")
        }

        if (!wallet.hasPrivateKey) {
            throw SignException.UnsupportedWalletType(wallet.type)
        }

        val isValidPasscode = passcodeManager.confirmation(activity, activity.getString(Localization.app_name))
        if (!isValidPasscode) {
            throw CancellationException("Passcode cancelled")
        }

        val privateKey = accountRepository.requestPrivateKey(activity, rnLegacy, wallet.id) ?: throw SendException.UnableSendTransaction()

        return TONProof.sign(
            address = wallet.contract.address,
            secretKey = privateKey,
            payload = payload,
            domain = domain
        )
    }

}
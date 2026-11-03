package network.tos.wallet

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import network.tos.wallet.data.account.privacy.PrivacyBalanceState
import network.tos.wallet.data.account.privacy.PrivacyPoolSelection
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class PrivacyPaymentPoolTest {
    @Test fun enrollPoolForIsolatedPaymentWallet() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val encoded = InstrumentationRegistry.getArguments().getString("privacyLiveConfig").orEmpty()
        assumeTrue("Provide the isolated live pool configuration", encoded.isNotEmpty())
        check(instrumentation.targetContext.packageName == "network.tos.wallet.uk")
        val config = JSONObject(Base64.decode(encoded, Base64.NO_WRAP).decodeToString())
        check(config.getString("rpc") == "http://10.0.2.2:19655/jsonRPC")
        val profile = QuantumNetworkProfile(3, config.getString("root_hash"), config.getString("file_hash"))
        val pool = PrivacyPoolSelection(
            profile, "0:${config.getString("pool_account")}",
            Base64.decode(config.getString("code_boc"), Base64.NO_WRAP),
            config.getString("profile_hash"), config.getString("poseidon_manifest_hash"),
            config.getString("groth16_vk_hash"),
        )
        val accounts = GlobalContext.get().get<AccountRepository>()
        val wallet = accounts.getWallets().single()
        accounts.configureTrustedPrivacyPool(wallet, pool)
        InstrumentationRegistry.getArguments().getString("expectedPrivacyBalance")?.let { expected ->
            val observation = accounts.readPrivacyBalance(wallet)
            check(observation is PrivacyBalanceState.Observed)
            check(observation.summary.unspentBalance == expected) { "Unexpected proven wallet balance" }
        }
        if (InstrumentationRegistry.getArguments().getString("exportTestReceipt") == "true") {
            val receipt = accounts.issuePrivacyReceipt(wallet) { false }
            check(receipt.size == 2573)
            instrumentation.targetContext.cacheDir.resolve("public-test-payment-receipt.bin").writeBytes(receipt)
        }
        if (InstrumentationRegistry.getArguments().getString("verifyAcceptedRecovery") == "true") {
            check(accounts.privacyPendingStatus(wallet) { false }.name == "ACCEPTED")
            val fixture = JSONObject(instrumentation.targetContext.filesDir
                .resolve("tos-privacy-payment-fixture.json").readText())
            val relay = fixture.getJSONObject("relay").apply { remove("lifetime") }
            val recovered = accounts.recoverPrivacyPayment(wallet, relay.toString().encodeToByteArray()) { false }
            recovered.use { check(it == null) { "Confirmed payment became eligible for resubmission" } }
        }
        val recipientText = InstrumentationRegistry.getArguments().getString("paymentTestRecipient")
        if (recipientText != null) {
            val insufficient = InstrumentationRegistry.getArguments().getString("verifyInsufficientFunds") == "true"
            val before = accounts.readPrivacyBalance(wallet)
            check(before is PrivacyBalanceState.Observed)
            val balance = before.summary.unspentBalance
            if (insufficient) {
                check(balance == InstrumentationRegistry.getArguments().getString("expectedPrivacyBalance"))
            } else {
                check(balance == "1000000000")
            }
            val recipient = Base64.decode(recipientText, Base64.NO_WRAP)
            check(recipient.size == 2573)
            check(accounts.validatePrivacyRecipient(wallet, recipientText) { false })
            val fixture = JSONObject(instrumentation.targetContext.filesDir
                .resolve("tos-privacy-payment-fixture.json").readText())
            val phase1 = java.io.File(fixture.getString("phase1"))
            val ceremony = java.io.File(fixture.getString("ceremony"))
            accounts.loadPrivacyPaymentProver(wallet, phase1, ceremony) { false }.use { prover ->
                val relay = fixture.getJSONObject("relay").toString().encodeToByteArray()
                accounts.requestPrivacyRelayOffer(wallet, relay) { false }.use offerScope@{ offer ->
                    val quote = offer.details()
                    check(quote.fee == "1")
                    if (insufficient) {
                        val result = runCatching {
                            accounts.stagePrivacyPayment(wallet, recipient, balance, quote.fee,
                                quote.identity, prover, offer) { false }.close()
                        }
                        check(result.isFailure) { "Amount plus fee exceeds the verified balance" }
                        return@offerScope
                    }
                    accounts.stagePrivacyPayment(wallet, recipient, "100000000", quote.fee,
                        quote.identity, prover, offer) { false }.use { payment ->
                        accounts.submitOrRetryPrivacyPayment(payment) { false }
                    }
                }
            }
        }
    }
}

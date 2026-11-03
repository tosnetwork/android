package network.tos.wallet

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
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
        accounts.configureTrustedPrivacyPool(accounts.getWallets().single(), pool)
    }
}

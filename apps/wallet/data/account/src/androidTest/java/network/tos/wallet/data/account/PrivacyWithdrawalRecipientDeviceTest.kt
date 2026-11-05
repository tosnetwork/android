package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.wallet.data.account.privacy.PrivacyWithdrawalRecipient
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyWithdrawalRecipientDeviceTest {
    @Test fun withdrawalRecipientHonorsTestOnlyNetworkMarker() {
        val raw = "0:" + "22".repeat(32)
        val address = org.ton.block.AddrStd.parse(raw)
        for (bounceable in listOf(true, false)) {
            val testOnly = address.toString(userFriendly = true, bounceable = bounceable, testOnly = true)
            check(org.ton.block.AddrStd.parse(testOnly) == address)
            check(runCatching { PrivacyWithdrawalRecipient.parse(testOnly, testnet = false) }.isFailure)
            check(PrivacyWithdrawalRecipient.parse(testOnly, testnet = true) == address)
            val unrestricted = address.toString(userFriendly = true, bounceable = bounceable, testOnly = false)
            for (testnet in listOf(true, false)) {
                check(PrivacyWithdrawalRecipient.parse(unrestricted, testnet) == address)
                check(PrivacyWithdrawalRecipient.parse(raw, testnet) == address)
            }
        }
        for (input in listOf("invalid", "-1:" + "22".repeat(32))) {
            for (testnet in listOf(true, false)) {
                check(runCatching { PrivacyWithdrawalRecipient.parse(input, testnet) }.isFailure)
            }
        }
    }

}

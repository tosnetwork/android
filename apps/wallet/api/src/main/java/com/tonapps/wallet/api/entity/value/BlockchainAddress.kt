package com.tonapps.wallet.api.entity.value

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
data class BlockchainAddress(
    val value: String,
    val testnet: Boolean,
    val blockchain: Blockchain,
): Parcelable {

    @IgnoredOnParcel
    val key: String by lazy {
        if (testnet) {
            "${blockchain.id}:$value:testnet"
        } else {
            "${blockchain.id}:$value"
        }
    }

    companion object {

        fun valueOf(value: String): BlockchainAddress {
            val split = value.split(":")
            return if (split.size == 2) {
                BlockchainAddress(
                    value = split[1],
                    testnet = false,
                    blockchain = Blockchain.valueOf(split[0])
                )
            } else {
                BlockchainAddress(
                    value = split[2],
                    testnet = split[1] == "testnet",
                    blockchain = Blockchain.valueOf(split[0])
                )
            }
        }
    }
}
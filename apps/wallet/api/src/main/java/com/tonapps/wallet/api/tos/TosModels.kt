package com.tonapps.wallet.api.tos

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * Lightweight data models for TOS JSON-RPC responses (Phase 1 skeleton).
 *
 * These are neutral Tos* types; later they are mapped to the app's existing io.tonapi.models.*
 * (Account / TokenRates / AccountEvent, etc.) to replace the data layer's tonapi.io calls.
 */

data class TosBlockId(
    val workchain: Int,
    val shard: String,
    val seqno: Int,
    val rootHash: String,
    val fileHash: String,
) {
    companion object {
        fun fromJson(json: JSONObject?): TosBlockId? {
            json ?: return null
            return TosBlockId(
                workchain = json.optInt("workchain"),
                shard = json.optString("shard"),
                seqno = json.optInt("seqno"),
                rootHash = json.optString("root_hash"),
                fileHash = json.optString("file_hash"),
            )
        }
    }
}

data class TosTransactionId(
    val lt: Long,
    val hash: String,
) {
    companion object {
        fun fromJson(json: JSONObject?): TosTransactionId? {
            json ?: return null
            return TosTransactionId(
                lt = json.optString("lt", "0").toLongOrNull() ?: 0L,
                hash = json.optString("hash"),
            )
        }
    }
}

/** raw.fullAccountState (getAddressInformation) */
data class TosAccountState(
    val balance: BigInteger,
    /** uninit / active / frozen */
    val status: String,
    val codeBoc: String?,
    val dataBoc: String?,
    val lastTransactionId: TosTransactionId?,
    val blockId: TosBlockId?,
    val syncUtime: Long,
) {
    val isActive: Boolean get() = status == "active"

    companion object {
        fun fromJson(json: JSONObject): TosAccountState {
            val code = json.optString("code").takeIf { it.isNotBlank() }
            val data = json.optString("data").takeIf { it.isNotBlank() }
            val rawStatus = json.optString("state").ifBlank {
                if (code != null) "active" else "uninit"
            }
            return TosAccountState(
                balance = json.optString("balance", "0").toBigIntegerOrZero(),
                status = rawStatus,
                codeBoc = code,
                dataBoc = data,
                lastTransactionId = TosTransactionId.fromJson(json.optJSONObject("last_transaction_id")),
                blockId = TosBlockId.fromJson(json.optJSONObject("block_id")),
                syncUtime = json.optLong("sync_utime"),
            )
        }
    }
}

/** wallet.information (getWalletInformation) */
data class TosWalletInfo(
    val isWallet: Boolean,
    val balance: BigInteger,
    val accountState: String,
    val walletType: String?,
    val seqno: Int,
    val lastTransactionId: TosTransactionId?,
) {
    companion object {
        fun fromJson(json: JSONObject): TosWalletInfo = TosWalletInfo(
            // The node returns @type=ext.accounts.walletInformation, with field "wallet" (not is_wallet).
            isWallet = json.optBoolean("wallet", json.optBoolean("is_wallet", false)),
            balance = json.optString("balance", "0").toBigIntegerOrZero(),
            accountState = json.optString("account_state"),
            // seqno / wallet_type may be null (non-wallet account).
            walletType = json.optString("wallet_type").takeIf { it.isNotBlank() && it != "null" },
            seqno = json.optInt("seqno", 0),
            lastTransactionId = TosTransactionId.fromJson(json.optJSONObject("last_transaction_id")),
        )
    }
}

/** query.fees.source_fees (estimateFee), values in nano-TOS */
data class TosFees(
    val inFwdFee: Long,
    val storageFee: Long,
    val gasFee: Long,
    val fwdFee: Long,
) {
    val total: Long get() = inFwdFee + storageFee + gasFee + fwdFee

    companion object {
        fun fromJson(json: JSONObject): TosFees {
            val src = json.optJSONObject("source_fees") ?: json
            return TosFees(
                inFwdFee = src.optLong("in_fwd_fee"),
                storageFee = src.optLong("storage_fee"),
                gasFee = src.optLong("gas_fee"),
                fwdFee = src.optLong("fwd_fee"),
            )
        }
    }
}

/** sendBoc.result (sendBocReturnHash) */
data class TosSendResult(
    val hash: String?,
    val status: Int,
) {
    val accepted: Boolean get() = status == 1

    companion object {
        fun fromJson(json: JSONObject): TosSendResult = TosSendResult(
            hash = json.optString("hash").takeIf { it.isNotBlank() },
            status = json.optInt("status", 0),
        )
    }
}

/** smc.runResult (runGetMethod). The stack keeps its raw JSON, parsed on demand. */
data class TosRunResult(
    val exitCode: Int,
    val gasUsed: Long,
    val stack: JSONArray,
) {
    val success: Boolean get() = exitCode == 0 || exitCode == 1

    companion object {
        fun fromJson(json: JSONObject): TosRunResult = TosRunResult(
            exitCode = json.optInt("exit_code"),
            gasUsed = json.optLong("gas_used"),
            stack = json.optJSONArray("stack") ?: JSONArray(),
        )
    }
}

/** raw.transaction (getTransactions) — raw transaction, parsed into AccountEvent later. */
data class TosRawTransaction(
    val lt: Long,
    val hash: String,
    val utime: Long,
    val fee: BigInteger,
    val account: String,
    val dataBoc: String?,
    val inMsgHash: String?,
) {
    companion object {
        fun fromJson(json: JSONObject): TosRawTransaction {
            val txId = json.optJSONObject("transaction_id")
            return TosRawTransaction(
                lt = txId?.optString("lt", "0")?.toLongOrNull() ?: 0L,
                hash = txId?.optString("hash") ?: "",
                utime = json.optLong("utime"),
                fee = json.optString("fee", "0").toBigIntegerOrZero(),
                account = json.optString("account"),
                dataBoc = json.optString("data").takeIf { it.isNotBlank() },
                inMsgHash = json.optString("in_msg_hash").takeIf { it.isNotBlank() },
            )
        }
    }
}

/** An account's jetton wallet address + balance for a given jetton master (queried via runGetMethod). */
data class TosJettonBalance(
    val jettonMaster: String,
    val jettonWalletAddress: String,
    val balance: BigInteger,
)

/** A jetton wallet's get_wallet_data result: its balance and the jetton master it belongs to. */
data class TosJettonWalletData(
    val jettonWallet: String,
    val balance: BigInteger,
    val jettonMaster: String?,
)

/** An NFT item's get_nft_data result: index, collection (null if standalone), owner. */
data class TosNftItemData(
    val nftItem: String,
    val index: BigInteger,
    val collection: String?,
    val owner: String?,
)

/** blocks.masterchainInfo (getMasterchainInfo) */
data class TosMasterchainInfo(
    val last: TosBlockId?,
    val init: TosBlockId?,
    val stateRootHash: String,
) {
    companion object {
        fun fromJson(json: JSONObject): TosMasterchainInfo = TosMasterchainInfo(
            last = TosBlockId.fromJson(json.optJSONObject("last")),
            init = TosBlockId.fromJson(json.optJSONObject("init")),
            stateRootHash = json.optString("state_root_hash"),
        )
    }
}

internal fun String.toBigIntegerOrZero(): BigInteger =
    this.trim().toBigIntegerOrNull() ?: BigInteger.ZERO

package network.tos.wallet.data.account.entities

import android.os.Parcel
import android.os.Parcelable
import network.tos.blockchain.ton.TonNetwork
import network.tos.blockchain.ton.contract.BaseWalletContract
import network.tos.blockchain.ton.contract.WalletFeature
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import org.ton.block.AddrStd
import network.tos.blockchain.ton.extensions.EmptyPrivateKeyEd25519
import network.tos.blockchain.ton.extensions.hex
import network.tos.blockchain.ton.extensions.publicKeyFromHex
import network.tos.blockchain.ton.extensions.toAccountId
import network.tos.blockchain.ton.extensions.toRawAddress
import network.tos.blockchain.ton.extensions.toWalletAddress
import network.tos.extensions.readBooleanCompat
import network.tos.extensions.readEnum
import network.tos.extensions.readParcelableCompat
import network.tos.extensions.writeBooleanCompat
import network.tos.extensions.writeEnum
import network.tos.wallet.api.entity.value.Blockchain
import network.tos.wallet.api.entity.value.BlockchainAddress
import network.tos.wallet.data.account.Wallet
import kotlinx.parcelize.Parcelize
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.api.pub.PublicKeyEd25519
import org.ton.cell.Cell
import org.ton.contract.wallet.WalletTransfer

data class WalletEntity(
    val id: String,
    val identity: WalletIdentity,
    val type: Wallet.Type,
    val label: Wallet.Label,
    val ledger: Ledger? = null,
    val keystone: Keystone? = null,
    val initialized: Boolean,
): Parcelable {
    init {
        if (identity is WalletIdentity.Quantum) {
            require(type == Wallet.Type.Default || type == Wallet.Type.Testnet)
            require(ledger == null && keystone == null) { "Quantum cannot use a classical hardware signer" }
        }
    }

    constructor(id: String, publicKey: PublicKeyEd25519, type: Wallet.Type, version: WalletVersion,
                label: Wallet.Label, ledger: Ledger? = null, keystone: Keystone? = null,
                initialized: Boolean, networkGlobalId: Int? = null) : this(
        id, WalletIdentity.Classical(publicKey, version, networkGlobalId), type, label, ledger, keystone, initialized
    )

    val classicalPublicKey: PublicKeyEd25519? get() = (identity as? WalletIdentity.Classical)?.publicKey
    val publicKey: PublicKeyEd25519 get() = requireNotNull(classicalPublicKey) { "Quantum has no Ed25519 public key" }
    val quantumIdentity: TosQuantumSelfPayIdentity? get() = (identity as? WalletIdentity.Quantum)?.birth
    val isQuantum: Boolean get() = identity is WalletIdentity.Quantum
    val version: WalletVersion get() = identity.version
    val networkGlobalId: Int? get() = identity.networkGlobalId
    val accountAddress: AddrStd get() = quantumIdentity?.address ?: contract.address


    companion object {

        val EMPTY = WalletEntity(
            id = "",
            publicKey = EmptyPrivateKeyEd25519.publicKey(),
            type = Wallet.Type.Default,
            version = WalletVersion.V5BETA,
            label = Wallet.Label("", "", 0),
            ledger = null,
            keystone = null,
            initialized = false,
        )

        @JvmField
        val CREATOR = object : Parcelable.Creator<WalletEntity> {
            override fun createFromParcel(parcel: Parcel): WalletEntity {
                val id = requireNotNull(parcel.readString())
                val key = requireNotNull(parcel.readString())
                val type = requireNotNull(parcel.readEnum(Wallet.Type::class.java))
                val version = requireNotNull(parcel.readEnum(WalletVersion::class.java))
                val label = requireNotNull(parcel.readParcelableCompat<Wallet.Label>())
                val ledger = parcel.readParcelableCompat<Ledger>()
                val keystone = parcel.readParcelableCompat<Keystone>()
                val initialized = parcel.readBooleanCompat()
                val hasNetwork = parcel.readByte().toInt()
                require(hasNetwork == 0 || hasNetwork == 1)
                val network = if (hasNetwork == 1) parcel.readInt() else null
                val birth = if (key.startsWith("qsi1:")) WalletIdentity.quantumFromBase64(key.removePrefix("qsi1:")) else null
                val identity = WalletIdentity.restore(
                    if (birth == null) key.publicKeyFromHex().key.toByteArray() else null,
                    birth?.toBoc(), version, network
                )
                return WalletEntity(id, identity, type, label, ledger, keystone, initialized)
            }

            override fun newArray(size: Int): Array<WalletEntity?> = arrayOfNulls(size)
        }

    }

    @Parcelize
    data class Ledger(
        val deviceId: String,
        val accountIndex: Int
    ) : Parcelable

    @Parcelize
    data class Keystone(
        val xfp: String,
        val path: String
    ) : Parcelable

    val contract: BaseWalletContract by lazy {
        require(!isQuantum) { "Quantum requires its own self-pay signing flow" }
        val network = networkGlobalId ?: if (testnet) TonNetwork.TESTNET.value else TonNetwork.MAINNET.value

        require(version != WalletVersion.TOSV5R1 || networkGlobalId != null) { "TOS wallet network is required" }
        BaseWalletContract.create(publicKey, version.title, network)
    }

    val maxMessages: Int
        get() = if (isQuantum) 255 else if (type == Wallet.Type.Ledger) 1 else contract.maxMessages

    val testnet: Boolean
        get() = type == Wallet.Type.Testnet

    val signer: Boolean
        get() = type == Wallet.Type.Signer || type == Wallet.Type.SignerQR

    val hasPrivateKey: Boolean
        get() = !isQuantum && (type == Wallet.Type.Default || type == Wallet.Type.Testnet || type == Wallet.Type.Lockup)

    val accountId: String = accountAddress.toAccountId()

    val address: String = accountAddress.toWalletAddress(testnet)

    val blockchainAddress: BlockchainAddress
        get() = BlockchainAddress(
            value = address,
            testnet = testnet,
            blockchain = Blockchain.TON
        )

    val isWatchOnly: Boolean
        get() = type == Wallet.Type.Watch

    val isLedger: Boolean
        get() = type == Wallet.Type.Ledger

    val isKeystone: Boolean
        get() = type == Wallet.Type.Keystone

    val isW5: Boolean
        get() = version == WalletVersion.V5BETA || version == WalletVersion.V5R1 || version == WalletVersion.TOSV5R1

    val isExternal: Boolean
        get() = signer || isLedger || isKeystone

    // Enabled when the dedicated authenticated self-pay send flow is installed.
    val isSendSupported: Boolean get() = !isQuantum && !isWatchOnly

    /** Quantum is sendable only through its dedicated proof-backed TOS payment flow. */
    val isQuantumSelfPayAvailable: Boolean get() = isQuantum

    val isTonConnectSupported: Boolean
        get() = !isQuantum && type != Wallet.Type.Watch

    fun isMyAddress(address: String): Boolean {
        return address.toRawAddress().equals(accountId, ignoreCase = true)
    }

    fun isSupportedFeature(feature: WalletFeature): Boolean {
        return !isQuantum && contract.isSupportedFeature(feature)
    }

    fun createBody(
        seqNo: Int,
        validUntil: Long,
        gifts: List<WalletTransfer>,
        internalMessage: Boolean,
    ): Cell {
        return contract.createTransferUnsignedBody(
            validUntil = validUntil,
            seqNo = seqNo,
            gifts = gifts.toTypedArray(),
            internalMessage = internalMessage,
        )
    }

    fun sign(
        privateKey: PrivateKeyEd25519,
        seqNo: Int,
        body: Cell
    ): Cell {
        return contract.createTransferMessageCell(
            address = contract.address,
            privateKey = privateKey,
            seqNo = seqNo,
            unsignedBody = body,
        )
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString((identity as? WalletIdentity.Quantum)?.let { "qsi1:" + it.toBase64() } ?: publicKey.hex())
        parcel.writeEnum(type)
        parcel.writeEnum(version)
        parcel.writeParcelable(label, flags)
        parcel.writeParcelable(ledger, flags)
        parcel.writeParcelable(keystone, flags)
        parcel.writeBooleanCompat(initialized)
        parcel.writeByte(if (networkGlobalId != null) 1.toByte() else 0.toByte())
        networkGlobalId?.let(parcel::writeInt)
    }

    override fun describeContents(): Int {
        return 0
    }
}

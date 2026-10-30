package network.tos.wallet.data.account

import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.blockchain.ton.contract.walletVersion
import network.tos.blockchain.ton.extensions.hex
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.entities.WalletIdentity
import network.tos.wallet.data.rn.data.RNWallet
import network.tos.wallet.data.rn.data.RNWallet.Companion.int
import network.tos.wallet.data.rn.data.RNLedger
import network.tos.wallet.data.rn.data.RNKeystone
import org.ton.api.pub.PublicKeyEd25519
import org.ton.crypto.hex

internal fun WalletEntity.toRNWallet(): RNWallet = RNWallet(
    name = label.accountName, color = RNWallet.resolveColor(label.color), emoji = RNWallet.fixEmoji(label.emoji),
    identifier = id, pubkey = classicalPublicKey?.hex(),
    network = if (testnet) RNWallet.Network.Testnet else RNWallet.Network.Mainnet,
    type = if (isQuantum) RNWallet.Type.Quantum else when (type) {
        Wallet.Type.Default, Wallet.Type.Testnet -> RNWallet.Type.Regular
        Wallet.Type.Watch -> RNWallet.Type.WatchOnly
        Wallet.Type.Lockup -> RNWallet.Type.Lockup
        Wallet.Type.SignerQR -> RNWallet.Type.Signer
        Wallet.Type.Signer -> RNWallet.Type.SignerDeeplink
        Wallet.Type.Ledger -> RNWallet.Type.Ledger
        Wallet.Type.Keystone -> RNWallet.Type.Keystone
    },
    version = walletVersion(version), workchain = accountAddress.workchainId,
    allowedDestinations = null, configPubKey = null,
    ledger = ledger?.let { RNLedger(deviceId = it.deviceId, accountIndex = it.accountIndex) },
    keystone = keystone?.let { RNKeystone(it.xfp, it.path) },
    networkGlobalId = networkGlobalId,
    quantumIdentity = (identity as? WalletIdentity.Quantum)?.toBase64(),
)

internal fun RNWallet.toWalletEntity(): WalletEntity? {
    val revision = walletVersion(version)
    if (revision == WalletVersion.UNKNOWN) return null
    val label = Wallet.Label(name,
        emoji.replace(Regex("^ic-"), "custom_").removeSuffix("-32").replace("-", "_"), color.int)
    if (type == RNWallet.Type.Quantum) {
        val birth = WalletIdentity.quantumFromBase64(requireNotNull(quantumIdentity))
        val identity = WalletIdentity.restore(null, birth.toBoc(), revision, networkGlobalId)
        return WalletEntity(identifier, identity,
            if (network == RNWallet.Network.Testnet) Wallet.Type.Testnet else Wallet.Type.Default,
            label, initialized = false)
    }
    val kind = if (network == RNWallet.Network.Testnet) Wallet.Type.Testnet else when (type) {
        RNWallet.Type.Regular -> Wallet.Type.Default
        RNWallet.Type.WatchOnly -> Wallet.Type.Watch
        RNWallet.Type.Lockup -> Wallet.Type.Lockup
        RNWallet.Type.SignerDeeplink -> Wallet.Type.Signer
        RNWallet.Type.Signer -> Wallet.Type.SignerQR
        RNWallet.Type.Ledger -> Wallet.Type.Ledger
        RNWallet.Type.Keystone -> Wallet.Type.Keystone
        RNWallet.Type.Quantum -> error("Quantum must carry its birth identity")
    }
    return WalletEntity(identifier, PublicKeyEd25519(hex(requireNotNull(pubkey))), kind, revision, label,
        ledger?.let { WalletEntity.Ledger(it.deviceId, it.accountIndex) },
        keystone?.let { WalletEntity.Keystone(it.xfp, it.path) }, initialized = false, networkGlobalId = networkGlobalId)
}

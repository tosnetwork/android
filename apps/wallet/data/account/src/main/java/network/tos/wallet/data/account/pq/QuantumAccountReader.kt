package network.tos.wallet.data.account.pq

import network.tos.security.privacy.PrivacyNative
import network.tos.blockchain.ton.contract.TosQuantumAccountState

import android.content.Context
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
import network.tos.security.pq.QuantumVerifiedRead
import org.json.JSONArray
import org.json.JSONObject
import org.ton.boc.BagOfCells
import org.ton.cell.CellType
import java.util.UUID

/** Read using the wallet's retained trust root and enrolled rollback checkpoint.
 * Reading funds does not require PRIMARY admission or a usable signing key.
 */
class QuantumAccountReader(
    private val context: Context,
    private val transport: QuantumProofTransport,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    internal data class Observation(
        val id: UUID,
        val identity: TosQuantumSelfPayIdentity,
        val profile: QuantumNetworkProfile,
        val network: QuantumVerifiedRead,
        val proof: QuantumVerifiedRead,
        val snapshot: QuantumSelfPayAccount.Snapshot,
    ) {
        fun requireFresh(now: Long) {
            network.requireLive(now, MAXIMUM_AGE)
            proof.requireLive(now, MAXIMUM_AGE)
            require(now - proof.provenAccountTime(rawAddress(identity)) in -60..MAXIMUM_AGE)
        }
    }

    fun read(id: UUID, identity: TosQuantumSelfPayIdentity): QuantumSelfPayAccount.Snapshot = observe(id, identity).snapshot

    internal fun observe(id: UUID, identity: TosQuantumSelfPayIdentity, configIndices: IntArray = intArrayOf(19)): Observation {
        QuantumRecoveryRecord.findIdentity(context, identity)?.let { recovery ->
            require(recovery.id == id) { "Recovery enrollment identifier mismatch" }
            return observeEnrolled(id, identity, recovery.profile, configIndices)
        }
        val record = QuantumCreationRecord.load(context, id)
        require(record.id == id && record.identity == identity) { "Wallet birth identity mismatch" }
        return observeEnrolled(id, identity, record.profile, configIndices)
    }

    internal fun observeRecoveryImport(checkpoint: QuantumPaymentOutbox.ImportCheckpoint,
                                        backup: network.tos.security.pq.QuantumBackupInspection,
                                        profile: QuantumNetworkProfile): Observation {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        backup.requireProfile(profile.globalId, hex(profile.rootHash), hex(profile.fileHash), backup.snapshot.namespace)
        val session = QuantumProofSession(context, checkpoint.id, profile.anchor())
        val request = QuantumSelfPayNetwork.request(MAXIMUM_AGE)
        try { session.enrollBound(request, now(), transport) }
        catch (error: Exception) {
            // Native initialization refuses existing state; resume without deleting or resetting it.
            session.readBound(request, now(), transport)
        }
        val observed = observeEnrolled(checkpoint.id, backup.birth, profile, intArrayOf(8, 19, 48))
        val namespace = QuantumSelfPayNetwork.recoveryNamespace(observed.network, profile.globalId, now())
        require(namespace.contentEquals(backup.snapshot.namespace)) { "Recovery network namespace mismatch" }
        observed.requireFresh(now())
        return observed
    }

    internal fun readEnrolled(id: UUID, identity: TosQuantumSelfPayIdentity, profile: QuantumNetworkProfile): QuantumSelfPayAccount.Snapshot =
        observeEnrolled(id, identity, profile).snapshot

    internal fun observeEnrolled(id: UUID, identity: TosQuantumSelfPayIdentity, profile: QuantumNetworkProfile, configIndices: IntArray = intArrayOf(19)): Observation {
        require(configIndices.isNotEmpty() && configIndices.size <= 8 && 19 in configIndices && configIndices.toSet().size == configIndices.size && configIndices.all { it in setOf(8,18,19,20,21,24,25,48) })
        require(identity.wallet.initialAuthority.globalId == profile.globalId) { "Wallet network identity mismatch" }
        val session = QuantumProofSession(context, id, profile.anchor())
        val request = JSONObject().put("mode", "live").put("max_age_seconds", MAXIMUM_AGE)
            .put("config_params", JSONArray(configIndices.toList())).toString().toByteArray(Charsets.UTF_8)
        val network = session.readBound(request, now(), transport)
        val root = BagOfCells(network.provenConfigParam(19)).roots.single()
        require(root.type == CellType.ORDINARY && root.levelMask.level == 0)
        val config = root.beginParse()
        require(config.loadInt(32).toInt() == profile.globalId) { "Account network global ID mismatch" }
        require(config.remainingBits == 0 && config.refsPosition == config.refs.size)
        val address = rawAddress(identity)
        val proof = session.readBound(network.requestAtCheckpoint(address, maximumAge = MAXIMUM_AGE), now(), transport)
        network.requireSameCheckpoint(proof)
        val account = QuantumSelfPayAccount.bind(identity, proof, now(), MAXIMUM_AGE)
        network.requireLive(now(), MAXIMUM_AGE)
        return Observation(id, identity, profile, network, proof, account.snapshot(now())).also { it.requireFresh(now()) }
    }

    /** Verified account state, not a private balance or a completed history replay. */
    internal class PoolObservation(
        val id: UUID,
        val profile: QuantumNetworkProfile,
        val network: QuantumVerifiedRead,
        val address: org.ton.block.AddrStd,
        stateJSON: ByteArray,
        private val proof: QuantumVerifiedRead,
    ) {
        private val encoded = stateJSON.copyOf()
        val stateJSON: ByteArray get() = encoded.copyOf()
        fun requireFresh(now: Long) {
            network.requireLive(now, MAXIMUM_AGE)
            network.requireSameCheckpoint(proof)
            proof.requireLive(now, MAXIMUM_AGE)
            require(now - proof.provenAccountTime(rawAddressFor(address)) in -60..MAXIMUM_AGE)
        }
    }

    internal fun observePool(id: UUID, identity: TosQuantumSelfPayIdentity, profile: QuantumNetworkProfile,
                             address: org.ton.block.AddrStd,
                             expectedCode: org.ton.cell.Cell, profileHash: ByteArray,
                             manifestHash: ByteArray, verifyingKeyHash: ByteArray): PoolObservation {
        require(listOf(profileHash, manifestHash, verifyingKeyHash).all { it.size == 32 })
        val profilePin = profileHash.copyOf()
        val manifestPin = manifestHash.copyOf()
        val keyPin = verifyingKeyHash.copyOf()
        require(identity.wallet.initialAuthority.globalId == profile.globalId)
        // Tor relaying needs private notes, not a funded/deployed ordinary wallet.
        val session = QuantumProofSession(context, id, profile.anchor())
        val request = JSONObject().put("mode", "live").put("max_age_seconds", MAXIMUM_AGE)
            .put("config_params", org.json.JSONArray().put(19).put(21)).toString().toByteArray(Charsets.UTF_8)
        val network = session.readBound(request, now(), transport)
        val configRoot = BagOfCells(network.provenConfigParam(19)).roots.single()
        require(configRoot.type == CellType.ORDINARY && configRoot.levelMask.level == 0)
        val config = configRoot.beginParse()
        require(config.loadInt(32).toInt() == profile.globalId)
        require(config.remainingBits == 0 && config.refsPosition == config.refs.size)
        val proof = session.readBound(network.requestAtCheckpoint(rawAddressFor(address), maximumAge = MAXIMUM_AGE), now(), transport)
        network.requireSameCheckpoint(proof)
        proof.requireLive(now(), MAXIMUM_AGE)
        val bytes = proof.accountState(rawAddressFor(address), QuantumPaymentQuote.hex(expectedCode.hash().toByteArray()))
        val data = TosQuantumAccountState.data(bytes, address, expectedCode)
        val decoded = PrivacyNative.decodePoolState(org.ton.boc.BagOfCells(data).toByteArray(), data.hash().toByteArray())
        val json = org.json.JSONObject(String(decoded, Charsets.UTF_8))
        val key = android.util.Base64.decode(json.getString("verifying_key"), android.util.Base64.NO_WRAP)
        require(key.size == 1248 && json.getString("profile_hash") == QuantumPaymentQuote.hex(profilePin) &&
            json.getString("poseidon_manifest_hash") == QuantumPaymentQuote.hex(manifestPin) &&
            json.getString("groth16_vk_hash") == QuantumPaymentQuote.hex(keyPin) &&
            java.security.MessageDigest.getInstance("SHA-256").digest(key).contentEquals(keyPin)) {
            "Privacy pool deployment pins do not match"
        }
        return PoolObservation(id, profile, network, address, decoded, proof).also { it.requireFresh(now()) }
    }

    /** Token discovery rows are hints; only this proof path returns verified units. */
    internal data class TokenObservation(
        val owner: Observation,
        val master: org.ton.block.AddrStd,
        val wallet: org.ton.block.AddrStd,
        val masterState: network.tos.blockchain.ton.contract.TokenMasterState,
        val state: network.tos.blockchain.ton.contract.TokenWalletState?,
        private val masterProof: QuantumVerifiedRead,
        private val walletProof: QuantumVerifiedRead,
    ) {
        val balance: java.math.BigInteger get() = state?.balance ?: java.math.BigInteger.ZERO
        fun requireFresh(now: Long) {
            owner.requireFresh(now)
            for ((proof, address) in listOf(masterProof to master, walletProof to wallet)) {
                proof.requireLive(now, MAXIMUM_AGE)
                owner.network.requireSameCheckpoint(proof)
                require(now - proof.provenAccountTime(rawAddressFor(address)) in -60..MAXIMUM_AGE)
            }
        }
    }

    internal fun observeToken(owner: Observation, master: org.ton.block.AddrStd,
                                       wallet: org.ton.block.AddrStd): TokenObservation {
        val family = network.tos.blockchain.ton.contract.TokenContractCodes.family(owner.identity.address, master, wallet)
        val code = family.wallet
        val masterCode = family.master
        val tokenCodec = network.tos.blockchain.ton.contract.TokenWalletState
        owner.requireFresh(now())
        require(wallet == tokenCodec.address(owner.identity.address, master, code)) { "Unbound Token index row" }
        val session = QuantumProofSession(context, owner.id, owner.profile.anchor())
        fun read(address: org.ton.block.AddrStd): QuantumVerifiedRead {
            val proof = session.readBound(owner.network.requestAtCheckpoint(rawAddressFor(address), maximumAge = MAXIMUM_AGE), now(), transport)
            owner.network.requireSameCheckpoint(proof)
            proof.requireLive(now(), MAXIMUM_AGE)
            return proof
        }
        val masterProof = read(master)
        val masterBytes = masterProof.accountState(rawAddressFor(master), masterCode.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
        val masterData = network.tos.blockchain.ton.contract.TosQuantumAccountState.data(masterBytes, master, masterCode)
        val masterState = network.tos.blockchain.ton.contract.TokenMasterState.decode(masterData, code)
        val walletProof = read(wallet)
        val state = if (walletProof.provenAccountState(rawAddressFor(wallet)) == null) null else {
            val bytes = walletProof.accountState(rawAddressFor(wallet), code.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
            val data = network.tos.blockchain.ton.contract.TosQuantumAccountState.data(bytes, wallet, code)
            tokenCodec.decode(data, wallet, owner.identity.address, master, code)
        }
        return TokenObservation(owner, master, wallet, masterState, state, masterProof, walletProof).also { it.requireFresh(now()) }
    }

    companion object {
        private const val MAXIMUM_AGE = 300L
        internal fun rawAddressFor(address: org.ton.block.AddrStd): String =
            "${address.workchainId}:" + address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
        internal fun rawAddress(identity: TosQuantumSelfPayIdentity): String =
            "0:" + identity.address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

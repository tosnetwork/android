package network.tos.security.pq

import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.TosQuantumSelfPayWallet
import network.tos.blockchain.ton.extensions.toByteArray
import org.junit.Assert.*
import org.junit.Test

class QuantumBackupIdentityTest {
    private val network = ByteArray(32) { 3 }
    private val root = ByteArray(32) { 4 }
    private val file = ByteArray(32) { 5 }
    private fun authority(global: Int = -239, namespace: ByteArray = network, key: Byte = 7) =
        QuantumSelfPayAuthority(global, namespace, ByteArray(1312) { key }, ByteArray(32) { 8 })
    private fun payload(birth: ByteArray, snapshot: ByteArray): QuantumBackupPayload {
        val role = QuantumBackupPayload.Role(1, 0, 0, ByteArray(32), ByteArray(32),
            1, intArrayOf(), byteArrayOf(), byteArrayOf())
        val bytes = QuantumBackupPayload.encodeUnverified(-239, root, file, network, birth, snapshot, listOf(role))
        return try { QuantumBackupPayload.parseUnverified(bytes) } finally { bytes.fill(0); role.wipe() }
    }

    @Test fun originalBirthSurvivesDifferentSnapshotKeys() {
        val birth = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority()))
        val snapshot = authority(key = 9)
        payload(birth.toBoc(), snapshot.cell.toByteArray()).use { parsed ->
            val bound = parsed.validateIdentity(-239, root, file, network)
            assertEquals(birth.address, bound.birth.address)
            assertArrayEquals(snapshot.hash, bound.snapshot.hash)
            assertArrayEquals(birth.toBoc(), parsed.birthBoc)
            assertNotEquals(TosQuantumSelfPayWallet(snapshot).address, bound.birth.address)
            for (field in 0..3) assertThrows(IllegalArgumentException::class.java) {
                parsed.validateIdentity(if (field == 0) -238 else -239,
                    if (field == 1) ByteArray(32) else root,
                    if (field == 2) ByteArray(32) else file,
                    if (field == 3) ByteArray(32) else network)
            }
        }
    }

    @Test fun rejectsMalformedAndCrossNetworkBirthOrSnapshot() {
        val good = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority())).toBoc()
        val foreign = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority(global = -238))).toBoc()
        val snapshot = authority().cell.toByteArray()
        val foreignSnapshot = authority(namespace = ByteArray(32)).cell.toByteArray()
        for ((birth, state) in listOf(foreign to snapshot, good to foreignSnapshot,
            byteArrayOf(1) to snapshot, good to byteArrayOf(1),
            (good + byteArrayOf(0)) to snapshot, good to (snapshot + byteArrayOf(0)))) {
            payload(birth, state).use { parsed ->
                assertThrows(IllegalArgumentException::class.java) {
                    parsed.validateIdentity(-239, root, file, network)
                }
            }
        }
    }
}

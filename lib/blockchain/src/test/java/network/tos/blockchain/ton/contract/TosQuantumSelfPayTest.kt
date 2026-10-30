package network.tos.blockchain.ton.contract

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.buildCell
import org.ton.crypto.hex

class TosQuantumSelfPayTest {
    private val vector = Json.parseToJsonElement(requireNotNull(javaClass.classLoader?.getResourceAsStream("tos-quantum-self-pay.json"))
        .bufferedReader().use { it.readText() }).jsonObject
    private fun text(name: String) = vector.getValue(name).jsonPrimitive.content
    private fun authority(policy: QuantumPolicy = QuantumPolicy.READY) = QuantumSelfPayAuthority(
        text("global_id").toInt(), hex(text("namespace")), hex(text("primary_key")), hex(text("rescue_key")), policy)
    private fun wallet() = TosQuantumSelfPayWallet(authority())
    private fun policy(retired: Int = 0) = buildCell {
        storeUInt(0xa1, 8); storeBytes(hex(text("namespace"))); storeUInt(0, 64)
        storeUInt(retired, 16); storeBit(false)
        storeBytes(hex("5e4380aedc95f8cb72de55f7506de0269b47c03ad1d1ed0e5184c332544262c0"))
    }

    @Test fun readyMigrationAndPrimaryRequireEligiblePolicyWhileRequiredRescueDoesNot() {
        val wallet = wallet()
        val state = QuantumSelfPayState(authority())
        fun migrate(successor: QuantumSelfPayAuthority, cp48: Cell? = null) = TosQuantumSelfPayRequest(
            wallet.address, state, QuantumAuthRole.RESCUE, text("deadline").toLong(),
            QuantumSelfPayAction.Migrate(successor), text("now").toLong(), cp48)
        assertThrows(IllegalArgumentException::class.java) { migrate(authority()) }
        assertThrows(IllegalArgumentException::class.java) { migrate(authority(), policy(2)) }
        migrate(authority(), policy())
        migrate(authority(QuantumPolicy.REQUIRED))
        migrate(authority(QuantumPolicy.REQUIRED), policy(2))
        for (cp48 in listOf(null, policy(2))) {
            assertThrows(IllegalArgumentException::class.java) {
                TosQuantumSelfPayRequest(wallet.address, state, QuantumAuthRole.PRIMARY, text("deadline").toLong(),
                    QuantumSelfPayAction.Execute(Cell.empty()), text("now").toLong(), cp48)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TosQuantumSelfPayRequest(wallet.address, QuantumSelfPayState(authority(), retired = 2), QuantumAuthRole.RESCUE,
                text("deadline").toLong(), QuantumSelfPayAction.Migrate(authority()), text("now").toLong(), policy())
        }
    }

    @Test fun malformedBackupHeadersAndReferencesAreRejectedBeforeSdkDecoding() {
        for (boc in listOf(
            "b5ee9c7201010001000000", // zero cells
            "b5ee9c72010101010002010000", // root outside table
            "b5ee9c7201010101000300010001", // reference outside table
            "b5ee9c7201010101000300010000", // self reference
            "b5ee9c7201010201000600010001010000", // cycle
            "b5ee9c720101020100040000000000", // unreachable cell
            "b5ee9c720101010100070005000000000000", // five references
            "b5ee9c7201010101000300000100", // missing top-up bit
            "b5ee9c7201010101000300000180", // empty partial byte
            "b5ee9c7201010101000200000000", // trailing bytes
            "b5ee9c720401ffffffff000000010000000002000000000000", // oversized declared table
        )) {
            assertThrows(boc, IllegalArgumentException::class.java) { TosQuantumSelfPayIdentity.fromBoc(hex(boc)) }
        }
    }

    @Test fun birthIdentitySurvivesRotationAndRejectsSubstitutedRestoreData() {
        val wallet = wallet()
        val identity = TosQuantumSelfPayIdentity(wallet)
        assertArrayEquals(hex(text("identity_hash")), identity.cell.hash().toByteArray())
        assertEquals(identity, TosQuantumSelfPayIdentity.fromBoc(java.util.Base64.getDecoder().decode(text("identity_boc"))))
        assertEquals(identity, TosQuantumSelfPayIdentity.fromBoc(identity.toBoc()))
        identity.requireNetwork(42, wallet.initialAuthority.namespace)
        assertThrows(IllegalArgumentException::class.java) { identity.requireNetwork(43, wallet.initialAuthority.namespace) }
        assertThrows(IllegalArgumentException::class.java) { identity.requireNetwork(42, ByteArray(32)) }
        val newKey = wallet.initialAuthority.primaryKey.also { it[0] = (it[0].toInt() xor 1).toByte() }
        val successor = QuantumSelfPayAuthority(42, wallet.initialAuthority.namespace, newKey, wallet.initialAuthority.rescueKey)
        val rotated = QuantumSelfPayState(successor, epoch = 1uL)
        val restored = TosQuantumSelfPayIdentity.fromBoc(identity.toBoc())
        assertEquals(wallet.address, restored.address)
        assertArrayEquals(successor.hash, restored.wallet.readState(restored.address, wallet.code, rotated.cell).authority.hash)
        assertNotEquals(wallet.address, TosQuantumSelfPayWallet(successor).address)
        fun encoded(init: Cell, address: AddrStd = AddrStd(0, init.hash())) = buildCell {
            storeUInt(0x51534931, 32); storeBytes(address.address.toByteArray()); storeRef(init)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TosQuantumSelfPayIdentity.parse(encoded(TosPqWallet.stateInit(wallet.code, rotated.cell)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TosQuantumSelfPayIdentity.parse(encoded(TosPqWallet.stateInit(Cell.empty(), wallet.initialData)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TosQuantumSelfPayIdentity.parse(encoded(wallet.stateInit, AddrStd(0, ByteArray(32))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TosQuantumSelfPayIdentity.fromBoc(ByteArray(TosQuantumSelfPayIdentity.MAX_BOC_BYTES + 1))
        }
    }

    @Test fun exactVmExecutedAddressStateAndAllRequestVectors() {
        val wallet = wallet()
        assertArrayEquals(hex(text("wallet_code_hash")), wallet.code.hash().toByteArray())
        assertArrayEquals(hex(text("authority_hash")), wallet.initialAuthority.hash)
        assertArrayEquals(hex(text("initial_state_hash")), wallet.initialData.hash().toByteArray())
        assertEquals(AddrStd(text("wallet_address")), wallet.address)
        val parsed = wallet.readState(wallet.address, wallet.code, wallet.initialData)
        assertEquals(0uL, parsed.epoch)
        assertEquals(wallet.initialData.hash(), parsed.cell.hash())
        for (row in vector.getValue("cases").jsonArray.map { it.jsonObject }) {
            fun field(name: String) = row.getValue(name).jsonPrimitive.content
            val role = if (field("role") == "1") QuantumAuthRole.PRIMARY else QuantumAuthRole.RESCUE
            val action = when (field("kind").toInt()) {
                0 -> QuantumSelfPayAction.Execute(Cell.empty())
                1 -> QuantumSelfPayAction.ConfigureRequired
                3 -> QuantumSelfPayAction.LockPrimary
                else -> QuantumSelfPayAction.Migrate(authority(QuantumPolicy.REQUIRED))
            }
            val r = TosQuantumSelfPayRequest(wallet.address, parsed, role, text("deadline").toLong(), action, text("now").toLong(), policy())
            assertArrayEquals(field("name"), hex(field("request_hash")), r.digest)
            val body = r.submission(hex(field("signature")))
            assertArrayEquals(hex(field("submission_hash")), body.hash().toByteArray())
            assertArrayEquals(hex(field("external_hash")), wallet.external(body, false).hash().toByteArray())
        }
    }

    @Test fun proofReceiptRequiresOriginalUnverifiedBirthAddress() {
        val wallet = wallet()
        val row = vector.getValue("probe").jsonObject
        fun field(name: String) = row.getValue(name).jsonPrimitive.content
        val probe = TosQuantumSelfPayProbe(wallet.address, authority(), hex(field("challenge")), text("deadline").toLong(), text("now").toLong())
        assertEquals(AddrStd(field("address")), probe.address)
        assertArrayEquals(hex(field("digest")), probe.digest)
        assertArrayEquals(hex(field("immutable_hash")), probe.immutable.hash().toByteArray())
        assertArrayEquals(hex(field("initial_state_hash")), probe.initialData.hash().toByteArray())
        val verified = buildCell { storeUInt(1, 8); storeBit(true); storeRef(probe.immutable) }
        probe.requireVerified(probe.address, probe.code, verified)
        assertThrows(IllegalArgumentException::class.java) { probe.requireVerified(probe.address, probe.code, probe.initialData) }
        val forgedBirth = AddrStd(0, TosPqWallet.stateInit(probe.code, verified).hash())
        assertNotEquals(probe.address, forgedBirth)
        assertThrows(IllegalArgumentException::class.java) { probe.requireVerified(forgedBirth, probe.code, verified) }
        assertThrows(IllegalArgumentException::class.java) { probe.requireVerified(probe.address, wallet.code, verified) }
    }

    @Test fun countersRetirementDomainsAndMalformedStateFailClosed() {
        val wallet = wallet()
        val saturated = QuantumSelfPayState(authority(), seqno = 0xffffffffL, rescueNonce = ULong.MAX_VALUE)
        fun request(state: QuantumSelfPayState, action: QuantumSelfPayAction, role: QuantumAuthRole = QuantumAuthRole.RESCUE) =
            TosQuantumSelfPayRequest(wallet.address, state, role, text("deadline").toLong(), action, text("now").toLong())
        request(saturated, QuantumSelfPayAction.LockPrimary)
        request(saturated, QuantumSelfPayAction.Migrate(authority(QuantumPolicy.REQUIRED)))
        assertThrows(IllegalArgumentException::class.java) { request(saturated, QuantumSelfPayAction.Execute(Cell.empty())) }
        assertThrows(IllegalArgumentException::class.java) { request(QuantumSelfPayState(authority(), epoch = ULong.MAX_VALUE), QuantumSelfPayAction.LockPrimary) }
        assertThrows(IllegalArgumentException::class.java) { request(QuantumSelfPayState(authority(), retired = 2), QuantumSelfPayAction.Execute(Cell.empty()), QuantumAuthRole.PRIMARY) }
        assertThrows(IllegalArgumentException::class.java) { request(QuantumSelfPayState(authority()), QuantumSelfPayAction.LockPrimary, QuantumAuthRole.PRIMARY) }
        val s = wallet.initialData.beginParse()
        val trailing = buildCell { storeBits(s.loadBits(280)); storeBit(false); storeRef(wallet.initialAuthority.cell) }
        assertThrows(IllegalArgumentException::class.java) { QuantumSelfPayState.parse(trailing) }
        assertThrows(IllegalArgumentException::class.java) { wallet.readState(AddrStd(0, ByteArray(32)), wallet.code, wallet.initialData) }
        val a = authority(); val escaped = a.primaryKey; escaped.fill(0)
        assertArrayEquals(hex(text("primary_key")), a.primaryKey)
    }
}

package network.tos.blockchain.ton.contract

import network.tos.blockchain.ton.extensions.storeAddress
import org.junit.Assert.*
import org.junit.Test
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.buildCell
import org.ton.crypto.hex
import java.math.BigInteger

class QuantumStoredSelfPayRequestTest {
    private val namespace = ByteArray(32) { 0x42 }
    private fun authority(policy: QuantumPolicy = QuantumPolicy.READY) = QuantumSelfPayAuthority(1, namespace, ByteArray(1312), ByteArray(32), policy)
    private val identity = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority()))
    private val intent = QuantumPaymentIntent(identity.address, BigInteger.ONE, false, "parser")
    private val policy = buildCell {
        storeUInt(0xa1, 8); storeBytes(namespace); storeUInt(0, 64); storeUInt(0, 16); storeBit(false)
        storeBytes(hex("5e4380aedc95f8cb72de55f7506de0269b47c03ad1d1ed0e5184c332544262c0"))
    }
    private fun raw(role: Int = 1, kind: Int = 0, epoch: ULong = 0u, nonce: ULong = 0u, deadline: Long = 200,
                    payload: Cell = buildCell { storeUInt(0x45584543, 32); storeRef(intent.actions()) },
                    tail: Boolean = false, extraRef: Boolean = false): Cell = buildCell {
        storeUInt(0x51535031, 32); storeInt(1, 32); storeBytes(namespace); storeAddress(identity.address)
        storeBytes(authority().hash); storeUInt(role, 8); storeUInt(BigInteger(epoch.toString()), 64)
        storeUInt(BigInteger(nonce.toString()), 64); storeUInt(deadline, 32); storeUInt(kind, 8); storeRef(payload)
        if (tail) storeBit(false)
        if (extraRef) storeRef(buildCell { })
    }
    @Test fun currentBuilderRoundTripsAllSupportedOperationsAndExactUnsignedWidths() {
        val state = QuantumSelfPayState(authority(), epoch = ULong.MAX_VALUE - 1u, primaryNonce = ULong.MAX_VALUE - 1u, rescueNonce = ULong.MAX_VALUE)
        for ((role, action) in listOf(
            QuantumAuthRole.PRIMARY to QuantumSelfPayAction.Execute(intent.actions()),
            QuantumAuthRole.RESCUE to QuantumSelfPayAction.ConfigureRequired,
            QuantumAuthRole.RESCUE to QuantumSelfPayAction.LockPrimary,
            QuantumAuthRole.RESCUE to QuantumSelfPayAction.Migrate(authority(QuantumPolicy.REQUIRED)))) {
            val built = TosQuantumSelfPayRequest(identity.address, state, role, 200, action, 100, policy)
            val decoded = QuantumStoredSelfPayRequest.parse(BagOfCells(built.request).toByteArray())
            decoded.requireIdentity(identity)
            assertArrayEquals(built.digest, decoded.cell.hash().toByteArray())
            assertEquals(role, decoded.role); assertEquals(state.epoch, decoded.epoch)
            assertEquals(if (role == QuantumAuthRole.PRIMARY) state.primaryNonce else state.rescueNonce, decoded.nonce)
            assertEquals(200L, decoded.deadline)
        }
        val rescue = TosQuantumSelfPayRequest(identity.address, QuantumSelfPayState(authority()), QuantumAuthRole.RESCUE,
            200, QuantumSelfPayAction.Execute(intent.actions()), 100)
        assertEquals(0, QuantumStoredSelfPayRequest.parse(rescue.request).kind)
        assertThrows(IllegalArgumentException::class.java) { intent.requireBound(rescue.request) }
        val maxEpoch = QuantumStoredSelfPayRequest.parse(raw(epoch = ULong.MAX_VALUE, nonce = ULong.MAX_VALUE - 1u))
        assertEquals(ULong.MAX_VALUE, maxEpoch.epoch)
    }
    @Test fun unknownKindsRolesPayloadTagsAndEveryTailAreRefused() {
        for (cell in listOf(raw(role = 0), raw(role = 3), raw(kind = 2), raw(kind = 255), raw(kind = 1), raw(tail = true), raw(extraRef = true),
            raw(payload = buildCell { storeUInt(0x45584543, 32); storeRef(intent.actions()); storeBit(false) }),
            raw(payload = buildCell { storeUInt(0x45584544, 32); storeRef(intent.actions()) }),
            raw(payload = buildCell { storeUInt(0x45584543, 32); storeRef(intent.actions()); storeRef(buildCell { }) }))) {
            assertNotNull(runCatching { QuantumStoredSelfPayRequest.parse(cell) }.exceptionOrNull())
        }
        val changed = QuantumPaymentIntent(identity.address, BigInteger.TEN, false, "parser")
        assertThrows(IllegalArgumentException::class.java) { changed.requireBound(raw()) }
    }
    @Test fun originalCounterExhaustionRulesDoNotReadCurrentChainCounters() {
        assertThrows(IllegalArgumentException::class.java) { QuantumStoredSelfPayRequest.parse(raw(deadline = 0)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumStoredSelfPayRequest.parse(raw(nonce = ULong.MAX_VALUE)) }
        val configure = buildCell { storeUInt(0x434f4e46, 32); storeUInt(2, 2) }
        assertThrows(IllegalArgumentException::class.java) {
            QuantumStoredSelfPayRequest.parse(raw(role = 2, kind = 1, epoch = ULong.MAX_VALUE, payload = configure))
        }
        QuantumStoredSelfPayRequest.parse(raw(role = 2, kind = 1, nonce = ULong.MAX_VALUE, payload = configure))
    }
    @Test fun identityBindingAndReturnedByteArraysCannotRetargetTheDecodedRequest() {
        val decoded = QuantumStoredSelfPayRequest.parse(raw())
        decoded.requireIdentity(identity)
        decoded.namespace.fill(0); decoded.authorityHash.fill(0)
        assertArrayEquals(namespace, decoded.namespace); assertArrayEquals(authority().hash, decoded.authorityHash)
        val foreign = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(QuantumSelfPayAuthority(2, namespace, ByteArray(1312), ByteArray(32))))
        assertThrows(IllegalArgumentException::class.java) { decoded.requireIdentity(foreign) }
    }
}

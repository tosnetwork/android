package network.tos.blockchain.ton.contract

import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.cell.buildCell
import org.ton.crypto.hex

class TosQuantumInitialRecoveryTest {
    private val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("tos-quantum-initial-recovery.json")).use { it.readBytes() }
    private val text = bytes.toString(Charsets.UTF_8)
    private fun tag(n: Int) = buildCell { storeUInt(n, 8) }
    private val codes = QuantumCodes(tag(1), tag(2), tag(3))
    private val pins = QuantumCodePins(codes.wallet.hash().toByteArray(), codes.module.hash().toByteArray(), codes.vault.hash().toByteArray())
    private val address = AddrStd(0, hex("017b4078cbfce4b21954669c79ed5f9785176eae6518e8f3bb9448ed2ab12b46"))
    private fun parse(s: String = text, expected: AddrStd = address) = TosQuantumInitialRecovery.parseAndReconstruct(s.toByteArray(), codes, pins, expected)
    @Test fun independentInitialManifestReconstructsAndHintNeverChangesIdentity() {
        val m = parse()
        assertEquals(address, m.genesis.address)
        assertEquals(ULong.MAX_VALUE, m.lastObservedEpoch)
        assertEquals(TosQuantumInitialRecovery.SeedProfile.RAW_MASTER_32, m.derivation.primary)
        assertEquals(address, parse(text.replace("18446744073709551615", "null")).genesis.address)
    }
    @Test fun duplicateUnknownMalformedAndOverwideFieldsAreRefused() {
        for (input in listOf(text.replaceFirst("{", "{\"schema\":\"fake\","),
            text.replaceFirst("{", "{\"secret\":\"never-allowed\","),
            text.replace("\"wallet_id\": 42", "\"wallet_id\": \"42\""),
            text.replace("\"wallet_id\": 42", "\"wallet_id\": 4294967296"),
            text.replace("\"schema\"", "\"\\u0073chema\"", true).replaceFirst("{", "{\"schema\":\"fake\","),
            "{\"derivation\":{\"deep\":{}}}")) {
            assertThrows(Exception::class.java) { parse(input) }
        }
        assertThrows(Exception::class.java) { TosQuantumInitialRecovery.parseAndReconstruct(ByteArray(16385), codes, pins, address) }
        assertThrows(Exception::class.java) { TosQuantumInitialRecovery.parseAndReconstruct(byteArrayOf(0xff.toByte()), codes, pins, address) }
    }
    @Test fun independentTrustAndReconstructedCommitmentsAreRequired() {
        assertThrows(Exception::class.java) { parse(expected = AddrStd(0, ByteArray(32))) }
        val commitment = "1d54665e5652d75d891c35b1c80b09c52b5186c28b2533ed054feb9c51b85876"
        assertThrows(Exception::class.java) { parse(text.replace(commitment, "00".repeat(32))) }
        assertThrows(Exception::class.java) { parse(text.replace("raw-master-32-v1", "classic-seed-v1")) }
    }
    @Test fun malformedEncodingNeverEchoesInput() {
        val marker = "PRIVATE_RECOVERY_INPUT"
        val error = assertThrows(Exception::class.java) { parse("{\"schema\":\"$marker\"} garbage") }
        assertFalse(error.toString().contains(marker))
        assertNull(error.cause)
    }
    @Test fun preparedPublicMetadataMatchesIndependentCorpusAndRoundTrips() {
        val tree = ByteArray(32).also { it[30] = 1; it[31] = 0xc8.toByte() }
        val network = ByteArray(32).also { it[31] = 123 }
        val fee = hex("000000010000000800000003" + "33".repeat(16) + "44".repeat(32))
        val profile = TosQuantumInitialRecovery.SeedProfile.RAW_MASTER_32
        val m = TosQuantumInitialRecovery.prepare(codes, pins, 42, network, 42, ByteArray(1312) { 0x11 },
            ByteArray(32) { 0x22 }, QuantumPolicy.READY, tree, fee, 1779992790,
            TosQuantumInitialRecovery.Derivation(0, 0, profile, profile, profile))
        val expected = kotlinx.serialization.json.Json.parseToJsonElement(text.replace("18446744073709551615", "null"))
        assertEquals(expected, kotlinx.serialization.json.Json.parseToJsonElement(m.toJson().toString(Charsets.UTF_8)))
        assertEquals(address, TosQuantumInitialRecovery.parseAndReconstruct(m.toJson(), codes, pins, address).genesis.address)
        val first = m.toJson(); first.fill(0)
        assertEquals(address, TosQuantumInitialRecovery.parseAndReconstruct(m.toJson(), codes, pins, address).genesis.address)
    }
}

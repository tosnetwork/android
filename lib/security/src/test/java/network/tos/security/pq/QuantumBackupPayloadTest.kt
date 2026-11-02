package network.tos.security.pq

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class QuantumBackupPayloadTest {
    private fun fixture(roles: IntArray = intArrayOf(1, 2), source: Int = 1,
                        word: Int = 2047, password: ByteArray = byteArrayOf()): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(1)
            out.writeInt(-3)
            out.write(ByteArray(96) { it.toByte() })
            out.writeInt(1); out.writeByte(9)
            out.writeInt(1); out.writeByte(8)
            out.writeByte(roles.size)
            roles.forEach { role ->
                out.writeByte(role)
                out.writeInt(-1); out.writeInt(-1)
                out.write(ByteArray(if (role == 1) 32 else 48) { 7 })
                out.write(ByteArray(32) { 6 })
                out.writeByte(source)
                if (source == 2) {
                    out.writeByte(1); out.writeByte(12)
                    repeat(12) { out.writeShort(word) }
                    out.writeShort(password.size); out.write(password)
                }
                if (source == 3) out.write(ByteArray(32) { 5 })
            }
        }
        return bytes.toByteArray()
    }

    @Test fun parsesUnsignedCountersAndWipesOwnedSecrets() {
        for (source in 1..3) {
            val input = fixture(source = source, password = "é 😀".toByteArray())
            val parsed = QuantumBackupPayload.parseUnverified(input)
            assertEquals(-3, parsed.globalId)
            assertEquals(2, parsed.roles.size)
            assertEquals(4294967295L, parsed.roles[0].account)
            assertEquals(4294967295L, parsed.roles[1].generation)
            assertEquals(48, parsed.roles[1].seed.size)
            if (source == 2) assertEquals(2047, parsed.roles[0].wordIndices[0])
            parsed.close()
            parsed.roles.forEach {
                assertTrue(it.seed.all { byte -> byte == 0.toByte() })
                assertTrue(it.wordIndices.all { word -> word == 0 })
                assertTrue(it.phrasePassword.all { byte -> byte == 0.toByte() })
                assertTrue(it.master.all { byte -> byte == 0.toByte() })
            }
            assertArrayEquals(fixture(source = source, password = "é 😀".toByteArray()), input)
        }
    }

    @Test fun rejectsEveryTruncationAndTrailingData() {
        for (source in 1..3) {
            val input = fixture(source = source)
            for (size in 0 until input.size) {
                assertThrows(IllegalArgumentException::class.java) {
                    QuantumBackupPayload.parseUnverified(input.copyOf(size))
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                QuantumBackupPayload.parseUnverified(input + byteArrayOf(0))
            }
        }
    }

    @Test fun encodingPreservesExactWireAndRejectsInvalidSources() {
        for (source in 1..3) {
            val original = fixture(source = source, password = "é 😀".toByteArray())
            QuantumBackupPayload.parseUnverified(original).use { parsed ->
                assertArrayEquals(original, QuantumBackupPayload.encodeUnverified(parsed.globalId,
                    parsed.rootHash, parsed.fileHash, parsed.namespace, parsed.birthBoc,
                    parsed.authorityBoc, parsed.roles))
            }
        }
        QuantumBackupPayload.parseUnverified(fixture(source = 2)).use { parsed ->
            parsed.roles[0].wordIndices[0] = 2048
            assertThrows(IllegalArgumentException::class.java) {
                QuantumBackupPayload.encodeUnverified(parsed.globalId, parsed.rootHash,
                    parsed.fileHash, parsed.namespace, parsed.birthBoc, parsed.authorityBoc, parsed.roles)
            }
        }
        QuantumBackupPayload.parseUnverified(fixture()).use { parsed ->
            assertThrows(IllegalArgumentException::class.java) {
                QuantumBackupPayload.encodeUnverified(parsed.globalId, ByteArray(31),
                    parsed.fileHash, parsed.namespace, parsed.birthBoc, parsed.authorityBoc, parsed.roles)
            }
        }
    }

    @Test fun rejectsInvalidRolesAndSourceMaterials() {
        for (roles in listOf(intArrayOf(), intArrayOf(1, 1), intArrayOf(2, 1), intArrayOf(3))) {
            assertThrows(IllegalArgumentException::class.java) {
                QuantumBackupPayload.parseUnverified(fixture(roles))
            }
        }
        for (input in listOf(fixture(source = 4), fixture(source = 2, word = 2048),
            fixture(source = 2, password = ByteArray(1025)),
            fixture(source = 2, password = byteArrayOf(0xc0.toByte(), 0xaf.toByte())))) {
            assertThrows(Exception::class.java) { QuantumBackupPayload.parseUnverified(input) }
        }
    }
}

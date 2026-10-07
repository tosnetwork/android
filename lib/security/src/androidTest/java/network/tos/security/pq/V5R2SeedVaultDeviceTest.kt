package network.tos.security.pq

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class V5R2SeedVaultDeviceTest {
    @Test fun encryptedRolesBindContextAndSignPop() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = app.getSharedPreferences("v5r2-custody-${UUID.randomUUID()}", 0)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val context = V5R2SeedVault.Context(ByteArray(32) { 1 }, 42, 0, 0)
        val wrong = V5R2SeedVault.Context(ByteArray(32) { 1 }, 43, 0, 0)
        val vault = V5R2SeedVault(prefs)
        try {
            for (role in V5R2Role.entries) {
                val seed = ByteArray(role.seedSize) { 9 }
                val expected = V5R2Crypto.publicKey(role, seed)
                assertArrayEquals(expected, vault.importAndWipe("test", role, context, key, seed))
                assertArrayEquals(ByteArray(role.seedSize), seed)
                assertArrayEquals(expected, V5R2SeedVault(prefs).publicKey("test", role, context, key))
                val wrongContext = runCatching { vault.publicKey("test", role, wrong, key) }.exceptionOrNull()
                assertTrue(wrongContext is javax.crypto.AEADBadTagException)
                val digest = ByteArray(32) { 7 }
                val signature = vault.sign("test", role, context, key, expected, V5R2Purpose.POP, digest)
                assertTrue(V5R2Crypto.verify(role, V5R2Purpose.POP, expected, digest, signature))
                val duplicate = ByteArray(role.seedSize) { 4 }
                assertTrue(runCatching { vault.importAndWipe("test", role, context, key, duplicate) }.isFailure)
                assertArrayEquals(ByteArray(role.seedSize), duplicate)
            }
        } finally { check(prefs.edit().clear().commit()) }
    }
}

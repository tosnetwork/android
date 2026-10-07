package network.tos.security.pq

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Dedicated R2 wrapping key. Repository authentication precedes secret operations.
 * No hardware PQ signing claim. Legacy wallet deletion cannot remove this alias. */
object V5R2DeviceKey {
    private const val ALIAS = "network.tos.wallet.v5r2.wrapping.v1"
    @Synchronized fun get(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            check(create) { "R2 device wrapping key absent" }
            val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true)
            if (Build.VERSION.SDK_INT >= 28) builder.setUnlockedDeviceRequired(true)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(builder.build()); generateKey()
            }
        }
        return (store.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
}

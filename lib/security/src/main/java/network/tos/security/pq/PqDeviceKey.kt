package network.tos.security.pq

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Dedicated nonexportable AES wrapping key. App PIN authentication is required by the
 * wallet repository before every secret operation. No claim of hardware PQ signing.
 * API 28+ additionally requires the device to be unlocked; failure is never downgraded. */
object PqDeviceKey {
    private const val ALIAS = "network.tos.wallet.pq.wrapping.v1"
    /** Call only after the authenticated repository has removed its final seed. */
    @Synchronized fun delete() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(ALIAS) }
    }
    @Synchronized fun get(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            check(create) { "PQ device key is absent; restore an encrypted backup" }
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

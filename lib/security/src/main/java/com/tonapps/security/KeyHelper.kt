package com.tonapps.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeyHelper {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_SIZE = 256
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val keyStore: KeyStore by lazy {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE)
        store.load(null)
        store
    }

    fun createIfNotExists(alias: String, requireUnlockedDevice: Boolean = false) {
        // Do not log key aliases: they reveal the key-management structure of the vault.
        if (!keyExists(alias)) {
            generateKey(alias, requireUnlockedDevice)
        }
    }

    /**
     * Generate the master key for [alias].
     *
     * First principles: the vault master key should live in a dedicated secure element
     * (StrongBox) when the device has one, so it is far harder to extract than a
     * TEE/software-backed key. StrongBox is, however, unreliable on a non-trivial slice
     * of devices (it can advertise support yet fail to generate or to encrypt/decrypt).
     *
     * To get the hardware benefit without risking a device that silently cannot use the
     * key, we try StrongBox first and then immediately run a real encrypt→decrypt
     * self-test with the freshly created key. Only a key that actually round-trips is
     * kept; on any failure we delete it and fall back to the standard key. This runs
     * once per fresh alias (existing keys are never touched), so it cannot break or
     * migrate vaults of current users.
     */
    private fun generateKey(alias: String, requireUnlockedDevice: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Strongest: StrongBox + (optionally) unlocked-device-required.
            if (tryGenerateKey(getParameterKeyStrongBox(alias, requireUnlockedDevice)) && selfTest(alias)) {
                return
            }
            deleteKey(alias)
            // Next: TEE/software-backed + unlocked-device-required. setUnlockedDeviceRequired
            // is unreliable on some OEM devices (e.g. certain Samsung models), so we keep the
            // key only if it actually round-trips; otherwise we drop the flag below.
            if (requireUnlockedDevice) {
                if (tryGenerateKey(getParameterKey(alias, requireUnlockedDevice = true)) && selfTest(alias)) {
                    return
                }
                deleteKey(alias)
            }
        }
        // Last resort: standard key (older APIs, or OEMs where the hardened flags fail the
        // self-test). Never weaker than the previous behaviour.
        generateKeySwallowing(getParameterKey(alias, requireUnlockedDevice = false))
    }

    /** Generates a key, returning false (instead of throwing) on any failure. */
    private fun tryGenerateKey(parameter: KeyGenParameterSpec): Boolean {
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(parameter)
            generator.generateKey()
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun generateKeySwallowing(parameter: KeyGenParameterSpec) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(parameter)
        try {
            generator.generateKey()
        } catch (e: Throwable) {
            // device locked
        }
    }

    /** Real encrypt→decrypt round-trip to confirm the freshly created key is usable. */
    private fun selfTest(alias: String): Boolean {
        return try {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry ?: return false
            val key: SecretKey = entry.secretKey
            val sample = byteArrayOf(0x54, 0x4f, 0x53, 0x6b, 0x65, 0x79) // "TOSkey"

            val encryptCipher = Cipher.getInstance(TRANSFORMATION)
            encryptCipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = encryptCipher.iv
            val ciphertext = encryptCipher.doFinal(sample)

            val decryptCipher = Cipher.getInstance(TRANSFORMATION)
            decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            decryptCipher.doFinal(ciphertext).contentEquals(sample)
        } catch (e: Throwable) {
            false
        }
    }

    private fun deleteKey(alias: String) {
        try {
            keyStore.deleteEntry(alias)
        } catch (e: Throwable) {
            // nothing to delete
        }
    }

    private fun keyExists(alias: String): Boolean {
        return keyStore.containsAlias(alias)
    }

    private fun defaultParameterBuilder(alias: String, requireUnlockedDevice: Boolean): KeyGenParameterSpec.Builder {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        builder.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        builder.setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
        builder.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        builder.setKeySize(KEY_SIZE)
        builder.setUserAuthenticationRequired(false)
        builder.setRandomizedEncryptionRequired(true)
        // For secret-bearing vaults the master key should be unusable while the device is
        // locked, so a stolen-but-locked device cannot have its ciphertext decrypted.
        // Callers that read encrypted prefs in the background while locked must NOT request
        // this. Reliability across OEMs is enforced by the round-trip self-test in generateKey.
        if (requireUnlockedDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }
        return builder
    }

    private fun getParameterKey(alias: String, requireUnlockedDevice: Boolean): KeyGenParameterSpec {
        return defaultParameterBuilder(alias, requireUnlockedDevice).build()
    }

    private fun getParameterKeyStrongBox(alias: String, requireUnlockedDevice: Boolean): KeyGenParameterSpec {
        val builder = defaultParameterBuilder(alias, requireUnlockedDevice)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

}
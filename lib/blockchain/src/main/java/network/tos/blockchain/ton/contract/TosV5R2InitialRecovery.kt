package network.tos.blockchain.ton.contract

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.ton.block.AddrStd

/** Initial public metadata only. Live proofs, possession, retirement and fee continuity remain separate gates. */
class TosV5R2InitialRecovery private constructor(
    val genesis: TosV5R2Genesis, val derivation: Derivation, val lastObservedEpoch: ULong?,
    private val wire: JsonObject
) {
    fun toJson(): ByteArray = wire.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 16 * 1024) }
    enum class SeedProfile(val wire: String) {
        NATIVE_MNEMONIC("tos-native-mnemonic-v1"), RAW_MASTER_32("raw-master-32-v1")
    }
    data class Derivation(val account: Long, val generation: Long, val primary: SeedProfile,
                          val rescue: SeedProfile, val fee: SeedProfile)
    companion object {
        private const val SCHEMA = "TOS-WALLET-V5R2-INITIAL-RECOVERY-v1"
        private const val KDF = "TOS-WALLET-DUALROOT-KDF-v1"
        private const val FEE = "HSS-L1-LMS-SHA256-M32-H20-LMOTS-SHA256-N32-W4"
        private val json = Json { ignoreUnknownKeys = false; isLenient = false }
        private val fields = setOf("schema", "kdf", "derivation", "workchain", "global_id", "network", "wallet_id",
            "primary_key", "rescue_key", "policy", "fee_profile", "fee_tree_id", "fee_public_key", "fee_epoch0",
            "wallet_code", "module_code", "vault_code", "wallet_state_init", "module_state_init", "vault_state_init", "fee_config_hash")
        private val derivationFields = setOf("account_index", "key_generation", "primary_seed_profile", "rescue_seed_profile", "fee_seed_profile")

        /** Prepare public creation metadata. This does not demonstrate possession or fund a recovery route. */
        fun prepare(codes: V5R2Codes, pins: V5R2CodePins, globalId: Int, network: ByteArray, walletId: Long,
                    primaryKey: ByteArray, rescueKey: ByteArray, policy: V5R2Policy, tree: ByteArray,
                    feeKey: ByteArray, epoch0: Long, derivation: Derivation): TosV5R2InitialRecovery {
            require(derivation.account in 0..0xffffffffL && derivation.generation in 0..0xffffffffL)
            require(network.size == 32 && primaryKey.size == 1312 && rescueKey.size == 32 && tree.size == 32 && feeKey.size == 60)
            require(pins.wallet.size == 32 && pins.module.size == 32 && pins.vault.size == 32)
            val n = network.copyOf(); val p = primaryKey.copyOf(); val r = rescueKey.copyOf()
            val t = tree.copyOf(); val f = feeKey.copyOf()
            val trusted = V5R2CodePins(pins.wallet.copyOf(), pins.module.copyOf(), pins.vault.copyOf())
            val g = TosV5R2Genesis(codes, trusted, globalId, n, walletId, p, r, policy, t, f, epoch0)
            val obj = buildJsonObject {
                put("schema", SCHEMA); put("kdf", KDF)
                putJsonObject("derivation") {
                    put("account_index", derivation.account); put("key_generation", derivation.generation)
                    put("primary_seed_profile", derivation.primary.wire); put("rescue_seed_profile", derivation.rescue.wire)
                    put("fee_seed_profile", derivation.fee.wire)
                }
                put("workchain", 0); put("global_id", globalId); put("network", hex(n)); put("wallet_id", walletId)
                put("primary_key", hex(p)); put("rescue_key", hex(r))
                put("policy", if (policy == V5R2Policy.READY) "RESCUE_READY" else "SLH_REQUIRED")
                put("fee_profile", FEE); put("fee_tree_id", hex(t)); put("fee_public_key", hex(f)); put("fee_epoch0", epoch0)
                put("wallet_code", hex(trusted.wallet)); put("module_code", hex(trusted.module)); put("vault_code", hex(trusted.vault))
                put("wallet_state_init", hex(g.walletInit.hash().toByteArray()))
                put("module_state_init", hex(g.moduleInit.hash().toByteArray()))
                put("vault_state_init", hex(g.vaultInit.hash().toByteArray())); put("fee_config_hash", hex(g.configHash))
                put("last_observed_epoch", JsonNull)
            }
            return parseAndReconstruct(obj.toString().toByteArray(Charsets.UTF_8), codes, trusted, g.address)
        }
        private fun hex(value: ByteArray): String {
            val digits = "0123456789abcdef"
            return buildString { for (byte in value) { val n = byte.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) } }
        }

        /** Independently trusted pins and expected wallet must not be taken from this manifest. */
        fun parseAndReconstruct(encoded: ByteArray, codes: V5R2Codes, pins: V5R2CodePins,
                                expectedWallet: AddrStd): TosV5R2InitialRecovery {
            require(encoded.size <= 16 * 1024) { "Manifest size limit" }
            require(expectedWallet.workchainId == 0 && expectedWallet.anycast.value == null)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString()
            checkStructure(text)
            val root = safeJson(text) as? JsonObject ?: error("Manifest must be an object")
            require(root.keys - "last_observed_epoch" == fields) { "Unknown or missing manifest field" }
            require(string(root, "schema") == SCHEMA && string(root, "kdf") == KDF)
            require(integer(root, "workchain") == 0L && string(root, "fee_profile") == FEE)
            val global = integer(root, "global_id")
            require(global in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            val d = root["derivation"] as? JsonObject ?: error("Invalid derivation object")
            require(d.keys == derivationFields)
            val derivation = Derivation(u32(d, "account_index"), u32(d, "key_generation"),
                profile(d, "primary_seed_profile"), profile(d, "rescue_seed_profile"), profile(d, "fee_seed_profile"))
            require(bytes(root, "wallet_code", 32).contentEquals(pins.wallet) &&
                bytes(root, "module_code", 32).contentEquals(pins.module) &&
                bytes(root, "vault_code", 32).contentEquals(pins.vault)) { "Manifest release code mismatch" }
            val policy = when (string(root, "policy")) {
                "RESCUE_READY" -> V5R2Policy.READY
                "SLH_REQUIRED" -> V5R2Policy.REQUIRED
                else -> error("Unsupported rescue policy")
            }
            val genesis = TosV5R2Genesis(codes, pins, global.toInt(), bytes(root, "network", 32),
                u32(root, "wallet_id"), bytes(root, "primary_key", 1312), bytes(root, "rescue_key", 32), policy,
                bytes(root, "fee_tree_id", 32), bytes(root, "fee_public_key", 60), u32(root, "fee_epoch0"))
            require(genesis.address == expectedWallet) { "Manifest wallet differs from trusted enrollment" }
            require(bytes(root, "wallet_state_init", 32).contentEquals(genesis.walletInit.hash().toByteArray()) &&
                bytes(root, "module_state_init", 32).contentEquals(genesis.moduleInit.hash().toByteArray()) &&
                bytes(root, "vault_state_init", 32).contentEquals(genesis.vaultInit.hash().toByteArray()) &&
                bytes(root, "fee_config_hash", 32).contentEquals(genesis.configHash)) { "Reconstructed identity mismatch" }
            val hint = root["last_observed_epoch"]?.takeUnless { it == JsonNull }?.let {
                val p = it as? JsonPrimitive ?: error("Invalid epoch hint")
                require(!p.isString)
                p.content.toULongOrNull() ?: error("Invalid epoch hint")
            }
            return TosV5R2InitialRecovery(genesis, derivation, hint, root)
        }
        private fun safeJson(text: String): JsonElement = try { json.parseToJsonElement(text) }
            catch (_: Exception) { throw IllegalArgumentException("Invalid recovery manifest encoding") }
        private fun string(obj: JsonObject, field: String): String {
            val p = obj[field] as? JsonPrimitive ?: error("Invalid manifest field")
            require(p.isString); return p.content
        }
        private fun integer(obj: JsonObject, field: String): Long {
            val p = obj[field] as? JsonPrimitive ?: error("Invalid manifest integer")
            require(!p.isString); return p.content.toLongOrNull() ?: error("Invalid manifest integer")
        }
        private fun u32(obj: JsonObject, field: String): Long {
            val value = integer(obj, field); require(value in 0..0xffffffffL); return value
        }
        private fun profile(obj: JsonObject, field: String): SeedProfile =
            SeedProfile.entries.singleOrNull { it.wire == string(obj, field) } ?: error("Unsupported seed profile")
        private fun bytes(obj: JsonObject, field: String, size: Int): ByteArray {
            val value = string(obj, field)
            require(value.length == size * 2 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Canonical manifest hex required" }
            return ByteArray(size) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        // Bound nesting before DOM allocation and reject duplicate decoded keys,
        // including escaped aliases. The full JSON parser still validates grammar.
        private fun checkStructure(text: String) {
            val scopes = ArrayDeque<MutableSet<String>>()
            var start = -1; var escaped = false
            for (i in text.indices) {
                val c = text[i]
                if (start >= 0) {
                    if (escaped) { escaped = false; continue }
                    if (c == '\\') { escaped = true; continue }
                    if (c == '"') {
                        var next = i + 1
                        while (next < text.length && text[next] in " \t\r\n") next++
                        if (next < text.length && text[next] == ':') {
                            require(scopes.isNotEmpty())
                            val key = try { json.decodeFromString<String>(text.substring(start, i + 1)) }
                            catch (_: Exception) { throw IllegalArgumentException("Invalid recovery manifest encoding") }
                            require(scopes.last().add(key)) { "Duplicate manifest field" }
                        }
                        start = -1
                    }
                } else when (c) {
                    '"' -> start = i
                    '{' -> { require(scopes.size < 2) { "Manifest nesting limit" }; scopes.addLast(mutableSetOf()) }
                    '}' -> { require(scopes.isNotEmpty()); scopes.removeLast() }
                    '[', ']' -> error("Manifest arrays are not supported")
                }
            }
            require(start < 0 && scopes.isEmpty())
        }
    }
}

package network.tos.wallet.data.account.pq

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import network.tos.wallet.data.account.entities.WalletIdentity
import org.json.JSONObject
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.buildCell
import java.io.*
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID

/** Private no-backup authorization journal. Unknown writes never permit replacement signing. */
class QuantumPaymentOutbox(context: Context) {
    enum class Phase { RESERVED, SIGNING, SIGNED, SUBMITTING, UNKNOWN }
    class Entry private constructor(private val value: JSONObject) {
        val id: UUID get() = UUID.fromString(QuantumPaymentQuote.string(value, "id"))
        val identity: TosQuantumSelfPayIdentity get() = WalletIdentity.quantumFromBase64(QuantumPaymentQuote.string(value, "identity"))
        val profile: QuantumNetworkProfile get() = QuantumNetworkProfile.parse(value.getJSONObject("profile"))
        val intent: QuantumPaymentIntent get() = decodeIntent(value.getJSONObject("intent"))
        val deploy: Boolean get() = (value.get("deploy") as? Boolean ?: error("Expected exact deployment flag"))
        val phase: Phase get() = Phase.valueOf(QuantumPaymentQuote.string(value, "phase"))
        val requestBOC: ByteArray get() = decode(QuantumPaymentQuote.string(value, "request"))
        val requestHash: ByteArray get() = decode(QuantumPaymentQuote.string(value, "request_hash"))
        val signingPublicKey: ByteArray get() = decode(QuantumPaymentQuote.string(value, "public_key"))
        val signature: ByteArray? get() = if (value.isNull("signature")) null else decode(QuantumPaymentQuote.string(value, "signature"))
        val signedBOC: ByteArray? get() = if (value.isNull("message")) null else decode(QuantumPaymentQuote.string(value, "message"))
        val feeApproval: JSONObject? get() = if (value.isNull("approval")) null else
            (value.get("approval") as? JSONObject ?: error("Expected fee approval object")).let { JSONObject(it.toString()) }
        internal fun json() = JSONObject(value.toString())
        internal fun authorization(): QuantumStoredSelfPayRequest = QuantumStoredSelfPayRequest.parse(requestBOC).also {
            it.requireIdentity(identity); intent.requireBound(it)
        }
        internal fun request(): Cell = authorization().cell
        val deadline: Long get() = authorization().deadline
        companion object { internal fun parse(value: JSONObject) = Entry(JSONObject(value.toString())) }
    }
    private val directory = File(context.noBackupFilesDir, "quantum-payment-outbox")
    init {
        try { Os.mkdir(directory.absolutePath, 0x1c0) }
        catch (e: ErrnoException) { if (e.errno != OsConstants.EEXIST) throw e }
        val st = Os.lstat(directory.absolutePath)
        check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0)
        sync(directory); sync(directory.parentFile!!)
    }
    fun pending(identity: TosQuantumSelfPayIdentity): Entry? = locked { read(identity) }
    /** Capability consumption check only. This A-stage API never deletes or writes an entry. */
    internal fun validateExpired(capability: QuantumExpiredAuthorization, now: Long) = locked {
        capability.requireCurrent(checkNotNull(read(capability.identity)), now)
    }
    internal fun validateBinding(binding: QuantumAuthorizationBinding) = locked {
        binding.requireCurrent(checkNotNull(read(binding.identity)))
    }
    internal fun validateReceipt(receipt: QuantumPaymentOutcomeReader.Receipt, now: Long) = locked {
        receipt.requireCurrent(checkNotNull(read(receipt.identity)), now)
    }
    internal fun reserve(payment: QuantumPreparedPayment, now: Long): Entry = locked {
        payment.requireFresh(now)
        check(read(payment.identity) == null) { "An authorization is already pending" }
        check(directory.listFiles()!!.count { it.extension == "json" } < 64) { "Outbox capacity exceeded" }
        val request = payment.request.request
        payment.intent.requireBound(request)
        val entry = Entry.parse(JSONObject().put("version", 1).put("id", UUID.randomUUID().toString())
            .put("identity", WalletIdentity.Quantum(payment.identity).toBase64()).put("profile", payment.profile.json())
            .put("intent", encodeIntent(payment.intent)).put("deploy", payment.requiresDeployment)
            .put("phase", Phase.RESERVED.name).put("request", encode(BagOfCells(request).toByteArray()))
            .put("request_hash", encode(payment.request.digest)).put("public_key", encode(payment.signingPublicKey)))
        // An intent is limited to one 4096-byte text send; reserve signature/envelope and approval space.
        check(entry.requestBOC.size <= 524288 && entry.json().toString().toByteArray().size <= MAX_FILE - 16384)
        write(entry); entry
    }
    internal fun sign(entry: Entry, signer: (Entry) -> ByteArray) {
        val signing = update(entry, setOf(Phase.RESERVED)) { it.put("phase", Phase.SIGNING.name) }
        val signature = signer(signing)
        check(QuantumCrypto.verify(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY, signing.signingPublicKey, signing.requestHash, signature))
        update(signing, setOf(Phase.SIGNING)) { value ->
            value.put("signature", encode(signature)).put("message", encode(message(signing, signature)))
                .put("phase", Phase.SIGNED.name)
        }
    }
    internal fun beginSubmission(entry: Entry, quote: QuantumPaymentQuote, now: Long): ByteArray {
        val current = update(entry, setOf(Phase.SIGNED, Phase.SUBMITTING, Phase.UNKNOWN)) {
            quote.requireFresh(Entry.parse(it), now)
            it.put("approval", quote.approval()).put("phase", Phase.SUBMITTING.name)
        }
        return checkNotNull(current.signedBOC)
    }
    internal fun unknown(entry: Entry) {
        update(entry, setOf(Phase.SUBMITTING, Phase.UNKNOWN)) { it.put("phase", Phase.UNKNOWN.name) }
    }
    fun cancelUnsigned(entry: Entry) = locked {
        val current = checkNotNull(read(entry.identity))
        check(current.id == entry.id && current.phase == Phase.RESERVED)
        Os.remove(path(entry.identity).absolutePath); sync(directory)
    }
    private fun update(entry: Entry, phases: Set<Phase>, body: (JSONObject) -> Unit): Entry = locked {
        val current = checkNotNull(read(entry.identity))
        check(current.id == entry.id && current.phase in phases) { "Authorization transition refused" }
        val value = current.json(); body(value)
        Entry.parse(value).also(::write)
    }
    private fun read(identity: TosQuantumSelfPayIdentity): Entry? {
        val file = path(identity)
        val fd = try { Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0) }
            catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) return null else throw e }
        val bytes = FileInputStream(fd).use { input ->
            val st = Os.fstat(fd)
            check(st.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && st.st_uid == Os.getuid() && st.st_mode and 0x3f == 0 && st.st_size in 1..MAX_FILE.toLong())
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; check(n <= MAX_FILE - output.size()); output.write(buffer, 0, n) }
            output.toByteArray()
        }
        val text = bytes.toString(Charsets.UTF_8)
        var quoted = false; var escaped = false; var depth = 0
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; check(depth <= 32) }; '}', ']' -> { depth--; check(depth >= 0) } }
        }
        check(!quoted && depth == 0)
        val value = JSONObject(text); check(value.get("version") is Int && value.get("version") == 1)
        val entry = Entry.parse(value)
        check(entry.identity == identity && entry.profile.globalId == identity.wallet.initialAuthority.globalId)
        check(entry.requestBOC.size in 1..MAX_MESSAGE && entry.requestHash.size == 32 && entry.signingPublicKey.size == 1312)
        val authorization = entry.authorization()
        check(authorization.cell.hash().toByteArray().contentEquals(entry.requestHash) && authorization.globalId == entry.profile.globalId)
        when (entry.phase) {
            Phase.RESERVED, Phase.SIGNING -> check(entry.signature == null && entry.signedBOC == null && entry.feeApproval == null)
            else -> {
                val sig = checkNotNull(entry.signature); val boc = checkNotNull(entry.signedBOC)
                check(boc.size in 1..MAX_MESSAGE && QuantumCrypto.verify(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY, entry.signingPublicKey, entry.requestHash, sig))
                TosQuantumSelfPayIdentity.preflightOrdinaryBoc(boc, MAX_MESSAGE)
                check(BagOfCells(boc).roots.single().hash() == BagOfCells(message(entry, sig)).roots.single().hash())
            }
        }
        entry.feeApproval?.let { check(entry.phase in setOf(Phase.SUBMITTING, Phase.UNKNOWN)); QuantumPaymentQuote.requireApproval(it, checkNotNull(entry.signedBOC), entry.intent) }
        return entry
    }
    private fun message(entry: Entry, signature: ByteArray): ByteArray = BagOfCells(entry.identity.wallet.external(
        buildCell { storeRef(entry.request()); storeRef(TosPqWallet.byteChain(signature)) }, entry.deploy)).toByteArray().also { check(it.size in 1..MAX_MESSAGE) }
    private fun path(identity: TosQuantumSelfPayIdentity) = File(directory, identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it) } + ".json")
    private fun write(entry: Entry) {
        val bytes = entry.json().toString().toByteArray(Charsets.UTF_8); check(bytes.size <= MAX_FILE)
        val temporary = File(directory, "${UUID.randomUUID()}.pending")
        val fd = Os.open(temporary.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        FileOutputStream(fd).use { it.write(bytes); it.fd.sync() }
        Os.rename(temporary.absolutePath, path(entry.identity).absolutePath); sync(directory)
        // Any failed write keeps the pending file or prior durable phase; never reset it here.
    }
    private fun <T> locked(body: () -> T): T = synchronized(mutex) {
        val fd = Os.open(File(directory, ".lock").absolutePath, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW, 0x180)
        FileOutputStream(fd).use { stream ->
            val lease = try { stream.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            check(lease != null) { "Outbox is busy" }
            lease.use { check(directory.listFiles()!!.none { it.extension == "pending" }) { "Outbox write recovery required" }; body() }
        }
    }
    private fun sync(file: File) { val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0); try { Os.fsync(fd) } finally { Os.close(fd) } }
    companion object {
        private val mutex = Any()
        private const val MAX_FILE = 2 * 1024 * 1024
        private const val MAX_MESSAGE = 1024 * 1024
        internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        internal fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        internal fun decode(text: String): ByteArray { require(text.length <= 2_796_204); return Base64.decode(text, Base64.NO_WRAP) }
        internal fun encodeIntent(intent: QuantumPaymentIntent) = JSONObject().put("recipient", QuantumAccountReader.rawAddressFor(intent.recipient))
            .put("amount", intent.amount.toString()).put("bounce", intent.bounce).put("comment", intent.comment)
        internal fun decodeIntent(value: JSONObject) = QuantumPaymentIntent(org.ton.block.AddrStd.parse(QuantumPaymentQuote.string(value, "recipient")),
            QuantumPaymentQuote.coins(QuantumPaymentQuote.string(value, "amount")), (value.get("bounce") as? Boolean ?: error("Expected exact bounce flag")), if (value.isNull("comment")) null else QuantumPaymentQuote.string(value, "comment"))
    }
}

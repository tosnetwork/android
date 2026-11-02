package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.TokenContent
import network.tos.wallet.api.tos.TosRpcClient
import network.tos.wallet.api.tos.TosTokenIndex
import org.json.JSONObject
import org.ton.block.AddrStd
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.CancellationException

/** A refresh preserves the authenticated native balance even when discovery or one Token fails.
 * Index completeness remains an untrusted node claim, never an all-assets proof.
 */
class QuantumTokenAssets internal constructor(
    val native: QuantumSelfPayAccount.Snapshot,
    val tokens: List<Token>,
    val nodeReportsComplete: Boolean,
    val reachedRowLimit: Boolean,
    val unreadPositions: Int,
    val discoveryFailed: Boolean,
) {
    data class Token internal constructor(
        val master: String,
        val wallet: String,
        val units: BigInteger,
        val name: String?,
        val symbol: String?,
        /** Null means unresolved metadata. Never format those units with guessed precision. */
        val decimals: Int?,
        val metadataUri: String?,
        val metadataFailed: Boolean,
    )

    internal companion object {
        fun read(reader: QuantumAccountReader, rpc: TosRpcClient, id: UUID,
                 identity: network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity): QuantumTokenAssets {
            val owner = reader.observe(id, identity)
            val index = try {
                TosTokenIndex.decode(rpc.callTokenRead("getAccountTokens", JSONObject()
                    .put("address", QuantumAccountReader.rawAddress(identity)).put("limit", TosTokenIndex.MAXIMUM_ROWS)))
            } catch (error: Exception) {
                rethrowCancellation(error)
                owner.requireFresh(System.currentTimeMillis() / 1000)
                return QuantumTokenAssets(owner.snapshot, emptyList(), false, false, 0, true)
            }
            val tokens = ArrayList<Token>()
            val metadataClient = network.tos.wallet.api.tos.TosTokenOffchainMetadataClient()
            var unread = 0
            // Do not let a large or hostile discovery index hold a refresh indefinitely.
            val deadline = System.nanoTime() + 30_000_000_000L
            for ((position, hint) in index.positions.withIndex()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Token refresh cancelled")
                if (System.nanoTime() >= deadline) {
                    unread += index.positions.size - position
                    break
                }
                val observed = try {
                    reader.observeToken(owner, AddrStd.parse(hint.master), AddrStd.parse(hint.wallet))
                } catch (error: Exception) {
                    rethrowCancellation(error)
                    unread++
                    continue
                }
                val content = try { TokenContent.decode(observed.masterState.content) }
                catch (error: Exception) { rethrowCancellation(error); null }
                val needsRemote = content?.uri != null && (content.name == null || content.symbol == null || content.decimals == null)
                val remote = if (needsRemote) try { metadataClient.read(checkNotNull(content?.uri)) }
                    catch (error: Exception) { rethrowCancellation(error); null } else null
                tokens.add(Token(hint.master, hint.wallet, observed.balance, content?.name ?: remote?.name,
                    content?.symbol ?: remote?.symbol, content?.decimals ?: remote?.decimals,
                    content?.uri, content == null || (needsRemote && remote == null)))
            }
            // Reads share this checkpoint; none may be returned as current after its freshness window.
            owner.requireFresh(System.currentTimeMillis() / 1000)
            return QuantumTokenAssets(owner.snapshot, tokens.toList(), index.nodeReportsComplete,
                index.reachedRowLimit, unread, false)
        }

        private fun rethrowCancellation(error: Exception) {
            if (error is InterruptedException || error is CancellationException) throw error
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Token refresh cancelled")
        }
    }
}

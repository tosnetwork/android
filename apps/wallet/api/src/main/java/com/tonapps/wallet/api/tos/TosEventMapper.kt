package com.tonapps.wallet.api.tos

import com.tonapps.blockchain.ton.extensions.cellFromBase64
import com.tonapps.blockchain.ton.extensions.toAccountId
import io.tonapi.models.AccountAddress
import io.tonapi.models.AccountEvent
import io.tonapi.models.Action
import io.tonapi.models.ActionSimplePreview
import io.tonapi.models.TonTransferAction
import org.ton.block.AddrStd
import org.ton.block.IntMsgInfo
import org.ton.block.Message
import org.ton.block.MsgAddress
import org.ton.block.Transaction
import org.ton.cell.Cell
import org.ton.contract.CellStringTlbConstructor
import org.ton.tlb.CellRef
import org.ton.tlb.loadTlb

/**
 * Parses TOS raw transactions (the getTransactions data BOC) into the
 * io.tonapi.models.AccountEvent used by the app's events layer (Phase 1.5).
 *
 * Only TON transfers are parsed (amount and counterparties of internal in/out messages).
 * jetton/NFT/comment/fee details are added later. A transaction that fails to parse is
 * skipped without affecting the rest.
 */
object TosEventMapper {

    fun toAccountEvents(accountId: String, txs: List<TosRawTransaction>): List<AccountEvent> {
        return txs.mapNotNull { tx ->
            runCatching { toAccountEvent(accountId, tx) }.getOrNull()
        }.filter { it.actions.isNotEmpty() }
    }

    private fun toAccountEvent(accountId: String, tx: TosRawTransaction): AccountEvent {
        val boc = tx.dataBoc ?: throw IllegalStateException("no tx boc")
        val transaction = Transaction.loadTlb(boc.cellFromBase64())
        val aux = transaction.r1.value

        val actions = mutableListOf<Action>()

        // Incoming: internal in_msg carrying a value
        val inMsg: Message<*>? = try {
            aux.inMsg.value?.value
        } catch (e: Throwable) {
            null
        }
        (inMsg?.info as? IntMsgInfo)?.let { info ->
            val amount = info.value.coins.amount.value.toLong()
            if (amount > 0) {
                actions.add(tonTransfer(info.src, info.dest, amount, tx.hash, parseComment(inMsg)))
            }
        }

        // Outgoing: each internal out_msg
        for ((_, ref) in aux.outMsgs) {
            val msg = ref.value
            (msg.info as? IntMsgInfo)?.let { info ->
                val amount = info.value.coins.amount.value.toLong()
                actions.add(tonTransfer(info.src, info.dest, amount, tx.hash, parseComment(msg)))
            }
        }

        return AccountEvent(
            eventId = tx.hash,
            account = accountAddress(accountId),
            timestamp = tx.utime,
            actions = actions,
            isScam = false,
            lt = tx.lt,
            inProgress = false,
            extra = 0L,
            progress = 1f,
        )
    }

    private fun tonTransfer(src: MsgAddress, dest: MsgAddress, amount: Long, hash: String, comment: String?): Action {
        val sender = accountAddress(addrId(src))
        val recipient = accountAddress(addrId(dest))
        return Action(
            type = Action.Type.TonTransfer,
            status = Action.Status.ok,
            simplePreview = ActionSimplePreview(
                name = "TOS Transfer",
                description = comment ?: "",
                accounts = listOf(sender, recipient),
            ),
            baseTransactions = listOf(hash),
            tonTransfer = TonTransferAction(
                sender = sender,
                recipient = recipient,
                amount = amount,
                comment = comment,
            ),
        )
    }

    private fun addrId(addr: MsgAddress): String = (addr as? AddrStd)?.toAccountId() ?: ""

    /**
     * Parse a text comment (op == 0 followed by a snake string) from a message body.
     * Defensive: returns null on any failure so history never breaks.
     */
    private fun parseComment(msg: Message<*>): String? = try {
        val bodyCell: Cell? = (msg.body.x as? Cell) ?: (msg.body.y as? CellRef<*>)?.value as? Cell
        if (bodyCell == null) {
            null
        } else {
            val slice = bodyCell.beginParse()
            if (slice.loadUInt32().toLong() != 0L) {
                null
            } else {
                slice.loadTlb(CellStringTlbConstructor).decodeToString().takeIf { it.isNotBlank() }
            }
        }
    } catch (e: Throwable) {
        null
    }

    private fun accountAddress(accountId: String) = AccountAddress(
        address = accountId,
        isScam = false,
        isWallet = true,
    )
}

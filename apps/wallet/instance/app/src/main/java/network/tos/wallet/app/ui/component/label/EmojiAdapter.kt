package network.tos.wallet.app.ui.component.label

import android.graphics.Color
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.emoji.EmojiEntity
import network.tos.emoji.ui.EmojiView
import network.tos.wallet.app.R
import network.tos.uikit.color.iconPrimaryColor
import uikit.extensions.inflate

class EmojiAdapter(
    private val listener: (EmojiEntity) -> Unit
): RecyclerView.Adapter<EmojiAdapter.Holder>() {

    private var array = arrayOf<EmojiEntity>()

    class Holder(
        parent: ViewGroup,
        private val listener: (EmojiEntity) -> Unit
    ): RecyclerView.ViewHolder(parent.inflate(R.layout.view_name_emoji)) {

        private val emojiView = itemView.findViewById<EmojiView>(R.id.emoji)

        fun bind(emoji: EmojiEntity) {
            itemView.setOnClickListener {
                listener(emoji)
            }
            emojiView.setEmoji(emoji.value, itemView.context.iconPrimaryColor)
        }
    }

    fun submitList(emojis: Array<EmojiEntity>) {
        array = emojis
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent, listener)

    override fun getItemCount() = array.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(array[position])
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.setHasFixedSize(false)
        recyclerView.isNestedScrollingEnabled = true
        recyclerView.itemAnimator = null
        recyclerView.layoutAnimation = null
    }
}
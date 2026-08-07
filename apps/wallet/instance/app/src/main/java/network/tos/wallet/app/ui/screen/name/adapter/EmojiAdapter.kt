package network.tos.wallet.app.ui.screen.name.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.emoji.ui.EmojiView
import network.tos.wallet.app.extensions.isLightTheme
import network.tos.wallet.app.R
import network.tos.uikit.color.iconPrimaryColor

class EmojiAdapter(
    private val listener: (CharSequence) -> Unit
): RecyclerView.Adapter<EmojiAdapter.Holder>() {

    private companion object {
        private const val emojiViewType = 0
        private val viewPool = RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(emojiViewType,100)
        }
    }

    private var array = arrayOf<CharSequence>()

    class Holder(
        parent: ViewGroup,
        private val listener: (CharSequence) -> Unit
    ): RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.view_name_emoji, parent, false)) {

        private val context = itemView.context
        private val emojiView = itemView.findViewById<EmojiView>(R.id.emoji)

        fun bind(emoji: CharSequence) {
            itemView.setOnClickListener {
                listener(emoji)
            }
            emojiView.setEmoji(emoji, context.iconPrimaryColor)
        }
    }

    fun submitList(emojis: Array<CharSequence>) {
        array = emojis
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent, listener)

    override fun getItemCount() = array.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(array[position])
    }

    override fun getItemViewType(position: Int) = emojiViewType

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.setRecycledViewPool(viewPool)
        recyclerView.setHasFixedSize(false)
        recyclerView.isNestedScrollingEnabled = true
        recyclerView.itemAnimator = null
        recyclerView.layoutAnimation = null
    }
}
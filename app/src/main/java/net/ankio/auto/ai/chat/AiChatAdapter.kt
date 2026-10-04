package net.ankio.auto.ai.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.ankio.auto.databinding.ItemChatMsgBinding

class AiChatAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf()
) : RecyclerView.Adapter<AiChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(val binding: ItemChatMsgBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatMsgBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        with(holder.binding) {
            if (msg.role == "user") {
                layoutUser.visibility = View.VISIBLE
                layoutAi.visibility = View.GONE
                tvUserContent.text = msg.content
                if (!msg.imageUri.isNullOrBlank()) {
                    ivUserImage.visibility = View.VISIBLE
                    runCatching {
                        ivUserImage.setImageURI(android.net.Uri.parse(msg.imageUri))
                    }
                } else if (!msg.imageBase64.isNullOrBlank()) {
                    ivUserImage.visibility = View.VISIBLE
                    runCatching {
                        val pureBase64 = msg.imageBase64!!.substringAfter("base64,")
                        val bytes = android.util.Base64.decode(pureBase64, android.util.Base64.DEFAULT)
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ivUserImage.setImageBitmap(bitmap)
                    }
                } else {
                    ivUserImage.visibility = View.GONE
                }
            } else {
                layoutUser.visibility = View.GONE
                layoutAi.visibility = View.VISIBLE
                tvAiContent.text = msg.content

                if (msg.isExecutingTool && !msg.toolStatus.isNullOrBlank()) {
                    tvToolStatus.visibility = View.VISIBLE
                    tvToolStatus.text = msg.toolStatus
                } else {
                    tvToolStatus.visibility = View.GONE
                }
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(content: String, isExecutingTool: Boolean = false, toolStatus: String? = null) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx].content = content
            messages[lastIdx].isExecutingTool = isExecutingTool
            messages[lastIdx].toolStatus = toolStatus
            notifyItemChanged(lastIdx)
        }
    }
}

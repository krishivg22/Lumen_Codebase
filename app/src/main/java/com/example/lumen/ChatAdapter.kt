package com.example.lumen

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

sealed class ChatItem(val text: String)
class UserMessage(text: String) : ChatItem(text)
class BotMessage(text: String) : ChatItem(text)
class SystemMessage(text: String) : ChatItem(text)

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val items = mutableListOf<ChatItem>()

    fun addUserMessage(text: String) {
        items.add(UserMessage(text))
        notifyItemInserted(items.size - 1)
    }

    fun addBotMessage(text: String) {
        items.add(BotMessage(text))
        notifyItemInserted(items.size - 1)
    }

    fun addSystemMessage(text: String) {
        items.add(SystemMessage(text))
        notifyItemInserted(items.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val container = holder.itemView.findViewById<LinearLayout>(R.id.messageContainer)
        val tv = holder.itemView.findViewById<TextView>(R.id.tvMessage)

        tv.text = item.text

        // Default simple styling
        tv.setBackgroundColor(Color.parseColor("#EEEEEE"))
        tv.setTextColor(Color.parseColor("#000000"))

        // Use container gravity and padding to position left / right and keep spacing
        when (item) {
            is UserMessage -> {
                // align right: give larger left padding so it appears inset from left edge
                container.gravity = Gravity.END
                // left padding bigger (in px). 48px ~ comfortable space; adjust if you prefer dp conversion.
                container.setPadding(48, 8, 8, 8)
                // small highlight for user messages (subtle)
                tv.setBackgroundColor(Color.parseColor("#2196F3"))
                tv.setTextColor(Color.WHITE)
            }
            is BotMessage -> {
                // align left: give larger right padding so it doesn't touch right edge
                container.gravity = Gravity.START
                container.setPadding(8, 8, 48, 8)
                // neutral background
                tv.setBackgroundColor(Color.parseColor("#EEEEEE"))
                tv.setTextColor(Color.parseColor("#000000"))
            }
            is SystemMessage -> {
                // center system messages, small gray text
                container.gravity = Gravity.CENTER
                container.setPadding(8, 8, 8, 8)
                tv.setBackgroundColor(Color.TRANSPARENT)
                tv.setTextColor(Color.DKGRAY)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView)
}

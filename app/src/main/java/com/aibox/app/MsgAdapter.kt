package com.aibox.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class ChatMsg(val role: String, var content: String, val sys: String? = null) {
    /** 长内容折叠状态：true 表示用户已展开 */
    var expanded: Boolean = false
}

class MsgAdapter(
    private val items: MutableList<ChatMsg>,
    private val onAiLongClick: (Int) -> Unit = {},
    private val onUserLongClick: (Int) -> Unit = {},
    private val onAiCopy: (Int) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        /** 折叠提示行（点击展开） */
        private const val HINT = "🔧 执行命令...（点击展开）"
        private const val LIMIT = 200
        private val CMD_PREFIX = Regex("^\\s*(\\$ |>|>>|# )")
        private val CMD_WORD = Regex("\\b(curl|wget|busybox|tar|gunzip|make|pip|npm|apt)\\b", RegexOption.IGNORE_CASE)

        /** 命中命令特征的消息默认折叠；正常回复（ai）永不折叠 */
        fun shouldCollapse(content: String, role: String): Boolean {
            if (role == "ai") return false
            if (content.isBlank()) return false
            // 工具执行条目（🔧 开头）默认折叠
            if (role == "sys" && content.startsWith("🔧")) return true
            if (CMD_PREFIX.containsMatchIn(content)) return true
            if (content.contains("% Total", ignoreCase = true)) return true
            // 用户粘贴的长命令/日志才按长度折叠，避免误伤普通长文本
            return role == "user" && content.length > LIMIT && CMD_WORD.containsMatchIn(content)
        }
    }

    override fun getItemViewType(pos: Int): Int = when (items[pos].role) {
        "user" -> 0
        "ai" -> 1
        else -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> VHUser(inflater.inflate(R.layout.item_msg_user, parent, false))
            1 -> VHAi(inflater.inflate(R.layout.item_msg_ai, parent, false))
            else -> VHSys(inflater.inflate(R.layout.item_msg_sys, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val m = items[pos]
        val collapsible = shouldCollapse(m.content, m.role)
        val shown = if (collapsible && !m.expanded) HINT else m.content
        val click: (View) -> Unit = { if (collapsible) { m.expanded = !m.expanded; notifyItemChanged(pos) } }
        when (holder) {
            is VHUser -> {
                holder.body.text = shown.ifEmpty { "…" }
                holder.body.setOnClickListener(click)
                holder.itemView.setOnLongClickListener {
                    onUserLongClick(pos)
                    true
                }
            }
            is VHAi -> {
                holder.body.text = shown.ifEmpty { "…" }
                holder.body.setTextColor(ContextCompat.getColor(
                    holder.itemView.context,
                    if (m.content.startsWith("⚠️")) R.color.warning else R.color.text_primary
                ))
                holder.body.setOnClickListener(click)
                holder.copy.visibility = if (m.content.isBlank()) View.GONE else View.VISIBLE
                holder.copy.setOnClickListener { onAiCopy(pos) }
                holder.itemView.setOnLongClickListener {
                    onAiLongClick(pos)
                    true
                }
            }
            is VHSys -> {
                holder.body.text = shown
                holder.body.visibility = if (m.content.isBlank()) View.GONE else View.VISIBLE
                holder.body.setOnClickListener(click)
            }
        }
    }

    class VHUser(v: View) : RecyclerView.ViewHolder(v) {
        val body: TextView = v.findViewById(R.id.tvBody)
    }

    class VHAi(v: View) : RecyclerView.ViewHolder(v) {
        val body: TextView = v.findViewById(R.id.tvBody)
        val copy: ImageButton = v.findViewById(R.id.btnCopy)
    }

    class VHSys(v: View) : RecyclerView.ViewHolder(v) {
        val body: TextView = v.findViewById(R.id.tvBody)
    }
}

class SessionAdapter(
    private val items: MutableList<SessionRow>,
    private val onClick: (SessionRow) -> Unit,
    private val onDelete: (SessionRow) -> Unit,
    private val onLongClick: (SessionRow) -> Unit = {}
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val time: TextView = v.findViewById(R.id.tvTime)
        val del: ImageButton = v.findViewById(R.id.btnDel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = items[pos]
        h.title.text = s.title.ifBlank { "未命名对话" }
        h.sub.text = s.subtitle
        h.sub.visibility = if (s.subtitle.isBlank()) View.GONE else View.VISIBLE
        h.time.text = s.timeLabel
        h.time.visibility = if (s.timeLabel.isBlank()) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener { onClick(s) }
        h.del.setOnClickListener { onDelete(s) }
        h.itemView.setOnLongClickListener {
            onLongClick(s)
            true
        }
    }
}

package com.aibox.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class KeysActivity : AppCompatActivity() {

    private lateinit var adapter: KeyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(0))
        }
        box.addView(TextView(this).apply {
            text = "平台 Key 管理"; setTextColor(0xFFECEFF4.toInt()); textSize = 20f
        })
        box.addView(TextView(this).apply {
            text = "支持 DeepSeek / OpenAI 及其它 OpenAI 兼容接口。点击当前平台，对话页即使用该 Key。"
            setTextColor(0xFF9AA5B1.toInt()); textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        })

        adapter = KeyAdapter(mutableListOf()) { action, p ->
            when (action) {
                "active" -> {
                    val list = KeyManager.providers(this).map { it.copy(active = it.id == p.id) }
                    KeyManager.save(this, list); reload()
                    Toast.makeText(this, "已切换为 ${p.name}", Toast.LENGTH_SHORT).show()
                }
                "test" -> {
                    AiClient.test(p,
                        onOk = { r -> runOnUiThread { Toast.makeText(this, "✅ 连通：$r", Toast.LENGTH_LONG).show() } },
                        onError = { e -> runOnUiThread { Toast.makeText(this, "❌ $e", Toast.LENGTH_LONG).show() } })
                }
                "delete" -> {
                    val list = KeyManager.providers(this).filter { it.id != p.id }
                    KeyManager.save(this, list); reload()
                }
                "edit" -> showDialog(p)
            }
        }
        val recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@KeysActivity) }
        recycler.adapter = adapter
        box.addView(recycler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        box.addView(btn("＋ 添加平台") { showDialog(null) })

        setContentView(box)
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() {
        adapter.items = KeyManager.providers(this).toMutableList()
        adapter.notifyDataSetChanged()
    }

    private fun showDialog(existing: Provider?) {
        val fields = arrayOf("名称", "Base URL", "API Key", "模型")
        val values = arrayOf(
            existing?.name ?: "DeepSeek",
            existing?.baseUrl ?: "https://api.deepseek.com",
            existing?.apiKey ?: "",
            existing?.model ?: "deepseek-chat"
        )
        val edits = fields.mapIndexed { i, label ->
            EditText(this).apply {
                hint = label
                setText(values[i])
                setTextColor(0xFFECEFF4.toInt())
                setHintTextColor(0xFF9AA5B1.toInt())
                inputType = if (label == "API Key") android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        edits.forEach { col.addView(it) }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加平台" else "编辑平台")
            .setView(col)
            .setPositiveButton("保存") { _, _ ->
                val name = edits[0].text.toString().trim().ifBlank { "未命名" }
                val base = edits[1].text.toString().trim().ifBlank { "https://api.deepseek.com" }
                val key = edits[2].text.toString().trim()
                val model = edits[3].text.toString().trim().ifBlank { "deepseek-chat" }
                val list = KeyManager.providers(this)
                if (existing == null) {
                    list.add(Provider("p${System.currentTimeMillis()}", name, base, key, model, active = list.isEmpty()))
                } else {
                    val idx = list.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) list[idx] = existing.copy(name = name, baseUrl = base, apiKey = key, model = model)
                }
                KeyManager.save(this, list); reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(0xFF111418.toInt()); textSize = 15f
        setPadding(dp(8), dp(12), dp(8), dp(12))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF10A37F.toInt()) }
        setOnClickListener { onClick() }
    }

    class KeyAdapter(var items: MutableList<Provider>, val onClick: (String, Provider) -> Unit) : RecyclerView.Adapter<KeyAdapter.VH>() {

        class VH(val root: LinearLayout, val name: TextView, val meta: TextView, val actions: LinearLayout) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val name = TextView(parent.context).apply {
                setTextColor(0xFFECEFF4.toInt()); textSize = 16f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            }
            val meta = TextView(parent.context).apply {
                setTextColor(0xFF9AA5B1.toInt()); textSize = 12f
            }
            val actions = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL }
            val inner = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name); addView(meta); addView(actions)
                setPadding(dp(parent, 14), dp(parent, 12), dp(parent, 14), dp(parent, 12))
                background = GlassUi.panel(dp(parent, 16).toFloat())
            }
            val root = LinearLayout(parent.context).apply { addView(inner) }
            root.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            val lp = (root.getChildAt(0).layoutParams as ViewGroup.MarginLayoutParams)
            lp.setMargins(dp(parent, 4), dp(parent, 4), dp(parent, 4), dp(parent, 4))
            return VH(root, name, meta, actions)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            holder.name.text = (if (p.active) "● " else "") + p.name
            holder.meta.text = "${p.model}  ·  ${p.baseUrl}"
            holder.actions.removeAllViews()
            if (!p.active) holder.actions.addView(act(holder, "设为当前") { onClick("active", p) })
            holder.actions.addView(act(holder, "测试") { onClick("test", p) })
            holder.actions.addView(act(holder, "编辑") { onClick("edit", p) })
            holder.actions.addView(act(holder, "删除") { onClick("delete", p) })
        }

        private fun act(h: VH, s: String, onClick: () -> Unit) = TextView(h.root.context).apply {
            text = s; textSize = 12f
            setTextColor(0xFF10A37F.toInt())
            setPadding(dp(h.root, 8), dp(h.root, 6), dp(h.root, 8), dp(h.root, 6))
            setOnClickListener { onClick() }
        }

        override fun getItemCount() = items.size

        private fun dp(v: View, x: Int) = (x * v.resources.displayMetrics.density).toInt()
    }
}

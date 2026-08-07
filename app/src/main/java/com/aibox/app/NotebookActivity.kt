package com.aibox.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotebookActivity : AppCompatActivity() {

    private lateinit var db: NotebookDb
    private lateinit var adapter: NoteAdapter
    private lateinit var recycler: RecyclerView
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = NotebookDb(this)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(0))
        }
        box.addView(TextView(this).apply {
            text = "记事本"; setTextColor(0xFFECEFF4.toInt()); textSize = 20f
        })

        val search = EditText(this).apply {
            setTextColor(0xFFECEFF4.toInt())
            setHintTextColor(0xFF9AA5B1.toInt())
            hint = "搜索标题 / 内容 / 标签…"
            background = GlassUi.input()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        box.addView(search)

        adapter = NoteAdapter(mutableListOf()) { n ->
            startActivity(Intent(this, NoteEditActivity::class.java).putExtra("id", n.id))
        }
        recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@NotebookActivity) }
        recycler.adapter = adapter
        box.addView(recycler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val fab = TextView(this).apply {
            text = "＋"
            gravity = Gravity.CENTER
            textSize = 26f
            setTextColor(0xFF111418.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF10A37F.toInt())
            }
            setOnClickListener { startActivity(Intent(this@NotebookActivity, NoteEditActivity::class.java).putExtra("id", -1L)) }
        }
        val root = FrameLayout(this).apply {
            addView(box)
            addView(fab, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(20), dp(24))
            })
        }
        setContentView(root)

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { query = s.toString().trim(); reload() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() {
        adapter.items = db.search(query).toMutableList()
        adapter.notifyDataSetChanged()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    class NoteAdapter(var items: MutableList<Note>, val onClick: (Note) -> Unit) : RecyclerView.Adapter<NoteAdapter.VH>() {

        class VH(val root: LinearLayout, val title: TextView, val preview: TextView, val meta: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val title = TextView(parent.context).apply {
                setTextColor(0xFFECEFF4.toInt()); textSize = 16f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            }
            val preview = TextView(parent.context).apply {
                setTextColor(0xFF9AA5B1.toInt()); textSize = 13f; maxLines = 2
            }
            val meta = TextView(parent.context).apply {
                setTextColor(0xFF6B7684.toInt()); textSize = 11f
            }
            val inner = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(preview); addView(meta)
                setPadding(dp(parent, 14), dp(parent, 12), dp(parent, 14), dp(parent, 12))
                background = GlassUi.panel(dp(parent, 16).toFloat())
            }
            val root = LinearLayout(parent.context).apply { addView(inner) }
            root.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            val lp = (root.getChildAt(0).layoutParams as ViewGroup.MarginLayoutParams)
            lp.setMargins(dp(parent, 4), dp(parent, 4), dp(parent, 4), dp(parent, 4))
            return VH(root, title, preview, meta)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = items[position]
            holder.title.text = (if (n.pinned) "📌 " else "") + n.title.ifBlank { "(无标题)" }
            holder.preview.text = n.content.ifBlank { "（空）" }
            holder.meta.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(n.updatedAt)) +
                (if (n.tags.isNotBlank()) "  ·  #${n.tags.replace(" ", " #")}" else "")
            holder.root.setOnClickListener { onClick(n) }
        }

        override fun getItemCount() = items.size

        private fun dp(v: View, x: Int) = (x * v.resources.displayMetrics.density).toInt()
    }
}

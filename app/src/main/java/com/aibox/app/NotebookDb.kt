package com.aibox.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Note(
    val id: Long,
    val title: String,
    val content: String,
    val tags: String,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

class NotebookDb(ctx: Context) : SQLiteOpenHelper(ctx, "notes.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "tags TEXT DEFAULT ''," +
                "pinned INTEGER DEFAULT 0," +
                "created_at INTEGER," +
                "updated_at INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun all(): List<Note> {
        val out = mutableListOf<Note>()
        readableDatabase.query("notes", null, null, null, null, null, "pinned DESC, updated_at DESC").use { c ->
            while (c.moveToNext()) {
                out.add(rowToNote(c))
            }
        }
        return out
    }

    fun search(q: String): List<Note> {
        if (q.isBlank()) return all()
        val out = mutableListOf<Note>()
        val like = "%$q%"
        readableDatabase.query(
            "notes", null, "title LIKE ? OR content LIKE ? OR tags LIKE ?",
            arrayOf(like, like, like), null, null, "pinned DESC, updated_at DESC"
        ).use { c ->
            while (c.moveToNext()) out.add(rowToNote(c))
        }
        return out
    }

    fun insert(title: String, content: String, tags: String): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("title", title); put("content", content); put("tags", tags)
            put("pinned", 0); put("created_at", now); put("updated_at", now)
        }
        return writableDatabase.insert("notes", null, cv)
    }

    fun update(id: Long, title: String, content: String, tags: String, pinned: Boolean) {
        val cv = ContentValues().apply {
            put("title", title); put("content", content); put("tags", tags)
            put("pinned", if (pinned) 1 else 0); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("notes", cv, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
    }

    private fun rowToNote(c: android.database.Cursor) = Note(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        content = c.getString(c.getColumnIndexOrThrow("content")),
        tags = c.getString(c.getColumnIndexOrThrow("tags")),
        pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) == 1,
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )
}

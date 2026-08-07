package com.aibox.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SessionRow(val id: String, val title: String, val created: Long, val updated: Long, val pinned: Boolean = false, val subtitle: String = "", val timeLabel: String = "")
data class MsgRow(val role: String, val content: String, val ts: Long)

class ChatDb(ctx: Context) : SQLiteOpenHelper(ctx, "aibox.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, title TEXT, created INTEGER, updated INTEGER, pinned INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, content TEXT, ts INTEGER)")
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            try { db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (old < 3) {
            try { db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id)") } catch (_: Exception) {}
        }
    }

    fun insertSession(id: String, title: String): Long {
        val v = ContentValues().apply {
            put("id", id); put("title", title); put("created", System.currentTimeMillis()); put("updated", System.currentTimeMillis()); put("pinned", 0)
        }
        return writableDatabase.insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setPinned(id: String, pinned: Boolean) {
        val v = ContentValues().apply { put("pinned", if (pinned) 1 else 0) }
        writableDatabase.update("sessions", v, "id=?", arrayOf(id))
    }

    fun renameSession(oldId: String, newId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE messages SET session_id=? WHERE session_id=?", arrayOf(newId, oldId))
            db.execSQL("UPDATE sessions SET id=? WHERE id=?", arrayOf(newId, oldId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun renameTitle(id: String, title: String) {
        val v = ContentValues().apply { put("title", title) }
        writableDatabase.update("sessions", v, "id=?", arrayOf(id))
    }

    fun lastMessage(sessionId: String): MsgRow? {
        writableDatabase.rawQuery("SELECT role,content,ts FROM messages WHERE session_id=? ORDER BY id DESC LIMIT 1", arrayOf(sessionId)).use { c ->
            return if (c.moveToNext()) MsgRow(c.getString(0), c.getString(1), c.getLong(2)) else null
        }
    }

    fun touchSession(id: String, title: String? = null) {
        val v = ContentValues().apply { put("updated", System.currentTimeMillis()) }
        if (title != null) v.put("title", title)
        writableDatabase.update("sessions", v, "id=?", arrayOf(id))
    }

    fun sessions(): List<SessionRow> {
        val out = mutableListOf<SessionRow>()
        writableDatabase.rawQuery("SELECT id,title,created,updated,pinned FROM sessions ORDER BY pinned DESC, updated DESC", null).use { c ->
            while (c.moveToNext()) out.add(SessionRow(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4) == 1))
        }
        return out
    }

    fun deleteSession(id: String) {
        writableDatabase.delete("messages", "session_id=?", arrayOf(id))
        writableDatabase.delete("sessions", "id=?", arrayOf(id))
    }

    fun addMessage(sessionId: String, role: String, content: String) {
        if (content.isEmpty()) return
        val v = ContentValues().apply {
            put("session_id", sessionId); put("role", role); put("content", content); put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("messages", null, v)
        touchSession(sessionId)
    }

    fun messages(sessionId: String): List<MsgRow> {
        val out = mutableListOf<MsgRow>()
        writableDatabase.rawQuery("SELECT role,content,ts FROM messages WHERE session_id=? ORDER BY id", arrayOf(sessionId)).use { c ->
            while (c.moveToNext()) out.add(MsgRow(c.getString(0), c.getString(1), c.getLong(2)))
        }
        return out
    }
}

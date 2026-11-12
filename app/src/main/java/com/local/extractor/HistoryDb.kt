package com.local.extractor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

data class HistoryItem(
    val id: Long,
    val time: String,
    val type: String,
    val sourceInfo: String,
    val content: String
)

class HistoryDb(context: Context) : SQLiteOpenHelper(context, "extract_history.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time TEXT NOT NULL,
                type TEXT NOT NULL,
                source_info TEXT NOT NULL,
                content TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE history ADD COLUMN source_info TEXT NOT NULL DEFAULT '本地文件/未记录来源'")
            } catch (_: Exception) {
                db.execSQL("DROP TABLE IF EXISTS history")
                onCreate(db)
            }
        }
    }

    fun insert(type: String, sourceInfo: String, content: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val cv = ContentValues().apply {
            put("time", now)
            put("type", type)
            put("source_info", sourceInfo.ifBlank { "无原始链接/本地文件" })
            put("content", content)
        }
        writableDatabase.insert("history", null, cv)
        return now
    }

    fun getAll(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, time, type, source_info, content FROM history ORDER BY id DESC",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    HistoryItem(
                        id = it.getLong(0),
                        time = it.getString(1),
                        type = it.getString(2),
                        sourceInfo = it.getString(3),
                        content = it.getString(4)
                    )
                )
            }
        }
        return list
    }

    fun deleteById(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
    }

    fun clearAll() {
        writableDatabase.delete("history", null, null)
    }
}

package com.local.extractor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

data class HistoryItem(val id: Long, val type: String, val content: String, val time: String)

class HistoryDb(context: Context) : SQLiteOpenHelper(context, "extractor_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, content TEXT, time TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(type: String, content: String) {
        if (content.isBlank()) return
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val cv = ContentValues().apply {
            put("type", type)
            put("content", content)
            put("time", time)
        }
        writableDatabase.insert("history", null, cv)
    }

    fun getAll(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val cursor = readableDatabase.rawQuery("SELECT id, type, content, time FROM history ORDER BY id DESC", null)
        while (cursor.moveToNext()) {
            list.add(HistoryItem(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
        }
        cursor.close()
        return list
    }
}

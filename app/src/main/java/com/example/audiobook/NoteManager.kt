package com.example.audiobook

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Note(
    val id: Long = System.currentTimeMillis(),
    val bookTitle: String,
    val bookUri: String = "",
    val chunkIndex: Int,
    val selectedText: String,
    val noteText: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class Highlight(
    val id: Long = System.currentTimeMillis(),
    val bookTitle: String,
    val bookUri: String = "",
    val chunkIndex: Int,
    val text: String,
    val startPos: Int = -1,
    val endPos: Int = -1,
    val color: String = "#FFEB3B",
    val timestamp: Long = System.currentTimeMillis()
)

object NoteManager {
    private val notes = mutableListOf<Note>()
    private val highlights = mutableListOf<Highlight>()
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("book_notes", Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        notes.clear()
        highlights.clear()
        val notesJson = prefs.getString("notes", "[]") ?: "[]"
        val highlightsJson = prefs.getString("highlights", "[]") ?: "[]"
        val notesArr = JSONArray(notesJson)
        for (i in 0 until notesArr.length()) {
            val o = notesArr.getJSONObject(i)
            notes.add(Note(
                id = o.getLong("id"),
                bookTitle = o.getString("bookTitle"),
                bookUri = o.optString("bookUri", ""),
                chunkIndex = o.getInt("chunkIndex"),
                selectedText = o.getString("selectedText"),
                noteText = o.getString("noteText"),
                timestamp = o.getLong("timestamp")
            ))
        }
        val hlArr = JSONArray(highlightsJson)
        for (i in 0 until hlArr.length()) {
            val o = hlArr.getJSONObject(i)
            highlights.add(Highlight(
                id = o.getLong("id"),
                bookTitle = o.getString("bookTitle"),
                bookUri = o.optString("bookUri", ""),
                chunkIndex = o.getInt("chunkIndex"),
                text = o.getString("text"),
                startPos = o.optInt("startPos", -1),
                endPos = o.optInt("endPos", -1),
                color = o.getString("color"),
                timestamp = o.getLong("timestamp")
            ))
        }
    }

    private fun saveToPrefs() {
        val notesArr = JSONArray()
        for (n in notes) {
            val o = JSONObject()
            o.put("id", n.id)
            o.put("bookTitle", n.bookTitle)
            o.put("bookUri", n.bookUri)
            o.put("chunkIndex", n.chunkIndex)
            o.put("selectedText", n.selectedText)
            o.put("noteText", n.noteText)
            o.put("timestamp", n.timestamp)
            notesArr.put(o)
        }
        val hlArr = JSONArray()
        for (h in highlights) {
            val o = JSONObject()
            o.put("id", h.id)
            o.put("bookTitle", h.bookTitle)
            o.put("bookUri", h.bookUri)
            o.put("chunkIndex", h.chunkIndex)
            o.put("text", h.text)
            o.put("startPos", h.startPos)
            o.put("endPos", h.endPos)
            o.put("color", h.color)
            o.put("timestamp", h.timestamp)
            hlArr.put(o)
        }
        prefs.edit()
            .putString("notes", notesArr.toString())
            .putString("highlights", hlArr.toString())
            .apply()
    }

    fun addNote(note: Note) { notes.add(note); saveToPrefs() }
    fun addHighlight(highlight: Highlight) { highlights.add(highlight); saveToPrefs() }

    fun getNotesForBook(bookTitle: String): List<Note> =
        notes.filter { it.bookTitle == bookTitle }.sortedBy { it.chunkIndex }

    fun getHighlightsForBook(bookTitle: String): List<Highlight> =
        highlights.filter { it.bookTitle == bookTitle }.sortedBy { it.chunkIndex }

    fun getAllNotes(): List<Note> = notes.toList()
    fun getAllHighlights(): List<Highlight> = highlights.toList()

    fun exportBookToMarkdown(bookTitle: String): String {
        val sb = StringBuilder()
        sb.appendLine("# $bookTitle - 笔记与划线")
        sb.appendLine()

        val bookNotes = getNotesForBook(bookTitle)
        val bookHighlights = getHighlightsForBook(bookTitle)

        if (bookNotes.isNotEmpty()) {
            sb.appendLine("## 📝 笔记")
            sb.appendLine()
            for (note in bookNotes) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(note.timestamp))
                sb.appendLine("### 段落 ${note.chunkIndex + 1} (${date})")
                sb.appendLine()
                sb.appendLine("> ${note.selectedText}")
                sb.appendLine()
                sb.appendLine(note.noteText)
                sb.appendLine()
                sb.appendLine("---")
                sb.appendLine()
            }
        }

        if (bookHighlights.isNotEmpty()) {
            sb.appendLine("## 🖍 划线")
            sb.appendLine()
            for (hl in bookHighlights) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(hl.timestamp))
                sb.appendLine("- **段落 ${hl.chunkIndex + 1}** (${date}): ${hl.text}")
            }
            sb.appendLine()
        }

        if (bookNotes.isEmpty() && bookHighlights.isEmpty()) {
            sb.appendLine("暂无笔记或划线")
        }

        return sb.toString()
    }

    fun exportAllToMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# 我的读书笔记")
        sb.appendLine()
        val bookTitles = (notes.map { it.bookTitle } + highlights.map { it.bookTitle }).distinct()
        for (title in bookTitles) {
            sb.appendLine(exportBookToMarkdown(title))
            sb.appendLine()
        }
        return sb.toString()
    }
}

package com.example.audiobook

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

data class NotesItem(val isNote: Boolean, val note: Note? = null, val highlight: Highlight? = null)

class NotesActivity : AppCompatActivity() {

    private val allItems = mutableListOf<NotesItem>()
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)

        NoteManager.init(this)

        listView = findViewById(R.id.lvNotes)
        tvEmpty = findViewById(R.id.tvEmptyNotes)
        val btnExportBook = findViewById<Button>(R.id.btnExportBook)
        val btnExportAll = findViewById<Button>(R.id.btnExportAll)
        val btnBack = findViewById<Button>(R.id.btnBackNotes)

        btnBack.setOnClickListener { finish() }

        // Build combined list sorted by chunkIndex
        allItems.clear()
        allItems.addAll(NoteManager.getAllNotes().map { NotesItem(isNote = true, note = it) })
        allItems.addAll(NoteManager.getAllHighlights().map { NotesItem(isNote = false, highlight = it) })
        allItems.sortBy { it.note?.chunkIndex ?: it.highlight?.chunkIndex ?: 0 }

        btnExportBook.setOnClickListener {
            val books = allItems.map { it.note?.bookTitle ?: it.highlight?.bookTitle ?: "" }.distinct()
            if (books.isEmpty() || books.first().isEmpty()) { Toast.makeText(this, "暂无数据", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val items = books.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("导出本书笔记")
                .setItems(items) { dialog, which ->
                    val markdown = NoteManager.exportBookToMarkdown(items[which])
                    val file = File(cacheDir, "${items[which]}_notes.md")
                    file.writeText(markdown)
                    Toast.makeText(this, "已导出: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
                .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        btnExportAll.setOnClickListener {
            val markdown = NoteManager.exportAllToMarkdown()
            val file = File(cacheDir, "all_notes_${System.currentTimeMillis()}.md")
            file.writeText(markdown)
            Toast.makeText(this, "已导出: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }

        if (allItems.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            listView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            listView.visibility = View.VISIBLE
            updateList()
        }
    }

    private fun updateList() {
        val items = mutableListOf<String>()
        for (item in allItems) {
            if (item.isNote && item.note != null) {
                val note = item.note
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(note.timestamp))
                items.add("📝 [${note.bookTitle}] 第${note.chunkIndex + 1}段 (${date})\n   ${note.selectedText.take(50)} → ${note.noteText}")
            } else if (!item.isNote && item.highlight != null) {
                val hl = item.highlight
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(hl.timestamp))
                items.add("🖍 [${hl.bookTitle}] 第${hl.chunkIndex + 1}段 (${date})\n   ${hl.text.take(50)}")
            }
        }

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = allItems[position]
            val bookUri = item.note?.bookUri ?: item.highlight?.bookUri ?: ""
            val chunkIndex = item.note?.chunkIndex ?: item.highlight?.chunkIndex ?: 0
            val bookTitle = item.note?.bookTitle ?: item.highlight?.bookTitle ?: ""
            if (bookUri.isNotBlank()) {
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    data = Uri.parse(bookUri)
                    putExtra("scrollToChunk", chunkIndex)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "无法找到书籍: $bookTitle", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

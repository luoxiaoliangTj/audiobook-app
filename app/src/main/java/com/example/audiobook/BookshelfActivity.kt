package com.example.audiobook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BookshelfActivity : AppCompatActivity() {

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        for (uri in uris) addBook(uri)
    }

    private lateinit var prefs: SharedPreferences
    private val books = mutableListOf<BookInfo>()
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var btnAdd: Button

    data class BookInfo(val uri: String, val title: String, val chunks: Int = 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookshelf)

        prefs = getSharedPreferences("bookshelf", Context.MODE_PRIVATE)
        listView = findViewById(R.id.lvBooks)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnAdd = findViewById(R.id.btnAddBook)

        checkCrashLog()

        loadBooks()
        updateUI()

        btnAdd.setOnClickListener {
            try {
                filePicker.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开文件选择器: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val book = books[position]
            val uri = Uri.parse(book.uri)
            try {
                // 尝试获取持久化权限
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: SecurityException) {
                // 权限不可持久化，尝试直接打开（可能失败）
            }

            try {
                // 验证权限是否仍然有效
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) {
                        showReSelectDialog(position, book.title)
                        return@setOnItemClickListener
                    }
                } ?: run {
                    showReSelectDialog(position, book.title)
                    return@setOnItemClickListener
                }

                openBook(uri)
            } catch (e: SecurityException) {
                showReSelectDialog(position, book.title)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开书籍: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            showRemoveDialog(position)
            true
        }
    }

    private fun checkCrashLog() {
        if (AudiobookApp.hasCrashLog(this)) {
            AlertDialog.Builder(this)
                .setTitle("发现崩溃日志")
                .setMessage("检测到上次运行有崩溃，是否发送日志给开发者？")
                .setPositiveButton("发送") { _, _ -> sendCrashLog() }
                .setNegativeButton("取消") { _, _ -> AudiobookApp.clearCrashLog(this) }
                .show()
        }
    }

    private fun sendCrashLog() {
        try {
            val crashLog = AudiobookApp.getCrashLog(this)
            val file = File(cacheDir, "crash_log.txt")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Audiobook App Crash Log")
                putExtra(Intent.EXTRA_TEXT, crashLog)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "发送 Crash Log"))
        } catch (_: Exception) {
            Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addBook(uri: Uri) {
        val title = getFileName(uri)
        if (books.any { it.uri == uri.toString() }) {
            Toast.makeText(this, "这本书已在书架中: $title", Toast.LENGTH_SHORT).show()
            return
        }

        // 持久化 URI 权限
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // 某些 URI 不支持持久化权限，仍然保存但可能重启后失效
        }

        books.add(BookInfo(uri.toString(), title))
        saveBooks()
        updateUI()
        Toast.makeText(this, "已添加: $title", Toast.LENGTH_SHORT).show()
    }

    private fun openBook(uri: Uri) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(intent)
    }

    private fun showReSelectDialog(position: Int, oldTitle: String) {
        AlertDialog.Builder(this)
            .setTitle("需要重新选择")
            .setMessage("《${oldTitle}》的访问权限已失效，是否重新选择该文件？")
            .setPositiveButton("重新选择") { dialog, _ ->
                books.removeAt(position)
                saveBooks()
                updateUI()
                filePicker.launch(arrayOf("*/*"))
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showRemoveDialog(position: Int) {
        val book = books[position]
        AlertDialog.Builder(this)
            .setTitle("移除书籍")
            .setMessage("确定要从书架移除《${book.title}》吗？")
            .setPositiveButton("移除") { dialog, _ ->
                books.removeAt(position)
                saveBooks()
                updateUI()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun loadBooks() {
        books.clear()
        val json = prefs.getString("books", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                books.add(BookInfo(
                    obj.getString("uri"),
                    obj.getString("title"),
                    obj.optInt("chunks", 0)
                ))
            }
        } catch (_: Exception) {}
    }

    private fun saveBooks() {
        val arr = JSONArray()
        for (b in books) {
            arr.put(JSONObject().apply {
                put("uri", b.uri)
                put("title", b.title)
                put("chunks", b.chunks)
            })
        }
        prefs.edit().putString("books", arr.toString()).apply()
    }

    private fun updateUI() {
        tvEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE

        val adapter = object : ArrayAdapter<BookInfo>(this, R.layout.item_book, R.id.tvBookTitle, books) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val titleView = view.findViewById<TextView>(R.id.tvBookTitle)
                val chunksView = view.findViewById<TextView>(R.id.tvBookChunks)
                val book = books[position]
                titleView.text = book.title
                chunksView.text = "${book.chunks} 段"
                return view
            }
        }
        listView.adapter = adapter
    }

    private fun getFileName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "未知书籍"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return name
    }
}

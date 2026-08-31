package com.example.audiobook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var edgeTts: EdgeTtsClient
    private val debugLog = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var scrollContent: ScrollView
    private lateinit var textContent: TextView
    private var currentSelectionText: String? = null
    private var currentBookTitle: String = "未知书籍"
    private var currentBookUri: Uri? = null

    private var fullText = ""
    private var extractedImages = mutableMapOf<String, File>()

    private var chunkCharStarts = mutableListOf<Int>()
    private var chunkCharEnds = mutableListOf<Int>()

    private var capturedSelectionStart = -1
    private var capturedSelectionEnd = -1

    private var currentTheme = "dark"
    private var currentFontSize = 18
    private var currentFontColor = "#E0E0E0"
    private var currentBgColor = "#1A1A1A"

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        edgeTts = EdgeTtsClient(this) { msg -> dbg(msg) }
        NoteManager.init(this)
        PdfParser.init(this)

        scrollContent = findViewById(R.id.scrollContent)
        textContent = findViewById(R.id.textContent)

        setupTextSelection()
        setupButtonListeners()
        setupToggleListeners()

        val incomingUri = intent?.data
        val scrollToChunk = intent?.getIntExtra("scrollToChunk", -1) ?: -1
        if (incomingUri != null) {
            dbg("接收 URI: $incomingUri, scrollToChunk=$scrollToChunk")
            loadAndSpeakFile(incomingUri, scrollToChunk)
        }
    }

    private fun dbg(msg: String) {
        debugLog.append(msg).append("\n")
        Log.d("AudioBook", msg)
    }

    private fun setupTextSelection() {
        textContent.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                capturedSelectionStart = textContent.selectionStart
                capturedSelectionEnd = textContent.selectionEnd
                dbg("创建菜单: 捕获选区 start=$capturedSelectionStart end=$capturedSelectionEnd, text='${textContent.text.substring(capturedSelectionStart, capturedSelectionEnd).take(50)}'")
                menu.clear()
                menu.add(0, 1, 0, "▶ 朗读")
                menu.add(0, 2, 1, "📝 笔记")
                menu.add(0, 3, 2, "🖍 划线")
                menu.add(0, 4, 3, "📋 复制")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                capturedSelectionStart = textContent.selectionStart
                capturedSelectionEnd = textContent.selectionEnd
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val start = capturedSelectionStart
                val end = capturedSelectionEnd
                if (start < 0 || end < 0 || start >= end) return false
                val selected = textContent.text.substring(start, end)
                currentSelectionText = selected
                when (item.itemId) {
                    1 -> {
                        if (selected.isNotBlank()) {
                            SpeechState.isPlaying = true
                            updatePlayPauseButtons()
                            val startIdx = findChunkIndexForSelection(start)
                            SpeechState.currentIndex = startIdx
                            val firstChunk = SpeechState.chunks[startIdx]
                            val chunkStart = chunkCharStarts[startIdx]
                            val offsetInChunk = start - chunkStart
                            val trimmedFirst = if (offsetInChunk > 0 && offsetInChunk < firstChunk.length) {
                                firstChunk.substring(offsetInChunk)
                            } else firstChunk
                            val chunksToPlay = mutableListOf(trimmedFirst)
                            chunksToPlay.addAll(SpeechState.chunks.drop(startIdx + 1))
                            dbg("从选中位置朗读: start=$start, startIdx=$startIdx, offsetInChunk=$offsetInChunk, 后续${chunksToPlay.size}段")
                            edgeTts.startPlayback(chunks = chunksToPlay, startOffset = startIdx,
                                onChunkPlayed = { i -> runOnUiThread { SpeechState.currentIndex = i; scrollToChunk(i); updateProgressUI() }},
                                onComplete = { SpeechState.isPlaying = false; updatePlayPauseButtons() })
                        }
                    }
                    2 -> showNoteDialog(selected)
                    3 -> {
                        highlightSelection(start, end)
                        NoteManager.addHighlight(Highlight(bookTitle = currentBookTitle, bookUri = currentBookUri.toString(), chunkIndex = findChunkIndexForSelection(start), text = selected, startPos = start, endPos = end))
                        Toast.makeText(this@ReaderActivity, "已划线", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("选中内容", selected))
                        Toast.makeText(this@ReaderActivity, "已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }

    private fun highlightSelection(start: Int, end: Int) {
        // 保存当前可见区域中心的文本偏移
        val layout = textContent.layout ?: return
        val scrollY = scrollContent.scrollY
        val centerY = scrollY + scrollContent.height / 2
        val centerLine = layout.getLineForVertical(centerY)
        val savedCenterOffset = layout.getOffsetForHorizontal(centerLine, (layout.width / 2).toFloat()).coerceIn(0, textContent.text.length)

        // 直接对 Editable 添加 span
        val editable = textContent.text as? android.text.Editable
        if (editable != null) {
            editable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            val spannable = SpannableString(textContent.text)
            spannable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            textContent.text = spannable
        }

        // 等布局完成后，滚动回原来的文本位置
        scrollContent.post {
            val newLayout = textContent.layout ?: return@post
            if (savedCenterOffset < textContent.text.length) {
                val line = newLayout.getLineForOffset(savedCenterOffset)
                val y = newLayout.getLineTop(line)
                val newScrollY = (y - scrollContent.height / 2).coerceAtLeast(0)
                scrollContent.scrollTo(0, newScrollY)
            }
        }
    }

    private fun restoreHighlights() {
        val bookHighlights = NoteManager.getHighlightsForBook(currentBookTitle)
        if (bookHighlights.isEmpty()) return

        val editable = textContent.text as? android.text.Editable
        if (editable != null) {
            for (hl in bookHighlights) {
                val s = if (hl.startPos >= 0) hl.startPos else chunkCharStarts.getOrNull(hl.chunkIndex) ?: continue
                val e = if (hl.endPos >= 0) hl.endPos else chunkCharEnds.getOrNull(hl.chunkIndex) ?: continue
                if (s < editable.length && e <= editable.length) {
                    editable.setSpan(UnderlineSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } else {
            val spannable = SpannableString(textContent.text)
            for (hl in bookHighlights) {
                val s = if (hl.startPos >= 0) hl.startPos else chunkCharStarts.getOrNull(hl.chunkIndex) ?: continue
                val e = if (hl.endPos >= 0) hl.endPos else chunkCharEnds.getOrNull(hl.chunkIndex) ?: continue
                if (s < textContent.text.length && e <= textContent.text.length) {
                    spannable.setSpan(UnderlineSpan(), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            textContent.text = spannable
        }
        dbg("恢复 ${bookHighlights.size} 条划线")
    }

    private fun findChunkIndexForSelection(selectionStart: Int): Int {
        for (i in chunkCharStarts.indices) {
            if (selectionStart in chunkCharStarts[i]..chunkCharEnds[i]) return i
        }
        var nearest = 0
        var minDist = Int.MAX_VALUE
        for (i in chunkCharStarts.indices) {
            val dist = kotlin.math.abs(chunkCharStarts[i] - selectionStart)
            if (dist < minDist) { minDist = dist; nearest = i }
        }
        return nearest
    }

    private fun setupButtonListeners() {
        findViewById<Button>(R.id.btnBookshelf).setOnClickListener {
            startActivity(Intent(this, BookshelfActivity::class.java))
        }
        findViewById<Button>(R.id.btnSelectFile).setOnClickListener {
            val p = getRequiredPermission()
            if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) openFilePicker()
            else requestPermissionLauncher.launch(p)
        }
        findViewById<Button>(R.id.btnVoice).setOnClickListener { showVoiceMenu(it) }
        findViewById<Button>(R.id.btnTheme).setOnClickListener { showThemeMenu(it) }
        findViewById<Button>(R.id.btnMore).setOnClickListener { showMoreMenu(it) }

        findViewById<Button>(R.id.btnPlay).setOnClickListener {
            if (SpeechState.chunks.isEmpty()) { Toast.makeText(this, "无可播放内容", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!edgeTts.isCurrentlyPlaying()) {
                SpeechState.isPlaying = true
                if (SpeechState.currentIndex !in 0 until SpeechState.chunks.size) SpeechState.currentIndex = 0
                updatePlayPauseButtons()
                dbg("播放: idx=${SpeechState.currentIndex}")
                val startIdx = SpeechState.currentIndex
                val chunksToPlay = SpeechState.chunks.drop(startIdx)
                edgeTts.startPlayback(chunks = chunksToPlay, startOffset = startIdx,
                    onChunkPlayed = { i -> runOnUiThread { SpeechState.currentIndex = i; scrollToChunk(i); updateProgressUI() }},
                    onComplete = { SpeechState.isPlaying = false; updatePlayPauseButtons(); dbg("全部播放完成") })
            }
        }

        findViewById<Button>(R.id.btnPause).setOnClickListener {
            edgeTts.stopPlayback()
            SpeechState.isPlaying = false
            updatePlayPauseButtons()
        }

        findViewById<Button>(R.id.btnReplay).setOnClickListener {
            if (SpeechState.chunks.isEmpty()) { Toast.makeText(this, "无可播放内容", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            SpeechState.currentIndex = 0; SpeechState.isPlaying = true; updatePlayPauseButtons()
            edgeTts.startPlayback(chunks = SpeechState.chunks, startOffset = 0,
                onChunkPlayed = { i -> runOnUiThread { SpeechState.currentIndex = i; scrollToChunk(i); updateProgressUI() }},
                onComplete = { SpeechState.isPlaying = false; updatePlayPauseButtons(); dbg("全部播放完成") })
        }

        findViewById<Button>(R.id.btnPrevChapter).setOnClickListener { prevPage() }
        findViewById<Button>(R.id.btnNextChapter).setOnClickListener { nextPage() }
        findViewById<Button>(R.id.btnJumpTo).setOnClickListener { showJumpToDialog() }
    }

    private fun prevPage() {
        val layout = textContent.layout ?: return
        val currentScroll = scrollContent.scrollY
        val pageHeight = scrollContent.height
        val newScroll = (currentScroll - pageHeight).coerceAtLeast(0)
        scrollContent.scrollTo(0, newScroll)
    }

    private fun nextPage() {
        val layout = textContent.layout ?: return
        val currentScroll = scrollContent.scrollY
        val pageHeight = scrollContent.height
        val maxScroll = layout.height - pageHeight
        val newScroll = (currentScroll + pageHeight).coerceAtMost(maxScroll)
        scrollContent.scrollTo(0, newScroll)
    }

    private fun setupToggleListeners() {
        val topBar = findViewById<View>(R.id.topBar)
        val bottomBar = findViewById<View>(R.id.bottomBar)

        val toggleBars = {
            val showBars = topBar.visibility != View.VISIBLE
            topBar.visibility = if (showBars) View.VISIBLE else View.GONE
            bottomBar.visibility = if (showBars) View.VISIBLE else View.GONE
            dbg("点击屏幕: 工具栏 ${if (showBars) "显示" else "隐藏"}")
        }

        // Only toggle on scrollContent click, NOT on textContent (which needs taps for selection)
        scrollContent.setOnClickListener { toggleBars() }
    }

    private fun showThemeMenu(anchor: View) {
        dbg("打开主题菜单, 当前主题=$currentTheme")
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, "☀ 浅色")
        popup.menu.add(0, 1, 1, "🌙 深色")
        popup.menu.add(0, 2, 2, "◐ 灰色")
        popup.menu.add(0, 3, 3, "字体大小")
        popup.menu.add(0, 4, 4, "字体颜色")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> { currentTheme = "light"; dbg("切换主题: light"); applyTheme() }
                1 -> { currentTheme = "dark"; dbg("切换主题: dark"); applyTheme() }
                2 -> { currentTheme = "gray"; dbg("切换主题: gray"); applyTheme() }
                3 -> showFontSizeDialog()
                4 -> showFontColorDialog()
            }
            true
        }
        popup.show()
    }

    private fun showFontSizeDialog() {
        dbg("打开字体大小对话框, 当前=${currentFontSize}px")
        val sizes = arrayOf("小 (14px)", "标准 (16px)", "大 (18px)", "超大 (22px)", "特大 (28px)")
        val values = arrayOf(14, 16, 18, 22, 28)
        AlertDialog.Builder(this)
            .setTitle("字体大小")
            .setItems(sizes) { dialog, which ->
                currentFontSize = values[which]
                dbg("切换字体大小: ${currentFontSize}px")
                applyTheme()
                dialog.dismiss()
            }
            .show()
    }

    private fun showFontColorDialog() {
        dbg("打开字体颜色对话框, 当前=$currentFontColor")
        val colors = arrayOf("黑色", "白色")
        val values = arrayOf("#222222", "#FFFFFF")
        AlertDialog.Builder(this)
            .setTitle("字体颜色")
            .setItems(colors) { dialog, which ->
                currentFontColor = values[which]
                dbg("切换字体颜色: $currentFontColor")
                applyTheme()
                dialog.dismiss()
            }
            .show()
    }

    private fun applyTheme() {
        when (currentTheme) {
            "light" -> currentBgColor = "#FAF8F3"
            "dark" -> currentBgColor = "#1A1A1A"
            "gray" -> currentBgColor = "#2D2D2D"
        }
        textContent.setBackgroundColor(Color.parseColor(currentBgColor))
        textContent.setTextColor(Color.parseColor(currentFontColor))
        textContent.textSize = currentFontSize.toFloat()
        scrollContent.setBackgroundColor(Color.parseColor(currentBgColor))
        dbg("应用主题: $currentTheme, bg=$currentBgColor, font=$currentFontColor, size=$currentFontSize")
    }

    private fun showNoteDialog(selectedText: String) {
        val editText = EditText(this).apply { hint = "输入你的笔记..." }
        AlertDialog.Builder(this)
            .setTitle("📝 记笔记")
            .setMessage("选中内容：\n$selectedText")
            .setView(editText)
            .setPositiveButton("保存") { dialog, _ ->
                val noteText = editText.text.toString()
                if (noteText.isNotBlank()) {
                    NoteManager.addNote(Note(bookTitle = currentBookTitle, bookUri = currentBookUri.toString(), chunkIndex = findChunkIndexForSelection(textContent.selectionStart), selectedText = selectedText, noteText = noteText))
                    dbg("笔记已保存: ${noteText.take(30)}")
                    Toast.makeText(this, "笔记已保存", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun exportBookNotes() {
        val markdown = NoteManager.exportBookToMarkdown(currentBookTitle)
        val file = File(cacheDir, "${currentBookTitle}_notes.md")
        file.writeText(markdown)
        Toast.makeText(this, "本书笔记已导出: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        dbg("导出本书笔记: ${file.absolutePath}")
    }

    private fun showBookmarkDialog() {
        val bookmarks = BookmarkManager.getBookmarks(currentBookTitle)
        val items = if (bookmarks.isEmpty()) arrayOf("暂无书签")
        else bookmarks.map { bm -> "第${bm.page + 1}页 - ${bm.note}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📑 书签管理")
            .setItems(items) { dialog, which ->
                if (bookmarks.isNotEmpty()) {
                    val pos = bookmarks[which].page
                    if (pos < chunkCharStarts.size) {
                        scrollToChunk(pos)
                    }
                }
                dialog.dismiss()
            }
            .setNeutralButton("添加书签") { dialog, _ ->
                val editText = EditText(this).apply { hint = "书签备注..." }
                AlertDialog.Builder(this)
                    .setTitle("添加书签")
                    .setMessage("在当前位置添加书签")
                    .setView(editText)
                    .setPositiveButton("添加") { d, _ ->
                        val note = editText.text.toString().ifEmpty { "位置 ${SpeechState.currentIndex}" }
                        BookmarkManager.addBookmark(Bookmark(bookTitle = currentBookTitle, page = SpeechState.currentIndex, note = note))
                        Toast.makeText(this, "书签已添加", Toast.LENGTH_SHORT).show()
                        d.dismiss()
                    }
                    .setNegativeButton("取消") { d, _ -> d.dismiss() }
                    .show()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, "📑 书签")
        popup.menu.add(0, 1, 1, "📝 笔记")
        popup.menu.add(0, 2, 2, "📋 复制日志")
        popup.menu.add(0, 3, 3, "📤 导出笔记")
        popup.menu.add(0, 4, 4, "🐛 调试")
        popup.menu.add(0, 5, 5, "ℹ️ 关于")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> showBookmarkDialog()
                1 -> startActivity(Intent(this, NotesActivity::class.java).putExtra("bookTitle", currentBookTitle))
                2 -> copyLog()
                3 -> exportBookNotes()
                4 -> toggleDebug()
                5 -> showAbout()
            }
            true
        }
        popup.show()
    }

    private fun showAbout() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "unknown" }
        val exportPath = "${cacheDir.absolutePath}/"
        AlertDialog.Builder(this)
            .setTitle("📖 关于")
            .setMessage("有声书阅读器\n\n作者：LxlAI.com\n版本：$versionName\n\n导出路径：\n$exportPath\n\n支持 EPUB 全文朗读、笔记划线、书签管理")
            .setPositiveButton("确定") { d, _ -> d.dismiss() }
            .show()
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("AudioBookDebug", debugLog.toString()))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun toggleDebug() {
        val debugPanel = findViewById<View>(R.id.debugPanel)
        if (debugPanel.visibility == View.VISIBLE) debugPanel.visibility = View.GONE
        else debugPanel.visibility = View.VISIBLE
    }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) openFilePicker()
        else { dbg("权限被拒绝"); Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show() }
    }

    private val filePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) loadAndSpeakFile(uri) }

    private fun openFilePicker() { filePickerLauncher.launch("*/*") }

    private fun getFileName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) { val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0) name = c.getString(idx) }
        }
        return name
    }

    private fun readAsPlainText(uri: Uri): String =
        contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    private var isPdfBook = false

    private fun loadAndSpeakFile(uri: Uri, scrollToChunk: Int = -1) {
        val fileName = getFileName(uri)
        currentBookTitle = fileName
        currentBookUri = uri
        isPdfBook = false

        try {
            val fn = fileName.lowercase()
            dbg("打开: $fn")
            var plainText = ""
            var htmlContent: String? = null

            if (fn.endsWith(".epub")) {
                val book = EpubParser.parseEpub(this, contentResolver, uri)
                dbg("EPUB: ${book != null}, 章节: ${book?.chapters?.size}")

                if (book != null && book.chapters.isNotEmpty()) {
                    val tb = StringBuilder()
                    val htmlSb = StringBuilder()
                    for (c in book.chapters) {
                        val text = c.content ?: ""
                        val html = c.htmlContent ?: ""
                        if (text.isNotBlank()) tb.append(text).append("\n\n")
                        if (html.isNotBlank()) htmlSb.append(html).append("\n\n")
                    }
                    plainText = tb.toString().trim()
                    htmlContent = htmlSb.toString().trim()
                    dbg("提取纯文本: ${plainText.length} 字符, HTML: ${htmlContent?.length ?: 0} 字符")
                }

                // Extract images
                extractedImages.clear()
                extractedImages.putAll(EpubImageExtractor.extractImages(this, contentResolver, uri))
                dbg("提取 ${extractedImages.size} 张图片")
            } else if (fn.endsWith(".pdf")) {
                isPdfBook = true
                dbg("PDF 文件，使用 PdfParser 解析")
                try {
                    val pdfBook = PdfParser.parsePdf(this, contentResolver, uri)
                    dbg("PDF: ${pdfBook != null}, 页数: ${pdfBook?.pageCount}, 文本: ${pdfBook?.text?.length}")

                    if (pdfBook != null && pdfBook.text.isNotEmpty()) {
                        plainText = pdfBook.text
                        dbg("PDF 提取纯文本: ${plainText.length} 字符")
                    } else {
                        val crashLog = PdfParser.getCrashLog()
                        dbg("PDF 解析失败，日志: $crashLog")
                        Toast.makeText(this, "PDF 解析失败: ${if (crashLog.contains("init")) "PDFBox初始化失败" else "无法提取文本"}", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    dbg("PDF 异常: ${e.message}")
                    Toast.makeText(this, "PDF 解析异常: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                plainText = readAsPlainText(uri)
            }

            if (plainText.isNotEmpty()) {
                fullText = plainText
                SpeechState.chunks = splitIntoSpeechChunks(plainText)
                SpeechState.currentIndex = 0
                SpeechState.isPlaying = false
                updateProgressUI()
                dbg("分块: ${SpeechState.chunks.size}")

                if (SpeechState.chunks.isNotEmpty()) {
                    // Display: use Html.fromHtml if we have images, otherwise plain text
                    if (htmlContent != null && extractedImages.isNotEmpty()) {
                        var processedHtml = htmlContent!!
                        // Build filename -> File lookup for fallback matching
                        val fileByFilename = extractedImages.map { it.key.substringAfterLast("/") to it.value }.toMap()
                        // Use regex to find and replace img src attributes
                        val imgSrcRegex = Regex("""(<img[^>]+src\s*=\s*["'])([^"']+)(["'])""", RegexOption.IGNORE_CASE)
                        processedHtml = imgSrcRegex.replace(processedHtml) { matchResult ->
                            val prefix = matchResult.groupValues[1]
                            val originalSrc = matchResult.groupValues[2]
                            val suffix = matchResult.groupValues[3]
                            val fileName = originalSrc.substringAfterLast("/")
                            // Try exact match, then endsWith, then filename
                            val matchedFile = extractedImages[originalSrc]
                                ?: extractedImages.entries.find { it.key.endsWith("/$fileName") || it.key == fileName }?.value
                                ?: fileByFilename[fileName]
                            if (matchedFile != null) {
                                dbg("图片替换: $originalSrc -> ${matchedFile.absolutePath}")
                                "$prefix${matchedFile.absolutePath}$suffix"
                            } else {
                                dbg("图片未找到: $originalSrc")
                                matchResult.value
                            }
                        }
                        val imageGetter = Html.ImageGetter { source ->
                            val file = File(source)
                            if (file.exists()) {
                                val drawable = Drawable.createFromPath(source)
                                if (drawable != null) {
                                    val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
                                    val maxW = textContent.width - 48
                                    val w = if (maxW > 0 && ratio > 0) maxW.coerceAtMost(drawable.intrinsicWidth) else drawable.intrinsicWidth
                                    val h = (w / ratio).toInt()
                                    drawable.setBounds(0, 0, w, h)
                                }
                                drawable
                            } else null
                        }
                        val spanned = Html.fromHtml(processedHtml, imageGetter, null)
                        textContent.setText(spanned, TextView.BufferType.EDITABLE)
                        dbg("HTML 渲染完成，含图片")
                    } else {
                        // Use EDITABLE buffer type so we can add spans later without resetting text
                        textContent.setText(fullText, TextView.BufferType.EDITABLE)
                        dbg("全文加载: ${fullText.length} 字符")
                    }

                    restoreHighlights()

                    findViewById<View>(R.id.topBar).visibility = View.VISIBLE
                    findViewById<View>(R.id.bottomBar).visibility = View.VISIBLE
                    scrollContent.visibility = View.VISIBLE
                    updatePlayPauseButtons()
                    applyTheme()
                    dbg("内容已加载，工具栏设为 visible")
                    if (isPdfBook) {
                        Toast.makeText(this, "PDF 已加载，当前版本不支持朗读", Toast.LENGTH_LONG).show()
                        findViewById<Button>(R.id.btnPlay).visibility = View.GONE
                        findViewById<Button>(R.id.btnPause).visibility = View.GONE
                        findViewById<Button>(R.id.btnReplay).visibility = View.GONE
                        findViewById<Button>(R.id.btnVoice).visibility = View.GONE
                        findViewById<Button>(R.id.btnPrevChapter).visibility = View.GONE
                        findViewById<Button>(R.id.btnNextChapter).visibility = View.GONE
                        findViewById<Button>(R.id.btnJumpTo).visibility = View.GONE
                    } else {
                        Toast.makeText(this, "已加载 ${SpeechState.chunks.size} 段", Toast.LENGTH_SHORT).show()
                    }

                    if (scrollToChunk >= 0) {
                        textContent.post {
                            scrollToChunk(scrollToChunk)
                            dbg("滚动到 chunk $scrollToChunk")
                        }
                    }
                }
            } else {
                dbg("内容为空")
                Toast.makeText(this, "无可朗读文本", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            dbg("读取出错: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, "读取出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun splitIntoSpeechChunks(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        chunkCharStarts.clear()
        chunkCharEnds.clear()
        var currentChunk = StringBuilder()
        var chunkStartPos = 0
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j] != '。' && text[j] != '！' && text[j] != '？' && text[j] != '.' && text[j] != '!' && text[j] != '?') j++
            if (j < text.length) j++
            val sentence = text.substring(i, j)
            if (sentence.isNotBlank()) {
                if (currentChunk.length + sentence.length <= 200) {
                    if (currentChunk.isEmpty()) {
                        chunkStartPos = i
                    }
                    currentChunk.append(sentence)
                } else {
                    if (currentChunk.isNotEmpty()) {
                        chunkCharStarts.add(chunkStartPos)
                        chunkCharEnds.add(chunkStartPos + currentChunk.length)
                        chunks.add(currentChunk.toString())
                        currentChunk = StringBuilder()
                    }
                    if (sentence.length <= 200) {
                        currentChunk.append(sentence)
                        chunkStartPos = i
                    } else {
                        var k = 0
                        while (k < sentence.length) {
                            val part = sentence.substring(k, minOf(k + 200, sentence.length))
                            chunkCharStarts.add(i + k)
                            chunkCharEnds.add(i + k + part.length)
                            chunks.add(part)
                            k += 200
                        }
                    }
                }
            }
            i = j
        }
        if (currentChunk.isNotEmpty()) {
            chunkCharStarts.add(chunkStartPos)
            chunkCharEnds.add(chunkStartPos + currentChunk.length)
            chunks.add(currentChunk.toString())
        }
        val result = chunks.filter { it.isNotBlank() }
        for (idx in 0 until minOf(5, result.size)) {
            dbg("chunk[$idx]: start=${chunkCharStarts[idx]} end=${chunkCharEnds[idx]} len=${result[idx].length} text=${result[idx].take(30)}")
        }
        return result
    }

    private fun showJumpToDialog() {
        if (SpeechState.chunks.isEmpty()) { Toast.makeText(this, "无可播放内容", Toast.LENGTH_SHORT).show(); return }
        val editText = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "输入段落编号 (1-${SpeechState.chunks.size})"
        }
        AlertDialog.Builder(this)
            .setTitle("跳转到段落")
            .setView(editText)
            .setPositiveButton("朗读此处") { dialog, _ ->
                val n = editText.text.toString().toIntOrNull() ?: 1
                val idx = (n - 1).coerceIn(0, SpeechState.chunks.size - 1)
                SpeechState.currentIndex = idx; SpeechState.isPlaying = true; updatePlayPauseButtons()
                updateProgressUI()
                dbg("跳转到: idx=$idx")
                val chunksToPlay = SpeechState.chunks.drop(idx)
                edgeTts.startPlayback(chunks = chunksToPlay, startOffset = idx,
                    onChunkPlayed = { i -> runOnUiThread { SpeechState.currentIndex = i; scrollToChunk(i); updateProgressUI() }},
                    onComplete = { SpeechState.isPlaying = false; updatePlayPauseButtons(); dbg("全部播放完成") })
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun updatePlayPauseButtons() {
        findViewById<Button>(R.id.btnPlay).isEnabled = !SpeechState.isPlaying
        findViewById<Button>(R.id.btnPause).isEnabled = SpeechState.isPlaying
    }

    private fun showVoiceMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        EdgeTtsClient.VOICES.forEachIndexed { i, (voice, label) ->
            popup.menu.add(0, i, i, "$label${if (voice == edgeTts.voice) " ✓" else ""}")
        }
        popup.setOnMenuItemClickListener { item ->
            edgeTts.voice = EdgeTtsClient.VOICES[item.itemId].first
            dbg("切换语音: ${EdgeTtsClient.VOICES[item.itemId].first}")
            true
        }
        popup.show()
    }

    private fun updateProgressUI() {
        // Simple progress update
    }

    private fun scrollToChunk(chunkIdx: Int) {
        if (chunkIdx < 0 || chunkIdx >= chunkCharStarts.size) return
        val charStart = chunkCharStarts[chunkIdx]
        val layout = textContent.layout ?: return
        val text = textContent.text.toString()
        if (charStart >= text.length) return
        val line = layout.getLineForOffset(charStart)
        val y = layout.getLineTop(line)
        scrollContent.post { scrollContent.scrollTo(0, y - 24) }
        dbg("滚动到 chunk $chunkIdx, 字符 $charStart, y=$y")
    }

    private fun getRequiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
}

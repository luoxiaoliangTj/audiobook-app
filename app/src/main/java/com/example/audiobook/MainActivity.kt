package com.example.audiobook

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private var selectedFileUri: Uri? = null
    private var isPlaying = false
    private var progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private val CHUNK_SIZE = 200 // Characters per chunk for smoother pause/resume

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val utteranceCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val nextIndex = intent?.getIntExtra(TtsService.EXTRA_NEXT_CHUNK_INDEX, -1) ?: -1
            if (nextIndex >= 0 && nextIndex < SpeechState.chunks.size) {
                loadNextChunk(nextIndex)
            }
        }
    }

    private fun loadNextChunk(index: Int) {
        SpeechState.currentIndex = index
        SpeechState.isPlaying = true
        updatePlayPauseButtonStates()
        updateProgressUI()
        
        val intent = Intent(this, TtsService::class.java).apply {
            action = TtsService.ACTION_LOAD
            putExtra(TtsService.EXTRA_TEXT, SpeechState.chunks[index])
            putExtra(TtsService.EXTRA_CHUNK_INDEX, index)
            putExtra(TtsService.EXTRA_TOTAL_CHUNKS, SpeechState.chunks.size)
        }
        startService(intent)
        
        // Start playing the next chunk
        val playIntent = Intent(this, TtsService::class.java).apply {
            action = TtsService.ACTION_PLAY
        }
        startService(playIntent)
        startProgressUpdates()
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            loadAndSpeakFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupViews()
        checkPermissions()
        
        // Register broadcast receiver for auto-advance
        val filter = IntentFilter(TtsService.BROADCAST_UTTERANCE_COMPLETED)
        LocalBroadcastManager.getInstance(this).registerReceiver(utteranceCompletedReceiver, filter)
    }

    private fun setupViews() {
        val btnSelectFile = findViewById<Button>(R.id.btnSelectFile)
        val btnPlay = findViewById<Button>(R.id.btnPlay)
        val btnPause = findViewById<Button>(R.id.btnPause)
        val btnReplay = findViewById<Button>(R.id.btnReplay)
        val tvFileName = findViewById<TextView>(R.id.tvFileName)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvProgress = findViewById<TextView>(R.id.tvProgress)

        btnSelectFile.setOnClickListener {
            val permission = getRequiredPermission()
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                requestPermissionLauncher.launch(permission)
            }
        }

        btnPlay.setOnClickListener {
            SpeechState.isPlaying = true
            SpeechState.currentIndex = 0
            updatePlayPauseButtonStates()
            val intent = Intent(this, TtsService::class.java).apply {
                action = TtsService.ACTION_PLAY
            }
            startService(intent)
            startProgressUpdates()
        }

        btnPause.setOnClickListener {
            SpeechState.isPlaying = false
            updatePlayPauseButtonStates()
            val intent = Intent(this, TtsService::class.java).apply {
                action = TtsService.ACTION_STOP
            }
            startService(intent)
            stopProgressUpdates()
        }

        btnReplay.setOnClickListener {
            SpeechState.currentIndex = 0
            SpeechState.isPlaying = true
            updatePlayPauseButtonStates()
            val intent = Intent(this, TtsService::class.java).apply {
                action = TtsService.ACTION_PLAY
            }
            startService(intent)
            startProgressUpdates()
        }
    }

    private fun updatePlayPauseButtonStates() {
        val btnPlay = findViewById<Button>(R.id.btnPlay)
        val btnPause = findViewById<Button>(R.id.btnPause)
        btnPlay.isEnabled = !SpeechState.isPlaying
        btnPause.isEnabled = SpeechState.isPlaying
    }

    private fun checkPermissions() {
        val permission = getRequiredPermission()
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }

    private fun loadAndSpeakFile(uri: Uri) {
        findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvFileName).text = uri.lastPathSegment ?: "未选择文件"

        try {
            val fileName = uri.lastPathSegment?.lowercase() ?: ""
            val epubBook = if (fileName.endsWith(".epub")) {
                EpubParser.parseEpub(this, contentResolver, uri)
            } else {
                val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return@loadAndSpeakFile
                val fullText = inputStream.bufferedReader().use { it.readText() }
                // For plain text, create a simple EpubBook with one chapter
                EpubBook(
                    uri = uri,
                    title = uri.lastPathSegment ?: "Unknown",
                    author = null,
                    language = null,
                    identifier = null,
                    coverHref = null,
                    chapters = listOf(EpubChapter(
                        id = "chapter_0",
                        title = "全文",
                        href = "",
                        order = 0,
                        content = fullText
                    )),
                    spine = emptyList(),
                    manifest = emptyMap(),
                    metadata = emptyMap()
                )
            }
            
            epubBook?.let { book ->
                // Flatten all chapters into chunks for TTS
                val allChunks = mutableListOf<String>()
                val chapterChunkRanges = mutableListOf<Pair<Int, Int>>() // (startChunk, endChunk) for each chapter
                
                book.chapters.forEach { chapter ->
                    val chapterText = chapter.content ?: ""
                    if (chapterText.isNotEmpty()) {
                        val startChunk = allChunks.size
                        val chapterChunks = splitIntoSpeechChunks(chapterText)
                        allChunks.addAll(chapterChunks)
                        val endChunk = allChunks.size - 1
                        chapterChunkRanges.add(Pair(startChunk, endChunk))
                    } else {
                        chapterChunkRanges.add(Pair(-1, -1))
                    }
                }
                
                SpeechState.chunks = allChunks
                SpeechState.chapterChunkRanges = chapterChunkRanges
                SpeechState.currentChapterIndex = 0
                SpeechState.currentIndex = 0
                SpeechState.isPlaying = false
                SpeechState.currentBook = book

                updateProgressUI()

                // Start with first chunk
                if (SpeechState.chunks.isNotEmpty()) {
                    val intent = Intent(this, TtsService::class.java).apply {
                        action = TtsService.ACTION_LOAD
                        putExtra(TtsService.EXTRA_TEXT, SpeechState.chunks[0])
                        putExtra(TtsService.EXTRA_CHUNK_INDEX, 0)
                        putExtra(TtsService.EXTRA_TOTAL_CHUNKS, SpeechState.chunks.size)
                    }
                    startService(intent)
                    findViewById<View>(R.id.btnPlay).visibility = View.VISIBLE
                    findViewById<View>(R.id.btnPause).visibility = View.VISIBLE
                    findViewById<View>(R.id.btnReplay).visibility = View.VISIBLE
                }
            } ?: run {
                Toast.makeText(this, "无法提取文件内容", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无法读取文件: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
        }
    }

    private fun splitIntoSpeechChunks(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        // Split into paragraphs by one or more blank lines
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        val chunks = mutableListOf<String>()

        for (paragraph in paragraphs) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue

            // Split paragraph into sentences: we want to keep the punctuation with the sentence
            // Using split by lookbehind for punctuation: (?<=[。！？.!?])
            val sentences = trimmed.split(Regex("(?<=[。！？.!?])"))
            // Now sentences may have empty strings if there are consecutive punctuations? We'll filter.
            val sentenceList = sentences.filter { it.isNotEmpty() }

            var currentChunk = ""
            for (sentence in sentenceList) {
                if (currentChunk.length + sentence.length <= 200) {
                    currentChunk += sentence
                } else {
                    if (currentChunk.isNotEmpty()) {
                        chunks.add(currentChunk)
                        currentChunk = sentence
                    } else {
                        // The sentence itself is too long, we split it by 200
                        val parts = sentence.chunked(200).map { it.toString() }
                        chunks.addAll(parts)
                        currentChunk = ""
                    }
                }
            }
            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk)
                currentChunk = ""
            }
        }

        return chunks
    }

    private fun startProgressUpdates() {
        stopProgressUpdates() // Ensure no duplicate
        progressRunnable = object : Runnable {
            override fun run() {
                updateProgressUI()
                if (SpeechState.isPlaying && SpeechState.currentIndex < SpeechState.chunks.size) {
                    progressHandler.postDelayed(this, 500) // Update every 500ms
                }
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let {
            progressHandler.removeCallbacks(it)
        }
    }

    private fun updateProgressUI() {
        val tvProgress = findViewById<TextView>(R.id.tvProgress)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        if (SpeechState.chunks.isNotEmpty()) {
            val progressPercent = if (SpeechState.chunks.size > 0) {
                (SpeechState.currentIndex * 100) / SpeechState.chunks.size
            } else {
                0
            }
            progressBar.progress = progressPercent
            tvProgress.text = "${SpeechState.currentIndex}/${SpeechState.chunks.size} 段落"
        } else {
            progressBar.progress = 0
            tvProgress.text = "0/0 段落"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        stopService(Intent(this, TtsService::class.java))
        
        // Unregister broadcast receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(utteranceCompletedReceiver)
    }

    private fun getRequiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
}
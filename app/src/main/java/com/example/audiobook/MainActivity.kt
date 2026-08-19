package com.example.audiobook

import android.Manifest
import android.content.Intent
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
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private var selectedFileUri: Uri? = null
    private var isPlaying = false
    private var progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private val CHUNK_SIZE = 1000 // Characters per chunk for smoother pause/resume

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show()
        }
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
            val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return
            val fullText = inputStream.bufferedReader().use { it.readText() }

            // Split text into chunks for better pause/resume functionality
            SpeechState.chunks = chunkText(fullText)
            SpeechState.currentIndex = 0
            SpeechState.isPlaying = false

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
        } catch (e: Exception) {
            Toast.makeText(this, "无法读取文件: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
        }
    }

    private fun chunkText(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val endIndex = Math.min(index + CHUNK_SIZE, text.length)
            chunks.add(text.substring(index, endIndex))
            index = endIndex
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
    }

    private fun getRequiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
}
package com.example.audiobookapp

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.*
import nl.siegmann.epublib.epub.EpubReader
import nl.siegmann.epublib.service.MediatypeService
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

class ReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvContent: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnStop: Button
    private lateinit var sbProgress: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tts: TextToSpeech
    private var isSpeaking = false
    private var isPaused = false
    private var fullText = ""
    private var currentPosition = 0
    private var chunkSize = 1000
    private var chunks: List<String> = emptyList()
    private var currentChunkIndex = 0
    private var handler = android.os.Handler()
    private val updateInterval = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        tvContent = findViewById(R.id.tvContent)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnStop = findViewById(R.id.btnStop)
        sbProgress = findViewById(R.id.sbProgress)
        tvPosition = findViewById(R.id.tvPosition)

        val filePath = intent.getStringExtra("file_path")
        if (filePath == null) {
            Toast.makeText(this, "No file specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Load text from file based on extension
        when (filePath.lowercase()) {
            endsWith(".pdf") -> loadPdfText(file)
            endsWith(".epub") -> loadEpubText(file)
            else -> {
                Toast.makeText(this, "Unsupported file format", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Set up button click listeners
        btnPlayPause.setOnClickListener {
            if (isSpeaking) {
                pauseSpeech()
            } else {
                startSpeech()
            }
        }

        btnStop.setOnClickListener {
            stopSpeech()
        }

        // Set up seekbar
        sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekToPosition(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
    }

    private fun loadPdfText(file: File) {
        try {
            val document = PDDocument.load(file)
            val stripper = PDFTextStripper()
            fullText = stripper.getText(document)
            document.close()
            prepareText()
        } catch (e: Exception) {
            Log.e("ReaderActivity", "Error loading PDF", e)
            Toast.makeText(this, "Error loading PDF: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadEpubText(file: File) {
        try {
            val epubReader = EpubReader()
            val book = epubReader.readEpub(file)
            val text = book.getText().toString()
            fullText = text
            prepareText()
        } catch (e: Exception) {
            Log.e("ReaderActivity", "Error loading EPUB", e)
            Toast.makeText(this, "Error loading EPUB: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun prepareText() {
        // Clean up the text
        fullText = fullText.replace(Regex("\\s+"), " ").trim()
        
        // Split into chunks for TTS
        chunks = chunkText(fullText, chunkSize)
        currentPosition = 0
        currentChunkIndex = 0
        updatePosition()
    }

    private fun chunkText(text: String, chunkSize: Int): List<String> {
        val chunks = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val endIndex = minOf(index + chunkSize, text.length)
            chunks.add(text.substring(index, endIndex))
            index = endIndex
        }
        return chunks
    }

    private fun startSpeech() {
        if (chunks.isEmpty()) return
        
        isSpeaking = true
        isPaused = false
        updateButtonState()
        
        // Speak the current chunk
        speakCurrentChunk()
        
        // Start updating progress
        handler.post(updateProgressRunnable)
    }

    private fun pauseSpeech() {
        if (tts.isSpeaking) {
            tts.stop()
        }
        isPaused = true
        updateButtonState()
    }

    private fun stopSpeech() {
        tts.stop()
        isSpeaking = false
        isPaused = false
        currentPosition = 0
        currentChunkIndex = 0
        updateButtonState()
        updatePosition()
    }

    private fun speakCurrentChunk() {
        if (currentChunkIndex < chunks.size) {
            val text = chunks[currentChunkIndex]
            // Add utterance ID for tracking
            val utteranceId = "chunk_$currentChunkIndex"
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    private fun seekToPosition(progress: Int) {
        // Calculate which chunk and position within chunk
        val totalChars = chunks.joinToString("").length
        val newPosition = (totalProgress * progress / 100).toInt()
        
        var charCount = 0
        var chunkIndex = 0
        var charInChunk = 0
        
        for ((index, chunk) in chunks.withIndex()) {
            if (charCount + chunk.length >= newPosition) {
                chunkIndex = index
                charInChunk = newPosition - charCount
                break
            }
            charCount += chunk.length
        }
        
        currentChunkIndex = chunkIndex
        currentPosition = charInChunk
        
        // If we were speaking, restart from new position
        if (isSpeaking && !isPaused) {
            tts.stop()
            speakCurrentChunk()
        }
        
        updatePosition()
    }

    private fun updatePosition() {
        val totalChars = chunks.joinToString("").length
        val currentChar = chunks.take(currentChunkIndex).joinToString("").length + currentPosition
        val progress = if (totalChars > 0) (currentChar * 100 / totalChars) else 0
        
        sbProgress.progress = progress
        tvPosition.text = "$currentChar / $totalChars characters"
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (isSpeaking && !isPaused) {
                // Check if current utterance has finished
                // In a real implementation, you'd use UtteranceProgressListener
                // For simplicity, we'll advance after a delay based on speech rate
                updatePosition()
                
                // Simple timing - in reality, you'd want to use UtteranceProgressListener
                handler.postDelayed(this, 1000) // Update every second
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported")
            } else {
                // Set speech rate and pitch
                tts.setSpeechRate(1.0f)
                tts.setPitch(1.0f)
            }
        } else {
            Log.e("TTS", "Initialization failed")
            Toast.makeText(this, "TTS Initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (tts != null) {
            tts.stop()
            tts.shutdown()
        }
        handler.removeCallbacks(updateProgressRunnable)
        super.onDestroy()
    }

    private fun updateButtonState() {
        if (isSpeaking) {
            if (isPaused) {
                btnPlayPause.text = getString(R.string.play)
            } else {
                btnPlayPause.text = getString(R.string.pause)
            }
        } else {
            btnPlayPause.text = getString(R.string.play)
        }
        btnStop.isEnabled = isSpeaking
    }
}
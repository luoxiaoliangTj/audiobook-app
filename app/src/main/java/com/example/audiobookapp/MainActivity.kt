package com.example.audiobookapp

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.util.Locale
import kotlin.text.Regex
// EPUB import
import nl.siegmann.epublib.domain.Book
import nl.siegmann.epublib.epub.EpubReader

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var btnPickFile: Button
    private lateinit var btnPlayPause: Button
    private lateinit var tvFileName: TextView
    private lateinit var tvStatus: TextView

    private var fileUri: Uri? = null
    private var isPlaying = false
    private var currentPosition = 0
    private var textToSpeak: String = ""
    private var isPdf = false
    private var isEpub = false

    // ActivityResultLauncher for file picking
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            fileUri = it
            tvFileName.text = "Selected: ${getFileName(it)}"
            tvStatus.text = "Processing file..."
            // Determine file type and process accordingly
            when {
                it.toString().endsWith(".pdf", ignoreCase = true) -> {
                    isPdf = true
                    isEpub = false
                    processPdf(it)
                }
                it.toString().endsWith(".epub", ignoreCase = true) -> {
                    isPdf = false
                    isEpub = true
                    processEpub(it)
                }
                else -> {
                    // Assume text file
                    isPdf = false
                    isEpub = false
                    processTextFile(it)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        btnPickFile = findViewById(R.id.btnPickFile)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        tvFileName = findViewById(R.id.tvFileName)
        tvStatus = findViewById(R.id.tvStatus)

        // Initialize TextToSpeech
        tts = TextToSpeech(this, this)

        // Set button click listeners
        btnPickFile.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        btnPlayPause.setOnClickListener {
            if (isPlaying) {
                pauseSpeech()
            } else {
                if (textToSpeak.isNotEmpty()) {
                    resumeSpeech()
                } else {
                    Toast.makeText(this, "Please select a file first", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                toast("Language not supported")
            }
        } else {
            toast("TTS Initialization failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun speakText(text: String) {
        tvStatus.text = "Speaking..."
        isPlaying = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance")
    }

    @SuppressLint("MissingPermission")
    private fun pauseSpeech() {
        tts.stop()
        isPlaying = false
        tvStatus.text = "Paused"
    }

    @SuppressLint("MissingPermission")
    private fun resumeSpeech() {
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "utterance")
        tvStatus.text = "Speaking..."
        isPlaying = true
    }

    private fun processTextFile(uri: Uri) {
        Thread {
            try {
                val input: InputStreamReader = contentResolver.openInputStream(uri)?.let { InputStreamReader(it) } ?: return@Thread
                val bufferedReader = BufferedReader(input)
                val stringBuilder = StringBuilder()
                var line: String?
                while (bufferedReader.readLine().also { line = it } != null) {
                    stringBuilder.append(line)
                    stringBuilder.append('\n')
                }
                textToSpeak = stringBuilder.toString()
                runOnUiThread {
                    tvStatus.text = "Text file loaded. Ready to play."
                    if (textToSpeak.isNotEmpty()) {
                        btnPlayPause.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvStatus.text = "Error reading text file: ${e.localizedMessage}"
                }
            }
        }.start()
    }

    private fun processPdf(uri: Uri) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    // Read all bytes
                    val bytes = stream.readBytes()
                    // Create PdfiumCore instance
                    val pdfiumCore = PdfiumCore(this@MainActivity)
                    // Load PDF from bytes
                    val document = pdfiumCore.newDocument(bytes)
                    // Get page count
                    val pageCount = pdfiumCore.getPageCount(document)
                    // For now, we just report the page count. Text extraction from PDF is complex and not implemented.
                    textToSpeak = "PDF document loaded. Page count: $pageCount. Text extraction for PDF is not yet implemented."
                    // Close document
                    pdfiumCore.closeDocument(document)
                    runOnUiThread {
                        tvStatus.text = "PDF loaded (text extraction pending). Ready to play."
                        if (textToSpeak.isNotEmpty()) {
                            btnPlayPause.isEnabled = true
                        }
                    }
                } ?: run {
                    runOnUiThread {
                        tvStatus.text = "Could not open PDF file"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvStatus.text = "Error processing PDF: ${e.localizedMessage}"
                }
            }
        }.start()
    }

    private fun processEpub(uri: Uri) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val epubReader = EpubReader()
                    val book: Book = epubReader.readEpub(stream)
                    // Extract text from the book
                    val text = extractTextFromBook(book)
                    textToSpeak = text
                    runOnUiThread {
                        val title = book.metadata.title ?: "Unknown Title"
                        val author = book.metadata.author ?: "Unknown Author"
                        tvStatus.text = "EPUB loaded: $title by $author. Ready to play."
                        if (textToSpeak.isNotEmpty()) {
                            btnPlayPause.isEnabled = true
                        }
                    }
                } ?: run {
                    runOnUiThread {
                        tvStatus.text = "Could not open EPUB file"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvStatus.text = "Error processing EPUB: ${e.localizedMessage}"
                }
            }
        }.start()
    }

    /**
     * Extracts all text content from an EPUB book.
     */
    private fun extractTextFromBook(book: Book): String {
        val textBuilder = StringBuilder()
        // Add metadata
        val title = book.metadata.title ?: "Unknown Title"
        val author = book.metadata.author ?: "Unknown Author"
        textBuilder.append("Title: $title\n")
        textBuilder.append("Author: $author\n\n")
        // Add table of contents if available
        val toc = book.tableOfContents.toc
        if (toc.isNotEmpty()) {
            textBuilder.append("Table of Contents:\n")
            for (reference in toc) {
                textBuilder.append("  - ${reference.title}\n")
            }
            textBuilder.append("\n")
        }
        // Add content from each resource
        val resources = book.getResources()
        for (resource in resources) {
            if (resource.mediaType.isText) {
                val content = resource.data.inputStream().reader().readText()
                // Simple HTML tag removal (for demonstration; in production, use a proper HTML parser)
                val plainText = content.replace(Regex("(?i)<[^>]*>"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (plainText.isNotEmpty()) {
                    textBuilder.append(plainText).append("\n\n")
                }
            }
        }
        return textBuilder.toString()
    }

    private fun getFileName(uri: Uri): String {
        return uri.lastPathSegment ?: "Unknown"
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
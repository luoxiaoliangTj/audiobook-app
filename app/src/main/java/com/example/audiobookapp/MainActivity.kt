package com.example.audiobookapp

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.util.Locale

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

    // ActivityResultLauncher for file picking
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            fileUri = it
            tvFileName.text = "Selected: ${getFileName(it)}"
            // For simplicity, we only support .txt files in this version
            if (it.toString().endsWith(".txt", ignoreCase = true)) {
                tvStatus.text = "Processing text file..."
                processTextFile(it)
            } else {
                toast("Only .txt files are supported in this version.")
                tvStatus.text = "Unsupported file type."
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
            // Try Chinese first, then US
            val result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to US English
                val result2 = tts.setLanguage(Locale.US)
                if (result2 == TextToSpeech.LANG_MISSING_DATA || result2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    toast("Language not supported")
                }
            }
            // Set audio attributes for API 21+ (we are minSdk 21, so always true)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts.setAudioAttributes(attributes)
        } else {
            toast("TTS Initialization failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun speakText(text: String) {
        if (text.isBlank()) {
            toast("No text to speak")
            return
        }
        tvStatus.text = "Speaking..."
        isPlaying = true
        // We don't need to pass any parameters because we set the audio attributes in onInit
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
        if (textToSpeak.isNotEmpty()) {
            tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "utterance")
            tvStatus.text = "Speaking..."
            isPlaying = true
        }
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
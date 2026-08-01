package com.example.audiobookapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.media.MediaPlayer
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var buttonPickAudio: Button
    private lateinit var buttonPlay: Button
    private lateinit var textViewStatus: TextView
    private var selectedAudioUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null

    // Launcher for picking a document
    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedAudioUri = uri
            buttonPlay.isEnabled = true
            textViewStatus.text = "Selected: ${uri.lastPathSegment}"
        } else {
            textViewStatus.text = "No file selected"
            buttonPlay.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonPickAudio = findViewById(R.id.buttonPickAudio)
        buttonPlay = findViewById(R.id.buttonPlay)
        textViewStatus = findViewById(R.id.textViewStatus)

        buttonPickAudio.setOnClickListener {
            // Open document picker for audio files
            pickAudioLauncher.launch("audio/*")
        }

        buttonPlay.setOnClickListener {
            selectedAudioUri?.let { uri ->
                playAudio(uri)
            } ?: run {
                Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAudio(uri: Uri) {
        // Release any existing MediaPlayer
        mediaPlayer?.release()

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener { mp -> mp.start() }
                setOnErrorListener { mp, what, extra ->
                    Toast.makeText(this@MainActivity, "Error playing audio", Toast.LENGTH_SHORT).show()
                    true
                }
                setOnCompletionListener { mp ->
                    mp.release()
                    runOnUiThread {
                        textViewStatus.text = "Playback completed"
                        buttonPlay.isEnabled = true
                    }
                }
                prepareAsync()
                textViewStatus.text = "Playing..."
                buttonPlay.isEnabled = false
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Unable to play audio", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
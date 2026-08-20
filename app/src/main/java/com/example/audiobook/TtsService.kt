package com.example.audiobook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.Locale

class TtsService : Service(), TextToSpeech.OnInitListener, TextToSpeech.OnUtteranceCompletedListener {

    companion object {
        const val TAG = "TtsService"
        const val CHANNEL_ID = "tts_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_LOAD = "com.example.audiobook.LOAD"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CHUNK_INDEX = "chunkIndex"
        const val EXTRA_TOTAL_CHUNKS = "totalChunks"

        const val ACTION_PLAY = "com.example.audiobook.PLAY"
        const val ACTION_STOP = "com.example.audiobook.STOP"
        const val ACTION_NEXT_CHUNK = "com.example.audiobook.NEXT_CHUNK"
        
        // Broadcast actions
        const val BROADCAST_UTTERANCE_COMPLETED = "com.example.audiobook.UTTERANCE_COMPLETED"
        const val EXTRA_NEXT_CHUNK_INDEX = "nextChunkIndex"
    }

    private var tts: TextToSpeech? = null
    private var currentText: String = ""
    private var isPlaying = false
    private var currentChunkIndex: Int = 0
    private var totalChunks: Int = 0
    private var isUtteranceCompleted = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initTts()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio book playback"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceCompletedListener(this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == null || result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language not supported")
            }
            tts?.setSpeechRate(1.0f)
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD -> {
                currentText = intent.getStringExtra(EXTRA_TEXT) ?: ""
                currentChunkIndex = intent.getIntExtra(EXTRA_CHUNK_INDEX, 0)
                totalChunks = intent.getIntExtra(EXTRA_TOTAL_CHUNKS, 1)
                if (isUtteranceCompleted) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    Log.d(TAG, "Loaded chunk $currentChunkIndex/$totalChunks (${currentText.length} characters)")
                }
            }
            ACTION_PLAY -> {
                if (isUtteranceCompleted && !currentText.isEmpty()) {
                    startSpeaking()
                }
            }
            ACTION_STOP -> stopSpeaking()
            ACTION_NEXT_CHUNK -> {
                // Signal to load next chunk
                stopSpeaking()
                // The MainActivity will handle loading the next chunk
            }
        }
        return START_STICKY
    }

    fun startSpeaking() {
        if (currentText.isEmpty()) return
        isUtteranceCompleted = false
        tts?.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, "utterance" + System.currentTimeMillis())
        isPlaying = true
        updateNotification()
    }

    fun stopSpeaking() {
        tts?.stop()
        isPlaying = false
        updateNotification()
    }

    override fun onUtteranceCompleted(utteranceId: String?) {
        isUtteranceCompleted = true
        if (isPlaying && currentChunkIndex < totalChunks - 1) {
            // Auto-advance to next chunk
            val nextIndex = currentChunkIndex + 1
            Log.d(TAG, "Utterance completed for chunk $currentChunkIndex, advancing to $nextIndex")
            
            // Send broadcast to MainActivity to load next chunk
            val intent = Intent(BROADCAST_UTTERANCE_COMPLETED).apply {
                putExtra(EXTRA_NEXT_CHUNK_INDEX, nextIndex)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } else if (isPlaying && currentChunkIndex >= totalChunks - 1) {
            Log.d(TAG, "Playback completed - reached end of book")
            isPlaying = false
            updateNotification()
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressText = if (totalChunks > 0) "$currentChunkIndex/$totalChunks" else "0/0"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("听书阅读器")
            .setContentText("正在播放: $progressText 段落")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(totalChunks, currentChunkIndex, false)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }
}
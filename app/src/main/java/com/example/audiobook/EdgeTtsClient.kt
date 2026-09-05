package com.example.audiobook

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EdgeTtsClient(private val context: Context, private val dbg: (String) -> Unit) {

    companion object {
        val VOICES = listOf(
            "zh-CN-XiaoxiaoNeural" to "晓晓(女)",
            "zh-CN-YunxiNeural" to "云希(男)",
            "zh-CN-YunjianNeural" to "云健(男)",
            "zh-CN-YunyangNeural" to "云扬(男,新闻)",
            "zh-CN-XiaoyiNeural" to "晓伊(女)"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isPlaying = AtomicBoolean(false)
    @Volatile private var bgThread: Thread? = null
    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var isPlayingFile = false
    private val playQueue = LinkedBlockingQueue<QueueItem>()
    private val currentChunkIndex = AtomicInteger(0)

    var voice = "zh-CN-XiaoxiaoNeural"
    var rate = "+0%"

    data class QueueItem(val filePath: String, val globalChunkIndex: Int)

    fun startPlayback(chunks: List<String>, startOffset: Int = 0, onChunkPlayed: (Int) -> Unit = {}, onComplete: () -> Unit) {
        stopPlayback()
        Thread.sleep(200)

        isPlaying.set(true)
        isPlayingFile = false
        playQueue.clear()
        currentChunkIndex.set(startOffset)

        bgThread = Thread {
            try {
                for ((localIdx, text) in chunks.withIndex()) {
                    if (!isPlaying.get()) break

                    val globalIdx = startOffset + localIdx

                    if (text.isBlank()) {
                        dbg("滚动到 chunk $globalIdx")
                        currentChunkIndex.set(globalIdx)
                        mainHandler.post { onChunkPlayed(globalIdx) }
                        continue
                    }

                    dbg("TTS: 合成 (${text.length} 字符) [global=$globalIdx]")
                    val mp3 = tryGoogleTts(text)
                    if (mp3.isEmpty()) {
                        dbg("TTS: 合成失败")
                        dbg("滚动到 chunk $globalIdx")
                        currentChunkIndex.set(globalIdx)
                        mainHandler.post { onChunkPlayed(globalIdx) }
                        continue
                    }
                    dbg("TTS: Google 音频 ${mp3.size} bytes")

                    val tmp = File.createTempFile("tts_", ".mp3", context.cacheDir)
                    tmp.writeBytes(mp3)
                    dbg("TTS: MP3 ${tmp.absolutePath}")

                    playQueue.put(QueueItem(tmp.absolutePath, globalIdx))

                    mainHandler.post {
                        if (isPlaying.get()) playNextIfIdle(onChunkPlayed, onComplete)
                    }
                }

                // 等队列清空且当前文件播完
                while ((playQueue.isNotEmpty() || isPlayingFile) && isPlaying.get()) {
                    Thread.sleep(200)
                }

                if (isPlaying.get()) {
                    isPlaying.set(false)
                    mainHandler.post(onComplete)
                }
            } catch (e: InterruptedException) {
                dbg("TTS: 后台线程被中断")
            } catch (e: Exception) {
                dbg("TTS: 后台线程出错: ${e.message}")
                if (isPlaying.get()) {
                    isPlaying.set(false)
                    mainHandler.post(onComplete)
                }
            }
        }
        bgThread?.start()
    }

    // 主线程调用
    private fun playNextIfIdle(onChunkPlayed: (Int) -> Unit, onComplete: () -> Unit) {
        if (!isPlaying.get()) return
        if (isPlayingFile) return

        val item = playQueue.poll() ?: return
        dbg("TTS: playNext global=${item.globalChunkIndex} ${item.filePath}")

        try {
            // 停掉旧播放器
            try {
                currentPlayer?.stop()
                currentPlayer?.release()
            } catch (_: Exception) {}
            currentPlayer = null

            val player = MediaPlayer()
            currentPlayer = player

            player.setOnPreparedListener { p ->
                dbg("TTS: 已准备好 chunk=${item.globalChunkIndex}")
                if (isPlaying.get()) {
                    try {
                        p.start()
                        dbg("TTS: 播放中 chunk=${item.globalChunkIndex}")
                    } catch (e: Exception) {
                        dbg("TTS: start() 出错: ${e.message}")
                    }
                }
            }

            player.setOnCompletionListener { p ->
                dbg("TTS: 完成 chunk=${item.globalChunkIndex}")
                isPlayingFile = false
                try { p.release() } catch (_: Exception) {}
                if (currentPlayer === p) currentPlayer = null
                try { File(item.filePath).delete() } catch (_: Exception) {}

                val next = item.globalChunkIndex + 1
                currentChunkIndex.set(next)
                mainHandler.post {
                    onChunkPlayed(next)
                    playNextIfIdle(onChunkPlayed, onComplete)
                }
            }

            player.setOnErrorListener { p, _, what ->
                dbg("TTS: 错误 chunk=${item.globalChunkIndex} what=$what")
                isPlayingFile = false
                try { p.release() } catch (_: Exception) {}
                if (currentPlayer === p) currentPlayer = null
                try { File(item.filePath).delete() } catch (_: Exception) {}
                mainHandler.post { playNextIfIdle(onChunkPlayed, onComplete) }
                true
            }

            player.setAudioAttributes(AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build())
            player.setDataSource(item.filePath)
            isPlayingFile = true
            player.prepareAsync()
            dbg("TTS: prepareAsync chunk=${item.globalChunkIndex}")
        } catch (e: Throwable) {
            dbg("TTS: 出错 ${e.javaClass.name}: ${e.message}")
            isPlayingFile = false
            try {
                currentPlayer?.release()
            } catch (_: Exception) {}
            currentPlayer = null
            try { File(item.filePath).delete() } catch (_: Exception) {}
            mainHandler.post { playNextIfIdle(onChunkPlayed, onComplete) }
        }
    }

    fun stopPlayback() {
        isPlaying.set(false)
        isPlayingFile = false
        playQueue.clear()
        mainHandler.removeCallbacksAndMessages(null)

        try {
            currentPlayer?.stop()
            currentPlayer?.release()
        } catch (_: Exception) {}
        currentPlayer = null

        bgThread?.interrupt()
        bgThread = null
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying.get()
    fun getCurrentChunkIndex(): Int = currentChunkIndex.get()

    private fun tryGoogleTts(text: String): ByteArray {
        repeat(3) { attempt ->
            try {
                val encoded = URLEncoder.encode(text, "UTF-8")
                val url = "https://translate.google.com/translate_tts?ie=UTF-8&q=$encoded&tl=zh-CN&client=tw-ob"
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .addHeader("Referer", "https://translate.google.com/")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: byteArrayOf()
                    if (bytes.size > 4) {
                        val header = String(bytes.sliceArray(0..3), Charsets.US_ASCII)
                        if (header.startsWith("ID3") || bytes[0] == 0xFF.toByte()) {
                            return bytes
                        } else {
                            dbg("Google: 非MP3数据，header=$header size=${bytes.size}, attempt=$attempt")
                        }
                    } else {
                        dbg("Google: 响应太小 (${bytes.size} bytes), attempt=$attempt")
                    }
                } else {
                    dbg("Google: HTTP ${resp.code}, attempt=$attempt")
                }
            } catch (e: Exception) {
                dbg("Google: ${e.message}, attempt=$attempt")
            }
            if (attempt < 2) Thread.sleep(500)
        }
        dbg("Google: 3次重试后仍然失败")
        return byteArrayOf()
    }
}

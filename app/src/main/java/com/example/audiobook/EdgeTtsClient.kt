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
        // TTS 引擎类型: "google" (旧版) / "nvidia" (NVIDIA TTS NIM 远端)
        var engine = "nvidia"
        // NVIDIA TTS NIM 服务器地址（局域网部署），可被设置页覆盖
        var nvidiaBaseUrl = "http://192.168.2.116:9000"
        // NVIDIA Magpie-Multilingual 声音（zh-CN 官方支持名单，来自 TTS support matrix）
        val VOICES = listOf(
            "Magpie-Multilingual.ZH-CN.HouZhen" to "HouZhen(中文)",
            "Magpie-Multilingual.ZH-CN.Siwei" to "Siwei(中文)",
            "Magpie-Multilingual.ZH-CN.Diego" to "Diego(中文)",
            "Magpie-Multilingual.ZH-CN.Pascal" to "Pascal(中文)",
            "Magpie-Multilingual.ZH-CN.Long" to "Long(中文)",
            "Magpie-Multilingual.ZH-CN.Jason" to "Jason(中文)",
            "Magpie-Multilingual.ZH-CN.Isabela" to "Isabela(中文)",
            "Magpie-Multilingual.ZH-CN.Louise" to "Louise(中文)",
            "Magpie-Multilingual.EN-US.Aria" to "Aria(英文)"
        )
        // 保留旧 Google 备用声音列表
        val GOOGLE_VOICES = listOf(
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

    var voice = "Magpie-Multilingual.ZH-CN.HouZhen"
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
                    val (audio, ext) = when (engine) {
                        "nvidia" -> {
                            val wav = tryNvidiaTts(text)
                            if (wav.isEmpty()) {
                                // NVIDIA 失败时回退到 Google
                                val g = tryGoogleTts(text)
                                if (g.isEmpty()) byteArrayOf() to "mp3" else g to "mp3"
                            } else wav to "wav"
                        }
                        else -> tryGoogleTts(text) to "mp3"
                    }
                    if (audio.isEmpty()) {
                        dbg("TTS: 合成失败")
                        dbg("滚动到 chunk $globalIdx")
                        currentChunkIndex.set(globalIdx)
                        mainHandler.post { onChunkPlayed(globalIdx) }
                        continue
                    }
                    dbg("TTS: 音频 ${audio.size} bytes (${ext})")

                    val tmp = File.createTempFile("tts_", ".$ext", context.cacheDir)
                    tmp.writeBytes(audio)
                    dbg("TTS: $ext ${tmp.absolutePath}")

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

    /**
     * NVIDIA TTS NIM HTTP REST 合成
     * POST {base}/v1/audio/synthesize  (multipart/form-data)
     * 参数: text, language, voice, sample_rate_hz
     * 返回: audio/wav (16-bit mono, 默认 22050 Hz)
     */
    private fun tryNvidiaTts(text: String): ByteArray {
        repeat(3) { attempt ->
            try {
                val language = if (voice.startsWith("Magpie-Multilingual.EN")) "en-US" else "zh-CN"
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("text", text)
                    .addFormDataPart("language", language)
                    .addFormDataPart("voice", voice)
                    .addFormDataPart("sample_rate_hz", "22050")
                    .build()
                val req = Request.Builder()
                    .url("$nvidiaBaseUrl/v1/audio/synthesize")
                    .post(body)
                    .build()
                val resp = client.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: byteArrayOf()
                    // WAV 校验: "RIFF" 头
                    if (bytes.size > 12 && String(bytes.sliceArray(0..3), Charsets.US_ASCII) == "RIFF") {
                        return bytes
                    } else {
                        dbg("NVIDIA: 非WAV数据, size=${bytes.size}, attempt=$attempt")
                    }
                } else {
                    val err = resp.body?.string()?.take(200) ?: ""
                    dbg("NVIDIA: HTTP ${resp.code} $err, attempt=$attempt")
                }
            } catch (e: Exception) {
                dbg("NVIDIA: ${e.message}, attempt=$attempt")
            }
            if (attempt < 2) Thread.sleep(500)
        }
        dbg("NVIDIA: 3次重试后仍然失败")
        return byteArrayOf()
    }
}

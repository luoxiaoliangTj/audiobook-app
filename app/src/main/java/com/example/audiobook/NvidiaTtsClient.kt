package com.example.audiobook

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * NVIDIA NIM TTS 客户端（远端托管 API，integrate.api.nvidia.com）
 *
 * 使用 NVIDIA Magpie TTS 模型做文本转语音：
 *  - magpie-tts-multilingual：12 语言（含中文 zh-CN），预设音色
 *  - magpie-tts-zeroshot：仅英/西/法，支持参考音频克隆音色（需用户授权句）
 *
 * API key 从 local.properties 注入 BuildConfig.NVIDIA_API_KEY（不进 git）。
 */
class NvidiaTtsClient(private val context: Context, private val dbg: (String) -> Unit) {

    companion object {
        // NVIDIA NIM 托管 TTS 端点（与 Hermes providers.nvidia.base_url 同源）
        const val BASE_URL = "https://877104f7-e885-42b9-8de8-f6e4c6303969.invocation.api.nvcf.nvidia.com"

        // 预设音色（Magpie-Multilingual，zh-CN 官方名单）
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

        // 克隆音色（仅英文文本可用）
        const val VOICE_CLONE_EN = "Magpie-ZeroShot-Multilingual"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isPlaying = AtomicBoolean(false)
    @Volatile private var bgThread: Thread? = null
    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var isPlayingFile = false
    private val playQueue = LinkedBlockingQueue<QueueItem>()
    private val currentChunkIndex = AtomicInteger(0)

    var voice = "Magpie-Multilingual.ZH-CN.HouZhen"
    // 零样本克隆参考音频（16bit 单声道 WAV ≥22.05kHz，≤5秒）。为空则用预设音色
    var clonePromptFile: File? = null

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
                    val wav = tryNvidiaTts(text)
                    if (wav.isEmpty()) {
                        dbg("TTS: 合成失败")
                        currentChunkIndex.set(globalIdx)
                        mainHandler.post { onChunkPlayed(globalIdx) }
                        continue
                    }
                    dbg("TTS: WAV ${wav.size} bytes")

                    val tmp = File.createTempFile("tts_", ".wav", context.cacheDir)
                    tmp.writeBytes(wav)

                    playQueue.put(QueueItem(tmp.absolutePath, globalIdx))

                    mainHandler.post {
                        if (isPlaying.get()) playNextIfIdle(onChunkPlayed, onComplete)
                    }
                }

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

    private fun playNextIfIdle(onChunkPlayed: (Int) -> Unit, onComplete: () -> Unit) {
        if (!isPlaying.get()) return
        if (isPlayingFile) return

        val item = playQueue.poll() ?: return
        dbg("TTS: playNext global=${item.globalChunkIndex}")

        try {
            try {
                currentPlayer?.stop()
                currentPlayer?.release()
            } catch (_: Exception) {}
            currentPlayer = null

            val player = MediaPlayer()
            currentPlayer = player

            player.setOnPreparedListener { p ->
                if (isPlaying.get()) {
                    try { p.start() } catch (e: Exception) { dbg("TTS: start() 出错: ${e.message}") }
                }
            }

            player.setOnCompletionListener { p ->
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
                dbg("TTS: 错误 what=$what")
                isPlayingFile = false
                try { p.release() } catch (_: Exception) {}
                if (currentPlayer === p) currentPlayer = null
                try { File(item.filePath).delete() } catch (_: Exception) {}
                mainHandler.post { playNextIfIdle(onChunkPlayed, onComplete) }
                true
            }

            player.setAudioAttributes(AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA).build())
            player.setDataSource(item.filePath)
            isPlayingFile = true
            player.prepareAsync()
        } catch (e: Throwable) {
            dbg("TTS: 出错 ${e.javaClass.name}: ${e.message}")
            isPlayingFile = false
            try { currentPlayer?.release() } catch (_: Exception) {}
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

    /**
     * 调用 NVIDIA NIM 托管 TTS（Magpie-Multilingual）。
     * 端点（NVCF invocation，POST multipart）——已实测验证：
     *   https://877104f7-e885-42b9-8de8-f6e4c6303969.invocation.api.nvcf.nvidia.com/v1/audio/synthesize
     * 鉴权：Authorization: Bearer <nvapi key>
     * 参数：text, language, voice, sample_rate_hz
     * 返回：audio/wav (RIFF)
     */
    private fun tryNvidiaTts(text: String): ByteArray {
        val apiKey = BuildConfig.NVIDIA_API_KEY
        if (apiKey.isBlank()) {
            dbg("NVIDIA: 未配置 API key")
            return byteArrayOf()
        }
        repeat(3) { attempt ->
            try {
                val useClone = clonePromptFile != null && clonePromptFile!!.exists() && isEnglishVoice()
                val voiceName = if (useClone) VOICE_CLONE_EN else voice
                val language = if (voiceName.startsWith("Magpie-Multilingual.EN") || voiceName == VOICE_CLONE_EN) "en-US" else "zh-CN"

                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("text", text)
                    .addFormDataPart("language", language)
                    .addFormDataPart("voice", voiceName)
                    .addFormDataPart("sample_rate_hz", "22050")
                if (useClone) {
                    bodyBuilder.addFormDataPart(
                        "audio_prompt", clonePromptFile!!.name,
                        clonePromptFile!!.asRequestBody("audio/wav".toMediaType())
                    )
                }

                val req = Request.Builder()
                    .url("https://877104f7-e885-42b9-8de8-f6e4c6303969.invocation.api.nvcf.nvidia.com/v1/audio/synthesize")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("accept", "audio/wav")
                    .post(bodyBuilder.build())
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: byteArrayOf()
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
        return byteArrayOf()
    }

    private fun isEnglishVoice(): Boolean = voice.startsWith("Magpie-Multilingual.EN")
}

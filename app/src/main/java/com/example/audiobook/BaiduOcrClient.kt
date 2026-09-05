package com.example.audiobook

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Baidu OCR client for text recognition from images.
 * API keys are loaded from BuildConfig (set via local.properties).
 */
class BaiduOcrClient(
    private val apiKey: String = BuildConfig.BAIDU_OCR_API_KEY,
    private val secretKey: String = BuildConfig.BAIDU_OCR_SECRET_KEY
) {
    private val client = OkHttpClient()
    private var accessToken: String? = null
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    interface OcrCallback {
        fun onProgress(pageNum: Int, totalPages: Int)
        fun onPageResult(pageNum: Int, text: String)
        fun onComplete(totalText: String)
        fun onError(error: String)
    }

    suspend fun recognizePages(bitmaps: List<Bitmap>, callback: OcrCallback) {
        isCancelled = false
        val totalPages = bitmaps.size
        val allText = StringBuilder()

        try {
            // Get access token
            withContext(Dispatchers.Main) { callback.onProgress(0, totalPages) }
            val token = getAccessToken()
            if (token == null) {
                withContext(Dispatchers.Main) { callback.onError("获取百度访问令牌失败") }
                return
            }

            for (i in bitmaps.indices) {
                if (isCancelled) break

                withContext(Dispatchers.Main) { callback.onProgress(i + 1, totalPages) }

                val bitmap = bitmaps[i]
                val base64 = bitmap.toBase64()
                val text = recognizeText(token, base64)

                if (text != null) {
                    allText.append("\n\n--- 第 ${i + 1} 页 ---\n")
                    allText.append(text)
                    withContext(Dispatchers.Main) { callback.onPageResult(i + 1, text) }
                } else {
                    withContext(Dispatchers.Main) { callback.onError("第 ${i + 1} 页识别失败") }
                }

                // Rate limit: max 10 requests per second
                delay(150)
            }

            withContext(Dispatchers.Main) { callback.onComplete(allText.toString()) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { callback.onError("OCR 错误: ${e.message}") }
        }
    }

    private suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", apiKey)
                .add("client_secret", secretKey)
                .build()

            val request = Request.Builder()
                .url("https://aip.baidubce.com/oauth/2.0/token")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return@withContext null)
            accessToken = json.optString("access_token")
            accessToken
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun recognizeText(token: String, imageBase64: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("image", imageBase64)
                .add("language_type", "CHN_ENG")
                .build()

            val request = Request.Builder()
                .url("https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic?access_token=$token")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return@withContext null)
            val wordsResult = json.optJSONArray("words_result") ?: return@withContext null

            val sb = StringBuilder()
            for (i in 0 until wordsResult.length()) {
                sb.append(wordsResult.getJSONObject(i).optString("words"))
                sb.append("\n")
            }
            sb.toString().trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun Bitmap.toBase64(): String {
        val bos = java.io.ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 80, bos)
        val bytes = bos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

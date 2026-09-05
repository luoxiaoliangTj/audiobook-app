package com.example.audiobook

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class BaiduOcrClient(
    private val context: android.content.Context,
    private val onProgress: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var accessToken: String? = null

    companion object {
        private const val API_KEY = "o0a5JjKCo9NvZPTpmg6pNd5b"
        private const val SECRET_KEY = "8lBFvkjBOytX0oxD8trFMXmhEOcQwCDh"
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic"
    }

    fun start() {
        scope.launch {
            try {
                onProgress("获取百度访问令牌...")
                val token = getAccessToken()
                if (token == null) {
                    onError("获取访问令牌失败")
                } else {
                    accessToken = token
                    onProgress("令牌获取成功，准备识别...")
                    onResult("READY")
                }
            } catch (e: Exception) {
                onError("初始化失败: ${e.message}")
            }
        }
    }

    fun recognizePage(bitmap: Bitmap, pageNum: Int) {
        val currentToken = accessToken
        if (currentToken == null) {
            onError("未获取访问令牌")
            return
        }
        scope.launch {
            try {
                onProgress("识别第 $pageNum 页...")
                val result = callOcr(bitmap, currentToken)
                onResult("PAGE:$pageNum:$result")
            } catch (e: Exception) {
                onError("第 $pageNum 页识别失败: ${e.message}")
            }
        }
    }

    fun cancel() {
        scope.cancel()
    }

    private suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", API_KEY)
            .add("client_secret", SECRET_KEY)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(formBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext null
        val json = JSONObject(body)
        if (json.has("access_token")) {
            json.getString("access_token")
        } else {
            null
        }
    }

    private suspend fun callOcr(bitmap: Bitmap, token: String): String = withContext(Dispatchers.IO) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val imageBytes = stream.toByteArray()
        val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val formBody = FormBody.Builder()
            .add("image", imageBase64)
            .add("language_type", "CHN_ENG")
            .add("detect_direction", "true")
            .add("paragraph", "true")
            .build()

        val request = Request.Builder()
            .url("$OCR_URL?access_token=$token")
            .post(formBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response")
        val json = JSONObject(body)

        if (json.has("error_code")) {
            throw Exception("OCR API error: ${json.getString("error_msg")}")
        }

        val wordsResult = json.optJSONArray("words_result") ?: return@withContext ""
        val sb = StringBuilder()
        for (i in 0 until wordsResult.length()) {
            val item = wordsResult.getJSONObject(i)
            sb.append(item.getString("words"))
            if (item.optBoolean("is_paragraph_end", false)) {
                sb.append("\n\n")
            } else {
                sb.append("\n")
            }
        }
        sb.toString()
    }
}

package com.example.audiobook

import android.content.ContentResolver
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Html
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

object EpubImageExtractor {
    private const val TAG = "EpubImageExtractor"

    fun extractImages(context: Context, contentResolver: ContentResolver, uri: Uri): Map<String, File> {
        val result = mutableMapOf<String, File>()
        var inputStream: InputStream? = null
        var tempFile: File? = null
        try {
            inputStream = contentResolver.openInputStream(uri) ?: return result
            tempFile = File(context.cacheDir, "temp_epub_img_${System.currentTimeMillis()}.epub")
            tempFile.parentFile?.mkdirs()
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            outputStream.close()
            inputStream.close()
            inputStream = null

            val zipFile = ZipFile(tempFile)
            val imageExtensions = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg")
            val entries = zipFile.entries()
            val imageDir = File(context.cacheDir, "epub_images")
            imageDir.mkdirs()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val name = e.name.substringAfterLast("/").lowercase()
                if (imageExtensions.any { name.endsWith(it) }) {
                    val imgFile = File(imageDir, e.name.replace("/", "_"))
                    val ins = zipFile.getInputStream(e)
                    val fos = FileOutputStream(imgFile)
                    ins.copyTo(fos)
                    fos.close()
                    ins.close()
                    result[e.name] = imgFile
                }
            }
            Log.d(TAG, "Extracted ${result.size} images")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract images", e)
        } finally {
            inputStream?.close()
            tempFile?.delete()
        }
        return result
    }
}

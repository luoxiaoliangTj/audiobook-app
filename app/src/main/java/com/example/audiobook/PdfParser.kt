package com.example.audiobook

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

data class PdfBook(
    val uri: Uri,
    val title: String,
    val pageCount: Int,
    val text: String
)

object PdfParser {

    private var initialized = false
    private val crashLog = StringBuilder()

    fun init(context: Context) {
        if (!initialized) {
            try {
                PDFBoxResourceLoader.init(context)
                initialized = true
                log("PDFBoxResourceLoader initialized")
            } catch (e: Exception) {
                log("PDFBoxResourceLoader init failed: ${e.message}")
            }
        }
    }

    fun getCrashLog(): String = crashLog.toString()

    private fun log(msg: String) {
        crashLog.append("[PdfParser] ").append(msg).append("\n")
        android.util.Log.d("PdfParser", msg)
    }

    fun parsePdf(context: Context, contentResolver: ContentResolver, uri: Uri): PdfBook? {
        try {
            log("parsePdf: $uri")
            init(context)

            // Step 1: Copy PDF to temp file (more reliable on Android)
            val tempFile = File(context.cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
            log("Creating temp file: ${tempFile.absolutePath}")

            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    log("Cannot open input stream for URI")
                    return null
                }
                log("Temp file size: ${tempFile.length()} bytes")
            } catch (e: Exception) {
                log("Copy to temp failed: ${stackTrace(e)}")
                return null
            }

            // Step 2: Load PDF from temp file
            log("Loading PDF from temp file...")
            val document: PDDocument
            try {
                document = PDDocument.load(tempFile)
                log("PDF loaded, pages: ${document.numberOfPages}")
            } catch (e: Exception) {
                log("PDDocument.load failed: ${stackTrace(e)}")
                tempFile.delete()
                return null
            }

            // Step 3: Handle encrypted PDFs
            if (document.isEncrypted) {
                log("PDF is encrypted, attempting to remove security...")
                try {
                    document.setAllSecurityToBeRemoved(true)
                } catch (e: Exception) {
                    log("Remove security failed: ${e.message}")
                }
            }

            // Step 4: Extract text
            var text: String = ""
            try {
                val stripper = PDFTextStripper()
                text = stripper.getText(document)
                log("Text extracted: ${text.length} chars")
            } catch (e: Exception) {
                log("Text extraction failed: ${stackTrace(e)}")
                text = ""
            }

            val pageCount = document.numberOfPages

            // Step 5: Close document and cleanup
            try {
                document.close()
                tempFile.delete()
            } catch (e: Exception) {
                log("Cleanup failed: ${e.message}")
            }

            return PdfBook(
                uri = uri,
                title = getFileName(contentResolver, uri),
                pageCount = pageCount,
                text = text.trim()
            )
        } catch (e: Throwable) {
            log("FATAL: ${stackTrace(e)}")
            return null
        }
    }

    private fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String {
        var name = uri.lastPathSegment ?: "未知PDF"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return name
    }
}

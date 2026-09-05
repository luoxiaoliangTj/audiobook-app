package com.example.audiobook

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
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
    private val logs = StringBuilder()

    fun init(context: Context) {
        logs.clear()
        try {
            PDFBoxResourceLoader.init(context)
            initialized = true
            log("PDFBoxResourceLoader init OK")
        } catch (e: Throwable) {
            log("PDFBoxResourceLoader init FAILED: ${stackTrace(e)}")
        }
    }

    fun getLogs(): String = logs.toString()

    private fun log(msg: String) {
        logs.append("[PdfParser] ").append(msg).append("\n")
        Log.d("PdfParser", msg)
    }

    private fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    fun parsePdf(context: Context, contentResolver: ContentResolver, uri: Uri): PdfBook? {
        try {
            log("parsePdf: $uri")
            init(context)
            if (!initialized) {
                log("PDFBox NOT initialized, aborting")
                return null
            }

            log("File descriptor opened, loading PDF...")
            val document: PDDocument
            try {
                // Copy to temp file first (more reliable on Android)
                val tempFile = File(context.cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                log("Temp file: ${tempFile.absolutePath}, size: ${tempFile.length()}")
                document = PDDocument.load(tempFile)
                log("PDF loaded OK, pages: ${document.numberOfPages}")
            } catch (e: Throwable) {
                log("PDDocument.load FAILED: ${stackTrace(e)}")
                return null
            }

            if (document.isEncrypted) {
                log("PDF encrypted, trying to remove security...")
                try {
                    document.setAllSecurityToBeRemoved(true)
                } catch (e: Throwable) {
                    log("Remove security failed: ${e.message}")
                }
            }

            var text: String = ""
            try {
                val stripper = PDFTextStripper()
                text = stripper.getText(document)
                log("Text extracted: ${text.length} chars")
            } catch (e: Throwable) {
                log("Text extraction FAILED: ${stackTrace(e)}")
            }


            val pageCount = document.numberOfPages
            document.close()

            log("Done. pages=$pageCount, text=${text.length}")
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

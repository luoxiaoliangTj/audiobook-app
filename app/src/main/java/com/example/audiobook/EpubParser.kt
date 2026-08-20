package com.example.audiobook

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

object EpubParser {
    private const val TAG = "EpubParser"

    /**
     * Extract plain text from an EPUB file using built-in Android APIs
     */
    fun extractText(context: Context, contentResolver: ContentResolver, uri: Uri): String? {
        var inputStream: InputStream? = null
        var tempFile: File? = null
        
        try {
            // Copy to temp file since ZipFile needs a File
            inputStream = contentResolver.openInputStream(uri) ?: return null
            tempFile = File(context.cacheDir, "temp_epub_${System.currentTimeMillis()}.epub")
            tempFile.parentFile?.mkdirs()
            
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            outputStream.close()
            inputStream.close()
            inputStream = null
            
            return extractTextFromEpubFile(tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse EPUB", e)
            return null
        } finally {
            inputStream?.close()
            tempFile?.delete()
        }
    }
    
    private fun extractTextFromEpubFile(epubFile: File): String? {
        val zipFile = ZipFile(epubFile)
        try {
            // First, find the OPF file (package document)
            val opfEntry = zipFile.entries().asSequence()
                .firstOrNull { it.name.endsWith(".opf") }
            
            if (opfEntry == null) {
                Log.e(TAG, "No OPF file found in EPUB")
                return null
            }
            
            // Parse OPF to find spine and manifest
            val opfInputStream = zipFile.getInputStream(opfEntry)
            val opfContent = opfInputStream.bufferedReader().use { it.readText() }
            opfInputStream.close()
            
            val (spineItems, manifestItems) = parseOpf(opfContent, opfEntry.name)
            
            // Extract text from spine items in order
            val stringBuilder = StringBuilder()
            
            for (itemId in spineItems) {
                val href = manifestItems[itemId]
                if (href != null) {
                    // Resolve href relative to OPF file location
                    val opfDir = opfEntry.name.substringBeforeLast("/")
                    val resolvedHref = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                    
                    val entry = zipFile.getEntry(resolvedHref)
                    if (entry != null) {
                        val entryStream = zipFile.getInputStream(entry)
                        val htmlContent = entryStream.bufferedReader().use { it.readText() }
                        entryStream.close()
                        
                        val plainText = htmlToPlainText(htmlContent)
                        if (plainText.isNotEmpty()) {
                            stringBuilder.append(plainText).append("\n\n")
                        }
                    }
                }
            }
            
            return stringBuilder.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse EPUB", e)
            return null
        } finally {
            zipFile.close()
        }
    }
    
    private fun parseOpf(opfContent: String, opfPath: String): Pair<List<String>, Map<String, String>> {
        val spineItems = mutableListOf<String>()
        val manifestItems = mutableMapOf<String, String>()
        
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(opfContent))
            
            var inManifest = false
            var inSpine = false
            var eventType = parser.eventType
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "manifest" -> inManifest = true
                            "spine" -> inSpine = true
                            "item" -> {
                                if (inManifest) {
                                    val id = parser.getAttributeValue(null, "id")
                                    val href = parser.getAttributeValue(null, "href")
                                    val mediaType = parser.getAttributeValue(null, "media-type")
                                    if (id != null && href != null && mediaType == "application/xhtml+xml") {
                                        manifestItems[id] = href
                                    }
                                }
                            }
                            "itemref" -> {
                                if (inSpine) {
                                    val idref = parser.getAttributeValue(null, "idref")
                                    if (idref != null) {
                                        spineItems.add(idref)
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "manifest" -> inManifest = false
                            "spine" -> inSpine = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing OPF", e)
        }
        
        return spineItems to manifestItems
    }
    
    private fun htmlToPlainText(html: String): String {
        return html
            .replace("<[^>]+>".toRegex(), " ")  // Remove HTML tags
            .replace("&nbsp;".toRegex(), " ")   // Replace &nbsp; with space
            .replace("&".toRegex(), "&")    // Replace & with &
            .replace("<".toRegex(), "<")     // Replace < with <
            .replace(">".toRegex(), ">")     // Replace > with >
            .replace("\\\"".toRegex(), "\"")  // Replace " with "
            .replace("'".toRegex(), "'")    // Replace ' with '
            .replace("\\s+".toRegex(), " ")     // Normalize whitespace
            .trim()
    }
}
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

    fun parseEpub(context: Context, contentResolver: ContentResolver, uri: Uri): EpubBook? {
        var inputStream: InputStream? = null
        var tempFile: File? = null
        try {
            inputStream = contentResolver.openInputStream(uri) ?: return null
            tempFile = File(context.cacheDir, "temp_epub_${System.currentTimeMillis()}.epub")
            tempFile.parentFile?.mkdirs()
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            outputStream.close()
            inputStream.close()
            inputStream = null
            return parseEpubFile(tempFile, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse EPUB", e)
            return null
        } finally {
            inputStream?.close()
            tempFile?.delete()
        }
    }

    private fun parseEpubFile(epubFile: File, originalUri: Uri): EpubBook? {
        val zipFile = ZipFile(epubFile)
        try {
            // 1. Find OPF
            val opfEntry = zipFile.entries().asSequence()
                .firstOrNull { it.name.endsWith(".opf") }
            if (opfEntry == null) {
                Log.e(TAG, "No OPF file found")
                return null
            }

            val opfDir = opfEntry.name.substringBeforeLast("/", "")
            val opfInputStream = zipFile.getInputStream(opfEntry)
            val opfBytes = opfInputStream.readBytes()
            opfInputStream.close()
            val opfContent = String(opfBytes, Charsets.UTF_8)

            // 2. Parse OPF
            val (manifestItems, spineItems, metadata) = parseOpf(opfContent, opfDir)

            // 3. Build chapters from spine (simplified - no NAV/NCX dependency)
            val chapters = mutableListOf<EpubChapter>()
            for ((index, spineItem) in spineItems.withIndex()) {
                val manifestItem = manifestItems[spineItem.idref]
                val href = manifestItem?.href ?: ""

                // Derive title from href
                val title = href.substringAfterLast("/").substringBeforeLast(".")
                    .ifEmpty { "Chapter ${index + 1}" }
                    .replace("_", " ").replace("-", " ")

                // Find and extract chapter content
                val entry = findZipEntry(zipFile, href, opfDir)
                val rawHtml = if (entry != null) extractEntryContent(zipFile, entry) else null
                val content = rawHtml?.let { htmlToPlainText(it) } ?: ""

                chapters.add(EpubChapter(
                    id = spineItem.idref,
                    title = title,
                    href = href,
                    order = index,
                    content = content,
                    htmlContent = rawHtml,
                    wordCount = content.split("\\s+".toRegex()).size
                ))
            }

            Log.d(TAG, "Parsed ${chapters.size} chapters from ${spineItems.size} spine items")

            return EpubBook(
                uri = originalUri,
                title = metadata["title"] ?: "Unknown",
                author = metadata["creator"],
                language = metadata["language"],
                identifier = metadata["identifier"],
                coverHref = metadata["cover"]?.let { manifestItems[it]?.href },
                chapters = chapters,
                spine = spineItems,
                manifest = manifestItems,
                metadata = metadata
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse EPUB file", e)
            return null
        } finally {
            zipFile.close()
        }
    }

    private fun extractEntryContent(zipFile: ZipFile, entry: java.util.zip.ZipEntry): String {
        val inputStream = zipFile.getInputStream(entry)
        val rawBytes = inputStream.readBytes()
        inputStream.close()
        val contentForCharset = String(rawBytes, Charsets.UTF_8)
        val charset = detectCharset(contentForCharset)
        return if (charset.lowercase().replace("-", "") == "utf8") {
            contentForCharset
        } else {
            String(rawBytes, java.nio.charset.Charset.forName(charset))
        }
    }

    private fun parseOpf(opfContent: String, opfDir: String): Triple<Map<String, EpubManifestItem>, List<EpubSpineItem>, Map<String, String>> {
        val manifestItems = mutableMapOf<String, EpubManifestItem>()
        val spineItems = mutableListOf<EpubSpineItem>()
        val metadata = mutableMapOf<String, String>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(opfContent))

            var inManifest = false
            var inSpine = false
            var inMetadata = false
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "manifest" -> inManifest = true
                            "spine" -> inSpine = true
                            "metadata" -> inMetadata = true
                            "item" -> {
                                if (inManifest) {
                                    val id = parser.getAttributeValue(null, "id") ?: ""
                                    val href = parser.getAttributeValue(null, "href") ?: ""
                                    val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                                    val properties = parser.getAttributeValue(null, "properties") ?: ""
                                    if (id.isNotEmpty() && href.isNotEmpty()) {
                                        manifestItems[id] = EpubManifestItem(id, href, mediaType, properties)
                                        if (properties.contains("cover-image") || href.lowercase().contains("cover")) {
                                            metadata["cover"] = id
                                        }
                                    }
                                }
                            }
                            "itemref" -> {
                                if (inSpine) {
                                    val idref = parser.getAttributeValue(null, "idref") ?: ""
                                    val linear = parser.getAttributeValue(null, "linear") ?: "yes"
                                    if (idref.isNotEmpty()) {
                                        spineItems.add(EpubSpineItem(idref, linear, ""))
                                    }
                                }
                            }
                            "dc:title", "title" -> {
                                if (inMetadata) {
                                    val title = parser.nextText()
                                    if (title.isNotEmpty()) metadata["title"] = title
                                }
                            }
                            "dc:creator", "creator" -> {
                                if (inMetadata) {
                                    val creator = parser.nextText()
                                    if (creator.isNotEmpty()) metadata["creator"] = creator
                                }
                            }
                            "dc:language", "language" -> {
                                if (inMetadata) {
                                    val lang = parser.nextText()
                                    if (lang.isNotEmpty()) metadata["language"] = lang
                                }
                            }
                            "dc:identifier", "identifier" -> {
                                if (inMetadata) {
                                    val id = parser.nextText()
                                    if (id.isNotEmpty()) metadata["identifier"] = id
                                }
                            }
                            "meta" -> {
                                if (inMetadata) {
                                    val name = parser.getAttributeValue(null, "name") ?: ""
                                    val content = parser.getAttributeValue(null, "content") ?: ""
                                    if (name == "cover" && content.isNotEmpty()) {
                                        metadata["cover"] = content
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "manifest" -> inManifest = false
                            "spine" -> inSpine = false
                            "metadata" -> inMetadata = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing OPF", e)
        }

        return Triple(manifestItems, spineItems, metadata)
    }

    private fun findZipEntry(zipFile: ZipFile, href: String, opfDir: String): java.util.zip.ZipEntry? {
        val resolved = resolveHref(opfDir, href)

        // 1. Direct lookup
        zipFile.getEntry(resolved)?.let { return it }

        // 2. URL-decoded lookup
        try {
            val decoded = java.net.URLDecoder.decode(resolved, "UTF-8")
            zipFile.getEntry(decoded)?.let { return it }
        } catch (_: Exception) {}

        // 3. Manual %xx decode
        val manualDecode = resolved
            .replace("%20", " ").replace("%2F", "/").replace("%2f", "/")
            .replace("%3A", ":").replace("%3a", ":").replace("%5C", "\\").replace("%5c", "\\")
        if (manualDecode != resolved) {
            zipFile.getEntry(manualDecode)?.let { return it }
        }

        // 4. Case-insensitive scan
        val targetFilename = href.substringAfterLast("/").lowercase()
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.name.substringAfterLast("/").lowercase() == targetFilename ||
                e.name.lowercase() == resolved.lowercase()) {
                return e
            }
        }

        // 5. Try with leading ./
        if (resolved.startsWith("./")) {
            zipFile.getEntry(resolved.substring(2))?.let { return it }
        }

        return null
    }

    private fun resolveHref(opfDir: String, href: String): String {
        if (href.startsWith("/")) return href.substring(1)
        if (opfDir.isEmpty()) return href
        val parts = href.split("/")
        var resolvedDir = opfDir
        for (part in parts) {
            when (part) {
                ".." -> resolvedDir = resolvedDir.substringBeforeLast("/", "")
                "." -> {}
                else -> resolvedDir = if (resolvedDir.isEmpty()) part else "$resolvedDir/$part"
            }
        }
        return resolvedDir
    }

    private fun htmlToPlainText(html: String): String {
        val text = html
            // Strip <style> tags and their content first
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<p\\s*/?>"), "\n")
            .replace(Regex("</p>"), "\n")
            .replace(Regex("<div\\s*/?>"), "\n")
            .replace(Regex("</div>"), "\n")
            .replace(Regex("<h[1-6]\\s*/?>"), "\n")
            .replace(Regex("</h[1-6]>"), "\n")
            .replace(Regex("<li\\s*/?>"), "\n• ")
            .replace(Regex("</li>"), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\n\\s*\\n"), "\n\n")
        return text.trim()
    }

    private fun detectCharset(content: String): String {
        val xmlDeclRegex = Regex("<\\?xml[^>]*encoding\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        xmlDeclRegex.find(content)?.let { return it.groupValues[1] }
        val metaCharsetRegex = Regex("<meta[^>]*charset\\s*=\\s*[\"']?([^\"'\\s>]+)", RegexOption.IGNORE_CASE)
        metaCharsetRegex.find(content)?.let { return it.groupValues[1] }
        return "UTF-8"
    }
}

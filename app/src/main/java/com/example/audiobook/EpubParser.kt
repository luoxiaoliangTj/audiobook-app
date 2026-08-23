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
     * 完整解析 EPUB，返回结构化数据
     */
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
    
    /**
     * 仅提取纯文本（兼容旧接口）
     */
    fun extractText(context: Context, contentResolver: ContentResolver, uri: Uri): String? {
        return parseEpub(context, contentResolver, uri)?.let { book ->
            book.chapters.joinToString("\n\n") { chapter ->
                chapter.title + "\n" + chapter.content ?: ""
            }
        }
    }
    
    private fun parseEpubFile(epubFile: File, originalUri: Uri): EpubBook? {
        val zipFile = ZipFile(epubFile)
        try {
            // 1. 找到 OPF 文件
            val opfEntry = zipFile.entries().asSequence()
                .firstOrNull { it.name.endsWith(".opf") }
            
            if (opfEntry == null) {
                Log.e(TAG, "No OPF file found in EPUB")
                return null
            }
            
            val opfDir = opfEntry.name.substringBeforeLast("/")
            val opfInputStream = zipFile.getInputStream(opfEntry)
            val opfContent = opfInputStream.bufferedReader().use { it.readText() }
            opfInputStream.close()
            
            // 2. 解析 OPF - manifest, spine, metadata
            val (manifestItems, spineItems, metadata) = parseOpf(opfContent, opfDir)
            
            // 3. 解析 NAV/NCX 目录结构
            val chapters = parseNavigation(zipFile, opfDir, manifestItems, spineItems)
            
            // 4. 为每个章节提取内容并生成 CFI
            val chaptersWithContent = chapters.map { chapter ->
                val content = extractChapterContent(zipFile, opfDir, manifestItems, chapter)
                val wordCount = content?.split("\\s+".toRegex())?.size ?: 0
                chapter.copy(
                    content = content,
                    wordCount = wordCount,
                    cfi = generateChapterCfi(chapter.order)
                )
            }
            
            // 5. 扁平化章节列表（用于阅读顺序）
            val flatChapters = flattenChapters(chaptersWithContent)
            
            return EpubBook(
                uri = originalUri,
                title = metadata["title"] ?: "Unknown",
                author = metadata["creator"],
                language = metadata["language"],
                identifier = metadata["identifier"],
                coverHref = metadata["cover"]?.let { manifestItems[it]?.href },
                chapters = flatChapters,
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
                                        // 记录封面
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
                                    val properties = parser.getAttributeValue(null, "properties") ?: ""
                                    if (idref.isNotEmpty()) {
                                        spineItems.add(EpubSpineItem(idref, linear, properties))
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
    
    private fun parseNavigation(
        zipFile: ZipFile,
        opfDir: String,
        manifestItems: Map<String, EpubManifestItem>,
        spineItems: List<EpubSpineItem>
    ): List<EpubChapter> {
        // 尝试解析 EPUB3 NAV (HTML格式)
        val navEntry = manifestItems.values.firstOrNull { it.properties.contains("nav") || it.href.lowercase().contains("nav") }
            ?: manifestItems.values.firstOrNull { it.mediaType == "application/xhtml+xml" && it.href.lowercase().contains("toc") }
        
        // 尝试解析 EPUB2 NCX
        val ncxEntry = manifestItems.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        
        val navChapters = if (navEntry != null) {
            parseNavDocument(zipFile, opfDir, navEntry.href)
        } else if (ncxEntry != null) {
            parseNcxDocument(zipFile, opfDir, ncxEntry.href)
        } else {
            // 回退：直接从 Spine 生成扁平章节
            spineItems.mapIndexed { index, item ->
                val manifestItem = manifestItems[item.idref]
                EpubChapter(
                    id = item.idref,
                    title = manifestItem?.href?.substringAfterLast("/")?.substringBeforeLast(".") ?: "Chapter ${index + 1}",
                    href = manifestItem?.href ?: "",
                    order = index
                )
            }
        }
        
        return navChapters
    }
    
    private fun parseNavDocument(zipFile: ZipFile, opfDir: String, navHref: String): List<EpubChapter> {
        val resolvedHref = resolveHref(opfDir, navHref)
        val entry = zipFile.getEntry(resolvedHref) ?: return emptyList()
        
        val chapters = mutableListOf<EpubChapter>()
        
        try {
            val inputStream = zipFile.getInputStream(entry)
            val content = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()
            
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(content))
            
            var inNav = false
            var inOl = false
            var currentLevel = 1
            var order = 0
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "nav" -> {
                                val epubType = parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                                if (epubType == "toc") inNav = true
                            }
                            "ol" -> if (inNav) { inOl = true; currentLevel++ }
                            "li" -> if (inNav && inOl) {
                                // 查找 a 标签
                            }
                            "a" -> if (inNav && inOl) {
                                val href = parser.getAttributeValue(null, "href") ?: ""
                                val title = parser.nextText()
                                if (title.isNotEmpty()) {
                                    chapters.add(EpubChapter(
                                        id = "nav_$order",
                                        title = title,
                                        href = href,
                                        level = currentLevel - 1,
                                        order = order++
                                    ))
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "nav" -> inNav = false
                            "ol" -> if (inNav) { inOl = false; currentLevel-- }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NAV document", e)
        }
        
        return chapters
    }
    
    private fun parseNcxDocument(zipFile: ZipFile, opfDir: String, ncxHref: String): List<EpubChapter> {
        val resolvedHref = resolveHref(opfDir, ncxHref)
        val entry = zipFile.getEntry(resolvedHref) ?: return emptyList()
        
        val chapters = mutableListOf<EpubChapter>()
        
        try {
            val inputStream = zipFile.getInputStream(entry)
            val content = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()
            
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(content))
            
            var inNavMap = false
            var inNavPoint = false
            var currentLevel = 1
            var order = 0
            var currentTitle = ""
            var currentHref = ""
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "navMap" -> inNavMap = true
                            "navPoint" -> if (inNavMap) { inNavPoint = true; currentLevel++ }
                            "navLabel" -> if (inNavPoint) { /* skip */ }
                            "text" -> if (inNavPoint) {
                                currentTitle = parser.nextText()
                            }
                            "content" -> if (inNavPoint) {
                                currentHref = parser.getAttributeValue(null, "src") ?: ""
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "navMap" -> inNavMap = false
                            "navPoint" -> if (inNavMap) {
                                inNavPoint = false
                                currentLevel--
                                if (currentTitle.isNotEmpty()) {
                                    chapters.add(EpubChapter(
                                        id = "ncx_$order",
                                        title = currentTitle,
                                        href = currentHref,
                                        level = currentLevel,
                                        order = order++
                                    ))
                                }
                                currentTitle = ""
                                currentHref = ""
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NCX document", e)
        }
        
        return chapters
    }
    
    private fun extractChapterContent(
        zipFile: ZipFile,
        opfDir: String,
        manifestItems: Map<String, EpubManifestItem>,
        chapter: EpubChapter
    ): String? {
        val href = chapter.href
        if (href.isEmpty()) return null
        
        val resolvedHref = resolveHref(opfDir, href)
        val entry = zipFile.getEntry(resolvedHref) ?: return null
        
        try {
            val inputStream = zipFile.getInputStream(entry)
            val htmlContent = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()
            return htmlToPlainText(htmlContent)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting chapter content: $resolvedHref", e)
            return null
        }
    }
    
    private fun resolveHref(opfDir: String, href: String): String {
        if (href.startsWith("/")) return href.substring(1)
        if (opfDir.isEmpty()) return href
        // 处理相对路径
        val parts = href.split("/")
        var resolvedDir = opfDir
        for (part in parts) {
            when (part) {
                ".." -> resolvedDir = resolvedDir.substringBeforeLast("/")
                "." -> {}
                else -> resolvedDir = if (resolvedDir.isEmpty()) part else "$resolvedDir/$part"
            }
        }
        return resolvedDir
    }
    
    private fun flattenChapters(chapters: List<EpubChapter>): List<EpubChapter> {
        val flat = mutableListOf<EpubChapter>()
        fun recurse(chaps: List<EpubChapter>, baseOrder: Int) {
            chaps.forEachIndexed { index, chapter ->
                val newOrder = baseOrder + index
                flat.add(chapter.copy(order = newOrder))
                if (chapter.children.isNotEmpty()) {
                    recurse(chapter.children, newOrder + 1)
                }
            }
        }
        recurse(chapters, 0)
        return flat
    }
    
    private fun generateChapterCfi(order: Int): String {
        // 简化的 CFI 生成：epubcfi(/6/$order)
        return "epubcfi(/6/${order * 2 + 4})"
    }
    
    private fun htmlToPlainText(html: String): String {
        return html
            .replace("<script[^>]*>.*?</script>".toRegex(), "")  // Remove scripts
            .replace("<style[^>]*>.*?</style>".toRegex(), "")   // Remove styles
            .replace("<[^>]+>".toRegex(), " ")  // Remove HTML tags
            .replace("&nbsp;".toRegex(), " ")   // Replace &nbsp; with space
            .replace("&".toRegex(), "&")    // Replace & with &
            .replace("<".toRegex(), "<")     // Replace < with <
            .replace(">".toRegex(), ">")     // Replace > with >
            .replace("\\\\\\\"".toRegex(), "\\\"")  // Replace " with "
            .replace("'".toRegex(), "'")    // Replace ' with '
            .replace("\\s+".toRegex(), " ")     // Normalize whitespace
            .trim()
    }
}
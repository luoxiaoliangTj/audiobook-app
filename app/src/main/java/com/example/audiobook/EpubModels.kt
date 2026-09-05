package com.example.audiobook

import android.net.Uri

/**
 * EPUB 解析结果数据模型
 */
data class EpubBook(
    val uri: Uri,
    val title: String,
    val author: String?,
    val language: String?,
    val identifier: String?,
    val coverHref: String?,
    val chapters: List<EpubChapter>,
    val spine: List<EpubSpineItem>,
    val manifest: Map<String, EpubManifestItem>,
    val metadata: Map<String, String>
)

data class EpubChapter(
    val id: String,
    val title: String,
    val href: String,
    val level: Int = 1,           // 目录层级
    val order: Int,               // 阅读顺序
    val children: List<EpubChapter> = emptyList(),  // 子章节
    val cfi: String = "",         // 章节起始 CFI
    val wordCount: Int = 0,
    val content: String? = null,  // 章节纯文本内容
    val htmlContent: String? = null
)

data class EpubSpineItem(
    val idref: String,
    val linear: String = "yes",   // yes/no - 是否在正常阅读顺序中
    val properties: String = ""
)

data class EpubManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String = ""
)

/**
 * EPUB 内容片段（用于 TTS 分句）
 */
data class EpubChunk(
    val bookId: String,
    val chapterId: String,
    val chapterOrder: Int,
    val chunkIndex: Int,
    val text: String,
    val startOffset: Int,         // 章节内字符偏移
    val endOffset: Int,
    val cfi: String = ""          // 片段 CFI
)
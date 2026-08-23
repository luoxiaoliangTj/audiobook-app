package com.example.audiobook.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "books",
    indices = [Index(value = ["uri"], unique = true)]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,                    // 文件 URI (toString)
    val title: String,                  // 书名
    val author: String?,                // 作者
    val language: String?,              // 语言
    val identifier: String?,            // ISBN/UUID
    val coverPath: String?,             // 封面图片本地路径
    val fileType: String,               // "epub" / "txt"
    val totalChapters: Int = 0,         // 总章节数
    val totalChunks: Int = 0,           // 总分段数
    val totalWords: Long = 0,           // 总字数
    val lastReadAt: Long = System.currentTimeMillis(),  // 最后阅读时间
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapters",
    indices = [Index(value = ["bookId", "chapterOrder"], unique = true)]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,                   // 关联 BookEntity.id
    val chapterId: String,              // EPUB 内部章节 ID
    val title: String,                  // 章节标题
    val href: String,                   // 原始 href
    val chapterOrder: Int,              // 阅读顺序
    val level: Int = 1,                 // 目录层级
    val startChunk: Int = -1,           // 起始分段索引
    val endChunk: Int = -1,             // 结束分段索引
    val wordCount: Int = 0,             // 字数
    val cfi: String = "",               // 章节 CFI
    val content: String? = null         // 章节纯文本内容（可选，大文本建议单独存）
)

@Entity(
    tableName = "reading_progress",
    indices = [Index(value = ["bookId"], unique = true)]
)
data class ReadingProgressEntity(
    @PrimaryKey val bookId: Long,       // 关联 BookEntity.id
    val currentChapterIndex: Int = 0,   // 当前章节索引
    val currentChunkIndex: Int = 0,     // 当前分段索引
    val charOffset: Int = 0,            // 章节内字符偏移
    val cfi: String = "",               // 精确 CFI 位置
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["bookId", "chapterIndex", "chunkIndex"], unique = true),
        Index(value = ["createdAt"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,                   // 关联 BookEntity.id
    val chapterIndex: Int,              // 章节索引
    val chunkIndex: Int,                // 分段索引
    val charOffset: Int = 0,            // 字符偏移
    val cfi: String = "",               // CFI 位置
    val note: String = "",              // 备注/笔记
    val createdAt: Long = System.currentTimeMillis()
)
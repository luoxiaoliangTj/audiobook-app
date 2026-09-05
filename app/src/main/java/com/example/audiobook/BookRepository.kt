package com.example.audiobook.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.asLiveData
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookRepository(private val db: AppDatabase) {
    
    // Books
    suspend fun insertBook(book: BookEntity): Long = db.bookDao().insert(book)
    
    suspend fun updateBook(book: BookEntity) = db.bookDao().update(book)
    
    suspend fun getBookById(id: Long): BookEntity? = db.bookDao().getByIdSuspend(id)
    
    suspend fun getBookByUri(uri: String): BookEntity? = db.bookDao().getByUriSuspend(uri)
    
    fun getAllBooks(): LiveData<List<BookEntity>> = db.bookDao().getAllBooks().asLiveData(Dispatchers.IO)
    
    suspend fun deleteBook(id: Long) = db.bookDao().deleteById(id)
    
    suspend fun updateLastRead(bookId: Long) = db.bookDao().updateLastRead(bookId, System.currentTimeMillis())
    
    // Chapters
    suspend fun insertChapters(chapters: List<ChapterEntity>) = db.chapterDao().insertAll(chapters)
    
    suspend fun getChaptersByBookId(bookId: Long): List<ChapterEntity> = db.chapterDao().getByBookIdSuspend(bookId)
    
    fun observeChaptersByBookId(bookId: Long): LiveData<List<ChapterEntity>> = db.chapterDao().getByBookId(bookId).asLiveData(Dispatchers.IO)
    
    // Reading Progress
    suspend fun saveProgress(progress: ReadingProgressEntity) = db.readingProgressDao().insert(progress)
    
    suspend fun getProgress(bookId: Long): ReadingProgressEntity? = db.readingProgressDao().getByBookIdSuspend(bookId)
    
    fun observeProgress(bookId: Long): LiveData<ReadingProgressEntity?> = db.readingProgressDao().getByBookId(bookId).asLiveData(Dispatchers.IO)
    
    // Bookmarks
    suspend fun addBookmark(bookmark: BookmarkEntity): Long = db.bookmarkDao().insert(bookmark)
    
    suspend fun updateBookmark(bookmark: BookmarkEntity) = db.bookmarkDao().update(bookmark)
    
    suspend fun deleteBookmark(id: Long) = db.bookmarkDao().deleteById(id)
    
    suspend fun getBookmarks(bookId: Long): List<BookmarkEntity> = db.bookmarkDao().getByBookIdSuspend(bookId)
    
    fun observeBookmarks(bookId: Long): LiveData<List<BookmarkEntity>> = db.bookmarkDao().getByBookId(bookId).asLiveData(Dispatchers.IO)
    
    // High-level operations
    suspend fun saveBookWithChapters(book: BookEntity, chapters: List<ChapterEntity>) {
        val bookId = db.bookDao().insert(book)
        val chaptersWithBookId = chapters.map { it.copy(bookId = bookId) }
        db.chapterDao().insertAll(chaptersWithBookId)
    }
    
    suspend fun saveReadingState(
        bookId: Long,
        chapterIndex: Int,
        chunkIndex: Int,
        charOffset: Int,
        cfi: String
    ) {
        val progress = ReadingProgressEntity(
            bookId = bookId,
            currentChapterIndex = chapterIndex,
            currentChunkIndex = chunkIndex,
            charOffset = charOffset,
            cfi = cfi
        )
        db.readingProgressDao().insert(progress)
        db.bookDao().updateLastRead(bookId, System.currentTimeMillis())
    }
    
    companion object {
        @Volatile
        private var INSTANCE: BookRepository? = null
        
        fun getInstance(context: Context): BookRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = BookRepository(AppDatabase.getInstance(context))
                INSTANCE = instance
                instance
            }
        }
    }
}
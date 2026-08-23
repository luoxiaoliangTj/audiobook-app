package com.example.audiobook.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long
    
    @Update
    suspend fun update(book: BookEntity): Int
    
    @Query("SELECT * FROM books WHERE id = :id")
    fun getById(id: Long): Flow<BookEntity?>
    
    @Query("SELECT * FROM books WHERE uri = :uri")
    fun getByUri(uri: String): Flow<BookEntity?>
    
    @Query("SELECT * FROM books WHERE uri = :uri")
    suspend fun getByUriSuspend(uri: String): BookEntity?
    
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>
    
    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getByIdSuspend(id: Long): BookEntity?
    
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long): Int
    
    @Query("UPDATE books SET lastReadAt = :time, updatedAt = :time WHERE id = :id")
    suspend fun updateLastRead(id: Long, time: Long): Int
}

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chapter: ChapterEntity): Long
    
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterOrder ASC")
    fun getByBookId(bookId: Long): Flow<List<ChapterEntity>>
    
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterOrder ASC")
    suspend fun getByBookIdSuspend(bookId: Long): List<ChapterEntity>
    
    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getById(id: Long): ChapterEntity?
    
    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long): Int
}

@Dao
interface ReadingProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: ReadingProgressEntity): Long
    
    @Update
    suspend fun update(progress: ReadingProgressEntity): Int
    
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun getByBookId(bookId: Long): Flow<ReadingProgressEntity?>
    
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun getByBookIdSuspend(bookId: Long): ReadingProgressEntity?
    
    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long): Int
}

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<BookmarkEntity>): List<Long>
    
    @Update
    suspend fun update(bookmark: BookmarkEntity): Int
    
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun getByBookId(bookId: Long): Flow<List<BookmarkEntity>>
    
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getByBookIdSuspend(bookId: Long): List<BookmarkEntity>
    
    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getById(id: Long): BookmarkEntity?
    
    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long): Int
    
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long): Int
}
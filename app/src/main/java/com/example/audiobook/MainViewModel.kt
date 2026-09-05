package com.example.audiobook.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.audiobook.data.AppDatabase
import com.example.audiobook.data.BookEntity
import com.example.audiobook.data.BookRepository
import com.example.audiobook.data.ChapterEntity
import com.example.audiobook.data.ReadingProgressEntity
import com.example.audiobook.EpubBook
import com.example.audiobook.EpubChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = BookRepository.getInstance(application)
    private val db = AppDatabase.getInstance(application)
    
    // UI State
    private val _books = MutableLiveData<List<BookEntity>>()
    val books: LiveData<List<BookEntity>> = _books
    
    private val _currentBook = MutableLiveData<BookEntity?>()
    val currentBook: LiveData<BookEntity?> = _currentBook
    
    private val _chapters = MutableLiveData<List<ChapterEntity>>()
    val chapters: LiveData<List<ChapterEntity>> = _chapters
    
    private val _currentProgress = MutableLiveData<ReadingProgressEntity?>()
    val currentProgress: LiveData<ReadingProgressEntity?> = _currentProgress
    
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading
    
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error
    
    init {
        loadBooks()
    }
    
    fun loadBooks() {
        viewModelScope.launch {
            _isLoading.postValue(true)
            try {
                val bookList = repository.getAllBooks().value
                _books.postValue(bookList ?: emptyList())
            } catch (e: Exception) {
                _error.postValue("加载书籍失败: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }
    
    fun openBook(uri: String) {
        viewModelScope.launch {
            _isLoading.postValue(true)
            try {
                val book = repository.getBookByUri(uri)
                if (book != null) {
                    _currentBook.postValue(book)
                    loadChapters(book.id)
                    loadProgress(book.id)
                } else {
                    _error.postValue("书籍未找到，请重新导入")
                }
            } catch (e: Exception) {
                _error.postValue("打开书籍失败: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }
    
    fun loadChapters(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chapterList = repository.getChaptersByBookId(bookId)
                _chapters.postValue(chapterList)
            } catch (e: Exception) {
                _error.postValue("加载章节失败: ${e.message}")
            }
        }
    }
    
    fun loadProgress(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val progress = repository.getProgress(bookId)
                _currentProgress.postValue(progress)
            } catch (e: Exception) {
                _error.postValue("加载进度失败: ${e.message}")
            }
        }
    }
    
    // 导入新书籍
    fun importBook(epubBook: EpubBook) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.postValue(true)
            try {
                // Convert to entities
                val bookEntity = BookEntity(
                    uri = epubBook.uri.toString(),
                    title = epubBook.title,
                    author = epubBook.author,
                    language = epubBook.language,
                    identifier = epubBook.identifier,
                    coverPath = epubBook.coverHref,
                    fileType = "epub",
                    totalChapters = epubBook.chapters.size,
                    totalChunks = epubBook.chapters.sumOf { (it.content ?: "").length / 1000 + 1 },
                    totalWords = epubBook.chapters.sumOf { it.wordCount.toLong() }
                )
                
                val chapterEntities = epubBook.chapters.mapIndexed { index, chapter ->
                    ChapterEntity(
                        bookId = 0, // Will be set after book insert
                        chapterId = chapter.id,
                        title = chapter.title,
                        href = chapter.href,
                        chapterOrder = chapter.order,
                        level = chapter.level,
                        wordCount = chapter.wordCount,
                        cfi = chapter.cfi,
                        content = chapter.content
                    )
                }
                
                repository.saveBookWithChapters(bookEntity, chapterEntities)
                loadBooks()
                _error.postValue("导入成功: ${epubBook.title}")
            } catch (e: Exception) {
                _error.postValue("导入失败: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }
    
    // 保存阅读进度
    fun saveProgress(chapterIndex: Int, chunkIndex: Int, charOffset: Int, cfi: String) {
        _currentBook.value?.let { book ->
            viewModelScope.launch(Dispatchers.IO) {
                repository.saveReadingState(book.id, chapterIndex, chunkIndex, charOffset, cfi)
            }
        }
    }
    
    // 添加书签
    fun addBookmark(chapterIndex: Int, chunkIndex: Int, charOffset: Int, cfi: String, note: String) {
        _currentBook.value?.let { book ->
            viewModelScope.launch(Dispatchers.IO) {
                // TODO: implement bookmark insert via repository
            }
        }
    }
    
    // 清除错误
    fun clearError() {
        _error.postValue(null)
    }
}
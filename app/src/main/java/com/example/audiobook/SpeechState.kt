package com.example.audiobook

/**
 * Singleton to hold speech state across activity and service.
 */
object SpeechState {
    var chunks: List<String> = emptyList()
    var currentIndex: Int = 0
    var isPlaying: Boolean = false
    
    // Chapter tracking
    var currentChapterIndex: Int = 0
    var chapterChunkRanges: List<Pair<Int, Int>> = emptyList()  // (startChunk, endChunk) per chapter
    
    // Current book info
    var currentBook: EpubBook? = null
    
    fun getCurrentChapter(): EpubChapter? {
        return currentBook?.chapters?.getOrNull(currentChapterIndex)
    }
    
    fun getProgressInChapter(): Pair<Int, Int> {
        val chapter = getCurrentChapter()
        val range = chapterChunkRanges.getOrNull(currentChapterIndex) ?: Pair(0, 0)
        val current = currentIndex
        val start = range.first
        val end = range.second
        if (start == -1 || end == -1 || end < start) return Pair(0, 0)
        val progress = (current - start).coerceAtLeast(0).coerceAtMost(end - start + 1)
        val total = end - start + 1
        return Pair(progress, total)
    }
    
    fun getOverallProgress(): Pair<Int, Int> {
        return Pair(currentIndex.coerceAtLeast(0), chunks.size)
    }
    
    fun reset() {
        chunks = emptyList()
        currentIndex = 0
        isPlaying = false
        currentChapterIndex = 0
        chapterChunkRanges = emptyList()
        currentBook = null
    }
}
package com.example.audiobook

data class Bookmark(
    val id: Long = System.currentTimeMillis(),
    val bookTitle: String,
    val page: Int,
    val note: String,
    val timestamp: Long = System.currentTimeMillis()
)

object BookmarkManager {
    private val bookmarks = mutableListOf<Bookmark>()

    fun addBookmark(bm: Bookmark) { bookmarks.add(bm) }
    fun removeBookmark(id: Long) { bookmarks.removeAll { it.id == id } }
    fun getBookmarks(bookTitle: String): List<Bookmark> =
        bookmarks.filter { it.bookTitle == bookTitle }.sortedBy { it.page }

    fun exportBookmarksToMarkdown(bookTitle: String): String {
        val sb = StringBuilder()
        sb.appendLine("# $bookTitle - 书签")
        sb.appendLine()
        for (bm in getBookmarks(bookTitle)) {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(bm.timestamp))
            sb.appendLine("- **第${bm.page + 1}页** (${date}): ${bm.note}")
        }
        return sb.toString()
    }
}

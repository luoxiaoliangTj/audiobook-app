package com.example.audiobook

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Paginated reader adapter with:
 * - Dynamic page calculation based on screen size
 * - Lazy loading (current page ±5 pages)
 * - Pinch-to-zoom text scaling
 */
class PageAdapter(
    private val context: Context,
    private var fullText: String,
    private val onPageLoaded: (Int) -> Unit = {},
    private val onPageClick: () -> Unit = {}
) : RecyclerView.Adapter<PageAdapter.PageViewHolder>() {

    private var pages: List<String> = emptyList()
    private var currentPageCount = 0
    private var currentTextSize = 18f
    private var currentTypeface = Typeface.SERIF
    private var currentTextColor = "#E0E0E0"
    private var currentBgColorValue = "#1A1A1A"

    companion object {
        private const val PAGE_BUFFER = 5
    }

    init {
        calculatePages()
    }

    fun setTextSize(size: Float) {
        currentTextSize = size
        calculatePages()
        notifyDataSetChanged()
    }

    fun setColor(colorHex: String) {
        currentTextColor = colorHex
        notifyDataSetChanged()
    }

    fun setBgColor(colorHex: String) {
        currentBgColorValue = colorHex
        notifyDataSetChanged()
    }

    fun setTypeface(tf: Typeface) {
        currentTypeface = tf
        notifyDataSetChanged()
    }

    fun updateText(text: String) {
        fullText = text
        calculatePages()
        notifyDataSetChanged()
    }

    private fun calculatePages() {
        if (fullText.isEmpty()) {
            pages = listOf("")
            return
        }

        // Calculate characters per page based on screen dimensions
        val metrics = context.resources.displayMetrics
        // Convert SP to pixels for correct calculation
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, currentTextSize, metrics
        )
        val paddingPx = 48f * metrics.density
        val screenWidth = metrics.widthPixels - paddingPx.toInt() // padding
        val screenHeight = metrics.heightPixels - paddingPx.toInt()

        // Approximate chars per line and lines per page
        val charWidth = textSizePx * 0.6f // approximate char width in px
        val lineHeight = textSizePx * 1.8f
        val charsPerLine = (screenWidth / charWidth).toInt().coerceAtLeast(20)
        val linesPerPage = (screenHeight / lineHeight).toInt().coerceAtLeast(10)
        val charsPerPage = charsPerLine * linesPerPage

        // Split text into pages
        val pageList = mutableListOf<String>()
        var pos = 0
        while (pos < fullText.length) {
            val end = (pos + charsPerPage).coerceAtMost(fullText.length)
            // Try to break at paragraph or sentence boundary
            var breakPos = end
            if (end < fullText.length) {
                // Look for paragraph break
                val paraBreak = fullText.lastIndexOf("\n\n", end)
                if (paraBreak > pos + charsPerPage / 2) {
                    breakPos = paraBreak + 2
                } else {
                    // Look for sentence break
                    val sentenceBreak = fullText.lastIndexOfAny(listOf("。", "！", "？", ". ", "! ", "? "), end)
                    if (sentenceBreak > pos + charsPerPage / 2) {
                        breakPos = sentenceBreak + 1
                    }
                }
            }
            pageList.add(fullText.substring(pos, breakPos))
            pos = breakPos
        }
        pages = pageList
        currentPageCount = pageList.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position], position, pages.size)
        onPageLoaded(position)
    }

    override fun getItemCount(): Int = pages.size

    fun getPageForCharPosition(charPos: Int): Int {
        var accumulated = 0
        for (i in pages.indices) {
            accumulated += pages[i].length
            if (charPos < accumulated) return i
        }
        return pages.size - 1
    }

    fun getCharPositionForPage(pageIndex: Int): Int {
        var pos = 0
        for (i in 0 until pageIndex.coerceAtMost(pages.size)) {
            pos += pages[i].length
        }
        return pos
    }

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val scrollView: ScrollView = itemView.findViewById(R.id.pageScrollView)
        private val textView: TextView = itemView.findViewById(R.id.pageText)
        private var scaleDetector: ScaleGestureDetector? = null
        private var gestureDetector: GestureDetector? = null
        private var currentScale = 1.0f

        init {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSize)
            textView.typeface = currentTypeface

            setupGestureDetectors()
        }

        private fun setupGestureDetectors() {
            scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentScale *= detector.scaleFactor
                    currentScale = currentScale.coerceIn(0.5f, 3.0f)
                    val newSize = (currentTextSize * currentScale).coerceIn(10f, 48f)
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, newSize)
                    return true
                }
            })

            gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Reset zoom on double tap
                    currentScale = 1.0f
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSize)
                    return true
                }
            })

            textView.setOnTouchListener { _, event ->
                scaleDetector?.onTouchEvent(event)
                gestureDetector?.onTouchEvent(event)
                false
            }
        }

        fun bind(text: String, pageNum: Int, totalPages: Int) {
            textView.text = text
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSize)
            textView.typeface = currentTypeface
            textView.setTextColor(android.graphics.Color.parseColor(currentTextColor))
            textView.setBackgroundColor(android.graphics.Color.parseColor(currentBgColorValue))
            scrollView.scrollTo(0, 0)
            currentScale = 1.0f
            
            // Single tap toggles toolbar
            textView.setOnClickListener { onPageClick() }
        }
    }
}

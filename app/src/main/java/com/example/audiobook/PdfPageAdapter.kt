package com.example.audiobook

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference

/**
 * ViewPager2 adapter for PDF pages. Each page is a ZoomableImageView.
 * Uses WeakReference to avoid holding bitmaps in memory.
 */
class PdfPageAdapter(
    private val renderer: android.graphics.pdf.PdfRenderer,
    private val onPageClick: () -> Unit = {}
) : RecyclerView.Adapter<PdfPageAdapter.PdfPageViewHolder>() {

    private val bitmaps = mutableMapOf<Int, Bitmap>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return PdfPageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
        val bitmap = getBitmapForPage(position)
        holder.bind(bitmap, onPageClick)
    }

    override fun getItemCount(): Int = renderer.pageCount

    private fun getBitmapForPage(position: Int): Bitmap {
        return bitmaps.getOrPut(position) {
            val page = renderer.openPage(position)
            val bitmap = Bitmap.createBitmap(
                page.width * 2,
                page.height * 2,
                Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        }
    }

    inner class PdfPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ZoomableImageView = itemView.findViewById(R.id.pdfPageImage)

        fun bind(bitmap: Bitmap, onClick: () -> Unit) {
            imageView.setImageBitmap(bitmap)
            imageView.setOnClickListener { onClick() }
        }
    }
}

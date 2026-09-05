package com.example.audiobook

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val minScale = 1.0f
    private val maxScale = 5.0f
    private var currentScale = 1.0f
    private val imgMatrix = Matrix()

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleType = ScaleType.MATRIX
        setLayerType(LAYER_TYPE_HARDWARE, null)

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val d = detector.scaleFactor
                currentScale = (currentScale * d).coerceIn(minScale, maxScale)
                imgMatrix.postScale(d, d, detector.focusX, detector.focusY)
                fixTranslation()
                imageMatrix = imgMatrix
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetZoom()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (currentScale > minScale) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    imgMatrix.postTranslate(-distanceX, -distanceY)
                    fixTranslation()
                    imageMatrix = imgMatrix
                    return true
                }
                return false
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return true
    }

    private fun fixTranslation() {
        val values = FloatArray(9)
        imgMatrix.getValues(values)
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawableWidth = drawable?.intrinsicWidth?.toFloat() ?: 0f
        val drawableHeight = drawable?.intrinsicHeight?.toFloat() ?: 0f

        val scaledWidth = drawableWidth * currentScale
        val scaledHeight = drawableHeight * currentScale

        var fixX = 0f
        var fixY = 0f

        if (scaledWidth <= viewWidth) {
            fixX = (viewWidth - scaledWidth) / 2 - transX
        } else {
            if (transX > 0) fixX = -transX
            else if (transX < viewWidth - scaledWidth) fixX = viewWidth - scaledWidth - transX
        }

        if (scaledHeight <= viewHeight) {
            fixY = (viewHeight - scaledHeight) / 2 - transY
        } else {
            if (transY > 0) fixY = -transY
            else if (transY < viewHeight - scaledHeight) fixY = viewHeight - scaledHeight - transY
        }

        if (fixX != 0f || fixY != 0f) {
            imgMatrix.postTranslate(fixX, fixY)
        }
    }

    fun resetZoom() {
        currentScale = minScale
        post {
            if (width > 0 && height > 0 && drawable != null) {
                val drawableWidth = drawable.intrinsicWidth.toFloat()
                val drawableHeight = drawable.intrinsicHeight.toFloat()
                val viewWidth = width.toFloat()
                val viewHeight = height.toFloat()

                val scale = minOf(viewWidth / drawableWidth, viewHeight / drawableHeight)
                val dx = (viewWidth - drawableWidth * scale) / 2
                val dy = (viewHeight - drawableHeight * scale) / 2

                imgMatrix.reset()
                imgMatrix.postScale(scale, scale)
                imgMatrix.postTranslate(dx, dy)
                currentScale = scale
                imageMatrix = imgMatrix
            }
        }
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post {
            if (width > 0 && height > 0 && bm != null) {
                resetZoom()
            }
        }
    }
}

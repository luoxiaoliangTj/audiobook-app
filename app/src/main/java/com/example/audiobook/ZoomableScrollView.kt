package com.example.audiobook

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.widget.ScrollView
import android.graphics.Matrix

/**
 * ScrollView that detects multi-touch (pinch-zoom) and doesn't intercept it.
 * This allows child views to handle zoom gestures properly.
 */
class ZoomableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {

    private var isZooming = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // When zoomed in or multi-touch detected, don't intercept
        if (isZooming) {
            return false
        }
        
        // Detect multi-touch (pinch gesture starting)
        if (ev.pointerCount >= 2) {
            isZooming = true
            return false
        }
        
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) {
            isZooming = true
            return false
        }
        
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isZooming = false
            }
        }
        
        return super.onTouchEvent(ev)
    }
}

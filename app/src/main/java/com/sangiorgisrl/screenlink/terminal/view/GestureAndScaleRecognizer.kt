package com.sangiorgisrl.screenlink.terminal.view

/*
 * Derived from the terminal-view module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/** One [GestureDetector] and one [ScaleGestureDetector] fed from the same touch stream. */
class GestureAndScaleRecognizer(context: Context, private val listener: Listener) {

    interface Listener {
        fun onSingleTapUp(e: MotionEvent): Boolean

        fun onDoubleTap(e: MotionEvent): Boolean

        fun onScroll(e2: MotionEvent, dx: Float, dy: Float): Boolean

        fun onFling(e: MotionEvent, velocityX: Float, velocityY: Float): Boolean

        fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean

        fun onDown(x: Float, y: Float): Boolean

        fun onUp(e: MotionEvent): Boolean

        fun onLongPress(e: MotionEvent)
    }

    private var isAfterLongPress = false

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean =
            listener.onScroll(e2, dx, dy)

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean =
            listener.onFling(e2, velocityX, velocityY)

        override fun onDown(e: MotionEvent): Boolean = listener.onDown(e.x, e.y)

        override fun onLongPress(e: MotionEvent) {
            listener.onLongPress(e)
            isAfterLongPress = true
        }
    }, null, true /* ignoreMultitouch */)

    init {
        gestureDetector.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean = listener.onSingleTapUp(e)

            override fun onDoubleTap(e: MotionEvent): Boolean = listener.onDoubleTap(e)

            override fun onDoubleTapEvent(e: MotionEvent): Boolean = true
        })
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

        override fun onScale(detector: ScaleGestureDetector): Boolean =
            listener.onScale(detector.focusX, detector.focusY, detector.scaleFactor)
    }).apply { setQuickScaleEnabled(false) }

    fun onTouchEvent(event: MotionEvent) {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> isAfterLongPress = false

            MotionEvent.ACTION_UP -> if (!isAfterLongPress) {
                // Wanted e.g. in vim with mouse events on: lifting the finger after a long
                // press must not move the cursor.
                listener.onUp(event)
            }
        }
    }

    fun isInProgress(): Boolean = scaleDetector.isInProgress
}

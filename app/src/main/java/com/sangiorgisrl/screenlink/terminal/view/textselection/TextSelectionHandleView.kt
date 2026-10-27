package com.sangiorgisrl.screenlink.terminal.view.textselection

/*
 * Derived from the terminal-view module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.appcompat.content.res.AppCompatResources
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.terminal.view.TerminalView

/** The draggable handle that marks one end of a terminal text selection. */
@SuppressLint("ViewConstructor")
class TextSelectionHandleView(
    private val terminalView: TerminalView,
    private val cursorController: CursorController,
    private val initialOrientation: Int,
) : View(terminalView.context) {

    companion object {
        const val LEFT = 0
        const val RIGHT = 2
    }

    private var handle: PopupWindow? = null

    // Loaded through AppCompatResources, which caches the drawable instead of
    // inflating it per call. (This lint check only appeared once ML Kit's `common`
    // put appcompat on the classpath; the handles themselves are ours.)
    private val handleLeftDrawable: Drawable? =
        AppCompatResources.getDrawable(context, R.drawable.text_select_handle_left_material)
    private val handleRightDrawable: Drawable? =
        AppCompatResources.getDrawable(context, R.drawable.text_select_handle_right_material)
    private lateinit var handleDrawable: Drawable

    var isDragging = false
        private set

    private val tempCoords = IntArray(2)
    private var tempRect: Rect? = null

    private var pointX = 0
    private var pointY = 0
    private var touchToWindowOffsetX = 0f
    private var touchToWindowOffsetY = 0f
    private var hotspotX = 0f
    private var hotspotY = 0f
    private var touchOffsetY = 0f
    private var lastParentX = 0
    private var lastParentY = 0

    var handleHeight = 0
        private set

    var handleWidth = 0
        private set

    private var orientation = 0

    private var lastTime = 0L

    init {
        setOrientation(initialOrientation)
    }

    private fun initHandle() {
        handle = PopupWindow(terminalView.context, null, android.R.attr.textSelectHandleWindowStyle).apply {
            isSplitTouchEnabled = true
            isClippingEnabled = false
            windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setBackgroundDrawable(null)
            animationStyle = 0
            enterTransition = null
            exitTransition = null
            contentView = this@TextSelectionHandleView
        }
    }

    fun setOrientation(orientation: Int) {
        this.orientation = orientation
        var measuredWidth = 0
        when (orientation) {
            LEFT -> {
                handleDrawable = handleLeftDrawable!!
                measuredWidth = handleDrawable.intrinsicWidth
                hotspotX = measuredWidth * 3 / 4f
            }

            RIGHT -> {
                handleDrawable = handleRightDrawable!!
                measuredWidth = handleDrawable.intrinsicWidth
                hotspotX = measuredWidth / 4f
            }
        }

        handleHeight = handleDrawable.intrinsicHeight
        handleWidth = measuredWidth
        touchOffsetY = -handleHeight * 0.3f
        hotspotY = 0f
        invalidate()
    }

    fun show() {
        if (!isPositionVisible()) {
            hide()
            return
        }

        // Detach the handle from its parent first, or this blows up with
        // java.lang.IllegalStateException: The specified child already has a parent.
        // You must call removeView() on the child's parent first.
        removeFromParent()

        initHandle()
        invalidate() // make sure onDraw runs

        val coords = tempCoords
        terminalView.getLocationInWindow(coords)
        coords[0] += pointX
        coords[1] += pointY

        handle?.showAtLocation(terminalView, 0, coords[0], coords[1])
    }

    fun hide() {
        isDragging = false

        handle?.let {
            it.dismiss()
            // Detach as well: some cases keep showing the view after dismiss() otherwise.
            removeFromParent()
            handle = null // let the popup be collected
        }
        invalidate()
    }

    fun removeFromParent() {
        if (!isParentNull()) {
            (parent as ViewGroup).removeView(this)
        }
    }

    fun positionAtCursor(cx: Int, cy: Int, forceOrientationCheck: Boolean) {
        val x = terminalView.getPointX(cx)
        val y = terminalView.getPointY(cy + 1)
        moveTo(x, y, forceOrientationCheck)
    }

    private fun moveTo(x: Int, y: Int, forceOrientationCheck: Boolean) {
        val oldHotspotX = hotspotX
        checkChangedOrientation(x, forceOrientationCheck)
        pointX = (x - (if (isShowing()) oldHotspotX else hotspotX)).toInt()
        pointY = y

        if (!isPositionVisible()) {
            hide()
            return
        }

        var coords: IntArray? = null

        if (isShowing()) {
            coords = tempCoords
            terminalView.getLocationInWindow(coords)
            val x1 = coords[0] + pointX
            val y1 = coords[1] + pointY
            handle?.update(x1, y1, width, height)
        } else {
            show()
        }

        if (isDragging) {
            if (coords == null) {
                coords = tempCoords
                terminalView.getLocationInWindow(coords)
            }
            if (coords[0] != lastParentX || coords[1] != lastParentY) {
                touchToWindowOffsetX += coords[0] - lastParentX
                touchToWindowOffsetY += coords[1] - lastParentY
                lastParentX = coords[0]
                lastParentY = coords[1]
            }
        }
    }

    fun changeOrientation(orientation: Int) {
        if (this.orientation != orientation) {
            setOrientation(orientation)
        }
    }

    private fun checkChangedOrientation(posX: Int, force: Boolean) {
        if (!isDragging && !force) {
            return
        }
        val millis = SystemClock.currentThreadTimeMillis()
        if (millis - lastTime < 50 && !force) {
            return
        }
        lastTime = millis

        val hostView = terminalView
        val left = hostView.left
        val right = hostView.width
        val top = hostView.top
        val bottom = hostView.height

        val clip = tempRect ?: Rect().also { tempRect = it }
        clip.left = left + hostView.paddingLeft
        clip.top = top + hostView.paddingTop
        clip.right = right - hostView.paddingRight
        clip.bottom = bottom - hostView.paddingBottom

        val parent = hostView.parent
        if (parent == null || !parent.getChildVisibleRect(hostView, clip, null)) {
            return
        }

        if (posX - handleWidth < clip.left) {
            changeOrientation(RIGHT)
        } else if (posX + handleWidth > clip.right) {
            changeOrientation(LEFT)
        } else {
            changeOrientation(initialOrientation)
        }
    }

    private fun isPositionVisible(): Boolean {
        // A handle being dragged is always visible.
        if (isDragging) {
            return true
        }

        val hostView = terminalView
        val left = 0
        val right = hostView.width
        val top = 0
        val bottom = hostView.height

        val clip = tempRect ?: Rect().also { tempRect = it }
        clip.left = left + hostView.paddingLeft
        clip.top = top + hostView.paddingTop
        clip.right = right - hostView.paddingRight
        clip.bottom = bottom - hostView.paddingBottom

        val parent = hostView.parent
        if (parent == null || !parent.getChildVisibleRect(hostView, clip, null)) {
            return false
        }

        val coords = tempCoords
        hostView.getLocationInWindow(coords)
        val posX = coords[0] + pointX + hotspotX.toInt()
        val posY = coords[1] + pointY + hotspotY.toInt()

        return posX >= clip.left && posX <= clip.right &&
            posY >= clip.top && posY <= clip.bottom
    }

    override fun onDraw(c: Canvas) {
        val width = handleDrawable.intrinsicWidth
        val height = handleDrawable.intrinsicHeight
        handleDrawable.setBounds(0, 0, width, height)
        handleDrawable.draw(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        terminalView.updateFloatingToolbarVisibility(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val rawX = event.rawX
                val rawY = event.rawY
                touchToWindowOffsetX = rawX - pointX
                touchToWindowOffsetY = rawY - pointY
                val coords = tempCoords
                terminalView.getLocationInWindow(coords)
                lastParentX = coords[0]
                lastParentY = coords[1]
                isDragging = true
            }

            MotionEvent.ACTION_MOVE -> {
                val rawX = event.rawX
                val rawY = event.rawY

                val newPosX = rawX - touchToWindowOffsetX + hotspotX
                val newPosY = rawY - touchToWindowOffsetY + hotspotY + touchOffsetY

                cursorController.updatePosition(this, Math.round(newPosX), Math.round(newPosY))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(handleDrawable.intrinsicWidth, handleDrawable.intrinsicHeight)
    }

    fun isShowing(): Boolean = handle?.isShowing ?: false

    fun isParentNull(): Boolean = parent == null

}

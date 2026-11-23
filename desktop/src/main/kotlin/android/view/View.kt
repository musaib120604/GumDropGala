package android.view

import android.content.Context
import android.graphics.Canvas

class MotionEvent(val x: Float, val y: Float, val actionMasked: Int) {
    val action: Int get() = actionMasked
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
    }
}

class WindowInsets {
    val systemWindowInsetTop = 0
    val systemWindowInsetBottom = 0
}

/** Minimal View: the Swing host drives layout, drawing, input and lifecycle through the host* methods. */
open class View(val context: Context) {
    fun interface OnApplyWindowInsetsListener { fun onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets }

    var isFocusable = false
    var width = 0; private set
    var height = 0; private set
    val rootWindowInsets: WindowInsets? = null
    @Volatile var dirty = true

    fun setOnApplyWindowInsetsListener(l: OnApplyWindowInsetsListener?) { /* no system bars on desktop */ }
    open fun invalidate() { dirty = true }
    fun postInvalidate() { dirty = true }

    protected open fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {}
    protected open fun onDraw(canvas: Canvas) {}
    open fun onTouchEvent(e: MotionEvent): Boolean = false
    protected open fun onAttachedToWindow() {}
    protected open fun onDetachedFromWindow() {}

    fun hostLayout(w: Int, h: Int) { val ow = width; val oh = height; width = w; height = h; onSizeChanged(w, h, ow, oh) }
    fun hostDraw(c: Canvas) { dirty = false; onDraw(c) }
    fun hostAttach() = onAttachedToWindow()
    fun hostDetach() = onDetachedFromWindow()
}

class Choreographer private constructor() {
    fun interface FrameCallback { fun doFrame(frameTimeNanos: Long) }

    private val callbacks = ArrayList<FrameCallback>()
    fun postFrameCallback(cb: FrameCallback) { synchronized(callbacks) { callbacks.add(cb) } }
    fun removeFrameCallback(cb: FrameCallback) { synchronized(callbacks) { callbacks.remove(cb) } }
    fun dispatch(nanos: Long) {
        val copy = synchronized(callbacks) { val c = ArrayList(callbacks); callbacks.clear(); c }
        for (cb in copy) cb.doFrame(nanos)
    }

    companion object {
        private val inst = Choreographer()
        @JvmStatic fun getInstance(): Choreographer = inst
    }
}

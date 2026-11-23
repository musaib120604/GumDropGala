package android.graphics

import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D

/* Desktop stand-ins for the small slice of android.graphics the game uses, implemented on Java2D. */

open class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    fun width() = right - left
    fun height() = bottom - top
    fun set(l: Int, t: Int, r: Int, b: Int) { left = l; top = t; right = r; bottom = b }
}

class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    constructor(r: RectF) : this(r.left, r.top, r.right, r.bottom)
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun set(r: RectF) { left = r.left; top = r.top; right = r.right; bottom = r.bottom }
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
    fun offset(dx: Float, dy: Float) { left += dx; right += dx; top += dy; bottom += dy }
    fun inset(dx: Float, dy: Float) { left += dx; right -= dx; top += dy; bottom -= dy }
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

class Matrix() {
    internal val at = AffineTransform()
    constructor(o: Matrix) : this() { at.setTransform(o.at) }
    fun reset() { at.setToIdentity() }
    fun set(o: Matrix) { at.setTransform(o.at) }
    fun setScale(sx: Float, sy: Float) { at.setToScale(sx.toDouble(), sy.toDouble()) }
    fun setScale(sx: Float, sy: Float, px: Float, py: Float) {
        at.setToTranslation(px.toDouble(), py.toDouble()); at.scale(sx.toDouble(), sy.toDouble()); at.translate(-px.toDouble(), -py.toDouble())
    }
    fun setTranslate(dx: Float, dy: Float) { at.setToTranslation(dx.toDouble(), dy.toDouble()) }
    fun setRotate(deg: Float) { at.setToRotation(Math.toRadians(deg.toDouble())) }
    fun setRotate(deg: Float, px: Float, py: Float) { at.setToRotation(Math.toRadians(deg.toDouble()), px.toDouble(), py.toDouble()) }
    // post = apply after the existing transform, pre = before it
    fun postTranslate(dx: Float, dy: Float) { at.preConcatenate(AffineTransform.getTranslateInstance(dx.toDouble(), dy.toDouble())) }
    fun postScale(sx: Float, sy: Float) { at.preConcatenate(AffineTransform.getScaleInstance(sx.toDouble(), sy.toDouble())) }
    fun postRotate(deg: Float) { at.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(deg.toDouble()))) }
    fun preTranslate(dx: Float, dy: Float) { at.translate(dx.toDouble(), dy.toDouble()) }
    fun preScale(sx: Float, sy: Float) { at.scale(sx.toDouble(), sy.toDouble()) }
    fun preRotate(deg: Float) { at.rotate(Math.toRadians(deg.toDouble())) }
}

class Path() {
    enum class Direction { CW, CCW }
    internal val p = Path2D.Float(Path2D.WIND_NON_ZERO)

    private fun ensureStart() { if (p.currentPoint == null) p.moveTo(0f, 0f) }
    fun moveTo(x: Float, y: Float) { p.moveTo(x, y) }
    fun lineTo(x: Float, y: Float) { ensureStart(); p.lineTo(x, y) }
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) { ensureStart(); p.quadTo(x1, y1, x2, y2) }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { ensureStart(); p.curveTo(x1, y1, x2, y2, x3, y3) }
    fun close() { if (p.currentPoint != null) p.closePath() }
    fun reset() { p.reset() }
    fun rewind() { p.reset() }
    fun addCircle(x: Float, y: Float, r: Float, dir: Direction) { p.append(Ellipse2D.Float(x - r, y - r, r * 2f, r * 2f), false) }
    fun addOval(r: RectF, dir: Direction) { p.append(Ellipse2D.Float(r.left, r.top, r.width(), r.height()), false) }
    fun addRoundRect(r: RectF, rx: Float, ry: Float, dir: Direction) {
        p.append(RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), rx * 2f, ry * 2f), false)
    }
    fun addRect(l: Float, t: Float, r: Float, b: Float, dir: Direction) { p.append(java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t), false) }
    fun transform(m: Matrix) { p.transform(m.at) }
}

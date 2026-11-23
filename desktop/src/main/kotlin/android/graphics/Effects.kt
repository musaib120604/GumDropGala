package android.graphics

import java.awt.Color
import java.awt.Shape
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.Point2D
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.GradientPaint
import java.awt.MultipleGradientPaint
import kotlin.math.hypot
import kotlin.math.min

/* ------------------------------------------------------------ path effects */
abstract class PathEffect {
    internal open fun geometry(s: Shape): Shape = s
    internal open fun dash(): FloatArray? = null
    internal open fun phase(): Float = 0f
}

class DashPathEffect(private val intervals: FloatArray, private val ph: Float) : PathEffect() {
    override fun dash() = intervals
    override fun phase() = ph
}

class CornerPathEffect(private val radius: Float) : PathEffect() {
    override fun geometry(s: Shape): Shape {
        val out = Path2D.Float()
        val it = FlatteningPathIterator(s.getPathIterator(null), 0.25)
        val c = FloatArray(6)
        val pts = ArrayList<FloatArray>()
        var closed = false
        fun flush() {
            if (pts.isEmpty()) return
            if (pts.size < 3) {
                out.moveTo(pts[0][0], pts[0][1]); for (i in 1 until pts.size) out.lineTo(pts[i][0], pts[i][1])
            } else {
                out.moveTo(pts[0][0], pts[0][1])
                for (i in 1 until pts.size - 1) {
                    val a = pts[i - 1]; val b = pts[i]; val d = pts[i + 1]
                    val l1 = hypot((b[0] - a[0]).toDouble(), (b[1] - a[1]).toDouble()).toFloat()
                    val l2 = hypot((d[0] - b[0]).toDouble(), (d[1] - b[1]).toDouble()).toFloat()
                    val r1 = min(radius, l1 / 2f); val r2 = min(radius, l2 / 2f)
                    if (l1 < 1e-4f || l2 < 1e-4f) continue
                    out.lineTo(b[0] + (a[0] - b[0]) / l1 * r1, b[1] + (a[1] - b[1]) / l1 * r1)
                    out.quadTo(b[0], b[1], b[0] + (d[0] - b[0]) / l2 * r2, b[1] + (d[1] - b[1]) / l2 * r2)
                }
                out.lineTo(pts.last()[0], pts.last()[1])
            }
            if (closed) out.closePath()
            pts.clear(); closed = false
        }
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                PathIterator.SEG_MOVETO -> { flush(); pts.add(floatArrayOf(c[0], c[1])) }
                PathIterator.SEG_LINETO -> pts.add(floatArrayOf(c[0], c[1]))
                PathIterator.SEG_CLOSE -> { closed = true; flush() }
            }
            it.next()
        }
        flush()
        return out
    }
}

class ComposePathEffect(private val outer: PathEffect, private val inner: PathEffect) : PathEffect() {
    override fun geometry(s: Shape): Shape = outer.geometry(inner.geometry(s))
    override fun dash() = outer.dash() ?: inner.dash()
    override fun phase() = if (outer.dash() != null) outer.phase() else inner.phase()
}

/* ----------------------------------------------------------- colour filters */
object PorterDuff { enum class Mode { CLEAR, SRC, DST, SRC_OVER, SRC_IN, SRC_ATOP, MULTIPLY } }
abstract class ColorFilter
class PorterDuffColorFilter(val color: Int, val mode: PorterDuff.Mode) : ColorFilter()

/* ---------------------------------------------------------------- shaders */
abstract class Shader {
    enum class TileMode { CLAMP, REPEAT, MIRROR }
    internal var lm: Matrix? = null
    fun setLocalMatrix(m: Matrix?) { lm = m?.let { Matrix(it) } }
    internal abstract fun toAwt(): java.awt.Paint

    protected fun tx(x: Float, y: Float): Point2D.Float {
        val p = Point2D.Float(x, y); lm?.at?.transform(p, p); return p
    }
    protected fun cycle(t: TileMode) = when (t) {
        TileMode.CLAMP -> MultipleGradientPaint.CycleMethod.NO_CYCLE
        TileMode.REPEAT -> MultipleGradientPaint.CycleMethod.REPEAT
        TileMode.MIRROR -> MultipleGradientPaint.CycleMethod.REFLECT
    }
    protected fun fractions(n: Int, pos: FloatArray?): FloatArray {
        val f = FloatArray(n) { if (pos != null && it < pos.size) pos[it] else it / (n - 1).toFloat() }
        for (i in 1 until n) if (f[i] <= f[i - 1]) f[i] = f[i - 1] + 1e-4f
        if (f[n - 1] > 1f) { for (i in 0 until n) f[i] = f[i] / f[n - 1] ; for (i in 1 until n) if (f[i] <= f[i - 1]) f[i] = f[i - 1] + 1e-5f }
        return f
    }
}

class LinearGradient(private val x0: Float, private val y0: Float, private val x1: Float, private val y1: Float,
                     private val colors: IntArray, private val positions: FloatArray?, private val tile: TileMode) : Shader() {
    constructor(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int, tile: TileMode) :
        this(x0, y0, x1, y1, intArrayOf(c0, c1), null, tile)

    override fun toAwt(): java.awt.Paint {
        val a = tx(x0, y0); val b = tx(x1, y1)
        if (a.distance(b) < 1e-3) return Color(colors[0], true)
        if (colors.size == 2 && positions == null) return GradientPaint(a, Color(colors[0], true), b, Color(colors[1], true), tile != TileMode.CLAMP)
        return LinearGradientPaint(a, b, fractions(colors.size, positions), Array(colors.size) { Color(colors[it], true) }, cycle(tile))
    }
}

class RadialGradient(private val cx: Float, private val cy: Float, private val radius: Float,
                     private val colors: IntArray, private val positions: FloatArray?, private val tile: TileMode) : Shader() {
    constructor(cx: Float, cy: Float, r: Float, c0: Int, c1: Int, tile: TileMode) : this(cx, cy, r, intArrayOf(c0, c1), null, tile)

    override fun toAwt(): java.awt.Paint {
        val c = tx(cx, cy)
        val sc = lm?.at?.scaleX?.toFloat() ?: 1f
        val r = radius * kotlin.math.abs(sc)
        if (r < 1e-3f) return Color(colors[0], true)
        return RadialGradientPaint(c, r, fractions(colors.size, positions), Array(colors.size) { Color(colors[it], true) }, cycle(tile))
    }
}

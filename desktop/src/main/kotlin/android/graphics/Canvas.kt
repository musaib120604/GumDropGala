package android.graphics

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

class Canvas {
    internal val g: Graphics2D
    val width: Int
    val height: Int

    constructor(bitmap: Bitmap) {
        val img = bitmap.img ?: throw IllegalStateException("recycled bitmap")
        g = img.createGraphics(); width = img.width; height = img.height; init()
    }

    constructor(g2: Graphics2D, w: Int, h: Int) { g = g2; width = w; height = h; init() }

    private fun init() {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
    }

    fun dispose() { g.dispose() }

    /* ------------------------------------------------------------ state */
    private class State(val tx: AffineTransform, val clip: Shape?)
    private val stack = ArrayList<State>()

    fun save(): Int { stack.add(State(g.transform, g.clip)); return stack.size }
    fun restore() {
        if (stack.isEmpty()) return
        val s = stack.removeAt(stack.size - 1); g.transform = s.tx; g.clip = s.clip
    }
    fun translate(dx: Float, dy: Float) { g.translate(dx.toDouble(), dy.toDouble()) }
    fun scale(sx: Float, sy: Float) { g.scale(sx.toDouble(), sy.toDouble()) }
    fun scale(sx: Float, sy: Float, px: Float, py: Float) { g.translate(px.toDouble(), py.toDouble()); g.scale(sx.toDouble(), sy.toDouble()); g.translate(-px.toDouble(), -py.toDouble()) }
    fun rotate(deg: Float) { g.rotate(Math.toRadians(deg.toDouble())) }
    fun rotate(deg: Float, px: Float, py: Float) { g.rotate(Math.toRadians(deg.toDouble()), px.toDouble(), py.toDouble()) }
    fun clipRect(l: Float, t: Float, r: Float, b: Float): Boolean { g.clip(Rectangle2D.Float(l, t, r - l, b - t)); return true }
    fun clipPath(p: Path): Boolean { g.clip(p.p); return true }

    /* ------------------------------------------------------------ paint setup */
    private fun setPaint(p: Paint) {
        val sh = p.shader
        if (sh != null) {
            g.paint = sh.toAwt()
            val a = p.alpha
            g.composite = if (a < 255) AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a / 255f) else AlphaComposite.SrcOver
        } else {
            g.paint = Color(p.color, true); g.composite = AlphaComposite.SrcOver
        }
    }

    private fun setStroke(p: Paint) {
        val cap = when (p.strokeCap) { Paint.Cap.BUTT -> BasicStroke.CAP_BUTT; Paint.Cap.ROUND -> BasicStroke.CAP_ROUND; Paint.Cap.SQUARE -> BasicStroke.CAP_SQUARE }
        val join = when (p.strokeJoin) { Paint.Join.MITER -> BasicStroke.JOIN_MITER; Paint.Join.ROUND -> BasicStroke.JOIN_ROUND; Paint.Join.BEVEL -> BasicStroke.JOIN_BEVEL }
        val dash = p.pathEffect?.dash()
        g.stroke = if (dash != null && dash.isNotEmpty() && dash.all { it > 0f })
            BasicStroke(p.strokeWidth, cap, join, 10f, dash, p.pathEffect!!.phase())
        else BasicStroke(p.strokeWidth, cap, join, 10f)
    }

    private fun shape(s: Shape, p: Paint) {
        val sh = p.pathEffect?.geometry(s) ?: s
        if (p.style != Paint.Style.STROKE) { setPaint(p); g.fill(sh) }
        if (p.style != Paint.Style.FILL) { setPaint(p); setStroke(p); g.draw(sh) }
    }

    /* ------------------------------------------------------------ primitives */
    fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) = shape(Rectangle2D.Float(l, t, r - l, b - t), p)
    fun drawRect(r: RectF, p: Paint) = drawRect(r.left, r.top, r.right, r.bottom, p)
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) = shape(RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), rx * 2f, ry * 2f), p)
    fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, p: Paint) = shape(RoundRectangle2D.Float(l, t, r - l, b - t, rx * 2f, ry * 2f), p)
    fun drawCircle(cx: Float, cy: Float, r: Float, p: Paint) = shape(Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f), p)
    fun drawOval(r: RectF, p: Paint) = shape(Ellipse2D.Float(r.left, r.top, r.width(), r.height()), p)
    fun drawPath(path: Path, p: Paint) = shape(path.p, p)
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {
        setPaint(p); setStroke(p); g.draw(p.pathEffect?.geometry(Line2D.Float(x0, y0, x1, y1)) ?: Line2D.Float(x0, y0, x1, y1))
    }
    fun drawArc(r: RectF, start: Float, sweep: Float, useCenter: Boolean, p: Paint) =
        shape(Arc2D.Float(r.left, r.top, r.width(), r.height(), -start, -sweep, if (useCenter) Arc2D.PIE else Arc2D.OPEN), p)
    fun drawColor(color: Int) { g.paint = Color(color, true); g.composite = AlphaComposite.SrcOver; g.fill(g.clipBounds ?: Rectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat())) }

    fun drawText(text: String, x: Float, y: Float, p: Paint) {
        if (text.isEmpty()) return
        val adv = Typeface.advance(p.typeface, p.textSize, text)
        val dx = when (p.textAlign) { Paint.Align.LEFT -> 0f; Paint.Align.CENTER -> -adv / 2f; Paint.Align.RIGHT -> -adv }
        val outline = Typeface.outline(p.typeface, p.textSize, text)
        val old = g.transform
        g.translate((x + dx).toDouble(), y.toDouble())
        if (p.style != Paint.Style.STROKE) { setPaint(p); g.fill(outline) }
        if (p.style != Paint.Style.FILL) { setPaint(p); setStroke(p); g.draw(outline) }
        g.transform = old
    }

    /* ------------------------------------------------------------ bitmaps */

    private fun tinted(img: BufferedImage, f: PorterDuffColorFilter): BufferedImage {
        val key = (System.identityHashCode(img).toLong() shl 32) or (f.color.toLong() and 0xFFFFFFFFL)
        tintCache[key]?.let { return it }
        if (tintCache.size > 64) tintCache.clear()
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB_PRE)
        val tg = out.createGraphics()
        tg.drawImage(img, 0, 0, null)
        tg.composite = if (f.mode == PorterDuff.Mode.SRC_ATOP) AlphaComposite.SrcAtop else AlphaComposite.SrcIn
        tg.color = Color(f.color, true); tg.fillRect(0, 0, img.width, img.height); tg.dispose()
        tintCache[key] = out
        return out
    }

    // Large images (the backdrops) are resampled once to their on-screen pixel size instead of every frame.

    private fun prescaled(img: BufferedImage, dl: Float, dt: Float, dr: Float, db: Float): BufferedImage {
        if (img.width < 300) return img
        val m = g.transform
        val pw = Math.round((dr - dl) * Math.abs(m.scaleX)).toInt(); val ph = Math.round((db - dt) * Math.abs(m.scaleY)).toInt()
        if (pw < 8 || ph < 8 || (pw > img.width * 0.97 && pw < img.width * 1.03 && ph > img.height * 0.97 && ph < img.height * 1.03)) return img
        val key = (System.identityHashCode(img).toLong() shl 40) xor (pw.toLong() shl 20) xor ph.toLong()
        scaledCache[key]?.let { return it }
        if (scaledCache.size > 8) scaledCache.clear()
        val out = BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB)
        val sg = out.createGraphics()
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        sg.drawImage(img, 0, 0, pw, ph, null); sg.dispose()
        scaledCache[key] = out
        return out
    }

    private fun blit(bmp: Bitmap, src: Rect?, dl: Float, dt: Float, dr: Float, db: Float, p: Paint?) {
        var img = bmp.img ?: return
        val f = p?.colorFilter
        if (f is PorterDuffColorFilter && f.mode != PorterDuff.Mode.SRC) img = tinted(img, f)
        else if (src == null && (p?.alpha ?: 255) == 255) img = prescaled(img, dl, dt, dr, db)
        val sl = (src?.left ?: 0).toFloat(); val st = (src?.top ?: 0).toFloat()
        val sw = (if (src != null) src.width() else img.width).toFloat(); val sh = (if (src != null) src.height() else img.height).toFloat()
        if (sw <= 0f || sh <= 0f) return
        val a = p?.alpha ?: 255
        g.composite = if (a < 255) AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a / 255f) else AlphaComposite.SrcOver
        // Fast path: no rotation and an effective 1:1 scale -> a plain pixel-aligned blit (sharp, and far cheaper)
        val tt = g.transform
        if (src == null && tt.shearX == 0.0 && tt.shearY == 0.0) {
            val ex = tt.scaleX * (dr - dl) / sw; val ey = tt.scaleY * (db - dt) / sh
            if (ex > 0.995 && ex < 1.005 && ey > 0.995 && ey < 1.005) {
                val dx = Math.round(tt.scaleX * dl + tt.translateX).toInt(); val dy = Math.round(tt.scaleY * dt + tt.translateY).toInt()
                g.transform = IDENTITY
                g.drawImage(img, dx, dy, null)
                g.transform = tt
                return
            }
        }
        val at = AffineTransform()
        at.translate(dl.toDouble(), dt.toDouble())
        at.scale(((dr - dl) / sw).toDouble(), ((db - dt) / sh).toDouble())
        at.translate(-sl.toDouble(), -st.toDouble())
        if (src != null) { val old = g.clip; g.clip(Rectangle2D.Float(dl, dt, dr - dl, db - dt)); g.drawImage(img, at, null); g.clip = old }
        else g.drawImage(img, at, null)
    }

    fun drawBitmap(bmp: Bitmap, src: Rect?, dst: RectF, p: Paint?) = blit(bmp, src, dst.left, dst.top, dst.right, dst.bottom, p)
    fun drawBitmap(bmp: Bitmap, src: Rect?, dst: Rect, p: Paint?) = blit(bmp, src, dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat(), p)
    fun drawBitmap(bmp: Bitmap, left: Float, top: Float, p: Paint?) = blit(bmp, null, left, top, left + bmp.width, top + bmp.height, p)

    private companion object {
        // a Canvas is created for every frame, so image caches must outlive it
        val IDENTITY = AffineTransform()
        val tintCache = HashMap<Long, BufferedImage>()
        val scaledCache = HashMap<Long, BufferedImage>()
    }
}

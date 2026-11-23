package com.gumdropgala

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.gumdropgala.engine.BOMB_TYPE
import com.gumdropgala.engine.Special
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CandyColor(val base: Int, val light: Int, val dark: Int, val glow: Int, val name: String)

val CANDY_COLORS = arrayOf(
    CandyColor(0xFFFF2D55.toInt(), 0xFFFF9FB0.toInt(), 0xFFA8001F.toInt(), 0xFFFF7A92.toInt(), "Strawberry gumdrop"),
    CandyColor(0xFFFFD10A.toInt(), 0xFFFFF59A.toInt(), 0xFFD17D00.toInt(), 0xFFFFE96B.toInt(), "Lemon cube"),
    CandyColor(0xFF2FDC3C.toInt(), 0xFFB4FF98.toInt(), 0xFF0B7D1C.toInt(), 0xFF7DFF7A.toInt(), "Lime drop"),
    CandyColor(0xFF2A86FF.toInt(), 0xFFA5D3FF.toInt(), 0xFF0A3FBF.toInt(), 0xFF6FB6FF.toInt(), "Blueberry gem"),
    CandyColor(0xFFA43CFF.toInt(), 0xFFE0B4FF.toInt(), 0xFF560FA8.toInt(), 0xFFC887FF.toInt(), "Grape heart"),
    CandyColor(0xFFFF8412.toInt(), 0xFFFFCD8C.toInt(), 0xFFBD4300.toInt(), 0xFFFFAE5C.toInt(), "Tangerine bean")
)

/**
 * Draws the glossy candies into bitmaps once (at the exact pixel size needed) so each frame is just bitmap blits.
 * Every colour has its own silhouette and surface detail, so candies are tellable apart without relying on colour.
 */
class CandyArt {
    var assets: AssetManager? = null
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
    @Volatile private var size = 0
    @Volatile private var symbols = false
    @Volatile private var gen = 0

    val currentSize: Int get() = size

    fun rebuild(px: Int, symbols: Boolean) {
        if (px == size && symbols == this.symbols && cache.isNotEmpty()) return
        cache.clear() // don't recycle: a frame may still be drawing these; GC reclaims them
        size = px; this.symbols = symbols; gen++
        prewarm()
    }

    /** Decodes every board sprite off the UI thread so the first special candy never causes a hitch. */
    private fun prewarm() {
        val g = gen; val px = size; val sym = symbols
        Thread {
            try {
                for (type in 0 until 6) for (sp in 0 until 4) {
                    if (g != gen) return@Thread
                    val key = type * 8 + sp
                    if (!cache.containsKey(key)) {
                        val b = (if (sym) null else fromAsset(type, sp, px)) ?: render(type, sp, px, sym)
                        if (g == gen) cache.putIfAbsent(key, b)
                    }
                }
                if (g == gen && !cache.containsKey(999)) {
                    val b = (if (sym) null else fromAsset(0, Special.BOMB, px)) ?: render(0, Special.BOMB, px, sym)
                    if (g == gen) cache.putIfAbsent(999, b)
                }
            } catch (e: Throwable) { /* lazy path still works */ }
        }.also { it.name = "candy-prewarm"; it.priority = Thread.MIN_PRIORITY }.start()
    }

    fun get(type: Int, special: Int): Bitmap {
        val key = if (special == Special.BOMB) 999 else type * 8 + special
        val hit = cache[key]; if (hit != null) return hit
        val b = (if (symbols) null else fromAsset(type, special, size)) ?: render(type, special, size, symbols)
        return cache.putIfAbsent(key, b) ?: b
    }

    /** Standalone small render for HUD icons and avatars. */
    private val small = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
    fun icon(type: Int, special: Int, px: Int): Bitmap {
        val key = (if (special == Special.BOMB) 999 else type * 8 + special) * 1000 + px
        val hit = small[key]; if (hit != null) return hit
        val b = fromAsset(type, special, px) ?: render(type, special, px, false)
        return small.putIfAbsent(key, b) ?: b
    }

    /** Pre-rendered high-resolution sprite from assets/art, scaled down smoothly. Null if missing. */
    private fun fromAsset(type: Int, special: Int, px: Int): Bitmap? {
        val am = assets ?: return null
        return try {
            val name = if (special == Special.BOMB) "candy_bomb.png" else "candy_${type}_${special}.png"
            var b = am.open("art/$name").use { BitmapFactory.decodeStream(it) } ?: return null
            while (b.width / 2 >= px) { val h = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true); if (h !== b) b.recycle(); b = h }
            if (b.width != px) { val h = Bitmap.createScaledBitmap(b, px, px, true); if (h !== b) b.recycle(); b = h }
            b
        } catch (e: Exception) { null }
    }

    companion object {
        private fun p() = Paint(Paint.ANTI_ALIAS_FLAG)

        fun shapePath(type: Int): Path {
            val path = Path()
            when (type) {
                0 -> path.addCircle(0f, 0f, 0.92f, Path.Direction.CW)
                1 -> path.addRoundRect(RectF(-0.8f, -0.8f, 0.8f, 0.8f), 0.44f, 0.44f, Path.Direction.CW)
                2 -> {
                    path.moveTo(0f, -1f)
                    path.cubicTo(0.2f, -0.6f, 0.88f, -0.2f, 0.88f, 0.3f)
                    path.cubicTo(0.88f, 0.78f, 0.46f, 0.98f, 0f, 0.98f)
                    path.cubicTo(-0.46f, 0.98f, -0.88f, 0.78f, -0.88f, 0.3f)
                    path.cubicTo(-0.88f, -0.2f, -0.2f, -0.6f, 0f, -1f)
                    path.close()
                }
                3 -> roundedPolygon(path, 6, 1.0f)
                4 -> {
                    path.moveTo(0f, 0.96f)
                    path.cubicTo(-1.15f, 0.12f, -1.0f, -0.88f, -0.48f, -0.88f)
                    path.cubicTo(-0.16f, -0.88f, 0f, -0.64f, 0f, -0.46f)
                    path.cubicTo(0f, -0.64f, 0.16f, -0.88f, 0.48f, -0.88f)
                    path.cubicTo(1.0f, -0.88f, 1.15f, 0.12f, 0f, 0.96f)
                    path.close()
                }
                else -> {
                    path.addOval(RectF(-0.98f, -0.74f, 0.98f, 0.74f), Path.Direction.CW)
                    val m = Matrix(); m.setRotate(-28.6f); path.transform(m)
                }
            }
            return path
        }

        private fun roundedPolygon(path: Path, n: Int, radius: Float) {
            val xs = FloatArray(n) { cos(-PI / 2 + it * 2 * PI / n).toFloat() * radius }
            val ys = FloatArray(n) { sin(-PI / 2 + it * 2 * PI / n).toFloat() * radius }
            // Pull each corner in towards its neighbours and round it with a quadratic curve.
            val k = 0.3f
            fun lx(i: Int, j: Int) = xs[i] + (xs[j] - xs[i]) * k
            fun ly(i: Int, j: Int) = ys[i] + (ys[j] - ys[i]) * k
            for (i in 0 until n) {
                val prev = (i + n - 1) % n
                val next = (i + 1) % n
                if (i == 0) path.moveTo(lx(i, prev), ly(i, prev)) else path.lineTo(lx(i, prev), ly(i, prev))
                path.quadTo(xs[i], ys[i], lx(i, next), ly(i, next))
            }
            path.close()
        }

        fun render(type: Int, special: Int, s: Int, symbols: Boolean): Bitmap {
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(s / 2f, s / 2f)
            if (special == Special.BOMB || type == BOMB_TYPE) { drawBomb(c, s.toFloat()); return bmp }
            val col = CANDY_COLORS[type.coerceIn(0, 5)]
            val path = shapePath(type)
            val r = s * (if (special == Special.WRAPPED) 0.335f else 0.44f)
            c.save()
            c.scale(r, r)
            val paint = p()

            // 1) ground shadow and thick dark outline
            paint.style = Paint.Style.FILL
            paint.color = 0x5A190037
            c.save(); c.translate(0f, 0.1f); c.drawPath(path, paint); c.restore()
            paint.color = 0x40190037
            c.save(); c.translate(0f, 0.16f); c.drawPath(path, paint); c.restore()
            paint.color = col.dark
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.2f; paint.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            c.drawPath(path, paint)

            // 2) body: lit from the top-left, deep at the edge
            paint.shader = RadialGradient(-0.12f, -0.17f, 1.3f, intArrayOf(col.light, col.base, col.base, col.dark), floatArrayOf(0f, 0.3f, 0.72f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, paint)
            paint.shader = null

            c.save()
            c.clipPath(path)
            // 3) coloured light bouncing up from below
            paint.shader = RadialGradient(0.05f, 1.05f, 1.0f, col.glow, col.glow and 0x00FFFFFF, Shader.TileMode.CLAMP)
            paint.alpha = 230
            c.drawRect(-1.5f, -1.5f, 1.5f, 1.5f, paint)
            paint.shader = null; paint.alpha = 255
            // 4) domed bevel: a smaller, lighter copy of the shape
            c.save()
            c.translate(-0.03f, -0.06f); c.scale(0.76f, 0.76f)
            paint.shader = LinearGradient(0f, -1f, 0f, 1f, intArrayOf(col.light, col.base, col.glow), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            paint.alpha = 140
            c.drawPath(path, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.08f; paint.color = 0x8CFFFFFF.toInt()
            c.drawPath(path, paint)
            c.restore()
            paint.style = Paint.Style.FILL

            // 5) per-candy surface detail
            drawDetail(c, type, col)
            if (special == Special.STRIPE_H || special == Special.STRIPE_V) {
                paint.color = 0xEBFFFFFF.toInt()
                for (k in -3..3) {
                    if (special == Special.STRIPE_H) c.drawRect(-1.2f, k * 0.4f - 0.09f, 1.2f, k * 0.4f + 0.08f, paint)
                    else c.drawRect(k * 0.4f - 0.09f, -1.2f, k * 0.4f + 0.08f, 1.2f, paint)
                }
            }
            c.restore()

            // 6) glossy crescent on the rim, hard specular spot, sparkles
            c.save()
            c.clipPath(path)
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = 0.1f; paint.color = 0xD9FFFFFF.toInt()
            c.drawArc(RectF(-0.82f, -0.82f, 0.82f, 0.82f), 185.4f, 77.4f, false, paint)
            paint.style = Paint.Style.FILL
            c.save(); c.translate(-0.36f, -0.5f); c.rotate(-34.4f)
            paint.color = 0xFAFFFFFF.toInt()
            c.drawOval(RectF(-0.3f, -0.13f, 0.3f, 0.13f), paint)
            c.restore()
            c.restore()
            paint.color = 0xE6FFFFFF.toInt(); c.drawCircle(0.46f, 0.42f, 0.065f, paint)
            paint.color = 0x8CFFFFFF.toInt(); c.drawCircle(0.3f, 0.55f, 0.035f, paint)

            if (special == Special.WRAPPED) drawWrapper(c, path, col)
            if (symbols) drawGlyph(c, type)
            c.restore()
            return bmp
        }

        private fun drawDetail(c: Canvas, type: Int, col: CandyColor) {
            val paint = p()
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            when (type) {
                0 -> { // ball: swirl bands
                    paint.color = 0x66FFFFFF; paint.strokeWidth = 0.11f
                    c.drawArc(RectF(-0.5f, -0.46f, 0.54f, 0.58f), 28.6f, 121f, false, paint)
                    paint.color = 0x40780000.toInt(); paint.strokeWidth = 0.08f
                    c.drawArc(RectF(-0.34f, -0.3f, 0.38f, 0.42f), 206f, 114f, false, paint)
                }
                1 -> { // cube: sugar crystals
                    paint.style = Paint.Style.FILL; paint.color = 0xBFFFFFFF.toInt()
                    val rnd = Random(5)
                    for (i in 0 until 16) c.drawCircle((rnd.nextFloat() - 0.5f) * 1.3f, (rnd.nextFloat() - 0.5f) * 1.3f, 0.028f + rnd.nextFloat() * 0.022f, paint)
                    paint.style = Paint.Style.STROKE; paint.color = 0x47A05000; paint.strokeWidth = 0.05f
                    c.drawLine(-0.62f, 0.38f, 0.62f, 0.38f, paint)
                }
                2 -> { // drop: fold line
                    val fold = Path(); fold.moveTo(0f, -0.7f); fold.quadTo(-0.12f, 0.1f, 0f, 0.62f)
                    paint.color = 0x40005000; paint.strokeWidth = 0.07f; c.drawPath(fold, paint)
                    val hi = Path(); hi.moveTo(0.06f, -0.7f); hi.quadTo(-0.04f, 0.1f, 0.06f, 0.62f)
                    paint.color = 0x59FFFFFF; paint.strokeWidth = 0.05f; c.drawPath(hi, paint)
                }
                3 -> { // gem: hexagon facets
                    val hx = FloatArray(6) { cos(-PI / 2 + it * PI / 3).toFloat() * 1.05f }
                    val hy = FloatArray(6) { sin(-PI / 2 + it * PI / 3).toFloat() * 1.05f }
                    val tones = intArrayOf(0x5CFFFFFF, 0x1FFFFFFF, 0x33001478, 0x57001478, 0x2E001478, 0x33FFFFFF)
                    paint.style = Paint.Style.FILL
                    val tri = Path()
                    for (i in 0 until 6) {
                        val j = (i + 1) % 6
                        tri.reset(); tri.moveTo(0f, 0f); tri.lineTo(hx[i], hy[i]); tri.lineTo(hx[j], hy[j]); tri.close()
                        paint.color = tones[i]; c.drawPath(tri, paint)
                    }
                    paint.style = Paint.Style.STROKE; paint.color = 0x8CFFFFFF.toInt(); paint.strokeWidth = 0.045f
                    for (i in 0 until 6) c.drawLine(0f, 0f, hx[i], hy[i], paint)
                    paint.strokeWidth = 0.05f; paint.color = 0x80FFFFFF.toInt()
                    val inner = Path()
                    for (i in 0 until 6) if (i == 0) inner.moveTo(hx[i] * 0.5f, hy[i] * 0.5f) else inner.lineTo(hx[i] * 0.5f, hy[i] * 0.5f)
                    inner.close(); c.drawPath(inner, paint)
                }
                4 -> { // heart: crease and lobe shine
                    val crease = Path(); crease.moveTo(0f, -0.42f); crease.quadTo(0.08f, 0.1f, 0f, 0.74f)
                    paint.color = 0x47460078; paint.strokeWidth = 0.07f; c.drawPath(crease, paint)
                    paint.color = 0x66FFFFFF; paint.strokeWidth = 0.06f
                    c.drawArc(RectF(0.3f, -0.58f, 0.7f, -0.18f), 292f, 63f, false, paint)
                }
                else -> { // bean: bold diagonal band
                    c.save(); c.rotate(-28.6f)
                    paint.style = Paint.Style.FILL
                    paint.color = 0x4DFFFFFF; c.drawRect(-1.2f, -0.1f, 1.2f, 0.12f, paint)
                    paint.color = 0x33963200; c.drawRect(-1.2f, 0.2f, 1.2f, 0.28f, paint)
                    c.restore()
                }
            }
        }

        private fun drawWrapper(c: Canvas, path: Path, col: CandyColor) {
            val paint = p()
            for (sgn in intArrayOf(-1, 1)) {
                val end = Path()
                end.moveTo(sgn * 0.88f, 0f); end.lineTo(sgn * 1.42f, -0.5f); end.lineTo(sgn * 1.42f, 0.5f); end.close()
                paint.style = Paint.Style.FILL; paint.color = 0xF5FFFFFF.toInt(); c.drawPath(end, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.09f; paint.strokeJoin = Paint.Join.ROUND; paint.color = col.dark; c.drawPath(end, paint)
                paint.strokeWidth = 0.07f; paint.color = col.base
                c.drawLine(sgn * 1.1f, -0.08f, sgn * 1.34f, -0.3f, paint)
                c.drawLine(sgn * 1.1f, 0.08f, sgn * 1.34f, 0.3f, paint)
            }
            c.save(); c.scale(1.1f, 1.1f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.11f; paint.color = 0xEBFFFFFF.toInt(); c.drawPath(path, paint)
            c.restore()
        }

        private fun drawGlyph(c: Canvas, type: Int) { // colour-blind aid
            val paint = p()
            paint.color = 0xF2FFFFFF.toInt(); paint.strokeWidth = 0.13f; paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND
            c.save(); c.translate(0f, 0.08f)
            when (type) {
                0 -> { paint.style = Paint.Style.FILL; c.drawCircle(0f, 0f, 0.2f, paint) }
                1 -> { paint.style = Paint.Style.STROKE; c.drawRect(-0.2f, -0.2f, 0.2f, 0.2f, paint) }
                2 -> { paint.style = Paint.Style.STROKE; val t = Path(); t.moveTo(0f, -0.24f); t.lineTo(0.24f, 0.18f); t.lineTo(-0.24f, 0.18f); t.close(); c.drawPath(t, paint) }
                3 -> { paint.style = Paint.Style.FILL; val d = Path(); d.moveTo(0f, -0.26f); d.lineTo(0.2f, 0f); d.lineTo(0f, 0.26f); d.lineTo(-0.2f, 0f); d.close(); c.drawPath(d, paint) }
                4 -> { paint.style = Paint.Style.STROKE; c.drawLine(-0.24f, 0f, 0.24f, 0f, paint); c.drawLine(0f, -0.24f, 0f, 0.24f, paint) }
                else -> { paint.style = Paint.Style.STROKE; c.drawLine(-0.26f, 0.2f, 0f, -0.22f, paint); c.drawLine(0f, -0.22f, 0.26f, 0.2f, paint) }
            }
            c.restore()
        }

        private fun drawBomb(c: Canvas, s: Float) {
            val r = s * 0.43f
            val paint = p()
            paint.style = Paint.Style.FILL; paint.color = 0x73280A3C; c.drawCircle(0f, r * 0.1f, r, paint)
            paint.shader = RadialGradient(-r * 0.3f, -r * 0.4f, r * 1.1f, intArrayOf(0xFF9A6A82.toInt(), 0xFF5A3550.toInt(), 0xFF22111F.toInt()), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, r, paint)
            paint.shader = null
            c.save()
            val clip = Path(); clip.addCircle(0f, 0f, r, Path.Direction.CW); c.clipPath(clip)
            val rnd = Random(77)
            val cols = intArrayOf(0xFFFF6F9F.toInt(), 0xFFFFCF3E.toInt(), 0xFF4FD6A0.toInt(), 0xFF5AB6FF.toInt(), 0xFFB08CFF.toInt(), 0xFFFF9A52.toInt())
            for (i in 0 until 26) {
                val a = rnd.nextFloat() * (2f * PI.toFloat())
                val d = sqrt(rnd.nextFloat()) * r * 0.92f
                c.save(); c.translate(cos(a) * d, sin(a) * d); c.rotate(rnd.nextFloat() * 360f)
                paint.color = cols[i % 6]
                val w = r * 0.26f; val h = r * 0.085f
                c.drawRoundRect(RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), h / 2f, h / 2f, paint)
                c.restore()
            }
            c.restore()
            c.save(); c.translate(-r * 0.34f, -r * 0.45f); c.rotate(-28.6f)
            paint.shader = RadialGradient(0f, 0f, r * 0.5f, 0xCCFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawOval(RectF(-r * 0.5f, -r * 0.28f, r * 0.5f, r * 0.28f), paint)
            paint.shader = null
            c.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = r * 0.07f; paint.color = 0x59FFFFFF
            c.drawCircle(0f, 0f, r * 0.96f, paint)
        }

        private fun sqrt(x: Float): Float = kotlin.math.sqrt(x)
    }
}

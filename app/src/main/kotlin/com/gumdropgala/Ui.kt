package com.gumdropgala

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Palette. */
object Pal {
    const val INK = 0xFF3B2660.toInt()
    const val INK2 = 0xFF7A63A3.toInt()
    const val PAPER = 0xFFFFFAF3.toInt()
    const val EDGE = 0xFFF1D6C0.toInt()
    const val EDGE_SHADOW = 0xFFE4C2A4.toInt()
    const val PINK = 0xFFFF6B9A.toInt()
    const val LEMON = 0xFFFFC93C.toInt()
    const val LEMON_DARK = 0xFFC98400.toInt()
    const val MINT = 0xFF4FD39A.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val OFF_STAR = 0xFFD9CBE8.toInt()
    const val SCRIM = 0x8C301856.toInt()
}

class BtnStyle(val top: Int, val bottom: Int, val ledge: Int, val text: Int)

object Styles {
    val PINK = BtnStyle(0xFFFF93B9.toInt(), 0xFFFF5A8E.toInt(), 0xFFC73866.toInt(), 0xFFFFFFFF.toInt())
    val MINT = BtnStyle(0xFF74E4B5.toInt(), 0xFF2FC98A.toInt(), 0xFF1C9467.toInt(), 0xFFFFFFFF.toInt())
    val SKY = BtnStyle(0xFF79C8FF.toInt(), 0xFF3D9CF0.toInt(), 0xFF2670BD.toInt(), 0xFFFFFFFF.toInt())
    val LEMON = BtnStyle(0xFFFFE073.toInt(), 0xFFFFB81F.toInt(), 0xFFCC8A00.toInt(), 0xFF5A3A00.toInt())
    val GRAPE = BtnStyle(0xFFC3A4FF.toInt(), 0xFF8F64F0.toInt(), 0xFF6540BD.toInt(), 0xFFFFFFFF.toInt())
    val GHOST = BtnStyle(0xFFFFFFFF.toInt(), 0xFFF4E8FF.toInt(), 0xFFD4C0EE.toInt(), Pal.INK)
}

enum class Icon { COIN, HEART, STAR_ON, STAR_OFF, GIFT, USER, HOME, PAUSE, SLIDERS, LOCK, HAMMER, SHUFFLE, PLUS5, CHECK, CLOSE, JELLY }

class Hit(val l: Float, val t: Float, val r: Float, val b: Float, val onTap: (() -> Unit)?, val onDrag: ((Float, Float) -> Unit)?) {
    fun contains(x: Float, y: Float): Boolean = x >= l && x <= r && y >= t && y <= b
}

fun hsl(h: Float, s: Float, l: Float): Int {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f); hp < 2f -> Triple(x, c, 0f); hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c); hp < 5f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return 0xFF000000.toInt() or ((r1 + m) * 255f + 0.5f).toInt().shl(16) or ((g1 + m) * 255f + 0.5f).toInt().shl(8) or ((b1 + m) * 255f + 0.5f).toInt()
}

/**
 * Immediate-mode UI. Everything is laid out in "units": the screen is [w] x [h] units, where 1 unit is about one
 * dp-ish pixel on a 360-wide phone. Each frame the screens draw themselves and register tap/drag regions.
 */
class Ui {
    var w = 360f
    var h = 640f
    var safeTop = 0f
    var safeBottom = 0f
    var timeMs = 0f
    var bigText = false
    var inputOn = true
    lateinit var c: Canvas

    val hits = ArrayList<Hit>()
    var pointerDown = false
    var downX = 0f
    var downY = 0f

    val fontBlack: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    val fontBold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = RectF()
    val path = Path()

    fun begin(canvas: Canvas) { c = canvas; hits.clear() }

    /* ----- input regions ----- */
    fun hit(l: Float, t: Float, r: Float, b: Float, onTap: (() -> Unit)?, onDrag: ((Float, Float) -> Unit)? = null) {
        if (inputOn) hits.add(Hit(l, t, r, b, onTap, onDrag))
    }

    fun pressed(l: Float, t: Float, r: Float, b: Float): Boolean =
        inputOn && pointerDown && downX >= l && downX <= r && downY >= t && downY <= b

    /** Topmost region at a point (later registrations are drawn on top). */
    fun hitAt(x: Float, y: Float): Hit? {
        for (i in hits.indices.reversed()) if (hits[i].contains(x, y)) return hits[i]
        return null
    }

    /* ----- primitives ----- */
    fun fill(color: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = color }

    fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        fill(color); rect.set(l, t, r, b); c.drawRoundRect(rect, rad, rad, p)
    }

    fun rrectStroke(l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int, width: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = width; p.color = color
        rect.set(l, t, r, b); c.drawRoundRect(rect, rad, rad, p); p.style = Paint.Style.FILL
    }

    fun gradRRect(l: Float, t: Float, r: Float, b: Float, rad: Float, top: Int, bottom: Int) {
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, t, 0f, b, top, bottom, Shader.TileMode.CLAMP)
        rect.set(l, t, r, b); c.drawRoundRect(rect, rad, rad, p); p.shader = null
    }

    fun circle(cx: Float, cy: Float, r: Float, color: Int) { fill(color); c.drawCircle(cx, cy, r, p) }

    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER,
             stroke: Int = 0, strokeW: Float = 0f, font: Typeface = fontBlack): Float {
        val sz = size * (if (bigText) 1.12f else 1f)
        tp.typeface = font; tp.textSize = sz; tp.textAlign = align; tp.shader = null
        val base = y - (tp.fontMetrics.ascent + tp.fontMetrics.descent) / 2f
        if (stroke != 0 && strokeW > 0f) {
            tp.style = Paint.Style.STROKE; tp.strokeWidth = strokeW; tp.strokeJoin = Paint.Join.ROUND; tp.color = stroke
            c.drawText(s, x, base, tp)
        }
        tp.style = Paint.Style.FILL; tp.color = color
        c.drawText(s, x, base, tp)
        return tp.measureText(s)
    }

    fun textWidth(s: String, size: Float, font: Typeface = fontBlack): Float {
        tp.typeface = font; tp.textSize = size * (if (bigText) 1.12f else 1f); return tp.measureText(s)
    }

    /* ----- composite widgets ----- */
    fun card(l: Float, t: Float, r: Float, b: Float, rad: Float = 22f) {
        rrect(l, t + 5f, r, b + 5f, rad, Pal.EDGE_SHADOW)
        rrect(l, t, r, b, rad, Pal.PAPER)
        rrectStroke(l + 1.5f, t + 1.5f, r - 1.5f, b - 1.5f, rad - 1f, Pal.EDGE, 3f)
    }

    fun button(l: Float, t: Float, r: Float, b: Float, label: String, style: BtnStyle, onTap: () -> Unit, size: Float = 20f, enabled: Boolean = true) {
        val down = enabled && pressed(l, t, r, b)
        val off = if (down) 4f else 0f
        val rad = min(18f, (b - t) / 2.4f)
        val ledge = if (enabled) style.ledge else 0xFFB9AECB.toInt()
        val top = if (enabled) style.top else 0xFFE4DCEE.toInt()
        val bot = if (enabled) style.bottom else 0xFFCFC4DE.toInt()
        rrect(l, t + 6f, r, b + 6f - off, rad, ledge)
        gradRRect(l, t + off, r, b + off, rad, top, bot)
        rrect(l + 5f, t + off + 3f, r - 5f, t + off + 6f, 1.5f, 0x66FFFFFF)
        val txt = if (enabled) style.text else 0xFFFFFFFF.toInt()
        text(label, (l + r) / 2f, (t + b) / 2f + off, size, txt, Paint.Align.CENTER, if (style === Styles.GHOST || style === Styles.LEMON) 0 else (ledge and 0x00FFFFFF) or 0x66000000, 3f)
        if (enabled) hit(l, t, r, b, onTap)
    }

    fun roundButton(cx: Float, cy: Float, rad: Float, icon: Icon, onTap: () -> Unit) {
        val down = pressed(cx - rad, cy - rad, cx + rad, cy + rad)
        val off = if (down) 3f else 0f
        circle(cx, cy + 5f - off, rad, 0xFFCDB8EA.toInt())
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, cy - rad, 0f, cy + rad, 0xFFFFFFFF.toInt(), 0xFFF1E6FF.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + off, rad, p); p.shader = null
        icon(icon, cx, cy + off, rad * 1.05f)
        hit(cx - rad, cy - rad, cx + rad, cy + rad, onTap)
    }

    /** White pill with an icon and text; returns its width. Aligned by [alignRight]. */
    fun pill(x: Float, cy: Float, ic: Icon, label: String, alignRight: Boolean = false, onTap: (() -> Unit)? = null): Float {
        val hh = 34f
        val tw = textWidth(label, 17f)
        val wid = 12f + hh * 0.7f + 6f + tw + 12f
        val l = if (alignRight) x - wid else x
        rrect(l, cy - hh / 2f + 3f, l + wid, cy + hh / 2f + 3f, hh / 2f, 0x33783C96)
        rrect(l, cy - hh / 2f, l + wid, cy + hh / 2f, hh / 2f, Pal.WHITE)
        icon(ic, l + 12f + hh * 0.35f, cy, hh * 0.72f)
        text(label, l + 12f + hh * 0.7f + 6f, cy, 17f, Pal.INK, Paint.Align.LEFT)
        if (onTap != null) hit(l, cy - hh / 2f, l + wid, cy + hh / 2f, onTap)
        return wid
    }

    fun starShape(cx: Float, cy: Float, r: Float): Path {
        path.reset()
        for (i in 0 until 10) {
            val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
            val rr = if (i % 2 == 1) r * 0.48f else r
            if (i == 0) path.moveTo(cx + cos(a) * rr, cy + sin(a) * rr) else path.lineTo(cx + cos(a) * rr, cy + sin(a) * rr)
        }
        path.close()
        return path
    }

    /** Draws an icon centred at (cx, cy) with the given diameter. */
    fun icon(ic: Icon, cx: Float, cy: Float, size: Float) {
        c.save(); c.translate(cx, cy); val k = size / 24f; c.scale(k, k)
        p.shader = null
        when (ic) {
            Icon.COIN -> {
                circle(0f, 0f, 10f, 0xFFFFC93C.toInt()); strokeCircle(0f, 0f, 10f, 0xFFC98400.toInt(), 1.8f)
                strokeCircle(0f, 0f, 6.6f, 0xFFE9A000.toInt(), 1.6f)
                path.reset(); for (i in 0 until 10) { val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f; val rr = if (i % 2 == 1) 1.7f else 3.6f; if (i == 0) path.moveTo(cos(a) * rr, sin(a) * rr) else path.lineTo(cos(a) * rr, sin(a) * rr) }
                path.close(); fill(0xFFFFF6C8.toInt()); c.drawPath(path, p)
            }
            Icon.HEART -> {
                path.reset(); path.moveTo(0f, 9f); path.cubicTo(-9f, 2f, -10f, -7f, -5f, -8f); path.cubicTo(-2.5f, -8.5f, 0f, -6.5f, 0f, -5f)
                path.cubicTo(0f, -6.5f, 2.5f, -8.5f, 5f, -8f); path.cubicTo(10f, -7f, 9f, 2f, 0f, 9f); path.close()
                fill(0xFFFF5A7E.toInt()); c.drawPath(path, p); strokePath(0xFFC23A5A.toInt(), 1.6f)
                rect.set(-6f, -6f, -2.5f, -3.5f); fill(0xB3FFFFFF.toInt()); c.drawOval(rect, p)
            }
            Icon.STAR_ON, Icon.STAR_OFF -> {
                val on = ic == Icon.STAR_ON
                starShape(0f, 0.5f, 11f)
                fill(if (on) Pal.LEMON else Pal.OFF_STAR); c.drawPath(path, p)
                if (on) strokePath(Pal.LEMON_DARK, 1.4f)
            }
            Icon.GIFT -> {
                rrect(-8.5f, -3f, 8.5f, 9f, 2.5f, 0xFFFF7AA6.toInt()); rrectStroke(-8.5f, -3f, 8.5f, 9f, 2.5f, 0xFFC23A69.toInt(), 1.6f)
                rrect(-9.5f, -6f, 9.5f, -1.5f, 2f, 0xFFFF93B9.toInt()); rrectStroke(-9.5f, -6f, 9.5f, -1.5f, 2f, 0xFFC23A69.toInt(), 1.6f)
                rrect(-1.5f, -6f, 1.5f, 9f, 0f, 0xFFFFE073.toInt())
                circle(-3.2f, -8.2f, 3f, 0xFFFFE073.toInt()); circle(3.2f, -8.2f, 3f, 0xFFFFE073.toInt())
            }
            Icon.USER -> {
                circle(0f, -4f, 4.6f, 0xFFA57BFF.toInt()); strokeCircle(0f, -4f, 4.6f, 0xFF6540BD.toInt(), 1.6f)
                path.reset(); path.moveTo(-8.2f, 9f); path.cubicTo(-7.6f, 4.4f, -4f, 2.4f, 0f, 2.4f); path.cubicTo(4f, 2.4f, 7.6f, 4.4f, 8.2f, 9f); path.close()
                fill(0xFFA57BFF.toInt()); c.drawPath(path, p); strokePath(0xFF6540BD.toInt(), 1.6f)
            }
            Icon.HOME -> {
                path.reset(); path.moveTo(-8.5f, -0.8f); path.lineTo(0f, -8.2f); path.lineTo(8.5f, -0.8f); path.lineTo(8.5f, 8f); path.lineTo(3.2f, 8f); path.lineTo(3.2f, 2.5f)
                path.lineTo(-3.2f, 2.5f); path.lineTo(-3.2f, 8f); path.lineTo(-8.5f, 8f); path.close()
                fill(0xFF52B2FF.toInt()); c.drawPath(path, p); strokePath(0xFF2670BD.toInt(), 1.7f)
            }
            Icon.PAUSE -> { rrect(-6.5f, -7.5f, -1.9f, 7.5f, 2f, Pal.INK); rrect(1.9f, -7.5f, 6.5f, 7.5f, 2f, Pal.INK) }
            Icon.SLIDERS -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f; p.strokeCap = Paint.Cap.ROUND; p.color = Pal.INK
                c.drawLine(-8f, -5f, 0f, -5f, p); c.drawLine(7f, -5f, 8f, -5f, p); c.drawLine(-8f, 5f, -6f, 5f, p); c.drawLine(0f, 5f, 8f, 5f, p)
                p.style = Paint.Style.FILL; circle(3.5f, -5f, 2.7f, Pal.LEMON); circle(-3.2f, 5f, 2.7f, Pal.PINK)
            }
            Icon.LOCK -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f; p.strokeCap = Paint.Cap.ROUND; p.color = Pal.WHITE
                rect.set(-4f, -9f, 4f, -1f); c.drawArc(rect, 180f, 180f, false, p); c.drawLine(-4f, -5f, -4f, -1.5f, p); c.drawLine(4f, -5f, 4f, -1.5f, p)
                p.style = Paint.Style.FILL; rrect(-7f, -1.5f, 7f, 9f, 2.6f, Pal.WHITE); circle(0f, 3.6f, 1.7f, 0xFF9C8CB6.toInt())
            }
            Icon.HAMMER -> {
                c.save(); c.rotate(35f)
                rrect(-1.8f, -2f, 1.8f, 11f, 1.6f, 0xFFD99A5B.toInt()); rrectStroke(-1.8f, -2f, 1.8f, 11f, 1.6f, 0xFF9B6A35.toInt(), 1.2f)
                rrect(-6.5f, -9f, 6.5f, -1.5f, 2.2f, 0xFFFF6B9A.toInt()); rrectStroke(-6.5f, -9f, 6.5f, -1.5f, 2.2f, 0xFFC23A69.toInt(), 1.5f)
                c.restore()
            }
            Icon.SHUFFLE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = 0xFF2670BD.toInt()
                path.reset(); path.moveTo(-9f, -5f); path.lineTo(-4f, -5f); path.cubicTo(1f, -5f, 1f, 5f, 6f, 5f); path.lineTo(9f, 5f)
                c.drawPath(path, p)
                path.reset(); path.moveTo(-9f, 5f); path.lineTo(-4f, 5f); path.cubicTo(-1f, 5f, 0f, 3f, 1f, 1.5f)
                c.drawPath(path, p)
                path.reset(); path.moveTo(3f, -2f); path.cubicTo(4f, -3.5f, 5f, -5f, 7f, -5f); path.lineTo(9f, -5f)
                c.drawPath(path, p)
                path.reset(); path.moveTo(6.5f, -8f); path.lineTo(9.5f, -5f); path.lineTo(6.5f, -2f); c.drawPath(path, p)
                path.reset(); path.moveTo(6.5f, 2f); path.lineTo(9.5f, 5f); path.lineTo(6.5f, 8f); c.drawPath(path, p)
            }
            Icon.PLUS5 -> { circle(0f, 0f, 10f, 0xFFFFC93C.toInt()); strokeCircle(0f, 0f, 10f, 0xFFC98400.toInt(), 1.8f); text("+5", 0f, 0f, 12f, 0xFF5A3A00.toInt()) }
            Icon.CHECK -> {
                circle(0f, 0f, 11f, 0xFF2FC98A.toInt())
                p.style = Paint.Style.STROKE; p.strokeWidth = 3.2f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = Pal.WHITE
                path.reset(); path.moveTo(-5.4f, 0.6f); path.lineTo(-1.7f, 4.3f); path.lineTo(5.5f, -3.6f); c.drawPath(path, p)
            }
            Icon.CLOSE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 3.2f; p.strokeCap = Paint.Cap.ROUND; p.color = Pal.INK
                c.drawLine(-6f, -6f, 6f, 6f, p); c.drawLine(6f, -6f, -6f, 6f, p)
            }
            Icon.JELLY -> {
                rrect(-10f, -10f, 10f, 10f, 6f, 0xFFFF8FC7.toInt()); rrectStroke(-10f, -10f, 10f, 10f, 6f, 0xFFD6489A.toInt(), 1.8f)
                rrect(-6.5f, -6.5f, 1.5f, -2.5f, 2f, 0x99FFFFFF.toInt())
            }
        }
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        c.restore()
    }

    private fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, w: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = color; c.drawCircle(cx, cy, r, p); p.style = Paint.Style.FILL
    }

    private fun strokePath(color: Int, w: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = w; p.strokeJoin = Paint.Join.ROUND; p.color = color; c.drawPath(path, p); p.style = Paint.Style.FILL
    }

    /* ----- world backdrop ----- */
    private val hues = floatArrayOf(200f, 28f, 16f, 162f, 40f, 322f, 214f, 346f, 128f, 268f)

    fun skyTop(world: Int): Int = if (world == 9) hsl(262f, 0.48f, 0.62f) else hsl(hues[world % 10], 0.9f, 0.86f)
    fun skyBottom(world: Int): Int = if (world == 9) hsl(310f, 0.7f, 0.8f) else hsl((hues[world % 10] + 42f) % 360f, 0.95f, 0.93f)
    fun hill1(world: Int): Int = if (world == 9) hsl(280f, 0.4f, 0.52f) else hsl((hues[world % 10] + 120f) % 360f, 0.58f, 0.76f)
    fun hill2(world: Int): Int = if (world == 9) hsl(265f, 0.38f, 0.42f) else hsl((hues[world % 10] + 150f) % 360f, 0.52f, 0.68f)

    var assets: AssetManager? = null
    private val bgCache = HashMap<Int, Bitmap>()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun bg(world: Int): Bitmap? {
        bgCache[world]?.let { return it }
        val am = assets ?: return null
        return try {
            if (bgCache.size >= 3) { bgCache.values.forEach { it.recycle() }; bgCache.clear() }
            val o = BitmapFactory.Options(); o.inPreferredConfig = Bitmap.Config.RGB_565
            val b = am.open("art/bg_${world.coerceIn(0, 9)}.webp").use { BitmapFactory.decodeStream(it, null, o) }
            if (b != null) bgCache[world] = b
            b
        } catch (e: Exception) { null }
    }

    fun backdrop(world: Int) {
        val img = bg(world)
        if (img != null) {
            val s = max(w / img.width, h / img.height)
            val dw = img.width * s; val dh = img.height * s
            rect.set((w - dw) / 2f, (h - dh), (w + dw) / 2f, h) // anchor to the bottom so the hills stay visible
            c.drawBitmap(img, null, rect, bgPaint)
            for (i in 0 until 2) cloud(((timeMs * (0.005f + i * 0.003f) + i * 190f) % (w + 240f)) - 120f, h * (0.1f + i * 0.14f), 0.7f + i * 0.25f)
            return
        }
        fallbackBackdrop(world)
    }

    private fun fallbackBackdrop(world: Int) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.75f, skyTop(world), skyBottom(world), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // soft sun glow
        fill(0x33FFFFFF); c.drawCircle(w * 0.82f, h * 0.06f, w * 0.34f, p)
        // drifting clouds
        for (i in 0 until 3) {
            val speed = 0.006f + i * 0.0025f
            val x = ((timeMs * speed + i * 160f) % (w + 240f)) - 120f
            cloud(x, h * (0.12f + i * 0.13f), 0.7f + (i % 2) * 0.3f)
        }
        // hills
        val hy = h * 0.8f
        path.reset(); path.moveTo(0f, hy - 30f); path.quadTo(w * 0.22f, hy - 100f, w * 0.45f, hy - 40f); path.quadTo(w * 0.75f, hy + 20f, w, hy - 50f); path.lineTo(w, h); path.lineTo(0f, h); path.close()
        fill(hill2(world)); c.drawPath(path, p)
        path.reset(); path.moveTo(0f, hy + 10f); path.quadTo(w * 0.28f, hy - 50f, w * 0.55f, hy + 5f); path.quadTo(w * 0.8f, hy + 40f, w, hy - 15f); path.lineTo(w, h); path.lineTo(0f, h); path.close()
        fill(hill1(world)); c.drawPath(path, p)
    }

    private fun cloud(x: Float, y: Float, s: Float) {
        fill(0xD9FFFFFF.toInt())
        rect.set(x, y, x + 120f * s, y + 40f * s); c.drawRoundRect(rect, 20f * s, 20f * s, p)
        c.drawCircle(x + 38f * s, y + 6f * s, 24f * s, p)
        c.drawCircle(x + 80f * s, y + 10f * s, 19f * s, p)
    }
}

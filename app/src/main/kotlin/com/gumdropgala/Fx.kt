package com.gumdropgala

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

typealias Ease = (Float) -> Float

object Easing {
    val lin: Ease = { it }
    val outQ: Ease = { 1f - (1f - it) * (1f - it) }
    val inQ: Ease = { it * it }
    val outC: Ease = { val k = 1f - it; 1f - k * k * k }
    val inOut: Ease = { if (it < 0.5f) 2f * it * it else { val k = -2f * it + 2f; 1f - k * k / 2f } }
    val outBack: Ease = { val c1 = 1.70158f; val c3 = c1 + 1f; val k = it - 1f; 1f + c3 * k * k * k + c1 * k * k }
    val inBack: Ease = { val c1 = 1.70158f; val c3 = c1 + 1f; c3 * it * it * it - c1 * it * it }
    val outElastic: Ease = {
        if (it <= 0f) 0f else if (it >= 1f) 1f
        else (2.0.pow(-10.0 * it) * sin((it * 10.0 - 0.75) * (2.0 * PI / 3.0)) + 1.0).toFloat()
    }
}

/* ------------------------------------------------------------------ tweens */

interface Tweenable {
    fun getProp(p: Int): Float
    fun setProp(p: Int, v: Float)
}

/** A bag of floats that can be tweened (used for UI animation values). */
class Vals(n: Int) : Tweenable {
    val v = FloatArray(n)
    override fun getProp(p: Int): Float = v[p]
    override fun setProp(p: Int, v: Float) { this.v[p] = v }
}

/** A candy on the board. Position is in cell units (centre of the candy). */
class Sprite(var id: Int, var type: Int, var special: Int) : Tweenable {
    var x = 0f; var y = 0f; var sx = 1f; var sy = 1f; var sc = 1f; var rot = 0f; var a = 1f; var flash = 0f
    var phase = 0f

    override fun getProp(p: Int): Float = when (p) {
        P_X -> x; P_Y -> y; P_SX -> sx; P_SY -> sy; P_SC -> sc; P_ROT -> rot; P_A -> a; else -> flash
    }

    override fun setProp(p: Int, v: Float) {
        when (p) { P_X -> x = v; P_Y -> y = v; P_SX -> sx = v; P_SY -> sy = v; P_SC -> sc = v; P_ROT -> rot = v; P_A -> a = v; else -> flash = v }
    }

    companion object {
        const val P_X = 0; const val P_Y = 1; const val P_SX = 2; const val P_SY = 3
        const val P_SC = 4; const val P_ROT = 5; const val P_A = 6; const val P_FLASH = 7
    }
}

private class Tween {
    var target: Tweenable? = null
    var prop = 0
    var from = 0f
    var to = 0f
    var dur = 1f
    var t = 0f
    var ease: Ease = Easing.outQ
    var started = false
    var done: (() -> Unit)? = null
}

/** Pooled tween runner. Durations are in milliseconds and are divided by [speed] (reduce-motion speeds things up). */
class Tweens {
    private val active = ArrayList<Tween>()
    private val pool = ArrayList<Tween>()
    var speed = 1f

    val idle: Boolean get() = active.isEmpty()

    fun to(o: Tweenable, prop: Int, to: Float, durMs: Float, ease: Ease = Easing.outQ, delayMs: Float = 0f, done: (() -> Unit)? = null) {
        val tw = if (pool.isEmpty()) Tween() else pool.removeAt(pool.size - 1)
        tw.target = o; tw.prop = prop; tw.to = to; tw.dur = max(1f, durMs / speed); tw.t = -delayMs / speed
        tw.ease = ease; tw.started = false; tw.done = done
        active.add(tw)
    }

    fun update(dtMs: Float) {
        var i = active.size - 1
        while (i >= 0) {
            if (i >= active.size) { i--; continue }
            val tw = active[i]
            tw.t += dtMs
            if (tw.t >= 0f) {
                val o = tw.target
                if (o != null) {
                    if (!tw.started) { tw.from = o.getProp(tw.prop); tw.started = true }
                    val k = min(1f, tw.t / tw.dur)
                    o.setProp(tw.prop, tw.from + (tw.to - tw.from) * tw.ease(k))
                    if (k >= 1f) {
                        active[i] = active[active.size - 1]
                        active.removeAt(active.size - 1)
                        val cb = tw.done
                        tw.target = null; tw.done = null
                        pool.add(tw)
                        cb?.invoke()
                    }
                } else { active.removeAt(i) }
            }
            i--
        }
    }

    /** Stops every running tween on [o] so a new motion doesn't fight an old one. */
    fun cancel(o: Tweenable) {
        var i = active.size - 1
        while (i >= 0) {
            val tw = active[i]
            if (tw.target === o) { active.removeAt(i); tw.target = null; tw.done = null; pool.add(tw) }
            i--
        }
    }

    fun clear() {
        for (tw in active) { tw.target = null; tw.done = null; pool.add(tw) }
        active.clear()
    }
}

private class TimerItem(var left: Float, val fn: () -> Unit)

/** Delayed callbacks, in milliseconds. */
class Timers {
    private val list = ArrayList<TimerItem>()
    var speed = 1f

    fun after(ms: Float, fn: () -> Unit) { list.add(TimerItem(ms / speed, fn)) }

    fun update(dtMs: Float) {
        var i = list.size - 1
        while (i >= 0) {
            if (i < list.size) {
                val it = list[i]
                it.left -= dtMs
                if (it.left <= 0f) { list.removeAt(i); it.fn() }
            }
            i--
        }
    }

    fun clear() { list.clear() }
}

/* ------------------------------------------------------------------ particles & effects (all in board units) */

class Effects {
    // textures from assets/fx (all optional: the code-drawn shapes are used when missing)
    private var glowBmp: Bitmap? = null
    private var sparkleBmp: Bitmap? = null
    private var ringBmp: Bitmap? = null
    private var burstBmp: Bitmap? = null
    private val tr = RectF()
    private val src = android.graphics.Rect()
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun loadTextures(am: AssetManager) {
        fun ld(n: String): Bitmap? = try { am.open("art/$n").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        glowBmp = ld("fx_glow.png"); sparkleBmp = ld("fx_sparkle.png"); ringBmp = ld("fx_ring.png"); burstBmp = ld("fx_burst.png")
    }

    // sprite-sheet bursts (8 frames), positions in board cell units
    private val bn = 16
    private val bOn = BooleanArray(bn); private val bX = FloatArray(bn); private val bY = FloatArray(bn)
    private val bS = FloatArray(bn); private val bT = FloatArray(bn); private val bD = FloatArray(bn)
    private var bIdx = 0
    private var bActive = 0

    /** Plays the burst animation centred on a board cell. [size] is in cells; [delayMs] postpones the start. */
    fun burst(cx: Float, cy: Float, size: Float, durMs: Float = 460f, delayMs: Float = 0f) {
        val i = bIdx; bIdx = (bIdx + 1) % bn
        if (!bOn[i]) bActive++
        bOn[i] = true; bX[i] = cx; bY[i] = cy; bS[i] = size; bT[i] = -delayMs; bD[i] = durMs
    }

    private val rnd = Random(42)
    private val star = Path()

    // particles, struct-of-arrays so nothing is allocated while playing
    private val pn = 520
    private val px = FloatArray(pn); private val py = FloatArray(pn); private val pvx = FloatArray(pn); private val pvy = FloatArray(pn)
    private val pl = FloatArray(pn); private val pm = FloatArray(pn); private val ps = FloatArray(pn); private val pr = FloatArray(pn)
    private val pvr = FloatArray(pn); private val pg = FloatArray(pn)
    private val pc = IntArray(pn); private val psh = IntArray(pn)
    private val pon = BooleanArray(pn)
    private var pIdx = 0
    var activeCount = 0; private set

    fun emit(x: Float, y: Float, color: Int, n: Int, speed: Float, shape: Int, size: Float, grav: Float, life: Float) {
        for (i in 0 until n) {
            val p = pIdx; pIdx = (pIdx + 1) % pn
            if (!pon[p]) activeCount++
            val a = rnd.nextFloat() * (2f * PI.toFloat())
            val v = speed * (0.35f + rnd.nextFloat() * 0.9f)
            pon[p] = true; px[p] = x; py[p] = y; pvx[p] = cos(a) * v; pvy[p] = sin(a) * v - speed * 0.25f
            pl[p] = 0f; pm[p] = life * (0.7f + rnd.nextFloat() * 0.6f); ps[p] = size * (0.6f + rnd.nextFloat() * 0.8f)
            pc[p] = color; psh[p] = shape; pr[p] = rnd.nextFloat() * 6.28f; pvr[p] = (rnd.nextFloat() - 0.5f) * 12f; pg[p] = grav
        }
    }

    fun confetti(n: Int, width: Float) {
        for (i in 0 until n) {
            val p = pIdx; pIdx = (pIdx + 1) % pn
            if (!pon[p]) activeCount++
            pon[p] = true; px[p] = rnd.nextFloat() * width; py[p] = -10f - rnd.nextFloat() * 90f
            pvx[p] = (rnd.nextFloat() - 0.5f) * 90f; pvy[p] = 35f + rnd.nextFloat() * 130f
            pl[p] = 0f; pm[p] = 2600f + rnd.nextFloat() * 1600f; ps[p] = 3.5f + rnd.nextFloat() * 4f
            pc[p] = CONFETTI[rnd.nextInt(CONFETTI.size)]; psh[p] = 2; pr[p] = rnd.nextFloat() * 6.28f
            pvr[p] = (rnd.nextFloat() - 0.5f) * 14f; pg[p] = 50f
        }
    }

    // floating texts
    private val fn = 24
    private val ftOn = BooleanArray(fn); private val ftX = FloatArray(fn); private val ftY = FloatArray(fn)
    private val ftL = FloatArray(fn); private val ftM = FloatArray(fn); private val ftS = FloatArray(fn)
    private val ftT = arrayOfNulls<String>(fn); private val ftC = IntArray(fn)
    private var ftIdx = 0
    private var ftActive = 0

    fun floatText(x: Float, y: Float, text: String, size: Float, color: Int, lifeMs: Float = 950f) {
        val f = ftIdx; ftIdx = (ftIdx + 1) % fn
        if (!ftOn[f]) ftActive++
        ftOn[f] = true; ftX[f] = x; ftY[f] = y; ftL[f] = 0f; ftM[f] = lifeMs; ftT[f] = text; ftS[f] = size; ftC[f] = color
    }

    // beams, rings, bolts, flash
    private val ln = 90
    private val lOn = BooleanArray(ln); private val lKind = IntArray(ln)
    private val lx = FloatArray(ln); private val ly = FloatArray(ln); private val lx2 = FloatArray(ln); private val ly2 = FloatArray(ln)
    private val lt = FloatArray(ln); private val ld = FloatArray(ln); private val la = FloatArray(ln); private val lb = FloatArray(ln); private val lr = FloatArray(ln)
    private var lIdx = 0
    private var lActive = 0

    fun addLine(kind: Int, x: Float, y: Float, durMs: Float, x2: Float = 0f, y2: Float = 0f, delayMs: Float = 0f, radius: Float = 2f) {
        val f = lIdx; lIdx = (lIdx + 1) % ln
        if (!lOn[f]) lActive++
        lOn[f] = true; lKind[f] = kind; lx[f] = x; ly[f] = y; lx2[f] = x2; ly2[f] = y2; lt[f] = -delayMs; ld[f] = durMs
        la[f] = rnd.nextFloat() - 0.5f; lb[f] = rnd.nextFloat() - 0.5f; lr[f] = radius
    }

    val busy: Boolean get() = activeCount > 0 || ftActive > 0 || lActive > 0 || bActive > 0

    fun clear() {
        java.util.Arrays.fill(pon, false); java.util.Arrays.fill(ftOn, false); java.util.Arrays.fill(lOn, false); java.util.Arrays.fill(bOn, false); bActive = 0
        activeCount = 0; ftActive = 0; lActive = 0
    }

    fun update(dtMs: Float) {
        val d = dtMs / 1000f
        for (i in 0 until pn) {
            if (!pon[i]) continue
            pl[i] += dtMs
            if (pl[i] >= pm[i]) { pon[i] = false; activeCount--; continue }
            pvy[i] += pg[i] * d
            px[i] += pvx[i] * d + (if (psh[i] == 2) sin(pl[i] / 160f + pr[i]) * 0.3f else 0f)
            py[i] += pvy[i] * d
            pvx[i] *= if (psh[i] == 2) 0.995f else 0.985f
            pr[i] += pvr[i] * d
        }
        for (i in 0 until fn) if (ftOn[i]) { ftL[i] += dtMs; if (ftL[i] >= ftM[i]) { ftOn[i] = false; ftActive-- } }
        for (i in 0 until ln) if (lOn[i]) { lt[i] += dtMs; if (lt[i] >= ld[i]) { lOn[i] = false; lActive-- } }
        for (i in 0 until bn) if (bOn[i]) { bT[i] += dtMs; if (bT[i] >= bD[i]) { bOn[i] = false; bActive-- } }
    }

    /** Draws beams, particles and floating text. [cell] is the board cell size, [bx]/[by]/[bsize] the board rectangle. */
    fun draw(c: Canvas, p: Paint, cell: Float, bx: Float, by: Float, bsize: Float, textFont: android.graphics.Typeface) {
        p.style = Paint.Style.FILL
        for (i in 0 until ln) {
            if (!lOn[i] || lt[i] < 0f) continue
            val k = lt[i] / ld[i]
            val a = 1f - k
            val e = Easing.outC(min(1f, k * 1.6f))
            when (lKind[i]) {
                L_ROW -> {
                    val h = cell * (0.45f + 0.5f * a)
                    val cx = bx + lx[i] * cell; val cy = by + ly[i] * cell; val w = bsize * e
                    p.color = argb(a * 0.45f, 255, 226, 140); c.drawRect(cx - w, cy - h / 2f, cx + w, cy + h / 2f, p)
                    p.color = argb(a, 255, 255, 255); c.drawRect(cx - w, cy - h * 0.12f, cx + w, cy + h * 0.12f, p)
                }
                L_COL -> {
                    val w2 = cell * (0.45f + 0.5f * a)
                    val cx = bx + lx[i] * cell; val cy = by + ly[i] * cell; val h = bsize * e
                    p.color = argb(a * 0.45f, 255, 226, 140); c.drawRect(cx - w2 / 2f, cy - h, cx + w2 / 2f, cy + h, p)
                    p.color = argb(a, 255, 255, 255); c.drawRect(cx - w2 * 0.12f, cy - h, cx + w2 * 0.12f, cy + h, p)
                }
                L_RING -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = cell * 0.35f * a + 1f
                    p.color = argb(a, 255, 225, 150)
                    val rb = ringBmp
                    if (rb != null) {
                        val rr = e * cell * lr[i]
                        tp.alpha = (a * 255f).toInt().coerceIn(0, 255)
                        tr.set(bx + lx[i] * cell - rr, by + ly[i] * cell - rr, bx + lx[i] * cell + rr, by + ly[i] * cell + rr)
                        c.drawBitmap(rb, null, tr, tp); tp.alpha = 255
                    } else c.drawCircle(bx + lx[i] * cell, by + ly[i] * cell, e * cell * lr[i], p)
                    p.style = Paint.Style.FILL
                }
                L_BOLT -> {
                    val x1 = bx + lx[i] * cell; val y1 = by + ly[i] * cell; val x2 = bx + lx2[i] * cell; val y2 = by + ly2[i] * cell
                    val dx = x2 - x1; val dy = y2 - y1; val nx = -dy; val ny = dx
                    val m1x = x1 + dx * 0.33f + nx * la[i] * 0.25f; val m1y = y1 + dy * 0.33f + ny * la[i] * 0.25f
                    val m2x = x1 + dx * 0.66f + nx * lb[i] * 0.25f; val m2y = y1 + dy * 0.66f + ny * lb[i] * 0.25f
                    p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                    p.strokeWidth = 4f; p.color = argb(a * 0.6f, 150, 200, 255)
                    c.drawLine(x1, y1, m1x, m1y, p); c.drawLine(m1x, m1y, m2x, m2y, p); c.drawLine(m2x, m2y, x2, y2, p)
                    p.strokeWidth = 1.5f; p.color = argb(a, 255, 255, 255)
                    c.drawLine(x1, y1, m1x, m1y, p); c.drawLine(m1x, m1y, m2x, m2y, p); c.drawLine(m2x, m2y, x2, y2, p)
                    p.style = Paint.Style.FILL
                }
                L_FLASH -> { p.color = argb(a * 0.75f, 255, 255, 255); c.drawRect(bx - 6f, by - 6f, bx + bsize + 6f, by + bsize + 6f, p) }
            }
        }
        val bb = burstBmp; val gb = glowBmp
        if (bb != null) for (i in 0 until bn) {
            if (!bOn[i] || bT[i] < 0f) continue
            val k = bT[i] / bD[i]
            val frame = min(7, (k * 8f).toInt())
            val half = bS[i] * cell * 0.5f
            val cx = bx + bX[i] * cell; val cy = by + bY[i] * cell
            if (gb != null) { tp.alpha = ((1f - k) * 200f).toInt().coerceIn(0, 255); tr.set(cx - half * 1.1f, cy - half * 1.1f, cx + half * 1.1f, cy + half * 1.1f); c.drawBitmap(gb, null, tr, tp) }
            tp.alpha = 255
            src.set(frame * 128, 0, frame * 128 + 128, 128); tr.set(cx - half, cy - half, cx + half, cy + half)
            c.drawBitmap(bb, src, tr, tp)
        }
        for (i in 0 until pn) {
            if (!pon[i]) continue
            val k = pl[i] / pm[i]
            val alpha = if (k > 0.7f) 1f - (k - 0.7f) / 0.3f else 1f
            p.color = (pc[i] and 0x00FFFFFF) or ((alpha * 255f).toInt().coerceIn(0, 255) shl 24)
            when (psh[i]) {
                0 -> c.drawCircle(px[i], py[i], ps[i] * (1f - k * 0.5f), p)
                1 -> {
                    val sb = sparkleBmp
                    if (sb != null) {
                        val rr = ps[i] * 2.6f * (1f - k * 0.4f)
                        tp.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                        c.save(); c.translate(px[i], py[i]); c.rotate(pr[i] * 57.29578f * 0.3f)
                        tr.set(-rr, -rr, rr, rr); c.drawBitmap(sb, null, tr, tp); c.restore(); tp.alpha = 255
                    } else sparkle(c, p, px[i], py[i], ps[i] * 1.5f * (1f - k * 0.4f), pr[i])
                }
                else -> {
                    c.save(); c.translate(px[i], py[i]); c.rotate(pr[i] * 57.29578f)
                    val sy = abs(cos(pr[i] * 1.7f)) * 0.8f + 0.2f
                    c.drawRect(-ps[i] / 2f, -ps[i] / 3f * sy, ps[i] / 2f, ps[i] / 3f * sy, p)
                    c.restore()
                }
            }
        }
        p.typeface = textFont; p.textAlign = Paint.Align.CENTER
        for (i in 0 until fn) {
            if (!ftOn[i]) continue
            val k = ftL[i] / ftM[i]
            val a = if (k < 0.15f) k / 0.15f else if (k > 0.7f) 1f - (k - 0.7f) / 0.3f else 1f
            val sc = if (k < 0.2f) 0.6f + Easing.outBack(k / 0.2f) * 0.4f else 1f
            val y = ftY[i] - Easing.outQ(k) * 24f
            p.textSize = ftS[i] * sc
            val base = y - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f
            p.style = Paint.Style.STROKE; p.strokeWidth = ftS[i] * 0.28f; p.strokeJoin = Paint.Join.ROUND
            p.color = argb(a * 0.9f, 70, 25, 110); c.drawText(ftT[i] ?: "", ftX[i], base, p)
            p.style = Paint.Style.FILL; p.color = (ftC[i] and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)
            c.drawText(ftT[i] ?: "", ftX[i], base, p)
        }
        p.style = Paint.Style.FILL
    }

    fun sparkle(c: Canvas, p: Paint, x: Float, y: Float, r: Float, rot: Float) {
        star.reset()
        for (i in 0 until 8) {
            val a = rot + i * (PI.toFloat() / 4f)
            val rr = if (i % 2 == 1) r * 0.4f else r
            if (i == 0) star.moveTo(x + cos(a) * rr, y + sin(a) * rr) else star.lineTo(x + cos(a) * rr, y + sin(a) * rr)
        }
        star.close()
        c.drawPath(star, p)
    }

    companion object {
        const val L_ROW = 0; const val L_COL = 1; const val L_RING = 2; const val L_BOLT = 3; const val L_FLASH = 4
        val CONFETTI = intArrayOf(0xFFFF6B9A.toInt(), 0xFFFFC93C.toInt(), 0xFF4FD39A.toInt(), 0xFF52B2FF.toInt(), 0xFFA57BFF.toInt(), 0xFFFF9450.toInt(), 0xFFFFFFFF.toInt())
        fun argb(a: Float, r: Int, g: Int, b: Int): Int = ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (r shl 16) or (g shl 8) or b
    }
}

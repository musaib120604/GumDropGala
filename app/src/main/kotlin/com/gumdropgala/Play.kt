package com.gumdropgala

import android.graphics.Paint
import android.graphics.RectF
import com.gumdropgala.engine.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One level being played: owns the engine [Game], the candy sprites and the director that pulls engine events
 * one at a time and animates each before asking for the next.
 */
class Play(private val env: Env, val levelNo: Int, private val onEnded: (Ended) -> Unit) {
    val game = Game(levelConfig(levelNo), sugarCrush = true)
    private val sprites = HashMap<Int, Sprite>()
    private var iter: Iterator<Event>? = null
    private var wait = 0f
    private var selected: Pos? = null
    private var downCell: Pos? = null
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    var hammerArmed = false
    private var idleMs = 0f
    private var hint: Move? = null
    private var timeMs = 0f
    private val shake = Vals(1)
    private var ended: Ended? = null
    private var endDelay = 0f
    var scoreShown = 0f
    private val rect = RectF()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // board geometry in UI units
    var bx = 12f; var by = 150f; var bsize = 336f; var cell = 42f

    init {
        for (c in game.allCandies()) {
            val s = Sprite(c.id, c.type, c.special)
            s.x = c.c + 0.5f; s.y = c.r + 0.5f; s.phase = (c.id % 17) * 0.4f
            sprites[c.id] = s
        }
        // drop-in at the start
        if (!env.reduceMotion) for (s in sprites.values) {
            val ty = s.y; s.y = ty - 9f - (s.x * 0.4f)
            env.tw.to(s, Sprite.P_Y, ty, 520f + (7.5f - ty) * 40f, Easing.outBack, delayMs = s.x * 22f)
        }
    }

    val busy: Boolean get() = iter != null || wait > 0f || ended != null
    val canAct: Boolean get() = game.state == State.IDLE && iter == null && wait <= 0f && ended == null

    fun layout(top: Float, availW: Float, availH: Float) {
        bsize = min(availW - 20f, availH)
        cell = bsize / W
        bx = (env.ui.w - bsize) / 2f
        by = top
    }

    private fun px(c: Float) = bx + c * cell
    private fun py(r: Float) = by + r * cell

    /* ------------------------------------------------------------- input */
    private fun cellAt(x: Float, y: Float): Pos? {
        val c = ((x - bx) / cell).toInt(); val r = ((y - by) / cell).toInt()
        if (x < bx || y < by || !inBounds(r, c)) return null
        return Pos(r, c)
    }

    fun inBoard(x: Float, y: Float) = x >= bx && y >= by && x <= bx + bsize && y <= by + bsize

    fun onDown(x: Float, y: Float) {
        downX = x; downY = y; dragged = false; idleMs = 0f; hint = null
        if (!canAct) { downCell = null; return }
        val p = cellAt(x, y) ?: run { downCell = null; return }
        downCell = p
        if (hammerArmed) return
        val sel = selected
        if (sel != null && isAdjacent(sel, p)) { selected = null; trySwap(sel, p); downCell = null; return }
        if (sel == null || sel != p) { selected = p; env.sfx(Sfx.JIGGLE); env.hap(Hap.TICK); spriteAt(p)?.let { jiggle(it) } } else selected = null
        dragSprite = spriteAt(p)
    }

    private var dragSprite: Sprite? = null

    private fun spriteAt(p: Pos): Sprite? { val id = game.grid[p.r][p.c]?.id ?: return null; return sprites[id] }

    /** Puffy wobble: squashes, then springs back with a few elastic wiggles. */
    private fun jiggle(s: Sprite) {
        if (env.reduceMotion) return
        val tw = env.tw
        tw.cancel(s)
        s.sx = 1.22f; s.sy = 0.82f; s.rot = 0.16f
        tw.to(s, Sprite.P_SX, 1f, 620f, Easing.outElastic)
        tw.to(s, Sprite.P_SY, 1f, 620f, Easing.outElastic)
        tw.to(s, Sprite.P_ROT, 0f, 560f, Easing.outElastic)
    }

    /** Lets go of a candy that was being dragged: springs back to its cell. */
    private fun releaseDrag(p: Pos?) {
        val s = dragSprite ?: return
        dragSprite = null
        val tw = env.tw
        tw.cancel(s)
        val tx = (p?.c ?: s.x.toInt()) + 0.5f; val ty = (p?.r ?: s.y.toInt()) + 0.5f
        tw.to(s, Sprite.P_X, tx, 380f, Easing.outElastic); tw.to(s, Sprite.P_Y, ty, 380f, Easing.outElastic)
        tw.to(s, Sprite.P_SX, 1f, 420f, Easing.outElastic); tw.to(s, Sprite.P_SY, 1f, 420f, Easing.outElastic)
    }

    fun onMove(x: Float, y: Float) {
        val from = downCell ?: return
        if (dragged || !canAct || hammerArmed) return
        val dx = x - downX; val dy = y - downY
        if (abs(dx) < cell * 0.42f && abs(dy) < cell * 0.42f) {
            // follow the finger a little, stretching along the drag like jelly
            val ds = dragSprite
            if (ds != null && !env.reduceMotion) {
                env.tw.cancel(ds)
                val horiz = abs(dx) >= abs(dy)
                val off = ((if (horiz) dx else dy) / cell).coerceIn(-0.4f, 0.4f)
                val stretch = 1f + abs(off) * 0.7f; val squash = 1f - abs(off) * 0.4f
                ds.x = from.c + 0.5f + (if (horiz) off else 0f); ds.y = from.r + 0.5f + (if (horiz) 0f else off)
                ds.sx = if (horiz) stretch else squash; ds.sy = if (horiz) squash else stretch
            }
            return
        }
        dragged = true
        dragSprite?.let { env.tw.cancel(it); it.sx = 1f; it.sy = 1f }
        dragSprite = null
        val to = if (abs(dx) > abs(dy)) Pos(from.r, from.c + if (dx > 0) 1 else -1) else Pos(from.r + if (dy > 0) 1 else -1, from.c)
        downCell = null; selected = null
        if (inBounds(to.r, to.c)) trySwap(from, to)
    }

    fun onUp() {
        val p = downCell
        if (p != null && !hammerArmed) releaseDrag(p)
        if (p != null && hammerArmed && canAct) {
            hammerArmed = false
            env.sfx(Sfx.SPECIAL); env.hap(Hap.HEAVY)
            run(game.hammer(p.r, p.c).iterator())
        }
        downCell = null
    }

    private fun isAdjacent(a: Pos, b: Pos) = abs(a.r - b.r) + abs(a.c - b.c) == 1

    private fun trySwap(a: Pos, b: Pos) { selected = null; run(game.swap(a, b).iterator()) }

    private fun run(it: Iterator<Event>) { iter = it; wait = 0f }

    fun useShuffle() { if (canAct) run(game.shuffleBooster().iterator()) }
    fun addMoves(n: Int) { game.addMoves(n); env.sfx(Sfx.COIN); env.hap(Hap.SOFT); env.fx.floatText(env.ui.w / 2f, by + bsize / 2f, "+$n moves", 26f, Pal.LEMON) }

    /* ------------------------------------------------------------ update */
    fun update(dtMs: Float) {
        timeMs += dtMs
        env.tw.update(dtMs); env.timers.update(dtMs); env.fx.update(dtMs)
        scoreShown += (game.score - scoreShown) * min(1f, dtMs / 160f)
        if (abs(game.score - scoreShown) < 1f) scoreShown = game.score.toFloat()
        if (wait > 0f) wait -= dtMs
        if (wait <= 0f && env.tw.idle) {
            val it = iter
            if (it != null) {
                if (it.hasNext()) handle(it.next()) else { iter = null; afterTurn() }
            }
        }
        val e = ended
        if (e != null) { endDelay -= dtMs; if (endDelay <= 0f) { ended = null; endedFired = true; onEnded(e) } }
        if (canAct && !endedFired) {
            idleMs += dtMs
            if (idleMs > 6000f && hint == null) hint = game.findHint()
        }
    }

    private var endedFired = false

    private fun afterTurn() { idleMs = 0f; hint = null }

    private fun sp(id: Int): Sprite? = sprites[id]

    private fun wake(ms: Float) { wait = max(wait, ms) }

    private fun handle(e: Event) {
        val tw = env.tw
        when (e) {
            is Rejected -> {}
            is Invalid -> {
                val a = sp(game.grid[e.a.r][e.a.c]?.id ?: -1); val b = sp(game.grid[e.b.r][e.b.c]?.id ?: -1)
                env.sfx(Sfx.INVALID); env.hap(Hap.REJECT)
                if (a != null && b != null) {
                    val ax = a.x; val ay = a.y; val bx2 = b.x; val by2 = b.y
                    tw.to(a, Sprite.P_X, bx2, 110f, Easing.outQ); tw.to(a, Sprite.P_Y, by2, 110f, Easing.outQ)
                    tw.to(b, Sprite.P_X, ax, 110f, Easing.outQ); tw.to(b, Sprite.P_Y, ay, 110f, Easing.outQ)
                    tw.to(a, Sprite.P_X, ax, 120f, Easing.outQ, 120f); tw.to(a, Sprite.P_Y, ay, 120f, Easing.outQ, 120f)
                    tw.to(b, Sprite.P_X, bx2, 120f, Easing.outQ, 120f); tw.to(b, Sprite.P_Y, by2, 120f, Easing.outQ, 120f)
                }
                wake(260f)
            }
            is Swapped -> {
                val a = sp(e.idA); val b = sp(e.idB)
                env.sfx(Sfx.SWAP); env.hap(Hap.SOFT)
                env.sfx(Sfx.SQUISH, 0, 0.7f)
                val horiz = e.a.r == e.b.r
                for ((spr, to) in listOf(a to e.b, b to e.a)) {
                    if (spr == null) continue
                    tw.cancel(spr)
                    tw.to(spr, Sprite.P_X, to.c + 0.5f, 150f, Easing.inOut); tw.to(spr, Sprite.P_Y, to.r + 0.5f, 150f, Easing.inOut)
                    if (!env.reduceMotion) {
                        // stretch while travelling, then a jelly squash-and-settle on arrival
                        tw.to(spr, Sprite.P_SX, if (horiz) 1.3f else 0.8f, 70f, Easing.outQ)
                        tw.to(spr, Sprite.P_SY, if (horiz) 0.8f else 1.3f, 70f, Easing.outQ)
                        tw.to(spr, Sprite.P_SX, if (horiz) 0.8f else 1.2f, 70f, Easing.inOut, 70f)
                        tw.to(spr, Sprite.P_SY, if (horiz) 1.2f else 0.8f, 70f, Easing.inOut, 70f)
                        tw.to(spr, Sprite.P_SX, 1f, 260f, Easing.outElastic, 150f)
                        tw.to(spr, Sprite.P_SY, 1f, 260f, Easing.outElastic, 150f)
                    }
                }
                wake(170f)
            }
            is Matched -> {
                val biggest = e.groups.maxOfOrNull { it.size } ?: 3
                env.sfx(Sfx.MATCH, min(8, e.cascade - 1))
                env.hap(when { biggest >= 5 -> Hap.STRONG; biggest == 4 -> Hap.MEDIUM; else -> Hap.LIGHT })
                for (g in e.groups) for (k in g.cells) sp(game.grid.getOrNull(rowOf(k))?.getOrNull(colOf(k))?.id ?: -1)?.let { s ->
                    tw.to(s, Sprite.P_FLASH, 1f, 90f, Easing.outQ)
                    tw.to(s, Sprite.P_SC, 1.18f, 100f, Easing.outQ)
                }
                wake(110f)
            }
            is Cleared -> onCleared(e)
            is Fall -> {
                var maxD = 0f
                for (m in e.moves) sp(m.id)?.let { s ->
                    val d = (m.toR - m.fromR).toFloat(); maxD = max(maxD, d)
                    tw.to(s, Sprite.P_Y, m.toR + 0.5f, 150f + d * 55f, Easing.inQ)
                    tw.to(s, Sprite.P_SY, 0.86f, 70f, Easing.outQ, 150f + d * 55f - 30f)
                    tw.to(s, Sprite.P_SY, 1f, 160f, Easing.outElastic, 150f + d * 55f + 40f)
                    tw.to(s, Sprite.P_SX, 1.1f, 70f, Easing.outQ, 150f + d * 55f - 30f)
                    tw.to(s, Sprite.P_SX, 1f, 160f, Easing.outElastic, 150f + d * 55f + 40f)
                }
                env.sfx(Sfx.FALL, 0, 0.6f)
                wake(120f + maxD * 55f)
            }
            is Refill -> {
                var maxD = 0f
                for (s in e.spawns) {
                    val spr = Sprite(s.id, s.type, Special.NONE)
                    spr.x = s.c + 0.5f; spr.y = s.fromR + 0.5f; spr.phase = (s.id % 17) * 0.4f
                    sprites[s.id] = spr
                    val d = (s.toR - s.fromR).toFloat(); maxD = max(maxD, d)
                    tw.to(spr, Sprite.P_Y, s.toR + 0.5f, 170f + d * 55f, Easing.outBack)
                }
                wake(150f + maxD * 55f)
            }
            is CascadeStep -> {
                if (e.n >= 3) {
                    env.sfx(Sfx.CASCADE, min(8, e.n))
                    env.fx.floatText(env.ui.w / 2f, by + bsize * 0.42f, when { e.n >= 5 -> "Divine!"; e.n == 4 -> "Delicious!"; e.n == 3 -> "Sweet!"; else -> "" }, 34f, Pal.LEMON, 1100f)
                }
            }
            is SugarCrush -> {
                env.sfx(Sfx.MAKE); env.hap(Hap.STRONG)
                env.fx.floatText(env.ui.w / 2f, by + bsize * 0.4f, "Sugar Crush!", 34f, Pal.PINK, 1400f)
                for (cr in e.created) sp(cr.id)?.let { s ->
                    s.special = cr.special; s.sc = 1f
                    tw.to(s, Sprite.P_SC, 1.4f, 140f, Easing.outQ); tw.to(s, Sprite.P_SC, 1f, 300f, Easing.outElastic, 140f)
                    env.fx.emit(px(cr.c + 0.5f), py(cr.r + 0.5f), 0xFFFFFFFF.toInt(), 8, 110f, 1, cell * 0.12f, 0f, 480f)
                }
                wake(650f)
            }
            is Shuffled -> {
                env.sfx(Sfx.SHUFFLE); env.hap(Hap.MEDIUM)
                env.fx.floatText(env.ui.w / 2f, by + bsize / 2f, if (e.reason == "deadlock") "No moves - shuffling!" else "Shuffle!", 22f, Pal.WHITE, 1200f)
                for (m in e.moves) sp(m.id)?.let { s ->
                    s.type = m.type
                    tw.to(s, Sprite.P_X, m.c + 0.5f, 420f, Easing.inOut); tw.to(s, Sprite.P_Y, m.r + 0.5f, 420f, Easing.inOut)
                    tw.to(s, Sprite.P_ROT, 6.2832f, 420f, Easing.inOut)
                    s.rot = 0f
                }
                wake(480f)
            }
            is Ended -> {
                ended = e
                if (e.result == GameResult.WIN) {
                    env.sfx(Sfx.WIN); env.hap(Hap.WIN)
                    if (!env.reduceMotion) env.fx.confetti(140, env.ui.w)
                    env.fx.floatText(env.ui.w / 2f, by + bsize * 0.4f, "Level Complete!", 34f, Pal.LEMON, 1500f)
                } else { env.sfx(Sfx.LOSE); env.hap(Hap.MEDIUM); env.fx.floatText(env.ui.w / 2f, by + bsize * 0.4f, "Out of moves", 30f, Pal.WHITE, 1300f) }
                endDelay = if (e.result == GameResult.WIN) 1500f else 900f
            }
        }
    }

    private fun onCleared(e: Cleared) {
        val tw = env.tw; val fx = env.fx
        val big = e.effects.isNotEmpty()
        var sx = 0f; var sy = 0f
        for (cc in e.cleared) {
            sx += px(cc.c + 0.5f); sy += py(cc.r + 0.5f)
            val s = sprites.remove(cc.id)
            val colour = CANDY_COLORS[cc.type.coerceIn(0, 5)].glow
            fx.emit(px(cc.c + 0.5f), py(cc.r + 0.5f), colour, if (env.reduceMotion) 3 else 7, 90f, 0, cell * 0.1f, 220f, 520f)
            fx.emit(px(cc.c + 0.5f), py(cc.r + 0.5f), 0xFFFFFFFF.toInt(), if (env.reduceMotion) 1 else 3, 120f, 1, cell * 0.1f, 80f, 420f)
            if (s != null) {
                tw.to(s, Sprite.P_SC, 1.35f, 90f, Easing.outQ)
                tw.to(s, Sprite.P_A, 0f, 150f, Easing.lin, 40f)
                // keep drawing the dying sprite until its tween finishes
                dying.add(s); env.timers.after(260f) { dying.remove(s) }
            }
        }
        for (cr in e.created) sp(cr.id)?.let { s ->
            s.special = cr.special; s.type = cr.type
            s.sc = 1f; s.flash = 0f
            tw.to(s, Sprite.P_SC, 1.5f, 120f, Easing.outQ); tw.to(s, Sprite.P_SC, 1f, 300f, Easing.outElastic, 120f)
            fx.emit(px(cr.c + 0.5f), py(cr.r + 0.5f), 0xFFFFFFFF.toInt(), 12, 130f, 1, cell * 0.12f, 0f, 520f)
            fx.addLine(L_RING_, cr.c + 0.5f, cr.r + 0.5f, 380f, radius = 1.6f)
            fx.burst(cr.c + 0.5f, cr.r + 0.5f, 2.6f, 420f)
            env.sfx(Sfx.MAKE); env.hap(Hap.MEDIUM)
        }
        for (cv in e.converted) sp(cv.id)?.let { s ->
            s.special = cv.special; s.sc = 1f; s.flash = 0f
            tw.to(s, Sprite.P_SC, 1.4f, 120f, Easing.outQ); tw.to(s, Sprite.P_SC, 1f, 260f, Easing.outElastic, 120f)
        }
        for (s in sprites.values) { if (s.flash != 0f) tw.to(s, Sprite.P_FLASH, 0f, 120f); if (s.sc != 1f && s.special == Special.NONE) tw.to(s, Sprite.P_SC, 1f, 120f) }
        var shakeAmt = 0f
        for ((i, ef) in e.effects.withIndex()) {
            val d = i * 40f
            when (ef) {
                is Effect.Row -> { fx.addLine(Effects.L_ROW, 4f, ef.r + 0.5f, 420f, delayMs = d); env.sfx(Sfx.SPECIAL); env.hap(Hap.HEAVY); shakeAmt = max(shakeAmt, 3f) }
                is Effect.Col -> { fx.addLine(Effects.L_COL, ef.c + 0.5f, 4f, 420f, delayMs = d); env.sfx(Sfx.SPECIAL); env.hap(Hap.HEAVY); shakeAmt = max(shakeAmt, 3f) }
                is Effect.Blast -> { fx.burst(ef.c + 0.5f, ef.r + 0.5f, ef.radius * 2f + 2.2f, delayMs = d); fx.addLine(L_RING_, ef.c + 0.5f, ef.r + 0.5f, 420f, delayMs = d, radius = ef.radius + 1.1f); env.sfx(Sfx.BOOM); env.hap(Hap.HEAVY); shakeAmt = max(shakeAmt, 5f) }
                is Effect.BigCross -> {
                    fx.burst(ef.c + 0.5f, ef.r + 0.5f, 5f, delayMs = d)
                    fx.addLine(Effects.L_ROW, 4f, ef.r + 0.5f, 450f, delayMs = d); fx.addLine(Effects.L_COL, ef.c + 0.5f, 4f, 450f, delayMs = d)
                    fx.addLine(L_RING_, ef.c + 0.5f, ef.r + 0.5f, 450f, delayMs = d, radius = 2.4f); env.sfx(Sfx.BOOM); env.hap(Hap.BOMB); shakeAmt = max(shakeAmt, 7f)
                }
                is Effect.Bomb -> bolts(ef.r, ef.c, ef.targets, d)
                is Effect.BombConvert -> bolts(ef.r, ef.c, ef.targets, d)
                is Effect.BombAll -> {
                    fx.burst(ef.c + 0.5f, ef.r + 0.5f, 11f, 620f, d)
                    fx.addLine(Effects.L_FLASH, 0f, 0f, 520f, delayMs = d); fx.addLine(L_RING_, ef.c + 0.5f, ef.r + 0.5f, 560f, delayMs = d, radius = 9f)
                    env.sfx(Sfx.BOOM); env.hap(Hap.BOMB); shakeAmt = max(shakeAmt, 9f)
                }
            }
        }
        if (e.effects.any { it is Effect.Bomb || it is Effect.BombConvert }) { env.sfx(Sfx.BOLT); env.hap(Hap.BOMB); shakeAmt = max(shakeAmt, 5f) }
        if (shakeAmt > 0f && !env.reduceMotion) { shake.v[0] = shakeAmt; tw.to(shake, 0, 0f, 380f, Easing.lin) }
        val n = max(1, e.cleared.size)
        fx.floatText(sx / n, sy / n, "+${e.gain}", if (e.gain > 400) 26f else 20f, if (e.cascade > 1) Pal.LEMON else Pal.WHITE)
        wake(if (big) 330f else 230f)
    }

    private val dying = ArrayList<Sprite>()
    private val L_RING_ = Effects.L_RING

    private fun bolts(r: Int, c: Int, targets: List<Int>, delay: Float) {
        for ((i, k) in targets.withIndex()) env.fx.addLine(Effects.L_BOLT, c + 0.5f, r + 0.5f, 360f, colOf(k) + 0.5f, rowOf(k) + 0.5f, delay + i * 26f)
    }

    /* -------------------------------------------------------------- draw */
    fun draw() {
        val ui = env.ui; val c = ui.c
        val shx = if (shake.v[0] > 0.01f) sin(timeMs * 0.12f) * shake.v[0] else 0f
        val shy = if (shake.v[0] > 0.01f) sin(timeMs * 0.17f + 1f) * shake.v[0] else 0f
        c.save(); c.translate(shx, shy)

        // board plate + checker: drawn once into a bitmap, then blitted every frame
        drawBoardLayer(c)
        // jelly
        for (r in 0 until H) for (cc in 0 until W) {
            val j = game.jelly[r][cc]
            if (j > 0) {
                val l = bx + cc * cell; val t = by + r * cell
                ui.rrect(l + 2f, t + 2f, l + cell - 2f, t + cell - 2f, 9f, if (j >= 2) 0xB3E0409A.toInt() else 0x99FF7FC2.toInt())
                ui.rrectStroke(l + 2f, t + 2f, l + cell - 2f, t + cell - 2f, 9f, if (j >= 2) 0xFFB52A7C.toInt() else 0xFFE55AA8.toInt(), 2.2f)
                ui.rrect(l + 6f, t + 6f, l + cell * 0.55f, t + 11f, 3f, 0x66FFFFFF)
            }
        }
        // hint
        val h = hint
        val hintPulse = if (h != null) (sin(timeMs / 180f) * 0.5f + 0.5f) else 0f

        // candies
        fun drawSprite(s: Sprite, idle: Boolean) {
            val bmp = env.art.get(s.type, s.special)
            var scl = s.sc
            var wob = 0f
            if (idle && !env.reduceMotion) { scl *= 1f + sin(timeMs / 420f + s.phase) * 0.012f }
            val sel = selected
            var rot = s.rot
            val gr = ((s.y - 0.5f)).toInt(); val gc = ((s.x - 0.5f)).toInt()
            if (sel != null && sel.r == gr && sel.c == gc && abs(s.y - 0.5f - gr) < 0.05f) { scl *= 1.12f + sin(timeMs / 120f) * 0.04f; rot += sin(timeMs / 150f) * 0.08f }
            if (h != null && ((h.a.r == gr && h.a.c == gc) || (h.b.r == gr && h.b.c == gc))) { scl *= 1f + hintPulse * 0.16f; wob = hintPulse }
            val cx = px(s.x); val cy = py(s.y)
            val hw = cell * 0.5f * s.sx * scl; val hh = cell * 0.5f * s.sy * scl
            c.save(); c.translate(cx, cy)
            if (rot != 0f) c.rotate(rot * 57.29578f)
            bmpPaint.alpha = (s.a * 255f).toInt().coerceIn(0, 255)
            rect.set(-hw, -hh, hw, hh)
            c.drawBitmap(bmp, null, rect, bmpPaint)
            if (s.flash > 0.01f) {
                bmpPaint.alpha = (s.flash * 150f * s.a).toInt().coerceIn(0, 255)
                bmpPaint.colorFilter = flashFilter
                c.drawBitmap(bmp, null, rect, bmpPaint)
                bmpPaint.colorFilter = null
            }
            bmpPaint.alpha = 255
            c.restore()
        }
        c.save(); c.clipRect(bx - 4f, by - 4f, bx + bsize + 4f, by + bsize + 4f)
        val idle = canAct
        for (s in sprites.values) drawSprite(s, idle)
        for (s in dying) drawSprite(s, false)
        c.restore()

        // hammer hint
        if (hammerArmed) {
            ui.rrectStroke(bx - 4f, by - 4f, bx + bsize + 4f, by + bsize + 4f, 22f, Pal.PINK, 4f)
            ui.text("Tap a candy to smash it", ui.w / 2f, by - 18f, 15f, Pal.WHITE, Paint.Align.CENTER, Pal.INK, 4f)
        }
        c.restore()
        env.fx.draw(c, ui.p, cell, bx, by, bsize, ui.fontBlack)
    }

    private var plate: android.graphics.Bitmap? = null
    private var plateKey = 0L
    private val plateDst = RectF()

    private fun drawBoardLayer(c: android.graphics.Canvas) {
        val ui = env.ui
        val sc = env.ui.w.let { if (it > 0f) (c.width.toFloat() / it) else 1f }
        val pad = 14f
        val wU = bsize + pad * 2f; val hU = bsize + pad * 2f + 6f
        val wPx = (wU * sc).toInt().coerceAtLeast(1); val hPx = (hU * sc).toInt().coerceAtLeast(1)
        val key = (wPx.toLong() shl 32) or hPx.toLong()
        var bmp = plate
        if (bmp == null || key != plateKey) {
            bmp = try { android.graphics.Bitmap.createBitmap(wPx, hPx, android.graphics.Bitmap.Config.ARGB_8888) } catch (e: Throwable) { null }
            if (bmp == null) { drawBoardLayerDirect(c); return }
            val pc = android.graphics.Canvas(bmp)
            pc.scale(sc, sc); pc.translate(pad - bx + 0f, pad - by)
            val saved = ui.c; ui.c = pc
            drawBoardLayerDirect(pc)
            ui.c = saved
            plate = bmp; plateKey = key
        }
        plateDst.set(bx - pad, by - pad, bx - pad + wU, by - pad + hU)
        c.drawBitmap(bmp, null, plateDst, bmpPaint)
    }

    private fun drawBoardLayerDirect(c: android.graphics.Canvas) {
        val ui = env.ui
        ui.rrect(bx - 8f, by - 8f + 5f, bx + bsize + 8f, by + bsize + 8f + 5f, 24f, 0x55301856)
        ui.gradRRect(bx - 8f, by - 8f, bx + bsize + 8f, by + bsize + 8f, 24f, 0xCC5A3F98.toInt(), 0xD9301D5E.toInt())
        ui.rrectStroke(bx - 8f, by - 8f, bx + bsize + 8f, by + bsize + 8f, 24f, 0x99FFFFFF.toInt(), 3f)
        for (r in 0 until H) for (cc in 0 until W) {
            val l = bx + cc * cell; val t = by + r * cell
            ui.rrect(l + 1f, t + 1f, l + cell - 1f, t + cell - 1f, 8f, if ((r + cc) % 2 == 0) 0x2EFFFFFF else 0x14FFFFFF)
        }
    }

    private val flashFilter = android.graphics.PorterDuffColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_ATOP)

    fun destroy() { env.tw.clear(); env.timers.clear(); env.fx.clear() }
}

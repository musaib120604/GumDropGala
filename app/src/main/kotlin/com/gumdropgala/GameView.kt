package com.gumdropgala

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.gumdropgala.engine.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Screen { HOME, MAP, PLAY }
enum class Modal { NONE, INTRO, PAUSE, SETTINGS, PROFILE, DAILY, WIN, LOSE, SHOP }

/** The whole app is this one view: it draws every screen and modal, and routes touches. */
class GameView(ctx: Context, private val onQuit: () -> Unit) : View(ctx), Choreographer.FrameCallback {
    val env = Env(ctx)
    private val ui = env.ui
    private var screen = Screen.HOME
    private var modal = Modal.NONE
    private var modalFrom = Modal.NONE
    private var play: Play? = null
    private var selLevel = 1
    private var scale = 1f
    private var lastNs = 0L
    private var lifeTickMs = 0f
    private var attached = false
    private val anim = Vals(8) // 0 modal pop, 1 fade, 2..4 win stars, 5 title bounce
    private var mapScroll = 0f
    private var mapDragged = false
    private var lastTouchY = 0f
    private var downHit: Hit? = null
    private var boardTouch = false
    private var toast = ""
    private var toastMs = 0f
    private var winCoins = 0
    private var winStars = 0
    private var winScore = 0
    private var winNewBest = false
    private var dailyReward: DailyReward? = null
    private val rectF = RectF()

    init {
        isFocusable = true
        anim.v[1] = 1f
        env.tw.to(anim, 1, 0f, 450f, Easing.lin)
        centerMapOn(env.save.unlocked)
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            run { ui.safeTop = insets.systemWindowInsetTop / max(scale, 0.01f); ui.safeBottom = insets.systemWindowInsetBottom / max(scale, 0.01f) }
            insets
        }
    }

    /* ---------------------------------------------------------- lifecycle */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true; lastNs = 0L
        env.audio.start(); env.audio.startMusic(worldOf(env.save.unlocked))
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        attached = false
        Choreographer.getInstance().removeFrameCallback(this)
        env.audio.stop(); env.persist()
        super.onDetachedFromWindow()
    }

    fun pause() { env.audio.stop(); env.persist(); Choreographer.getInstance().removeFrameCallback(this); if (screen == Screen.PLAY && modal == Modal.NONE) openModal(Modal.PAUSE) }
    fun resume() {
        if (!attached) return
        env.save.tickLives(); lastNs = 0L
        env.audio.start(); env.audio.startMusic(if (screen == Screen.PLAY) play?.game?.level?.world ?: 0 else worldOf(env.save.unlocked))
        Choreographer.getInstance().removeFrameCallback(this); Choreographer.getInstance().postFrameCallback(this)
    }

    /** Returns true if the back press was consumed. */
    fun back(): Boolean {
        when {
            modal == Modal.PAUSE -> closeModal()
            modal == Modal.WIN || modal == Modal.LOSE -> { leavePlay() }
            modal != Modal.NONE -> closeModal()
            screen == Screen.PLAY -> openModal(Modal.PAUSE)
            screen == Screen.MAP -> go(Screen.HOME)
            else -> return false
        }
        return true
    }

    private fun worldOf(level: Int) = ((level.coerceIn(1, LEVEL_COUNT)) - 1) / LEVELS_PER_WORLD

    override fun doFrame(frameTimeNanos: Long) {
        if (!attached) return
        val dt = if (lastNs == 0L) 16f else min(50f, (frameTimeNanos - lastNs) / 1_000_000f)
        lastNs = frameTimeNanos
        ui.timeMs += dt
        val p = play
        if (screen == Screen.PLAY && p != null) p.update(dt) else { env.tw.update(dt); env.timers.update(dt); env.fx.update(dt) }
        if (toastMs > 0f) toastMs -= dt
        lifeTickMs += dt; if (lifeTickMs >= 1000f) { lifeTickMs = 0f; env.save.tickLives() }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /* ------------------------------------------------------------- layout */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scale = w / 360f
        ui.w = 360f; ui.h = h / scale
        rootWindowInsets?.let {
            @Suppress("DEPRECATION")
            run { ui.safeTop = it.systemWindowInsetTop / scale; ui.safeBottom = it.systemWindowInsetBottom / scale }
        }
        env.art.rebuild(max(24, (42f * 0.98f * scale).toInt()), env.save.settings.symbols)
        ui.preloadBackdrop(worldOf(env.save.unlocked))
    }

    /* -------------------------------------------------------------- input */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x / scale; val y = e.y / scale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ui.pointerDown = true; ui.downX = x; ui.downY = y; lastTouchY = y; mapDragged = false
                downHit = ui.hitAt(x, y)
                boardTouch = false
                val dh = downHit
                if (dh?.onDrag != null) dh.onDrag.invoke(x, y)
                else if (dh == null && screen == Screen.PLAY && modal == Modal.NONE) {
                    val p = play
                    if (p != null && p.inBoard(x, y)) { boardTouch = true; p.onDown(x, y) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dh = downHit
                if (dh?.onDrag != null) dh.onDrag.invoke(x, y)
                else if (boardTouch) play?.onMove(x, y)
                else if (screen == Screen.MAP && modal == Modal.NONE) {
                    if (abs(y - ui.downY) > 9f) mapDragged = true
                    if (mapDragged) { mapScroll = (mapScroll + (y - lastTouchY)).coerceIn(0f, mapMax()); lastTouchY = y }
                }
                if (!mapDragged) lastTouchY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ui.pointerDown = false
                if (boardTouch) play?.onUp()
                else {
                    // Hit regions are rebuilt every frame, so compare the regions under the finger now, not stale objects.
                    val up = ui.hitAt(x, y)
                    val tap = up?.onTap
                    if (e.actionMasked == MotionEvent.ACTION_UP && downHit != null && tap != null && !mapDragged && up === ui.hitAt(ui.downX, ui.downY)) {
                        env.sfx(Sfx.BTN); env.hap(Hap.TAP)
                        tap.invoke()
                    }
                }
                boardTouch = false; downHit = null
            }
        }
        return true
    }

    /* --------------------------------------------------------- navigation */
    private fun go(s: Screen) {
        if (s != Screen.PLAY) { play?.destroy(); play = null; env.audio.startMusic(worldOf(env.save.unlocked)) }
        anim.v[1] = 1f; env.tw.to(anim, 1, 0f, 380f, Easing.lin) // after destroy(), which clears all tweens
        if (s == Screen.MAP) centerMapOn(env.save.unlocked)
        screen = s
    }

    private fun openModal(m: Modal) {
        modal = m; anim.v[0] = 0f
        env.tw.to(anim, 0, 1f, if (env.reduceMotion) 90f else 360f, Easing.outBack)
    }

    private fun closeModal() { modal = Modal.NONE }

    private fun toast(s: String) { toast = s; toastMs = 1800f }

    private fun startLevel(n: Int) {
        env.save.tickLives()
        if (env.save.lives <= 0) { openModal(Modal.SHOP); return }
        closeModal()
        selLevel = n
        play?.destroy()
        val lv = levelConfig(n)
        val p = Play(env, n) { ended -> onEnded(ended) }
        play = p
        env.save.profile.played++
        env.persist()
        env.audio.startMusic(lv.world)
        anim.v[1] = 1f; env.tw.to(anim, 1, 0f, 380f, Easing.lin)
        screen = Screen.PLAY
    }

    private fun leavePlay() { closeModal(); go(Screen.MAP) }

    private fun onEnded(e: Ended) {
        val s = env.save; val pf = s.profile
        pf.totalScore += e.score; pf.bestCascade = max(pf.bestCascade, play?.game?.stats?.maxCascade ?: 0)
        pf.specials += play?.game?.progress?.specialsMade ?: 0
        if (e.result == GameResult.WIN) {
            pf.wins++
            winNewBest = e.stars > s.stars[selLevel]
            s.stars[selLevel] = max(s.stars[selLevel], e.stars)
            if (selLevel < LEVEL_COUNT) s.unlocked = max(s.unlocked, selLevel + 1)
            winCoins = 20 + e.stars * 10 + (if (winNewBest) 15 else 0)
            s.coins += winCoins; winStars = e.stars; winScore = e.score
            env.persist()
            openModal(Modal.WIN)
            anim.v[2] = 0f; anim.v[3] = 0f; anim.v[4] = 0f
            for (i in 0 until e.stars) {
                env.timers.after(450f + i * 420f) {
                    env.tw.to(anim, 2 + i, 1f, 380f, Easing.outBack); env.sfx(Sfx.STAR, i); env.hap(Hap.MEDIUM)
                    env.fx.emit(ui.w / 2f + (i - 1) * 70f, ui.h * 0.36f, Pal.LEMON, 14, 140f, 1, 5f, 0f, 700f)
                }
            }
        } else {
            pf.losses++
            s.spendLife(); env.persist()
            openModal(Modal.LOSE)
        }
    }

    private fun mapMax() = 100 * NODE_DY + 360f - ui.h
    private fun centerMapOn(level: Int) { mapScroll = ((level - 1) * NODE_DY - 140f).coerceIn(0f, max(0f, 100 * NODE_DY + 360f - 640f)) }

    /* --------------------------------------------------------------- draw */
    override fun onDraw(canvas: Canvas) {
        canvas.save(); canvas.scale(scale, scale)
        ui.begin(canvas)
        ui.inputOn = modal == Modal.NONE
        when (screen) {
            Screen.HOME -> drawHome()
            Screen.MAP -> drawMap()
            Screen.PLAY -> drawPlay()
        }
        if (modal != Modal.NONE) {
            ui.inputOn = true
            ui.fill(Pal.SCRIM); canvas.drawRect(0f, 0f, ui.w, ui.h, ui.p)
            ui.hit(0f, 0f, ui.w, ui.h, null) // swallow touches behind the card
            when (modal) {
                Modal.INTRO -> drawIntro(); Modal.PAUSE -> drawPause(); Modal.SETTINGS -> drawSettings()
                Modal.PROFILE -> drawProfile(); Modal.DAILY -> drawDaily(); Modal.WIN -> drawWin()
                Modal.LOSE -> drawLose(); Modal.SHOP -> drawShop(); else -> {}
            }
        }
        if (screen != Screen.PLAY) env.fx.draw(canvas, ui.p, 42f, 0f, 0f, ui.w, ui.fontBlack)
        else if (modal != Modal.NONE) env.fx.draw(canvas, ui.p, 42f, 0f, 0f, ui.w, ui.fontBlack)
        if (toastMs > 0f) {
            val a = min(1f, toastMs / 250f)
            val tw = ui.textWidth(toast, 16f) + 36f
            ui.rrect((ui.w - tw) / 2f, ui.h - 120f, (ui.w + tw) / 2f, ui.h - 84f, 18f, (Pal.INK and 0x00FFFFFF) or ((a * 235f).toInt() shl 24))
            ui.text(toast, ui.w / 2f, ui.h - 102f, 16f, (Pal.WHITE and 0x00FFFFFF) or ((a * 255f).toInt() shl 24))
        }
        if (anim.v[1] > 0.01f) {
            ui.fill(((anim.v[1] * 255f).toInt() shl 24) or 0x2B1B4A); canvas.drawRect(0f, 0f, ui.w, ui.h, ui.p)
        }
        canvas.restore()
    }

    private fun resourceBar(showCoins: Boolean = true, leftInset: Float = 0f, rightInset: Float = 0f) {
        val y = ui.safeTop + 30f
        val s = env.save
        if (showCoins) ui.pill(ui.w - 14f - rightInset, y, Icon.COIN, "${s.coins}", true) { openModal(Modal.SHOP) }
        var label = "${s.lives}"
        if (s.lives < MAX_LIVES && s.livesAt > 0) {
            val ms = max(0L, s.livesAt - System.currentTimeMillis()); val sec = (ms / 1000).toInt()
            label = "${s.lives}  ${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
        } else if (s.lives >= MAX_LIVES) label = "${s.lives}  Full"
        ui.pill(14f + leftInset, y, Icon.HEART, label) { if (s.lives < MAX_LIVES) openModal(Modal.SHOP) }
    }

    /* ---------------------------------------------------------------- home */
    private fun drawHome() {
        val w = ui.w; val h = ui.h; val c = ui.c
        ui.backdrop(worldOf(env.save.unlocked))
        resourceBar(true, 54f, 54f)
        ui.purpleButton(34f, ui.safeTop + 30f, 21f, Icon.GEAR) { modalFrom = Modal.NONE; openModal(Modal.SETTINGS) }
        ui.purpleButton(w - 34f, ui.safeTop + 30f, 21f, Icon.USERW) { openModal(Modal.PROFILE) }
        // bouncing candy parade
        val cy = h * 0.2f + 44f
        ui.circle(w / 2f, cy, 128f, 0x22FFFFFF); ui.circle(w / 2f, cy, 92f, 0x22FFFFFF)
        val sizePx = 56f
        for (i in 0 until 6) {
            val bmp = env.art.icon(i, Special.NONE, (sizePx * scale).toInt())
            val x = w / 2f + (i - 2.5f) * 52f
            val y = cy + sin(ui.timeMs / 380f + i * 0.9f) * 9f
            val rot = sin(ui.timeMs / 600f + i) * 8f
            c.save(); c.translate(x, y); c.rotate(rot)
            rectF.set(-24f, -24f, 24f, 24f); c.drawBitmap(bmp, null, rectF, null); c.restore()
        }
        // gold logo with a thick chocolate outline
        val ty = cy + 80f
        ui.text("Gumdrop", w / 2f, ty, 56f, Pal.LEMON, Paint.Align.CENTER, 0xFF7A3E00.toInt(), 18f)
        ui.text("Gala", w / 2f, ty + 56f, 56f, Pal.LEMON, Paint.Align.CENTER, 0xFF7A3E00.toInt(), 18f)
        val lvl = min(env.save.unlocked, LEVEL_COUNT)
        val py = h * 0.57f
        ui.button(50f, py, w - 50f, py + 76f, "Play", Styles.PINK_HOT, { selLevel = lvl; openModal(Modal.INTRO) }, 32f)
        ui.text("Level $lvl", w / 2f, py + 92f, 14f, Pal.WHITE, Paint.Align.CENTER, 0xFF8A1F5A.toInt(), 5f)
        ui.button(70f, py + 108f, w - 70f, py + 158f, "Level Map", Styles.BLUE_PILL, { go(Screen.MAP) }, 19f)
        val navTop = h - ui.safeBottom - 78f
        ui.text("${env.save.totalStars()} stars collected", w / 2f, navTop - 22f, 15f, Pal.WHITE, Paint.Align.CENTER, Pal.INK, 4f)
        ui.navBar(navTop, arrayOf("Daily", "Shop", "Map"), arrayOf(Icon.GIFT, Icon.SHOP, Icon.MAP), -1,
            arrayOf({ dailyReward = null; openModal(Modal.DAILY) }, { openModal(Modal.SHOP) }, { go(Screen.MAP) }))
        if (env.save.dailyState().canClaim) {
            val cx0 = 12f + (w - 24f) / 6f
            ui.circle(cx0 + 16f, navTop + 4f, 9f, 0xFFFF3B5C.toInt()); ui.text("!", cx0 + 16f, navTop + 4f, 12f, Pal.WHITE)
        }
    }

    /* ----------------------------------------------------------------- map */
    private val NODE_DY = 78f
    private val roadCorner = android.graphics.CornerPathEffect(36f)
    private val roadDash = android.graphics.ComposePathEffect(android.graphics.DashPathEffect(floatArrayOf(16f, 16f), 0f), roadCorner)

    private fun nodeX(n: Int): Float = ui.w / 2f + sin(n * 0.85f) * 96f
    private fun nodeY(n: Int): Float = ui.h - 170f - (n - 1) * NODE_DY + mapScroll

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        rectF.set(cx - rx, cy - ry, cx + rx, cy + ry); ui.fill(color); ui.c.drawOval(rectF, ui.p)
    }

    private fun drawMap() {
        val w = ui.w; val h = ui.h; val c = ui.c
        val centerLevel = (((h / 2f - (h - 170f + mapScroll)) / -NODE_DY) + 1).toInt().coerceIn(1, LEVEL_COUNT)
        ui.backdrop(worldOf(centerLevel))
        val unlocked = env.save.unlocked
        // candy road: pink ribbon with white dashes
        ui.path.reset()
        var started = false
        for (n in 1..LEVEL_COUNT) {
            val y = nodeY(n); if (y < -80f || y > h + 80f) { started = false; continue }
            if (!started) { ui.path.moveTo(nodeX(n), y); started = true } else ui.path.lineTo(nodeX(n), y)
        }
        ui.p.shader = null; ui.p.style = Paint.Style.STROKE; ui.p.strokeJoin = Paint.Join.ROUND
        ui.p.strokeCap = Paint.Cap.ROUND; ui.p.strokeWidth = 30f; ui.p.color = 0x55A81C62; ui.p.pathEffect = roadCorner; c.drawPath(ui.path, ui.p)
        ui.p.strokeWidth = 24f; ui.p.color = 0xFFFFA6D0.toInt(); c.drawPath(ui.path, ui.p)
        ui.p.strokeCap = Paint.Cap.BUTT; ui.p.color = 0xFFFFFFFF.toInt(); ui.p.pathEffect = roadDash; c.drawPath(ui.path, ui.p)
        ui.p.pathEffect = null; ui.p.style = Paint.Style.FILL
        for (n in 1..LEVEL_COUNT) {
            val y = nodeY(n); if (y < -60f || y > h + 60f) continue
            val x = nodeX(n)
            if ((n - 1) % LEVELS_PER_WORLD == 0) {
                val wy = y - 58f
                ui.pinkPanel(w / 2f - 110f, wy - 15f, w / 2f + 110f, wy + 15f, 15f)
                ui.text(WORLD_NAMES[worldOf(n)], w / 2f, wy, 14f, 0xFF8A2A5A.toInt())
            }
            val open = n <= unlocked; val st = env.save.stars[n]; val cur = n == unlocked
            val rx = if (cur) 37f else 30f; val ry = if (cur) 27f else 21f
            val bob = if (cur) sin(ui.timeMs / 300f) * 3f else 0f
            if (cur) {
                val pulse = sin(ui.timeMs / 260f) * 0.5f + 0.5f
                ui.circle(x, y + bob, 58f + pulse * 6f, 0x30FFFFFF); ui.circle(x, y + bob, 44f, 0x44FFF3B0)
            }
            oval(x, y + bob + 6f, rx, ry, if (open) 0xFFB8226B.toInt() else 0xFFA898C0.toInt())
            oval(x, y + bob, rx, ry, if (open) 0xFFFF4F9A.toInt() else 0xFFD9CCE8.toInt())
            oval(x - rx * 0.1f, y + bob - ry * 0.4f, rx * 0.78f, ry * 0.5f, 0x55FFFFFF)
            if (open) ui.text("$n", x, y + bob, if (cur) 24f else 20f, Pal.WHITE, Paint.Align.CENTER, 0xFF9B1F5A.toInt(), 5f) else ui.icon(Icon.LOCK, x, y + bob, 20f)
            if (open) for (i in 0 until 3) ui.icon(if (i < st) Icon.STAR_ON else Icon.STAR_OFF, x + (i - 1) * 18f, y + ry + 13f + (if (i == 1) 4f else 0f), 18f)
            if (cur) {
                val av = env.art.icon(env.save.profile.avatar % 6, Special.NONE, (40f * scale).toInt())
                val ay = y - ry - 34f + sin(ui.timeMs / 320f) * 4f
                ui.circle(x, ay + 3f, 24f, 0x55301856); ui.circle(x, ay, 24f, Pal.WHITE)
                rectF.set(x - 18f, ay - 18f, x + 18f, ay + 18f); c.drawBitmap(av, null, rectF, null)
            }
            if (open) ui.hit(x - rx, y - ry, x + rx, y + ry + 24f, { selLevel = n; openModal(Modal.INTRO) })
        }
        // header + tab bar
        resourceBar(true, 0f, 54f)
        ui.purpleButton(w - 34f, ui.safeTop + 30f, 21f, Icon.GEAR) { modalFrom = Modal.NONE; openModal(Modal.SETTINGS) }
        ui.navBar(h - ui.safeBottom - 78f, arrayOf("Map", "Daily", "Shop", "Home"), arrayOf(Icon.MAP, Icon.GIFT, Icon.SHOP, Icon.HOME), 0,
            arrayOf({ }, { dailyReward = null; openModal(Modal.DAILY) }, { openModal(Modal.SHOP) }, { go(Screen.HOME) }))
    }

    /* ---------------------------------------------------------------- play */
    private fun objText(o: ObjectiveStatus): String = when (o) {
        is ObjectiveStatus.Score -> "Score ${o.target}"
        is ObjectiveStatus.Collect -> "${o.current}/${o.target}"
        is ObjectiveStatus.Jelly -> "${o.current}/${o.target}"
        is ObjectiveStatus.MakeSpecial -> "${o.current}/${o.target}"
    }

    private fun drawObjIcon(o: ObjectiveStatus, cx: Float, cy: Float, size: Float) {
        when (o) {
            is ObjectiveStatus.Collect -> { val b = env.art.icon(o.type, Special.NONE, (size * scale).toInt()); rectF.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f); ui.c.drawBitmap(b, null, rectF, null) }
            is ObjectiveStatus.Jelly -> ui.icon(Icon.JELLY, cx, cy, size * 0.85f)
            is ObjectiveStatus.MakeSpecial -> { val b = env.art.icon(0, Special.STRIPE_H, (size * scale).toInt()); rectF.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f); ui.c.drawBitmap(b, null, rectF, null) }
            is ObjectiveStatus.Score -> ui.icon(Icon.STAR_ON, cx, cy, size * 0.9f)
        }
    }

    private fun drawPlay() {
        val p = play ?: return
        val w = ui.w; val h = ui.h; val g = p.game
        ui.backdrop(g.level.world)
        val top = ui.safeTop
        val ink = 0xFF8A2A5A.toInt(); val ink2 = 0xFF8A1F5A.toInt()
        // pink HUD: moves | objectives | pause, with the star meter along the bottom
        val pt = top + 8f; val pb = top + 102f
        ui.pinkPanel(10f, pt, w - 10f, pb, 22f)
        val mv = g.moves; val low = mv <= 5
        ui.text("Moves", 52f, pt + 18f, 14f, ink, Paint.Align.CENTER, 0, 0f, ui.fontBold)
        ui.text("$mv", 52f, pt + 46f, 34f, if (low) 0xFFE0103A.toInt() else 0xFF7A2A6A.toInt(), Paint.Align.CENTER, Pal.WHITE, 6f)
        val bl = 96f; val br = w - 68f
        ui.rrect(bl, pt + 10f, br, pt + 64f, 16f, 0x558A1F5A)
        val objs = g.objectiveStatus()
        val colW = (br - bl) / max(1, objs.size)
        for ((i, o) in objs.withIndex()) {
            val cx = bl + colW * (i + 0.5f); val cy = pt + 37f
            val label = if (o is ObjectiveStatus.Score) "${o.target}" else objText(o)
            drawObjIcon(o, cx - 22f, cy, 30f)
            ui.text(label, cx + 12f, cy, if (label.length > 5) 15f else 18f, if (o.done) 0xFF8CFFC8.toInt() else Pal.WHITE, Paint.Align.CENTER, ink2, 4f)
            if (o.done) ui.icon(Icon.CHECK, cx - 8f, cy - 15f, 14f)
        }
        ui.roundButton(w - 40f, pt + 37f, 22f, Icon.PAUSE) { openModal(Modal.PAUSE) }
        // star progress
        val lv = g.level
        val bx0 = 28f; val bx1 = w - 28f; val byy = pb - 17f
        ui.rrect(bx0, byy - 5f, bx1, byy + 5f, 5f, 0x99702060.toInt())
        val frac = (p.scoreShown / lv.stars[2]).coerceIn(0f, 1f)
        ui.gradRRect(bx0, byy - 5f, bx0 + max(10f, (bx1 - bx0) * frac), byy + 5f, 5f, 0xFFFFE073.toInt(), 0xFFFFA81F.toInt())
        for (k in 0 until 3) ui.icon(if (g.score >= lv.stars[k]) Icon.STAR_ON else Icon.STAR_OFF, bx0 + (bx1 - bx0) * (lv.stars[k].toFloat() / lv.stars[2]).coerceIn(0f, 1f) - (if (k == 2) 6f else 0f), byy, 20f)
        ui.text("Level ${p.levelNo}  ·  Score ${p.scoreShown.toInt()}", w / 2f, pb + 20f, 15f, Pal.WHITE, Paint.Align.CENTER, Pal.INK, 4.5f)
        // board, centred in the space between the HUD and the booster bar
        val barTop = h - ui.safeBottom - 98f
        val boardTop = pb + 40f
        val availH = barTop - 16f - boardTop
        val side = min(w - 20f, availH)
        p.layout(boardTop + max(0f, (availH - side) / 2f), w, availH)
        p.draw()
        // booster bar
        ui.pinkPanel(14f, barTop, w - 14f, barTop + 84f, 24f)
        val bs = env.save
        val items = boosterItems
        for (i in items.indices) {
            val it = items[i]
            val cx = w / 2f + (i - 1) * 92f; val cy = barTop + 35f
            val n = bs.boost(it.first)
            val armed = it.first == Booster.HAMMER && p.hammerArmed
            ui.circle(cx, cy + 4f, 29f, 0xFF1458A8.toInt())
            ui.circle(cx, cy, 29f, if (armed) Pal.LEMON_DARK else 0xFF3D9CF0.toInt())
            ui.circle(cx, cy - 1f, 24f, if (armed) 0xFFFFF1B8.toInt() else 0xFFF3FAFF.toInt())
            ui.icon(it.second, cx, cy - 1f, 30f)
            ui.circle(cx + 22f, cy + 22f, 11f, Pal.WHITE); ui.circle(cx + 22f, cy + 22f, 9f, if (n > 0) 0xFFFF4F9A.toInt() else 0xFFFFC93C.toInt())
            ui.text(if (n > 0) "$n" else "+", cx + 22f, cy + 22f, 12f, if (n > 0) Pal.WHITE else 0xFF5A3A00.toInt())
            ui.text(it.third, cx, barTop + 74f, 10.5f, ink, Paint.Align.CENTER, 0, 0f, ui.fontBold)
            ui.hit(cx - 34f, barTop + 2f, cx + 34f, barTop + 82f, { useBooster(it.first, p) })
        }
    }

    private val boosterItems = arrayOf(Triple(Booster.HAMMER, Icon.HAMMER, "Hammer"), Triple(Booster.SHUFFLE, Icon.SHUFFLE, "Shuffle"), Triple(Booster.MOVES, Icon.PLUS5, "+5 Moves"))

    private fun useBooster(b: Booster, p: Play) {
        if (!p.canAct) { toast("Wait for the candies to settle"); return }
        val s = env.save
        if (b == Booster.HAMMER && p.hammerArmed) { p.hammerArmed = false; return }
        if (s.boost(b) <= 0) {
            val cost = 80
            if (s.coins < cost) { toast("Not enough coins"); openModal(Modal.SHOP); return }
            s.coins -= cost; s.boosters[b.ordinal]++; env.sfx(Sfx.COIN); toast("Bought for $cost coins")
        }
        s.boosters[b.ordinal]--
        when (b) {
            Booster.HAMMER -> p.hammerArmed = true
            Booster.SHUFFLE -> p.useShuffle()
            Booster.MOVES -> p.addMoves(5)
        }
        env.persist()
    }

    /* -------------------------------------------------------------- modals */
    private fun popScale(): Float = (0.7f + 0.3f * anim.v[0]).coerceAtLeast(0.01f)

    private inline fun card(wid: Float, hei: Float, body: (Float, Float, Float, Float) -> Unit) {
        val l = (ui.w - wid) / 2f; val t = (ui.h - hei) / 2f
        val c = ui.c
        c.save(); c.scale(popScale(), popScale(), ui.w / 2f, ui.h / 2f)
        ui.card(l, t, l + wid, t + hei)
        body(l, t, l + wid, t + hei)
        c.restore()
    }

    private fun title(txt: String, cx: Float, y: Float) = ui.ribbon(cx, y, txt)

    private fun closeX(r: Float, t: Float) = ui.roundButton(r - 6f, t + 6f, 17f, Icon.CLOSE) { closeModal() }

    private fun objDesc(o: Objective): String = when (o) {
        is Objective.Score -> "Reach ${o.target} points"
        is Objective.Collect -> "Collect ${o.target} ${CANDY_COLORS[o.type].name.substringBefore(' ').lowercase()} candies"
        is Objective.Jelly -> "Clear all the jelly"
        is Objective.MakeSpecial -> "Make ${o.target} special candies"
    }

    private fun drawIntro() {
        val lv = levelConfig(selLevel)
        card(300f, 400f) { l, t, r, b ->
            val cx = (l + r) / 2f
            closeX(r, t)
            title("Level $selLevel", cx, t + 40f)
            ui.text(WORLD_NAMES[lv.world], cx, t + 68f, 13f, Pal.INK2, Paint.Align.CENTER, font = ui.fontBold)
            var y = t + 104f
            for (o in lv.objectives) {
                val st: ObjectiveStatus = when (o) {
                    is Objective.Collect -> ObjectiveStatus.Collect(o.type, 0, o.target, false)
                    is Objective.Jelly -> ObjectiveStatus.Jelly(0, 1, false)
                    is Objective.MakeSpecial -> ObjectiveStatus.MakeSpecial(0, o.target, false)
                    is Objective.Score -> ObjectiveStatus.Score(0, o.target, false)
                }
                drawObjIcon(st, l + 40f, y, 34f)
                ui.text(objDesc(o), l + 64f, y, 15f, Pal.INK, Paint.Align.LEFT)
                y += 46f
            }
            ui.text("${lv.moves} moves", cx, y + 6f, 18f, Pal.INK)
            y += 34f
            ui.text("Star targets", cx, y + 6f, 12f, Pal.INK2, Paint.Align.CENTER, font = ui.fontBold)
            for (k in 0 until 3) {
                val x = cx + (k - 1) * 84f
                ui.icon(Icon.STAR_ON, x - 26f, y + 32f, 22f)
                ui.text("${lv.stars[k]}", x + 6f, y + 32f, 14f, Pal.INK, Paint.Align.LEFT)
            }
            ui.button(l + 30f, b - 76f, r - 30f, b - 22f, "Play", Styles.PINK_HOT, { startLevel(selLevel) }, 24f)
        }
    }

    private fun drawPause() {
        card(280f, 330f) { l, t, r, b ->
            val cx = (l + r) / 2f
            title("Paused", cx, t + 42f)
            ui.button(l + 30f, t + 76f, r - 30f, t + 128f, "Resume", Styles.MINT, { closeModal() })
            ui.button(l + 30f, t + 142f, r - 30f, t + 194f, "Restart", Styles.SKY, { startLevel(selLevel) })
            ui.button(l + 30f, t + 208f, r - 30f, t + 260f, "Settings", Styles.GRAPE, { modalFrom = Modal.PAUSE; openModal(Modal.SETTINGS) })
            ui.button(l + 30f, t + 274f, r - 30f, t + 318f, "Quit to map", Styles.GHOST, { leavePlay() }, 16f)
        }
    }

    private fun slider(l: Float, r: Float, y: Float, label: String, v: Float, set: (Float) -> Unit) {
        ui.text(label, l, y - 18f, 14f, Pal.INK, Paint.Align.LEFT)
        ui.text("${(v * 100).toInt()}%", r, y - 18f, 13f, Pal.INK2, Paint.Align.RIGHT)
        ui.rrect(l, y - 5f, r, y + 5f, 5f, 0xFFE6DAF3.toInt())
        ui.rrect(l, y - 5f, l + (r - l) * v, y + 5f, 5f, Pal.PINK)
        val kx = l + (r - l) * v
        ui.circle(kx, y + 2f, 13f, 0xFFC73866.toInt()); ui.circle(kx, y, 13f, Pal.WHITE); ui.circle(kx, y, 7f, Pal.PINK)
        ui.hit(l - 12f, y - 24f, r + 12f, y + 24f, null) { x, _ -> set(((x - l) / (r - l)).coerceIn(0f, 1f)) }
    }

    private fun toggle(l: Float, r: Float, y: Float, label: String, on: Boolean, set: (Boolean) -> Unit) {
        ui.text(label, l, y, 15f, Pal.INK, Paint.Align.LEFT)
        val tx = r - 50f
        ui.rrect(tx, y - 15f, tx + 50f, y + 15f, 15f, if (on) Pal.MINT else 0xFFD9CBE8.toInt())
        ui.circle(if (on) tx + 35f else tx + 15f, y, 12f, Pal.WHITE)
        ui.hit(l, y - 20f, r, y + 20f, { set(!on); env.applySettings(); env.persist() })
    }

    private fun drawSettings() {
        val s = env.save.settings
        card(310f, 520f) { l0, t, r0, b ->
            val l = l0 + 26f; val r = r0 - 26f; val cx = (l0 + r0) / 2f
            ui.roundButton(r0 - 6f, t + 6f, 17f, Icon.CLOSE) { modal = if (modalFrom == Modal.PAUSE) Modal.PAUSE else Modal.NONE; modalFrom = Modal.NONE }
            title("Settings", cx, t + 40f)
            slider(l, r, t + 100f, "Music", s.music) { s.music = it; env.applySettings() }
            slider(l, r, t + 160f, "Sound effects", s.sfx) { s.sfx = it; env.applySettings() }
            toggle(l, r, t + 214f, "Vibration", s.vibrate) { s.vibrate = it; if (it) env.hap.fire(Hap.STRONG) }
            toggle(l, r, t + 256f, "Reduce motion", s.reduceMotion) { s.reduceMotion = it }
            toggle(l, r, t + 298f, "Candy symbols", s.symbols) { s.symbols = it; env.art.rebuild(max(24, (42f * 0.98f * scale).toInt()), it) }
            toggle(l, r, t + 340f, "Larger text", s.bigText) { s.bigText = it }
            if (!env.hap.supported) ui.text("This device reports no vibration motor", cx, t + 372f, 12f, Pal.INK2, font = ui.fontBold)
            ui.button(l, t + 392f, r, t + 436f, "Test vibration", Styles.SKY, { env.hap.fire(Hap.HEAVY); env.hap.fire(Hap.BOMB) }, 16f)
            ui.button(l, t + 452f, r, t + 496f, "Done", Styles.MINT, { env.persist(); modal = if (modalFrom == Modal.PAUSE) Modal.PAUSE else Modal.NONE; modalFrom = Modal.NONE }, 17f)
        }
    }

    private val NAMES = listOf("Sweet Tooth", "Candy Captain", "Gumdrop Gus", "Sugar Star", "Lolly Lou", "Jelly Jo", "Choco Champ", "Fizzy Fox")

    private fun drawProfile() {
        val pf = env.save.profile
        card(310f, 470f) { l, t, r, b ->
            val cx = (l + r) / 2f
            closeX(r, t)
            title("Profile", cx, t + 40f)
            val bmp = env.art.icon(pf.avatar % 6, Special.NONE, (84f * scale).toInt())
            ui.circle(cx, t + 118f, 50f, 0xFFF1E6FF.toInt())
            rectF.set(cx - 42f, t + 76f, cx + 42f, t + 160f); ui.c.drawBitmap(bmp, null, rectF, null)
            ui.hit(cx - 50f, t + 68f, cx + 50f, t + 168f, { pf.avatar = (pf.avatar + 1) % 6; env.persist() })
            ui.text(pf.name, cx, t + 186f, 20f, Pal.INK)
            ui.text("tap candy or name to change", cx, t + 206f, 11f, Pal.INK2, font = ui.fontBold)
            ui.hit(cx - 100f, t + 172f, cx + 100f, t + 200f, { val i = NAMES.indexOf(pf.name); pf.name = NAMES[(i + 1) % NAMES.size]; env.persist() })
            val rows = listOf("Stars" to "${env.save.totalStars()}", "Levels won" to "${env.save.levelsWon()}", "Games played" to "${pf.played}", "Win rate" to if (pf.played > 0) "${pf.wins * 100 / max(1, pf.wins + pf.losses)}%" else "-",
                "Best cascade" to "x${pf.bestCascade}", "Specials made" to "${pf.specials}", "Total score" to "${pf.totalScore}")
            var y = t + 236f
            for ((k, v) in rows) { ui.text(k, l + 28f, y, 14f, Pal.INK2, Paint.Align.LEFT, font = ui.fontBold); ui.text(v, r - 28f, y, 15f, Pal.INK, Paint.Align.RIGHT); y += 28f }
        }
    }

    private fun rewardLabel(r: DailyReward): String = when {
        r.fullLives -> "300 + lives"
        r.booster != null -> "${r.boosterCount}x " + when (r.booster) { Booster.HAMMER -> "Hammer"; Booster.SHUFFLE -> "Shuffle"; else -> "+5" }
        else -> "${r.coins}"
    }

    private fun drawDaily() {
        val st = env.save.dailyState()
        card(320f, 430f) { l, t, r, b ->
            val cx = (l + r) / 2f
            closeX(r, t)
            title("Daily Gift", cx, t + 40f)
            val streak = if (st.canClaim) st.dayIndex else (env.save.dailyStreak - 1).coerceAtLeast(0) % 7
            for (i in 0 until 7) {
                val col = i % 4; val row = i / 4
                val x = l + 20f + col * 70f + (if (row == 1) 35f else 0f); val y = t + 76f + row * 104f
                val claimed = if (st.canClaim) i < st.dayIndex else i <= streak
                val today = st.canClaim && i == st.dayIndex
                ui.rrect(x, y + 3f, x + 62f, y + 92f + 3f, 14f, 0xFFE4C2A4.toInt())
                ui.rrect(x, y, x + 62f, y + 92f, 14f, if (today) 0xFFFFF1B8.toInt() else if (claimed) 0xFFD6F5E6.toInt() else Pal.WHITE)
                if (today) ui.rrectStroke(x, y, x + 62f, y + 92f, 14f, Pal.LEMON, 3.5f)
                ui.text("Day ${i + 1}", x + 31f, y + 14f, 11f, Pal.INK2, font = ui.fontBold)
                val rw = DAILY_REWARDS[i]
                ui.icon(if (rw.booster == Booster.HAMMER) Icon.HAMMER else if (rw.booster == Booster.SHUFFLE) Icon.SHUFFLE else if (rw.booster == Booster.MOVES) Icon.PLUS5 else if (rw.fullLives) Icon.HEART else Icon.COIN, x + 31f, y + 44f, 28f)
                ui.text(rewardLabel(rw), x + 31f, y + 74f, if (rewardLabel(rw).length > 8) 10f else 12f, Pal.INK)
                if (claimed) ui.icon(Icon.CHECK, x + 50f, y + 12f, 16f)
            }
            if (st.canClaim) ui.button(l + 40f, b - 108f, r - 40f, b - 52f, "Claim", Styles.PINK, {
                val rw = env.save.claimDaily()
                if (rw != null) { env.persist(); env.sfx(Sfx.COIN); env.hap(Hap.WIN); env.fx.confetti(60, ui.w); toast("Reward claimed: ${rewardLabel(rw)}") }
            }, 22f)
            else ui.text("Come back tomorrow for the next gift", cx, b - 80f, 14f, Pal.INK2, font = ui.fontBold)
            ui.text("Miss a day and the streak restarts", cx, b - 24f, 11f, Pal.INK2, font = ui.fontBold)
        }
    }

    private fun drawWin() {
        card(300f, 440f) { l, t, r, b ->
            val cx = (l + r) / 2f
            title("Sweet!", cx, t + 44f)
            for (i in 0 until 3) {
                val x = cx + (i - 1) * 78f; val y = t + 112f - (if (i == 1) 10f else 0f)
                val a = anim.v[2 + i]
                ui.icon(Icon.STAR_OFF, x, y, 64f)
                if (a > 0.01f) ui.icon(Icon.STAR_ON, x, y, 64f * a.coerceAtLeast(0.01f))
            }
            ui.text("$winScore", cx, t + 190f, 36f, Pal.INK)
            ui.text(if (winNewBest) "New best!" else "Score", cx, t + 218f, 14f, Pal.INK2, font = ui.fontBold)
            ui.icon(Icon.COIN, cx - 28f, t + 250f, 24f); ui.text("+$winCoins", cx - 8f, t + 250f, 20f, Pal.INK, Paint.Align.LEFT)
            val next = selLevel + 1
            if (next <= LEVEL_COUNT) ui.button(l + 30f, t + 282f, r - 30f, t + 334f, "Next level", Styles.PINK_HOT, { selLevel = next; startLevel(next) }, 20f)
            ui.button(l + 30f, t + 350f, cx - 5f, t + 396f, "Map", Styles.SKY, { leavePlay() }, 16f)
            ui.button(cx + 5f, t + 350f, r - 30f, t + 396f, "Replay", Styles.GRAPE, { startLevel(selLevel) }, 16f)
        }
    }

    private fun drawLose() {
        card(300f, 400f) { l, t, r, b ->
            val cx = (l + r) / 2f
            title("Out of moves", cx, t + 44f)
            val g = play?.game
            if (g != null) for ((i, o) in g.objectiveStatus().withIndex()) { ui.text("${objDesc(g.level.objectives[i])}: ${objText(o)}", cx, t + 90f + i * 22f, 13f, Pal.INK2, font = ui.fontBold) }
            ui.icon(Icon.HEART, cx, t + 170f, 44f)
            ui.text("${env.save.lives} lives left", cx, t + 206f, 16f, Pal.INK)
            val canExtra = env.save.coins >= 120
            ui.button(l + 30f, t + 232f, r - 30f, t + 284f, "+5 moves  ·  120", Styles.LEMON, {
                val s = env.save
                if (s.coins >= 120 && play != null) { s.coins -= 120; s.lives++; s.lives = min(MAX_LIVES, s.lives); play!!.game.addMoves(5); env.persist(); closeModal(); toast("+5 moves") }
            }, 17f, canExtra)
            ui.button(l + 30f, t + 298f, r - 30f, t + 348f, "Try again", Styles.PINK, { startLevel(selLevel) }, 19f)
            ui.button(l + 30f, t + 356f - 4f, r - 30f, t + 388f, "Map", Styles.GHOST, { leavePlay() }, 14f)
        }
    }

    private fun drawShop() {
        val s = env.save
        card(310f, 470f) { l, t, r, b ->
            val cx = (l + r) / 2f
            closeX(r, t)
            title("Sweet Shop", cx, t + 40f)
            ui.pill(cx - 100f, t + 82f, Icon.COIN, "${s.coins}")
            ui.pill(cx + 20f, t + 82f, Icon.HEART, "${s.lives}/$MAX_LIVES")
            fun row(y: Float, ic: Icon, name: String, cost: Int, enabled: Boolean, act: () -> Unit) {
                ui.rrect(l + 22f, y, r - 22f, y + 60f, 16f, Pal.WHITE); ui.rrectStroke(l + 22f, y, r - 22f, y + 60f, 16f, Pal.EDGE, 2.5f)
                ui.icon(ic, l + 52f, y + 30f, 30f); ui.text(name, l + 78f, y + 30f, 15f, Pal.INK, Paint.Align.LEFT)
                ui.button(r - 110f, y + 10f, r - 30f, y + 50f, "$cost", Styles.LEMON, act, 16f, enabled)
            }
            row(t + 112f, Icon.HEART, "Refill lives", 100, s.lives < MAX_LIVES && s.coins >= 100) { s.coins -= 100; s.refillLives(); env.persist(); env.sfx(Sfx.COIN); toast("Lives refilled") }
            row(t + 182f, Icon.HAMMER, "Hammer x1", 80, s.coins >= 80) { s.coins -= 80; s.boosters[0]++; env.persist(); env.sfx(Sfx.COIN) }
            row(t + 252f, Icon.SHUFFLE, "Shuffle x1", 80, s.coins >= 80) { s.coins -= 80; s.boosters[1]++; env.persist(); env.sfx(Sfx.COIN) }
            row(t + 322f, Icon.PLUS5, "+5 Moves x1", 80, s.coins >= 80) { s.coins -= 80; s.boosters[2]++; env.persist(); env.sfx(Sfx.COIN) }
            ui.text("Owned: ${s.boosters[0]} hammer · ${s.boosters[1]} shuffle · ${s.boosters[2]} +5", cx, t + 404f, 12f, Pal.INK2, font = ui.fontBold)
            if (s.lives < MAX_LIVES && s.livesAt > 0) { val sec = (max(0L, s.livesAt - System.currentTimeMillis()) / 1000).toInt(); ui.text("Next life in ${sec / 60}:${(sec % 60).toString().padStart(2, '0')}", cx, t + 430f, 13f, Pal.INK2, font = ui.fontBold) }
            ui.text("Win levels to earn coins", cx, t + 452f, 11f, Pal.INK2, font = ui.fontBold)
        }
    }
}

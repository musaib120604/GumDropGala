package com.gumdropgala.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import com.gumdropgala.GameView
import java.io.File
import javax.imageio.ImageIO

/** Headless renderer used by CI to produce PNGs of the main screens (no display needed). */
object Screenshots {
    private const val W = 450; private const val H = 800
    private var t = 1_000_000_000L

    private fun frames(n: Int) { repeat(n) { Choreographer.getInstance().dispatch(t); t += 16_666_667L } }

    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) { try { val f = c.getDeclaredField(name); f.isAccessible = true; return f.get(o) } catch (e: NoSuchFieldException) { c = c.superclass } }
        return null
    }

    private fun call(o: Any, name: String, vararg args: Any?) {
        val m = o.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true; m.invoke(o, *args)
    }

    fun run(dir: String) {
        File(dir).mkdirs()
        if (System.getProperty("gumdrop.save.dir") == null) System.setProperty("gumdrop.save.dir", File(dir, ".save").absolutePath)
        val view = GameView(Context()) { }
        view.hostLayout(W, H); view.hostAttach()
        fun shot(name: String) {
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            view.hostDraw(Canvas(bmp))
            ImageIO.write(bmp.img, "png", File(dir, "$name.png"))
            println("wrote $name.png")
        }
        fun tap(x: Float, y: Float) {
            view.onTouchEvent(MotionEvent(x, y, MotionEvent.ACTION_DOWN)); frames(2)
            view.onTouchEvent(MotionEvent(x, y, MotionEvent.ACTION_UP)); frames(20)
        }
        frames(90); shot("1_home")
        tap(W / 2f, 622f); frames(30); shot("2_map")
        view.back(); frames(30)
        call(view, "startLevel", 2); frames(150); shot("3_play")
        // make one real move so matching, falling and effects are on screen
        val p = field(view, "play")
        if (p != null) {
            val game = field(p, "game")!!
            val mv = game.javaClass.getMethod("findMoves").invoke(game) as List<*>
            if (mv.isNotEmpty()) {
                val m0 = mv[0]!!
                val a = m0.javaClass.getMethod("getA").invoke(m0)!!
                val b = m0.javaClass.getMethod("getB").invoke(m0)!!
                fun n(o: Any, g: String) = (o.javaClass.getMethod(g).invoke(o) as Int)
                val bx = (field(p, "bx") as Float); val by = (field(p, "by") as Float); val cell = (field(p, "cell") as Float)
                val sc = W / 360f
                fun cx(pos: Any) = (bx + (n(pos, "getC") + 0.5f) * cell) * sc
                fun cy(pos: Any) = (by + (n(pos, "getR") + 0.5f) * cell) * sc
                view.onTouchEvent(MotionEvent(cx(a), cy(a), MotionEvent.ACTION_DOWN)); frames(2)
                view.onTouchEvent(MotionEvent(cx(b), cy(b), MotionEvent.ACTION_MOVE)); frames(2)
                view.onTouchEvent(MotionEvent(cx(b), cy(b), MotionEvent.ACTION_UP)); frames(34); shot("4_play_matching")
                frames(260); shot("5_play_settled")
                // rough software-rendering cost of one play-screen frame (the real window uses the GPU pipeline)
                val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                repeat(20) { view.hostDraw(Canvas(bmp)) }
                val t0 = System.nanoTime(); repeat(100) { view.hostDraw(Canvas(bmp)) }
                println("avg draw time: %.2f ms/frame".format((System.nanoTime() - t0) / 100e6))
            }
        }
        view.pause(); frames(40); shot("6_pause")
        view.back(); view.resume(); frames(10)
        call(view, "go", Class.forName("com.gumdropgala.Screen").enumConstants.first { it.toString() == "HOME" }); frames(20)
        run {
            val modal = Class.forName("com.gumdropgala.Modal").enumConstants
            call(view, "openModal", modal.first { it.toString() == "SETTINGS" }); frames(40); shot("7_settings")
            view.back(); frames(20)
            call(view, "openModal", modal.first { it.toString() == "SHOP" }); frames(40); shot("8_shop")
        }
        view.hostDetach()
    }
}

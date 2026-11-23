package com.gumdropgala.desktop

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import com.gumdropgala.GameView
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.locks.LockSupport
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.min
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "--screenshots") {
        System.setProperty("java.awt.headless", "true")
        Screenshots.run(args.getOrNull(1) ?: "screenshots")
        return
    }
    SwingUtilities.invokeLater { GameWindow().open() }
}

/** Hosts the portrait game view in a resizable window, letter-boxed between 9:16 and 9:20. */
class GamePanel(private val view: GameView) : JPanel() {
    private var ox = 0; private var oy = 0; private var vw = 0; private var vh = 0
    private var dpi = 1.0
    private var pxW = 0; private var pxH = 0

    init {
        isOpaque = true; isFocusable = true; background = Color(0x1B1030)
        val m = object : MouseAdapter() {
            private fun ev(e: MouseEvent, action: Int) = MotionEvent(((e.x - ox) * dpi).toFloat(), ((e.y - oy) * dpi).toFloat(), action)
            override fun mousePressed(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) { requestFocusInWindow(); view.onTouchEvent(ev(e, MotionEvent.ACTION_DOWN)) } }
            override fun mouseDragged(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) view.onTouchEvent(ev(e, MotionEvent.ACTION_MOVE)) }
            override fun mouseReleased(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) view.onTouchEvent(ev(e, MotionEvent.ACTION_UP)) }
            override fun mouseWheelMoved(e: MouseWheelEvent) { view.scrollMap((-e.preciseWheelRotation * 70.0).toFloat()) }
        }
        addMouseListener(m); addMouseMotionListener(m); addMouseWheelListener(m)
    }

    /** Recomputes the play area; returns true when the view had to be re-laid-out. */
    fun relayout(): Boolean {
        dpi = graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        vw = min(width, (height * 9f / 16f).toInt()).coerceAtLeast(1)
        vh = min(height, (vw / 0.45f).toInt()).coerceAtLeast(1)
        ox = (width - vw) / 2; oy = (height - vh) / 2
        val pw = (vw * dpi).roundToInt().coerceAtLeast(1); val ph = (vh * dpi).roundToInt().coerceAtLeast(1)
        if (pw == pxW && ph == pxH) return false
        pxW = pw; pxH = ph
        view.hostLayout(pw, ph)
        return true
    }

    private var frameImg: BufferedImage? = null

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        relayout()
        // Render the whole frame into one back buffer, then blit it once: predictable speed on any Java2D pipeline.
        var img = frameImg
        if (img == null || img.width != pxW || img.height != pxH) { img = BufferedImage(pxW, pxH, BufferedImage.TYPE_INT_RGB); frameImg = img }
        val cv = Canvas(img.createGraphics(), pxW, pxH)
        view.hostDraw(cv)
        cv.dispose()
        g.drawImage(img, ox, oy, vw, vh, null)
        Toolkit.getDefaultToolkit().sync()
    }
}

class GameWindow {
    private val frame = JFrame("Gumdrop Gala")
    private val view = GameView(Context()) { quit() }
    private val panel = GamePanel(view)
    private var fullscreen = false
    @Volatile private var alive = true

    fun open() {
        try { ImageIO.read(GameWindow::class.java.getResourceAsStream("/icon.png"))?.let { frame.iconImage = it } } catch (e: Exception) { /* optional */ }
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val h = min(820, (screen.height * 0.92).toInt())
        panel.preferredSize = Dimension((h * 9f / 16f).toInt(), h)
        frame.contentPane.add(panel)
        frame.minimumSize = Dimension(320, 480)
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.pack(); frame.setLocationRelativeTo(null)
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { quit() }
            override fun windowIconified(e: WindowEvent) { view.pause() }
            override fun windowDeiconified(e: WindowEvent) { view.resume() }
        })
        panel.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> if (!view.back()) quit()
                    KeyEvent.VK_F11 -> toggleFullscreen()
                }
            }
        })
        frame.isVisible = true
        panel.requestFocusInWindow()
        panel.relayout()
        view.hostAttach()
        startLoop()
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val dev = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        frame.dispose()
        frame.isUndecorated = fullscreen
        if (fullscreen) dev.fullScreenWindow = frame else { dev.fullScreenWindow = null }
        frame.isVisible = true
        panel.requestFocusInWindow()
    }

    private fun quit() {
        if (!alive) return
        alive = false
        view.hostDetach() // saves progress and stops audio
        frame.dispose()
        System.exit(0)
    }

    private fun startLoop() {
        val t = Thread {
            val frameNs = 1_000_000_000L / 60
            var next = System.nanoTime()
            while (alive) {
                next += frameNs
                try {
                    SwingUtilities.invokeAndWait {
                        if (!alive) return@invokeAndWait
                        Choreographer.getInstance().dispatch(System.nanoTime())
                        if (frame.isShowing && view.dirty) panel.paintImmediately(0, 0, panel.width, panel.height)
                    }
                } catch (e: Exception) { /* keep rendering */ }
                val wait = next - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait) else next = System.nanoTime()
            }
        }
        t.name = "gumdrop-render"; t.isDaemon = true; t.start()
    }
}

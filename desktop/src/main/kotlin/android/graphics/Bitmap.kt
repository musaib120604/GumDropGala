package android.graphics

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO

class Bitmap internal constructor(internal var img: BufferedImage?) {
    enum class Config { ALPHA_8, RGB_565, ARGB_8888 }
    val width: Int get() = img?.width ?: 0
    val height: Int get() = img?.height ?: 0
    val isRecycled: Boolean get() = img == null
    fun recycle() { img = null }

    companion object {
        @JvmStatic fun createBitmap(w: Int, h: Int, cfg: Config): Bitmap =
            Bitmap(BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), if (cfg == Config.RGB_565) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB_PRE))

        @JvmStatic fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap {
            val s = src.img ?: return createBitmap(w, h, Config.ARGB_8888)
            if (s.width == w && s.height == h) return src
            val out = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB_PRE)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, if (filter) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(s, 0, 0, w, h, null); g.dispose()
            return Bitmap(out)
        }
    }
}

object BitmapFactory {
    class Options { @JvmField var inPreferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888 }

    @JvmStatic fun decodeStream(stream: InputStream?, outPadding: Rect? = null, opts: Options? = null): Bitmap? {
        if (stream == null) return null
        val raw = ImageIO.read(stream) ?: return null
        val type = if (opts?.inPreferredConfig == Bitmap.Config.RGB_565) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB_PRE
        if (raw.type == type) return Bitmap(raw)
        val out = BufferedImage(raw.width, raw.height, type)
        val g = out.createGraphics(); g.drawImage(raw, 0, 0, null); g.dispose()
        return Bitmap(out)
    }
}

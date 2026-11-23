package android.graphics

import java.awt.Font
import java.awt.Shape
import java.awt.font.FontRenderContext

class Typeface internal constructor(internal val base: Font) {
    private val sized = HashMap<Float, Font>()
    internal fun at(size: Float): Font = sized.getOrPut(size) { base.deriveFont(size) }

    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        internal val FRC = FontRenderContext(null, true, true)

        private fun load(res: String, fallbackStyle: Int): Font = try {
            Typeface::class.java.getResourceAsStream(res)!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }
        } catch (e: Throwable) { Font("SansSerif", fallbackStyle, 12) }

        private val black by lazy { Typeface(load("/fonts/FiraSans-Black.ttf", Font.BOLD)) }
        private val bold by lazy { Typeface(load("/fonts/FiraSans-Bold.ttf", Font.BOLD)) }

        @JvmStatic fun create(family: String?, style: Int): Typeface = if (family != null && family.contains("black")) black else bold
        val DEFAULT: Typeface get() = bold
        val DEFAULT_BOLD: Typeface get() = bold

        private class Entry(val outline: Shape, val advance: Float)
        private val cache = object : LinkedHashMap<String, Entry>(512, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Entry>?) = size > 3000
        }
        private fun entry(tf: Typeface, size: Float, text: String): Entry {
            val key = "${System.identityHashCode(tf)}|$size|$text"
            return cache.getOrPut(key) {
                val f = tf.at(size)
                val gv = f.createGlyphVector(FRC, text)
                Entry(gv.outline, f.getStringBounds(text, FRC).width.toFloat())
            }
        }
        internal fun outline(tf: Typeface, size: Float, text: String): Shape = entry(tf, size, text).outline
        internal fun advance(tf: Typeface, size: Float, text: String): Float = if (text.isEmpty()) 0f else entry(tf, size, text).advance
    }
}

class Paint(var flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Align { LEFT, CENTER, RIGHT }
    class FontMetrics(@JvmField var ascent: Float, @JvmField var descent: Float)

    var color: Int = 0xFF000000.toInt()
    var style: Style = Style.FILL
    var strokeWidth: Float = 0f
    var strokeCap: Cap = Cap.BUTT
    var strokeJoin: Join = Join.MITER
    var shader: Shader? = null
    var pathEffect: PathEffect? = null
    var colorFilter: ColorFilter? = null
    var textSize: Float = 12f
    var textAlign: Align = Align.LEFT
    var typeface: Typeface = Typeface.DEFAULT
    var isAntiAlias: Boolean = true

    var alpha: Int
        get() = color ushr 24
        set(v) { color = (v.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF) }

    private var fmFont: Font? = null
    private var fmCache = FontMetrics(0f, 0f)
    val fontMetrics: FontMetrics
        get() {
            val f = typeface.at(textSize)
            if (f !== fmFont) {
                val lm = f.getLineMetrics("Hg", Typeface.FRC)
                fmCache = FontMetrics(-lm.ascent, lm.descent); fmFont = f
            }
            return fmCache
        }

    fun measureText(s: String): Float = Typeface.advance(typeface, textSize, s)

    companion object { const val ANTI_ALIAS_FLAG = 1; const val FILTER_BITMAP_FLAG = 2 }
}

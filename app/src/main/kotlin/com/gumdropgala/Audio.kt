package com.gumdropgala

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

enum class Sfx { SELECT, SWAP, INVALID, MATCH, POP, FALL, MAKE, SPECIAL, BOOM, CASCADE, STAR, WIN, LOSE, BTN, COIN, SHUFFLE, BOLT }

/**
 * All sound is synthesised in code (no audio assets): effects are rendered once into short buffers and a
 * background thread mixes them, plus a looping procedural music track, into one AudioTrack.
 * Music and effects have independent volumes.
 */
class Audio {
    @Volatile var sfxVolume = 0.8f
    @Volatile var musicVolume = 0.55f
    @Volatile private var running = false
    @Volatile private var musicOn = false
    @Volatile private var world = 0

    private val rate = 22050
    private var thread: Thread? = null
    private val lock = Any()
    private val voices = ArrayList<Voice>()
    private val bank = HashMap<Int, ShortArray>()
    @Volatile private var bankReady = false

    private class Voice(val data: ShortArray, val gain: Float) { var pos = 0 }

    fun start() {
        if (running) return
        running = true
        val t = Thread { runMixer() }
        t.name = "gumdrop-audio"
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        thread?.let { try { it.join(400) } catch (e: InterruptedException) { /* ignore */ } }
        thread = null
        synchronized(lock) { voices.clear() }
    }

    fun startMusic(worldIndex: Int) { world = worldIndex; musicOn = true }
    fun stopMusic() { musicOn = false }

    fun play(s: Sfx, variant: Int = 0, gain: Float = 1f) {
        if (!running || !bankReady || sfxVolume <= 0f) return
        val data = synchronized(lock) { bank[s.ordinal * 16 + variant.coerceIn(0, 15)] ?: bank[s.ordinal * 16] } ?: return
        synchronized(lock) {
            if (voices.size >= 14) voices.removeAt(0)
            voices.add(Voice(data, gain))
        }
    }

    /* ------------------------------------------------------------ mixer */

    private fun runMixer() {
        var track: AudioTrack? = null
        try {
            renderBank()
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, 2048) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val n = 512
            val block = ShortArray(n)
            val mix = FloatArray(n)
            var music: ShortArray? = null
            var musicWorld = -1
            var musicPos = 0
            while (running) {
                java.util.Arrays.fill(mix, 0f)
                if (musicOn && musicVolume > 0f) {
                    if (music == null || musicWorld != world) { musicWorld = world; music = renderMusic(world); musicPos = 0 }
                    val mb = music
                    val mv = musicVolume * 0.42f / 32768f
                    for (i in 0 until n) { mix[i] += mb[musicPos] * mv; musicPos++; if (musicPos >= mb.size) musicPos = 0 }
                }
                val sv = sfxVolume / 32768f
                synchronized(lock) {
                    val it = voices.iterator()
                    while (it.hasNext()) {
                        val v = it.next()
                        val g = sv * v.gain
                        var i = 0
                        while (i < n && v.pos < v.data.size) { mix[i] += v.data[v.pos] * g; v.pos++; i++ }
                        if (v.pos >= v.data.size) it.remove()
                    }
                }
                for (i in 0 until n) block[i] = (tanh(mix[i].toDouble()) * 32000.0).toInt().toShort()
                track.write(block, 0, n)
            }
        } catch (e: Throwable) {
            // Audio is optional: if the device can't create a track the game stays silent but playable.
        } finally {
            try { track?.stop() } catch (e: Exception) { /* ignore */ }
            try { track?.release() } catch (e: Exception) { /* ignore */ }
            running = false
        }
    }

    /* ------------------------------------------------------------ synthesis */

    private val pent = intArrayOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21)
    private fun hz(semi: Int): Float = (261.63 * 2.0.pow(semi / 12.0)).toFloat()
    private val noiseRand = java.util.Random(1234)

    private fun tone(buf: FloatArray, start: Float, freq: Float, dur: Float, wave: Int, vol: Float, freqEnd: Float = 0f, wrap: Boolean = false) {
        val s0 = (start * rate).toInt()
        val n = (dur * rate).toInt()
        val att = max(1, (0.008f * rate).toInt())
        val decayRatio = 0.0001 / vol
        var phase = 0.0
        for (i in 0 until n) {
            var idx = s0 + i
            if (idx >= buf.size) { if (wrap) idx %= buf.size else break }
            val t = i.toFloat() / n
            val f = if (freqEnd > 0f) freq * (freqEnd / freq).pow(t) else freq
            phase += 2.0 * PI * f / rate
            val p = phase % (2.0 * PI)
            val w = when (wave) {
                0 -> sin(p)
                1 -> (2.0 / PI) * Math.asin(sin(p))
                2 -> if (p < PI) 1.0 else -1.0
                else -> p / PI - 1.0
            }
            val env = if (i < att) vol.toDouble() * i / att else vol.toDouble() * decayRatio.pow((i - att).toDouble() / max(1, n - att))
            buf[idx] += (w * env).toFloat()
        }
    }

    /** mode 0 = low-pass sweep, 1 = high-pass sweep, 2 = band-ish. */
    private fun noise(buf: FloatArray, start: Float, dur: Float, f0: Float, f1: Float, vol: Float, mode: Int, wrap: Boolean = false) {
        val s0 = (start * rate).toInt()
        val n = (dur * rate).toInt()
        var y1 = 0f
        var y2 = 0f
        val decayRatio = 0.0001 / vol
        for (i in 0 until n) {
            var idx = s0 + i
            if (idx >= buf.size) { if (wrap) idx %= buf.size else break }
            val t = i.toFloat() / n
            val fc = f0 * (max(30f, f1) / f0).pow(t)
            val a = (1.0 - exp(-2.0 * PI * fc / rate)).toFloat()
            val x = noiseRand.nextFloat() * 2f - 1f
            y1 += a * (x - y1)
            y2 += a * 0.5f * (y1 - y2)
            val o = when (mode) { 0 -> y1; 1 -> x - y1; else -> y1 - y2 }
            buf[idx] += o * (vol * decayRatio.pow(t.toDouble())).toFloat()
        }
    }

    private fun toShorts(buf: FloatArray): ShortArray = ShortArray(buf.size) { (tanh(buf[it].toDouble()) * 30000.0).toInt().toShort() }

    private fun renderBank() {
        val tmp = HashMap<Int, ShortArray>()
        fun put(s: Sfx, v: Int, buf: FloatArray) { tmp[s.ordinal * 16 + v] = toShorts(buf) }
        fun buf(sec: Float) = FloatArray((sec * rate).toInt())
        run { val b = buf(0.1f); tone(b, 0f, 660f, 0.07f, 1, 0.16f, 990f); put(Sfx.SELECT, 0, b) }
        run { val b = buf(0.15f); noise(b, 0f, 0.1f, 600f, 2400f, 0.1f, 2); tone(b, 0f, 300f, 0.09f, 0, 0.1f, 520f); put(Sfx.SWAP, 0, b) }
        run { val b = buf(0.25f); tone(b, 0f, 240f, 0.16f, 3, 0.1f, 150f); tone(b, 0.07f, 200f, 0.12f, 2, 0.05f, 120f); put(Sfx.INVALID, 0, b) }
        for (v in 0..8) {
            val b = buf(0.45f)
            val base = pent[v % pent.size] + (if (v > 4) 12 else 0)
            for (i in 0 until 5) tone(b, i * 0.045f, hz(base + pent[i % 5]), 0.2f, 1, 0.15f)
            tone(b, 0f, hz(base - 12), 0.18f, 0, 0.14f)
            put(Sfx.MATCH, v, b)
        }
        run { val b = buf(0.2f); tone(b, 0f, 520f, 0.12f, 0, 0.2f, 180f); noise(b, 0f, 0.08f, 3000f, 800f, 0.08f, 0); put(Sfx.POP, 0, b) }
        run { val b = buf(0.12f); tone(b, 0f, 150f, 0.08f, 0, 0.09f, 70f); put(Sfx.FALL, 0, b) }
        run { val b = buf(0.6f); intArrayOf(0, 4, 7, 12, 16).forEachIndexed { i, s -> tone(b, i * 0.05f, hz(s + 12), 0.25f, 0, 0.13f) }; put(Sfx.MAKE, 0, b) }
        run {
            val b = buf(0.5f); tone(b, 0f, 300f, 0.4f, 3, 0.09f, 1800f)
            intArrayOf(0, 4, 7).forEach { tone(b, 0.05f, hz(it + 12), 0.4f, 1, 0.1f) }
            noise(b, 0f, 0.3f, 4000f, 600f, 0.1f, 2); put(Sfx.SPECIAL, 0, b)
        }
        run {
            val b = buf(0.8f); noise(b, 0f, 0.6f, 1800f, 90f, 0.35f, 0); tone(b, 0f, 110f, 0.5f, 0, 0.35f, 38f); tone(b, 0f, 60f, 0.5f, 2, 0.1f, 30f)
            intArrayOf(0, 7, 12, 19).forEachIndexed { i, s -> tone(b, 0.1f + i * 0.06f, hz(s + 12), 0.4f, 1, 0.08f) }; put(Sfx.BOOM, 0, b)
        }
        for (v in 2..8) { val b = buf(0.25f); val s = pent[v % 6]; tone(b, 0f, hz(s + 12), 0.18f, 0, 0.12f, hz(s + 19)); put(Sfx.CASCADE, v, b) }
        for (v in 0..2) { val b = buf(0.5f); tone(b, 0f, hz(12 + v * 4), 0.4f, 1, 0.2f); tone(b, 0.05f, hz(24 + v * 4), 0.35f, 0, 0.12f); put(Sfx.STAR, v, b) }
        run {
            val b = buf(1.4f)
            intArrayOf(0, 4, 7, 12, 16, 19, 24).forEachIndexed { i, s -> tone(b, i * 0.11f, hz(s), 0.5f, 1, 0.17f); tone(b, i * 0.11f, hz(s + 12), 0.4f, 0, 0.08f) }
            put(Sfx.WIN, 0, b)
        }
        run { val b = buf(1.1f); intArrayOf(7, 4, 0, -5).forEachIndexed { i, s -> tone(b, i * 0.16f, hz(s), 0.45f, 1, 0.15f, hz(s - 1)) }; put(Sfx.LOSE, 0, b) }
        run { val b = buf(0.08f); tone(b, 0f, 880f, 0.045f, 2, 0.05f, 1200f); put(Sfx.BTN, 0, b) }
        run { val b = buf(0.3f); tone(b, 0f, 988f, 0.08f, 2, 0.07f); tone(b, 0.07f, 1319f, 0.18f, 2, 0.07f); put(Sfx.COIN, 0, b) }
        run { val b = buf(0.5f); noise(b, 0f, 0.45f, 500f, 3500f, 0.12f, 2); tone(b, 0f, 200f, 0.4f, 0, 0.08f, 600f); put(Sfx.SHUFFLE, 0, b) }
        run { val b = buf(0.25f); tone(b, 0f, 1400f, 0.18f, 3, 0.06f, 300f); noise(b, 0f, 0.15f, 6000f, 1500f, 0.07f, 1); put(Sfx.BOLT, 0, b) }
        synchronized(lock) { bank.putAll(tmp) }
        bankReady = true
    }

    /** A gentle 4-bar loop in a pentatonic key. The root shifts with each world. */
    private fun renderMusic(worldIndex: Int): ShortArray {
        val bpm = 112f
        val eighth = 60f / bpm / 2f
        val steps = 32
        val buf = FloatArray((steps * eighth * rate).toInt())
        val root = (worldIndex * 2) % 12 - 2
        val prog = arrayOf(intArrayOf(0, 4, 7), intArrayOf(-3, 0, 4), intArrayOf(-7, -3, 0), intArrayOf(-5, -1, 2))
        val arp = intArrayOf(0, 1, 2, 1, 0, 2, 1, 2)
        for (st in 0 until steps) {
            val bar = (st / 8) % 4
            val beat = st % 8
            val ch = prog[bar]
            val t0 = st * eighth
            if (beat == 0 || beat == 4) tone(buf, t0, hz(ch[0] + root - 12), 0.42f, 1, 0.3f, 0f, true)
            if (beat % 2 == 0) noise(buf, t0, 0.04f, 9000f, 7000f, 0.05f, 1, true)
            tone(buf, t0, hz(ch[arp[beat]] + root + 12), 0.22f, 0, 0.16f, 0f, true)
            val h = ((st * 2654435761L) and 0xFFFFFFFFL) % 100
            if (h < 46) tone(buf, t0, hz(pent[((h + bar * 3) % 8).toInt()] + root + 12), 0.3f, 1, 0.13f, 0f, true)
        }
        return ShortArray(buf.size) { (tanh(buf[it].toDouble()) * 30000.0).toInt().toShort() }
    }
}

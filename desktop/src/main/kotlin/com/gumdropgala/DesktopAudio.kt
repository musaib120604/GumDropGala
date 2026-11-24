package com.gumdropgala

import android.content.Context
import java.io.BufferedInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import kotlin.math.log10

/**
 * Desktop audio: plays the bundled OGG effects and music loops through Java Sound (OGG support comes from the
 * Vorbis SPI on the classpath). If anything can't be decoded it falls back to the built-in synthesiser,
 * so the game is never silent.
 */
class Audio(@Suppress("UNUSED_PARAMETER") private val ctx: Context) {
    private class Sample(val fmt: AudioFormat, val data: ByteArray)

    private val synth = SynthAudio()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "gumdrop-audio-io").also { it.isDaemon = true } }
    private val samples = HashMap<String, Sample>()
    @Volatile private var synthMode = false
    @Volatile private var loaded = false
    @Volatile private var running = false
    private var music: Clip? = null
    private var musicWorld = -1
    private var wantMusic = false
    private var wantWorld = 0
    private val live = AtomicInteger(0)

    var sfxVolume = 0.8f
        set(v) { field = v; synth.sfxVolume = v }
    var musicVolume = 0.55f
        set(v) { field = v; synth.musicVolume = v; applyMusicVolume() }

    private fun gain(clip: Clip, linear: Float) {
        try {
            val c = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val db = if (linear <= 0.0001f) c.minimum else (20.0 * log10(linear.toDouble())).toFloat()
            c.value = db.coerceIn(c.minimum, c.maximum)
        } catch (e: Exception) { /* no gain control: play at full volume */ }
    }

    private fun applyMusicVolume() { music?.let { gain(it, (musicVolume * 0.75f).coerceIn(0f, 1f)) } }

    private fun decode(path: String): Sample? {
        val stream = Audio::class.java.classLoader.getResourceAsStream(path) ?: return null
        return try {
            BufferedInputStream(stream).use { raw ->
                val ais = AudioSystem.getAudioInputStream(raw)
                val base = ais.format
                val pcm = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.sampleRate, 16, base.channels, base.channels * 2, base.sampleRate, false)
                AudioSystem.getAudioInputStream(pcm, ais).use { Sample(pcm, it.readAllBytes()) }
            }
        } catch (e: Throwable) { null }
    }

    fun start() {
        running = true
        if (!loaded) {
            loaded = true
            io.execute {
                var ok = 0
                for (n in SFX_NAMES) decode("audio/sfx_$n.ogg")?.let { synchronized(samples) { samples[n] = it }; ok++ }
                if (ok == 0) { synthMode = true; synth.start(); if (wantMusic) synth.startMusic(wantWorld) }
                else if (wantMusic) startMusic(wantWorld)
            }
        } else if (synthMode) synth.start()
        if (wantMusic) startMusic(wantWorld)
    }

    fun stop() {
        running = false
        stopClip()
        if (synthMode) synth.stop()
    }

    private fun nameFor(s: Sfx, v: Int): String = when (s) {
        Sfx.SELECT -> "select"; Sfx.SWAP -> "swap"; Sfx.INVALID -> "invalid"
        Sfx.MATCH -> "match_${v.coerceIn(0, 8)}"; Sfx.POP -> "pop"; Sfx.FALL -> "fall"; Sfx.MAKE -> "make"
        Sfx.SPECIAL -> "special"; Sfx.BOOM -> "boom"; Sfx.CASCADE -> "cascade_${v.coerceIn(2, 8)}"
        Sfx.STAR -> "star_${v.coerceIn(0, 2)}"; Sfx.WIN -> "win"; Sfx.LOSE -> "lose"; Sfx.BTN -> "btn"
        Sfx.COIN -> "coin"; Sfx.SHUFFLE -> "shuffle"; Sfx.BOLT -> "bolt"; Sfx.JIGGLE -> "jiggle"; Sfx.SQUISH -> "squish"
        Sfx.VOICE -> when (v) { 0 -> "voice_tasty"; 1 -> "voice_sweet"; 2 -> "voice_delicious"; else -> "voice_divine" }
    }

    fun play(s: Sfx, variant: Int = 0, gain: Float = 1f) {
        if (sfxVolume <= 0f || !running) return
        if (synthMode) { synth.play(s, variant, gain); return }
        val sample = synchronized(samples) { samples[nameFor(s, variant)] } ?: return
        if (live.get() >= 14) return
        val v = (sfxVolume * gain).coerceIn(0f, 1f)
        io.execute {
            try {
                val clip = AudioSystem.getClip()
                live.incrementAndGet()
                clip.addLineListener { e -> if (e.type == LineEvent.Type.STOP) { clip.close(); live.decrementAndGet() } }
                clip.open(sample.fmt, sample.data, 0, sample.data.size)
                gain(clip, v)
                clip.start()
            } catch (e: Throwable) { /* ignore a failed effect */ }
        }
    }

    fun startMusic(world: Int) {
        wantMusic = true; wantWorld = world
        if (synthMode) { synth.startMusic(world); return }
        if (!running || (music != null && musicWorld == world)) return
        io.execute {
            if (!running || (music != null && musicWorld == world)) return@execute
            stopClip()
            val smp = decode("audio/music_${world.coerceIn(0, 9)}.ogg")
            if (smp == null) { if (!synthMode) { synthMode = true; synth.start(); synth.startMusic(world) }; return@execute }
            try {
                val clip = AudioSystem.getClip()
                clip.open(smp.fmt, smp.data, 0, smp.data.size)
                gain(clip, (musicVolume * 0.75f).coerceIn(0f, 1f))
                clip.loop(Clip.LOOP_CONTINUOUSLY)
                music = clip; musicWorld = world
            } catch (e: Throwable) { synthMode = true; synth.start(); synth.startMusic(world) }
        }
    }

    fun stopMusic() { wantMusic = false; stopClip(); if (synthMode) synth.stopMusic() }

    private fun stopClip() {
        val c = music ?: return
        music = null; musicWorld = -1
        try { c.stop() } catch (e: Exception) { /* ignore */ }
        try { c.close() } catch (e: Exception) { /* ignore */ }
    }

    private companion object {
        val SFX_NAMES: List<String> = buildList {
            addAll(listOf("select", "swap", "invalid", "pop", "fall", "make", "special", "boom", "win", "lose", "btn", "coin", "shuffle", "bolt", "jiggle", "squish", "voice_tasty", "voice_sweet", "voice_delicious", "voice_divine"))
            for (i in 0..8) add("match_$i"); for (i in 2..8) add("cascade_$i"); for (i in 0..2) add("star_$i")
        }
    }
}

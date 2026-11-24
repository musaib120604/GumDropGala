package com.gumdropgala

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

/**
 * Plays the pre-rendered OGG sound effects (SoundPool) and music loops (MediaPlayer) from assets/audio.
 * If the assets can't be loaded for any reason it falls back to the built-in synthesiser, so the game is never silent.
 */
class Audio(private val ctx: Context) {
    private val synth = SynthAudio()
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private var synthMode = false
    private var player: MediaPlayer? = null
    private var playerWorld = -1
    private var wantMusic = false
    private var wantWorld = 0

    var sfxVolume = 0.8f
        set(v) { field = v; synth.sfxVolume = v }
    var musicVolume = 0.55f
        set(v) { field = v; synth.musicVolume = v; applyMusicVolume() }

    private fun applyMusicVolume() {
        val v = (musicVolume * 0.75f).coerceIn(0f, 1f)
        try { player?.setVolume(v, v) } catch (e: Exception) { /* ignore */ }
    }

    fun start() {
        if (pool == null) loadPool()
        if (synthMode) synth.start()
        if (wantMusic) startMusic(wantWorld)
    }

    fun stop() {
        releasePlayer()
        if (synthMode) synth.stop()
    }

    private fun loadPool() {
        try {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val sp = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(attrs).build()
            val files = ctx.assets.list("audio") ?: emptyArray()
            for (f in files) {
                if (!f.startsWith("sfx_") || !f.endsWith(".ogg")) continue
                val afd = ctx.assets.openFd("audio/$f")
                val id = sp.load(afd, 1)
                afd.close()
                ids[f.removePrefix("sfx_").removeSuffix(".ogg")] = id
            }
            pool = sp
            if (ids.isEmpty()) synthMode = true
        } catch (e: Exception) {
            synthMode = true
        }
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
        if (sfxVolume <= 0f) return
        if (synthMode) { synth.play(s, variant, gain); return }
        val id = ids[nameFor(s, variant)] ?: return
        val v = (sfxVolume * gain).coerceIn(0f, 1f)
        try { pool?.play(id, v, v, 1, 0, 1f) } catch (e: Exception) { /* ignore */ }
    }

    fun startMusic(world: Int) {
        wantMusic = true; wantWorld = world
        if (synthMode) { synth.startMusic(world); return }
        if (player != null && playerWorld == world) return
        releasePlayer()
        try {
            val afd = ctx.assets.openFd("audio/music_${world.coerceIn(0, 9)}.ogg")
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = true
            val v = (musicVolume * 0.75f).coerceIn(0f, 1f)
            mp.setVolume(v, v)
            mp.prepare()
            mp.start()
            player = mp; playerWorld = world
        } catch (e: Exception) {
            player = null
            synthMode = true; synth.start(); synth.startMusic(world)
        }
    }

    fun stopMusic() { wantMusic = false; releasePlayer(); if (synthMode) synth.stopMusic() }

    private fun releasePlayer() {
        val p = player ?: return
        player = null; playerWorld = -1
        try { p.stop() } catch (e: Exception) { /* ignore */ }
        try { p.release() } catch (e: Exception) { /* ignore */ }
    }
}

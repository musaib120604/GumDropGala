package com.gumdropgala

import android.content.Context

/** Everything the screens share. One instance for the life of the activity. */
class Env(val ctx: Context) {
    val ui = Ui()
    val art = CandyArt().also { it.assets = ctx.assets }
    val fx = Effects()
    val tw = Tweens()
    val timers = Timers()
    val audio = Audio(ctx)
    val hap = Haptics(ctx)
    val store = SaveStore(ctx)
    var save: SaveData = store.load()

    init { applySettings(); ui.assets = ctx.assets; fx.loadTextures(ctx.assets) }

    fun persist() { try { store.save(save) } catch (e: Exception) { /* best effort */ } }

    fun applySettings() {
        val s = save.settings
        audio.musicVolume = s.music
        audio.sfxVolume = s.sfx
        hap.enabled = s.vibrate
        ui.bigText = s.bigText
        val sp = if (s.reduceMotion) 2.2f else 1f
        tw.speed = sp
        timers.speed = sp
    }

    val reduceMotion: Boolean get() = save.settings.reduceMotion

    fun sfx(s: Sfx, variant: Int = 0, gain: Float = 1f) = audio.play(s, variant, gain)
    fun hap(h: Hap) = hap.fire(h)
}

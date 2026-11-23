package com.gumdropgala

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.os.SystemClock

/** The kinds of feedback the game asks for. Each maps to a different native vibration effect. */
enum class Hap { TICK, SOFT, REJECT, LIGHT, MEDIUM, STRONG, HEAVY, BOMB, WIN, TAP }

/**
 * Native haptics through [Vibrator] / [VibratorManager] and [VibrationEffect].
 * Never assumes the hardware exists, honours the in-game toggle and the system "touch vibration" setting,
 * rate-limits so long cascades don't buzz constantly, and never interrupts a long pattern with small ones.
 */
class Haptics(private val ctx: Context) {
    var enabled = true

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) { null }

    val supported: Boolean get() = try { vibrator?.hasVibrator() == true } catch (e: Exception) { false }

    private var lastAt = 0L
    private var lockUntil = 0L

    private fun systemAllows(): Boolean = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    } catch (e: Exception) { true }

    fun fire(h: Hap) {
        if (!enabled) return
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator() || !systemAllows()) return
            val now = SystemClock.uptimeMillis()
            val big = h == Hap.BOMB || h == Hap.WIN || h == Hap.HEAVY
            if (now < lockUntil && !big) return
            if (now - lastAt < 45 && !big) return
            lastAt = now
            if (h == Hap.BOMB) lockUntil = now + 450 else if (h == Hap.WIN) lockUntil = now + 500
            v.vibrate(effectFor(h, v))
        } catch (e: Exception) {
            // Vibration is best effort; a failure must never affect gameplay.
        }
    }

    private fun oneShot(v: Vibrator, ms: Long, amp: Int): VibrationEffect =
        VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE)

    private fun waveform(v: Vibrator, timings: LongArray, amps: IntArray): VibrationEffect =
        if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, -1) else VibrationEffect.createWaveform(timings, -1)

    private fun effectFor(h: Hap, v: Vibrator): VibrationEffect {
        val api29 = Build.VERSION.SDK_INT >= 29
        return when (h) {
            Hap.TICK -> if (api29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else oneShot(v, 8, 40)
            Hap.TAP -> oneShot(v, 6, 40)
            Hap.SOFT -> oneShot(v, 16, 70)
            Hap.LIGHT -> oneShot(v, 14, 90)
            Hap.REJECT -> waveform(v, longArrayOf(0, 22, 45, 22), intArrayOf(0, 200, 0, 200))
            Hap.MEDIUM -> if (api29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) else oneShot(v, 28, 160)
            Hap.STRONG -> if (api29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) else oneShot(v, 46, 230)
            Hap.HEAVY -> oneShot(v, 68, 255)
            Hap.WIN -> waveform(v, longArrayOf(0, 40, 60, 40, 60, 40, 60, 130), intArrayOf(0, 120, 0, 180, 0, 220, 0, 255))
            Hap.BOMB -> bombEffect(v)
        }
    }

    private fun bombEffect(v: Vibrator): VibrationEffect {
        if (Build.VERSION.SDK_INT >= 30) {
            val thud = VibrationEffect.Composition.PRIMITIVE_THUD
            val click = VibrationEffect.Composition.PRIMITIVE_CLICK
            if (v.areAllPrimitivesSupported(thud, click)) {
                return VibrationEffect.startComposition()
                    .addPrimitive(thud, 1.0f)
                    .addPrimitive(click, 0.7f, 50)
                    .addPrimitive(thud, 1.0f, 60)
                    .addPrimitive(thud, 0.8f, 80)
                    .compose()
            }
        }
        return waveform(v, longArrayOf(0, 70, 40, 60, 40, 110, 40, 160), intArrayOf(0, 255, 0, 200, 0, 255, 0, 255))
    }
}

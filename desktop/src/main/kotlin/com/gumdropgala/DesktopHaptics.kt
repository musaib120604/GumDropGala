package com.gumdropgala

import android.content.Context

/** Mirrors the Android enum so the shared game code compiles unchanged. */
enum class Hap { TICK, SOFT, REJECT, LIGHT, MEDIUM, STRONG, HEAVY, BOMB, WIN, TAP }

/** PCs have no vibration motor: every call is a no-op. */
class Haptics(@Suppress("UNUSED_PARAMETER") ctx: Context) {
    var enabled = true
    val supported: Boolean get() = false
    fun fire(h: Hap) { /* nothing to do on desktop */ }
}

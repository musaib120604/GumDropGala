package com.gumdropgala.engine

import kotlin.math.floor

/** Small, fast, seedable PRNG (mulberry32). Same sequence as the web version. */
class Rng(seed: Int) {
    private var a: Int = seed

    fun next(): Double {
        a += 0x6D2B79F5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }

    fun nextInt(bound: Int): Int = floor(next() * bound).toInt()
}

/** JavaScript-style Math.round for non-negative values (Kotlin's round() is banker's rounding). */
fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

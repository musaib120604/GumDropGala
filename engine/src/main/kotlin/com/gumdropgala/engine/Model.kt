package com.gumdropgala.engine

const val W = 8
const val H = 8

/** Candy type used for colour bombs. Normal candies use 0 until the level's colour count. */
const val BOMB_TYPE = 6

/** Special-candy states stored on [Candy.special]. */
object Special {
    const val NONE = 0
    const val STRIPE_H = 1 // clears its row
    const val STRIPE_V = 2 // clears its column
    const val WRAPPED = 3  // 3x3 blast
    const val BOMB = 4     // colour bomb
}

enum class State { IDLE, SWAPPING, MATCHING, CLEARING, FALLING, REFILLING, CASCADING, GAME_OVER }

enum class GameResult { WIN, LOSE }

class Candy(val id: Int, var type: Int, var special: Int, var r: Int, var c: Int)

data class Pos(val r: Int, val c: Int)

typealias Grid = Array<Array<Candy?>>

fun key(r: Int, c: Int): Int = r * W + c
fun rowOf(k: Int): Int = k / W
fun colOf(k: Int): Int = k % W
fun inBounds(r: Int, c: Int): Boolean = r in 0 until H && c in 0 until W

fun makeGrid(): Grid = Array(H) { arrayOfNulls<Candy>(W) }

/* ------------------------------------------------------------------ events */

sealed class Event

data class Rejected(val reason: String) : Event()
data class Invalid(val a: Pos, val b: Pos) : Event()
data class Swapped(val a: Pos, val b: Pos, val idA: Int, val idB: Int, val combo: Boolean) : Event()

enum class GroupKind { NONE, STRIPED, WRAPPED, BOMB }

data class GroupInfo(
    val type: Int, val cells: List<Int>, val size: Int, val shape: String,
    val kind: GroupKind, val spot: Int?, val special: Int?
)

data class Matched(val groups: List<GroupInfo>, val cascade: Int) : Event()

data class ClearedCandy(val id: Int, val r: Int, val c: Int, val type: Int, val special: Int)
data class CreatedSpecial(val id: Int, val r: Int, val c: Int, val special: Int, val type: Int)
data class ConvertedCandy(val id: Int, val r: Int, val c: Int, val special: Int)

sealed class Effect {
    data class Row(val r: Int, val c: Int) : Effect()
    data class Col(val r: Int, val c: Int) : Effect()
    data class Blast(val r: Int, val c: Int, val radius: Int) : Effect()
    data class BigCross(val r: Int, val c: Int) : Effect()
    data class Bomb(val r: Int, val c: Int, val type: Int, val targets: List<Int>) : Effect()
    data class BombConvert(val r: Int, val c: Int, val type: Int, val targets: List<Int>) : Effect()
    data class BombAll(val r: Int, val c: Int) : Effect()
}

data class Cleared(
    val cleared: List<ClearedCandy>, val created: List<CreatedSpecial>, val converted: List<ConvertedCandy>,
    val effects: List<Effect>, val gain: Int, val cascade: Int, val combo: Boolean, val triggered: Int, val score: Int
) : Event()

data class FallMove(val id: Int, val c: Int, val fromR: Int, val toR: Int)
data class Fall(val moves: List<FallMove>) : Event()

data class Spawn(val id: Int, val type: Int, val c: Int, val fromR: Int, val toR: Int)
data class Refill(val spawns: List<Spawn>) : Event()

data class CascadeStep(val n: Int) : Event()

/** Level won with moves to spare: these plain candies just turned into striped candies and are about to fire. */
data class SugarCrush(val created: List<CreatedSpecial>, val movesLeft: Int) : Event()

data class ShuffleMove(val id: Int, val fromR: Int, val fromC: Int, val r: Int, val c: Int, val type: Int)
data class Shuffled(val moves: List<ShuffleMove>, val reason: String) : Event()

data class Ended(val result: GameResult, val bonus: Int, val stars: Int, val score: Int) : Event()

package com.gumdropgala.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

const val LEVELS_PER_WORLD = 10
const val LEVEL_COUNT = 100

val WORLD_NAMES = listOf(
    "Gumdrop Meadow", "Sherbet Shore", "Cocoa Canyon", "Minty Mountains", "Caramel Caves",
    "Lollipop Lagoon", "Frosting Frontier", "Rainbow Ridge", "Sprinkle Springs", "Starlight Sundae"
)

sealed class Objective {
    data class Score(val target: Int) : Objective()
    data class Collect(val type: Int, val target: Int) : Objective()
    object Jelly : Objective()
    data class MakeSpecial(val target: Int) : Objective()
}

data class JellyCell(val r: Int, val c: Int, val layers: Int)

data class LevelConfig(
    val id: Int, val world: Int, val indexInWorld: Int, val colors: Int, val moves: Int,
    val objectives: List<Objective>, val jelly: List<JellyCell>, val stars: IntArray
)

private fun jellyPattern(n: Int, rng: Rng): List<JellyCell> {
    val pats = listOf("rows", "ring", "checker", "center", "columns", "corners")
    val p = pats[rng.nextInt(pats.size)]
    val layers = if (n > 60) 2 else 1
    val out = ArrayList<JellyCell>()
    for (r in 0 until H) for (c in 0 until W) {
        val on = when (p) {
            "rows" -> r == 3 || r == 4 || (n > 30 && r == 6)
            "ring" -> if (r == 1 || r == 6 || c == 1 || c == 6) (r in 1..6 && c in 1..6) else false
            "checker" -> (r + c) % 2 == 0 && r > 1 && r < 7
            "center" -> r in 2..5 && c in 2..5
            "columns" -> c == 2 || c == 5
            else -> (r < 3 && c < 3) || (r > 4 && c > 4) || (r < 3 && c > 4) || (r > 4 && c < 3)
        }
        if (on) out.add(JellyCell(r, c, if ((r + c) % 3 == 0) layers else 1))
    }
    if (out.size > 26) {
        val step = ceil(out.size / 26.0).toInt()
        return out.filterIndexed { i, _ -> i % step != 1 }.take(26)
    }
    return out
}

fun levelConfig(n: Int): LevelConfig {
    val rng = Rng(n * 104729 + 7)
    val world = (n - 1) / LEVELS_PER_WORLD
    val colors = if (n <= 10) 4 else if (n <= 45) 5 else (if (n % 3 == 0) 6 else 5)
    val kinds = listOf("score", "collect", "jelly", "special", "mixed")
    val kind = when (n) { 1 -> "score"; 2 -> "collect"; 3 -> "score"; else -> kinds[(n + world) % 5] }
    var moves = max(16, 30 - n / 8)
    val objectives = ArrayList<Objective>()
    var jelly: List<JellyCell> = emptyList()
    val baseScore = jsRound((moves * (140 + n * 3.4)) / 100.0) * 100
    val collectType = rng.nextInt(colors)
    val collectTarget = min(42, 12 + (n * 0.3).toInt())
    when (kind) {
        "score" -> objectives.add(Objective.Score(baseScore))
        "collect" -> objectives.add(Objective.Collect(collectType, collectTarget))
        "jelly" -> { jelly = jellyPattern(n, rng); moves += 4; objectives.add(Objective.Jelly) }
        "special" -> objectives.add(Objective.MakeSpecial(min(9, 3 + n / 14)))
        else -> {
            if (n % 2 == 1) {
                objectives.add(Objective.Collect(collectType, jsRound(collectTarget * 0.8)))
                objectives.add(Objective.Score(jsRound(baseScore * 0.7 / 100.0) * 100))
            } else {
                jelly = jellyPattern(n, rng); moves += 3
                objectives.add(Objective.Jelly)
                objectives.add(Objective.Collect(collectType, jsRound(collectTarget * 0.7)))
            }
        }
    }
    val s1 = if (kind == "score") baseScore else jsRound((moves * (110 + n * 2.6)) / 100.0) * 100
    val stars = intArrayOf(s1, jsRound(s1 * 1.45 / 100.0) * 100, jsRound(s1 * 2.0 / 100.0) * 100)
    return LevelConfig(n, world, (n - 1) % LEVELS_PER_WORLD, colors, moves, objectives, jelly, stars)
}

/** Neutral level for tests and tools. */
fun testLevel(colors: Int = 5, moves: Int = 20, objectives: List<Objective> = listOf(Objective.Score(100000)),
              jelly: List<JellyCell> = emptyList(), stars: IntArray = intArrayOf(1000, 2000, 3000)): LevelConfig =
    LevelConfig(9999, 0, 0, colors, moves, objectives, jelly, stars)

fun cascadeMultiplier(cascade: Int): Double = 1.0 + 0.5 * (cascade - 1)
fun scoreForClear(count: Int, cascade: Int, triggered: Int): Int =
    jsRound(count * 60 * cascadeMultiplier(cascade)) + triggered * 100

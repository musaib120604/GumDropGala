package com.gumdropgala.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/** Random-move and bot simulations: no crashes, no deadlocks, no inconsistent boards. */
class SimulationTest {
    private fun check(g: Game, label: String) {
        val ids = HashSet<Int>()
        for (r in 0 until H) for (c in 0 until W) {
            val cd = g.grid[r][c] ?: throw AssertionError("$label: hole $r,$c")
            if (!ids.add(cd.id)) throw AssertionError("$label: duplicate id")
            if (cd.r != r || cd.c != c) throw AssertionError("$label: position desync")
            val okType = if (cd.special == Special.BOMB) cd.type == BOMB_TYPE else cd.type in 0 until g.level.colors
            if (!okType) throw AssertionError("$label: bad type ${cd.type}")
        }
        if (g.state == State.IDLE) {
            if (findRuns(g.grid).isNotEmpty()) throw AssertionError("$label: unresolved match on idle board")
            if (!hasValidMove(g.grid)) throw AssertionError("$label: deadlock on idle board")
        }
        if (g.moves < 0) throw AssertionError("$label: negative moves")
        for (r in 0 until H) for (c in 0 until W) if (g.jelly[r][c] < 0) throw AssertionError("$label: negative jelly")
    }

    @Test fun randomPlayNeverBreaksTheBoard() {
        var turns = 0
        var events = 0
        for (i in 0 until 400) {
            val g = Game(levelConfig(1 + i % 100), seed = i + 1000)
            val rng = Rng(i * 31 + 5)
            check(g, "start $i")
            var t = 0
            while (t < 60 && g.state != State.GAME_OVER) {
                val roll = rng.next()
                val seq = when {
                    roll < 0.15 -> {
                        val a = Pos(rng.nextInt(H), rng.nextInt(W))
                        g.swap(a, Pos(a.r + (if (rng.next() < 0.5) 1 else 0), a.c + (if (rng.next() < 0.5) 1 else 0)))
                    }
                    roll < 0.20 -> g.hammer(rng.nextInt(H), rng.nextInt(W))
                    roll < 0.23 -> g.shuffleBooster()
                    else -> { val mv = g.findMoves(); val m = mv[rng.nextInt(mv.size)]; g.swap(m.a, m.b) }
                }
                events += seq.count()
                turns++; t++
                check(g, "game $i turn $t")
                if (g.state == State.GAME_OVER && g.result == GameResult.LOSE && rng.next() < 0.5) g.addMoves(3)
            }
        }
        assertTrue("ran $turns turns / $events events", turns > 5000)
    }

    /** A greedy bot should beat early levels almost always and still win a fair share of the late, harder levels. */
    @Test fun difficultyRampsGently() {
        fun play(n: Int, k: Int): Boolean {
            val g = Game(levelConfig(n), seed = n * 101 + k * 7 + 3)
            while (g.state != State.GAME_OVER) {
                val mv = g.findMoves()
                if (mv.isEmpty()) break
                var best = mv[0]
                var bestScore = Int.MIN_VALUE
                val want = g.objectiveStatus().filterIsInstance<ObjectiveStatus.Collect>().filter { !it.done }.map { it.type }
                for (m in mv) {
                    var s = if (isCombo(g.grid[m.a.r][m.a.c], g.grid[m.b.r][m.b.c])) 60 else 0
                    swapCells(g.grid, m.a, m.b)
                    findGroups(g.grid).forEach { gr -> s += gr.size * 2 + (if (gr.kind != GroupKind.NONE) 8 else 0) + (if (want.contains(gr.type)) gr.size * 3 else 0) }
                    swapCells(g.grid, m.a, m.b)
                    if (s > bestScore) { bestScore = s; best = m }
                }
                g.swap(best.a, best.b).count()
            }
            return g.result == GameResult.WIN
        }
        fun rate(from: Int, to: Int): Double { var w = 0; var t = 0; for (n in from..to) for (k in 0 until 4) { if (play(n, k)) w++; t++ }; return w.toDouble() / t }
        val early = rate(1, 10)
        val late = rate(61, 100)
        println("bot win rate: levels 1-10 = ${(early * 100).toInt()}%, levels 61-100 = ${(late * 100).toInt()}%")
        assertTrue("early levels too hard: $early", early >= 0.9)
        // levels were deliberately made harder: this bot (objective-aware but with no planning) now wins ~44% late;
        // below 35% would mean the late game is out of reach for ordinary play
        assertTrue("late levels impossible: $late", late >= 0.35)
        assertTrue("late levels not harder than early", late <= early)
    }
}

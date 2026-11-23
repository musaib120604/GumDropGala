package com.gumdropgala.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun layout(vararg over: Pair<String, String>): List<String> {
    val m = over.toMap()
    return (0 until H).map { r -> (0 until W).joinToString(" ") { c -> m["$r,$c"] ?: "." } }
}

private fun mk(vararg over: Pair<String, String>, lvl: LevelConfig = testLevel()) = Game(lvl, layout = layout(*over))
private fun P(r: Int, c: Int) = Pos(r, c)
private inline fun <reified T : Event> List<Event>.firstOf(): T = filterIsInstance<T>().first()
private inline fun <reified T : Event> List<Event>.allOf(): List<T> = filterIsInstance<T>()

private fun invariants(g: Game, label: String) {
    val ids = HashSet<Int>()
    for (r in 0 until H) for (c in 0 until W) {
        val cd = g.grid[r][c]
        assertTrue("$label: hole at $r,$c", cd != null)
        assertTrue("$label: duplicate id ${cd!!.id}", ids.add(cd.id))
        assertEquals("$label: candy.r", r, cd.r)
        assertEquals("$label: candy.c", c, cd.c)
    }
}

class EngineTest {
    /* ---------- match detection ---------- */
    @Test fun horizontalMatchOf3() {
        val g = mk("0,0" to "4", "0,1" to "4", "0,2" to "5", "1,2" to "4")
        val ev = g.swap(P(0, 2), P(1, 2)).toList()
        val m = ev.firstOf<Matched>()
        assertEquals(1, m.groups.size)
        assertEquals(3, m.groups[0].size)
        assertEquals(GroupKind.NONE, m.groups[0].kind)
        assertEquals(3, ev.firstOf<Cleared>().cleared.size)
    }

    @Test fun verticalMatchOf3() {
        val g = mk("0,0" to "4", "1,0" to "4", "2,0" to "5", "2,1" to "4")
        val m = g.swap(P(2, 0), P(2, 1)).toList().firstOf<Matched>()
        assertEquals(3, m.groups[0].size)
        assertTrue(m.groups[0].cells.all { colOf(it) == 0 })
    }

    @Test fun matchOf4MakesStripedAtSwappedCell() {
        val g = mk("0,0" to "4", "0,1" to "4", "0,2" to "5", "0,3" to "4", "1,2" to "4")
        val ev = g.swap(P(0, 2), P(1, 2)).toList()
        val cl = ev.firstOf<Cleared>()
        assertEquals(GroupKind.STRIPED, ev.firstOf<Matched>().groups[0].kind)
        assertEquals(3, cl.cleared.size)
        assertEquals(1, cl.created.size)
        assertEquals(Special.STRIPE_H, cl.created[0].special)
        assertEquals(0, cl.created[0].r); assertEquals(2, cl.created[0].c)
    }

    @Test fun vertical4MakesColumnStriper() {
        val g = mk("0,0" to "4", "1,0" to "4", "2,0" to "5", "3,0" to "4", "2,1" to "4")
        val ev = g.swap(P(2, 0), P(2, 1)).toList()
        assertEquals(Special.STRIPE_V, ev.firstOf<Cleared>().created[0].special)
    }

    @Test fun matchOf5MakesColourBomb() {
        val g = mk("0,0" to "4", "0,1" to "4", "0,2" to "5", "0,3" to "4", "0,4" to "4", "1,2" to "4")
        val ev = g.swap(P(0, 2), P(1, 2)).toList()
        assertEquals(GroupKind.BOMB, ev.firstOf<Matched>().groups[0].kind)
        assertEquals(Special.BOMB, ev.firstOf<Cleared>().created[0].special)
    }

    @Test fun lShapeMergesIntoOneWrappedCandy() {
        val g = mk("3,4" to "4", "3,5" to "4", "4,3" to "4", "5,3" to "4", "3,3" to "5", "2,3" to "4")
        val ev = g.swap(P(2, 3), P(3, 3)).toList()
        val m = ev.firstOf<Matched>()
        assertEquals(1, m.groups.size)
        assertEquals("L", m.groups[0].shape)
        assertEquals(GroupKind.WRAPPED, m.groups[0].kind)
        assertEquals(m.groups[0].cells.size, m.groups[0].cells.toSet().size)
        assertEquals(5, m.groups[0].size)
        val cl = ev.firstOf<Cleared>()
        assertEquals(4, cl.cleared.size)
        assertEquals(Special.WRAPPED, cl.created[0].special)
    }

    @Test fun tShapeDetected() {
        val g = mk("3,2" to "4", "3,4" to "4", "4,3" to "4", "5,3" to "4", "3,3" to "5", "2,3" to "4")
        val m = g.swap(P(2, 3), P(3, 3)).toList().firstOf<Matched>()
        assertEquals("T", m.groups[0].shape)
        assertEquals(GroupKind.WRAPPED, m.groups[0].kind)
        assertEquals(5, m.groups[0].size)
    }

    @Test fun simultaneousMatchesProcessedTogether() {
        val g = mk("3,3" to "4", "3,4" to "5", "3,5" to "4", "3,6" to "4", "2,3" to "5", "1,3" to "5")
        val ev = g.swap(P(3, 3), P(3, 4)).toList()
        assertEquals(2, ev.firstOf<Matched>().groups.size)
        assertEquals(6, ev.firstOf<Cleared>().cleared.size)
    }

    @Test fun touchingRunsOfSameTypeMerge() {
        val g = mk("1,1" to "4", "1,2" to "4", "1,3" to "4", "2,3" to "4", "3,3" to "4")
        val groups = findGroups(g.grid)
        assertEquals(1, groups.size)
        assertEquals(5, groups[0].size)
    }

    /* ---------- swaps ---------- */
    @Test fun invalidSwapIsRejectedAndCostsNothing() {
        val g = mk()
        val before = g.toText()
        val ev = g.swap(P(0, 0), P(0, 1)).toList()
        assertEquals(1, ev.size)
        assertTrue(ev[0] is Invalid)
        assertEquals(before, g.toText())
        assertEquals(20, g.moves)
        assertEquals(State.IDLE, g.state)
    }

    @Test fun nonAdjacentAndOutOfBoundsAreInvalid() {
        val g = mk()
        assertFalse(g.isValidSwap(P(0, 0), P(0, 2)))
        assertFalse(g.isValidSwap(P(0, 0), P(-1, 0)))
        assertFalse(g.isValidSwap(P(7, 7), P(7, 8)))
    }

    @Test fun inputBlockedWhileResolving() {
        val g = mk("0,0" to "4", "0,1" to "4", "0,2" to "5", "1,2" to "4")
        val it = g.swap(P(0, 2), P(1, 2)).iterator()
        it.next()
        assertNotEquals(State.IDLE, g.state)
        assertTrue(g.swap(P(5, 5), P(5, 6)).toList()[0] is Rejected)
        assertTrue(g.hammer(3, 3).toList()[0] is Rejected)
        assertTrue(g.shuffleBooster().toList()[0] is Rejected)
        while (it.hasNext()) it.next()
        assertEquals(State.IDLE, g.state)
    }

    @Test fun stateMachineVisitsDocumentedStatesInOrder() {
        val g = mk("5,0" to "4", "5,1" to "4", "5,2" to "5", "6,2" to "4")
        val seen = ArrayList<State>()
        val it = g.swap(P(5, 2), P(6, 2)).iterator()
        while (true) { val has = it.hasNext(); seen.add(g.state); if (!has) break; it.next() }
        val order = listOf(State.SWAPPING, State.MATCHING, State.CLEARING, State.FALLING, State.REFILLING)
        var i = 0
        seen.forEach { if (i < order.size && it == order[i]) i++ }
        assertEquals("states seen: $seen", order.size, i)
        assertEquals(State.IDLE, g.state)
    }

    /* ---------- gravity & refill ---------- */
    @Test fun gravityCompactsEachColumnIndependently() {
        val g = mk()
        val col2 = (0 until H).map { g.grid[it][2]!!.id }
        val col5 = (0 until H).map { g.grid[it][5]!!.id }
        g.grid[7][2] = null; g.grid[5][2] = null; g.grid[2][2] = null
        val moves = g.gravityForTest()
        assertNull(g.grid[0][2]); assertNull(g.grid[1][2]); assertNull(g.grid[2][2])
        val kept = listOf(col2[0], col2[1], col2[3], col2[4], col2[6])
        for (i in 0 until 5) assertEquals(kept[i], g.grid[3 + i][2]!!.id)
        for (r in 0 until H) assertEquals(col5[r], g.grid[r][5]!!.id)
        moves.forEach { assertEquals(it.id, g.grid[it.toR][it.c]!!.id) }
        val sp = g.refillForTest()
        assertEquals(3, sp.size)
        sp.forEach { assertTrue(it.fromR < 0 && it.toR >= 0) }
        invariants(g, "gravity")
    }

    @Test fun cascadesResolveUntilStable() {
        val g = mk("5,2" to "5", "6,2" to "5", "7,3" to "5", "4,2" to "4", "7,0" to "4", "7,1" to "4")
        val ev = g.swap(P(7, 2), P(7, 3)).toList()
        val clears = ev.allOf<Cleared>()
        assertTrue("expected a cascade", clears.size >= 2)
        assertEquals(2, ev.firstOf<CascadeStep>().n)
        assertTrue(clears[1].cleared.any { it.r == 7 && it.type == 4 })
        assertTrue(g.stats.maxCascade >= 2)
        assertTrue("board must be stable", findRuns(g.grid).isEmpty())
        invariants(g, "cascade")
    }

    /* ---------- specials & combos ---------- */
    private fun combo(a: Pair<String, String>, b: Pair<String, String>, ra: Pos, rb: Pos): Pair<Game, Cleared> {
        val g = mk(a, b)
        val ev = g.swap(ra, rb).toList()
        return g to ev.firstOf<Cleared>()
    }

    @Test fun stripedPlusStripedClearsCross() {
        val (_, cl) = combo("3,3" to "4H", "3,4" to "5V", P(3, 3), P(3, 4))
        assertEquals(15, cl.cleared.size); assertTrue(cl.combo)
    }

    @Test fun wrappedPlusWrappedClears5x5() {
        val (_, cl) = combo("3,3" to "4W", "3,4" to "5W", P(3, 3), P(3, 4))
        assertEquals(25, cl.cleared.size)
    }

    @Test fun stripedPlusWrappedClearsThreeRowsAndColumns() {
        val (_, cl) = combo("3,3" to "4H", "3,4" to "5W", P(3, 3), P(3, 4))
        assertEquals(39, cl.cleared.size)
    }

    @Test fun colourBombPlusPlainClearsThatColour() {
        val g = mk("3,3" to "B", "3,4" to "2")
        val n = g.allCandies().count { it.type == 2 && it.special != Special.BOMB }
        val cl = g.swap(P(3, 3), P(3, 4)).toList().firstOf<Cleared>()
        assertEquals(n + 1, cl.cleared.size)
        assertTrue(cl.effects[0] is Effect.Bomb)
    }

    @Test fun colourBombPlusStripedConvertsAndFires() {
        val g = mk("3,3" to "B", "3,4" to "2H")
        val n = g.allCandies().count { it.type == 2 }
        val cl = g.swap(P(3, 3), P(3, 4)).toList().firstOf<Cleared>()
        assertTrue(cl.converted.isNotEmpty())
        assertTrue(cl.cleared.size > n + 1)
    }

    @Test fun colourBombPlusColourBombClearsBoard() {
        val (_, cl) = combo("3,3" to "B", "3,4" to "B", P(3, 3), P(3, 4))
        assertEquals(64, cl.cleared.size)
    }

    @Test fun chainedSpecialEffectsTerminate() {
        val over = ArrayList<Pair<String, String>>()
        for (r in 0 until H) for (c in 0 until W) over.add("$r,$c" to "${(r + c) % 4}${if (r % 2 == 1) "H" else "W"}")
        val g = mk(*over.toTypedArray())
        val ev = g.swap(P(3, 3), P(3, 4)).toList()
        assertEquals(64, ev.firstOf<Cleared>().cleared.size)
        invariants(g, "chain")
    }

    @Test fun specialHitByBlastFiresOnce() {
        val g = mk("3,3" to "4W", "3,4" to "5", "3,5" to "5", "2,3" to "5", "4,2" to "4H", "3,2" to "5", "5,5" to "5", "1,1" to "4")
        val cl = g.hammer(3, 3).toList().firstOf<Cleared>()
        assertEquals(1, cl.effects.count { it is Effect.Row })
        assertEquals(cl.cleared.size, cl.cleared.map { it.id }.toSet().size)
    }

    @Test fun specialPlusPlainWithoutMatchIsInvalidButBombAlwaysValid() {
        assertFalse(mk("3,3" to "4H").isValidSwap(P(3, 3), P(3, 4)))
        assertTrue(mk("3,3" to "B").isValidSwap(P(3, 3), P(3, 4)))
    }

    /* ---------- deadlock & generation ---------- */
    private fun deadLayout(): List<String> = (0 until H).map { r -> (0 until W).joinToString(" ") { c -> ((r % 2) * 2 + (c % 2)).toString() } }

    @Test fun deadlockIsDetected() {
        val g = Game(testLevel(), layout = deadLayout())
        assertTrue(findRuns(g.grid).isEmpty())
        assertFalse(hasValidMove(g.grid))
    }

    @Test fun deadlockedBoardIsShuffledAutomatically() {
        val g = Game(testLevel(), layout = deadLayout())
        val ev = g.finishTurnForTest().toList()
        val sh = ev.firstOf<Shuffled>()
        assertEquals("deadlock", sh.reason)
        assertTrue(hasValidMove(g.grid))
        assertTrue(findRuns(g.grid).isEmpty())
        assertEquals(State.IDLE, g.state)
        invariants(g, "shuffle")
    }

    @Test fun shuffleKeepsIdentitiesAndSpecials() {
        val g = mk("1,1" to "4H", "2,2" to "5W", "6,6" to "B")
        val before = g.allCandies().map { "${it.id}:${it.special}" }.sorted()
        g.shuffleForTest("booster").toList()
        assertEquals(before, g.allCandies().map { "${it.id}:${it.special}" }.sorted())
        assertTrue(findRuns(g.grid).isEmpty())
    }

    @Test fun generatedBoardsHaveNoMatchesAndAValidMove() {
        for (seed in 1..400) {
            val g = Game(testLevel(colors = 4 + seed % 3), seed = seed)
            assertTrue("seed $seed", findRuns(g.grid).isEmpty())
            assertTrue("seed $seed", hasValidMove(g.grid))
            invariants(g, "gen $seed")
        }
    }

    /* ---------- scoring ---------- */
    @Test fun scoreCalculation() {
        assertEquals(180, scoreForClear(3, 1, 0))
        assertEquals(270, scoreForClear(3, 2, 0))
        assertEquals(480 + 100, scoreForClear(4, 3, 1))
        assertEquals(1.0, cascadeMultiplier(1), 0.0)
        val g = mk("0,0" to "4", "0,1" to "4", "0,2" to "5", "1,2" to "4")
        assertEquals(180, g.swap(P(0, 2), P(1, 2)).toList().firstOf<Cleared>().gain)
    }

    @Test fun specialCreationAddsBonus() {
        val g = mk("0,0" to "4", "0,1" to "4", "0,2" to "5", "0,3" to "4", "1,2" to "4")
        assertEquals(scoreForClear(3, 1, 0) + 120, g.swap(P(0, 2), P(1, 2)).toList().firstOf<Cleared>().gain)
    }

    /* ---------- level completion ---------- */
    private val winMatch = arrayOf("0,0" to "4", "0,1" to "4", "0,2" to "5", "1,2" to "4")

    @Test fun scoreObjectiveWinsWithBonusAndStars() {
        val g = mk(*winMatch, lvl = testLevel(objectives = listOf(Objective.Score(100))))
        val end = g.swap(P(0, 2), P(1, 2)).toList().firstOf<Ended>()
        assertEquals(GameResult.WIN, end.result)
        assertEquals(State.GAME_OVER, g.state)
        assertEquals(19 * 250, end.bonus)
        assertEquals(3, end.stars)
    }

    @Test fun collectObjective() {
        val g = mk(*winMatch, lvl = testLevel(objectives = listOf(Objective.Collect(4, 3))))
        assertFalse(g.isComplete())
        assertEquals(GameResult.WIN, g.swap(P(0, 2), P(1, 2)).toList().firstOf<Ended>().result)
    }

    @Test fun jellyObjective() {
        val jelly = listOf(JellyCell(0, 0, 1), JellyCell(0, 1, 1), JellyCell(0, 2, 1))
        val g = mk(*winMatch, lvl = testLevel(objectives = listOf(Objective.Jelly), jelly = jelly))
        assertEquals(3, g.jellyLeft())
        val ev = g.swap(P(0, 2), P(1, 2)).toList()
        assertEquals(0, g.jellyLeft())
        assertEquals(GameResult.WIN, ev.firstOf<Ended>().result)
    }

    @Test fun twoLayerJellyNeedsTwoHits() {
        val g = mk(*winMatch, lvl = testLevel(objectives = listOf(Objective.Jelly), jelly = listOf(JellyCell(0, 0, 2))))
        g.swap(P(0, 2), P(1, 2)).toList()
        assertEquals(1, g.jelly[0][0])
        assertNull(g.result)
    }

    @Test fun specialCandyObjective() {
        val g = mk("0,0" to "4", "0,1" to "4", "0,2" to "5", "0,3" to "4", "1,2" to "4", lvl = testLevel(objectives = listOf(Objective.MakeSpecial(1))))
        assertEquals(GameResult.WIN, g.swap(P(0, 2), P(1, 2)).toList().firstOf<Ended>().result)
    }

    @Test fun runningOutOfMovesLosesAndExtraMovesRevive() {
        val g = mk(*winMatch, lvl = testLevel(moves = 1))
        assertEquals(GameResult.LOSE, g.swap(P(0, 2), P(1, 2)).toList().firstOf<Ended>().result)
        assertEquals(State.GAME_OVER, g.state)
        assertTrue(g.swap(P(5, 5), P(5, 6)).toList()[0] is Rejected)
        g.addMoves(5)
        assertEquals(State.IDLE, g.state)
        assertEquals(5, g.moves)
    }

    @Test fun hammerClearsOneCandyAndCostsNoMove() {
        val g = mk()
        assertEquals(1, g.hammer(4, 4).toList().firstOf<Cleared>().cleared.size)
        assertEquals(20, g.moves)
        invariants(g, "hammer")
    }

    @Test fun hundredLevelsAreWellFormedAndPlayable() {
        val kinds = HashSet<String>()
        for (n in 1..100) {
            val lv = levelConfig(n)
            assertEquals(n, lv.id)
            assertTrue(lv.moves >= 14 && lv.objectives.isNotEmpty())
            assertTrue(lv.stars[0] < lv.stars[1] && lv.stars[1] < lv.stars[2])
            assertEquals((n - 1) / 10, lv.world)
            lv.objectives.forEach { kinds.add(it.javaClass.simpleName) }
            val g = Game(lv)
            assertTrue(findRuns(g.grid).isEmpty())
            assertTrue(hasValidMove(g.grid))
            if (lv.objectives.any { it is Objective.Jelly }) assertTrue(lv.jelly.isNotEmpty())
        }
        listOf("Score", "Collect", "Jelly", "MakeSpecial").forEach { assertTrue("objective $it", kinds.contains(it)) }
        assertEquals(10, WORLD_NAMES.size)
    }
}

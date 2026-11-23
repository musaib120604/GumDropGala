package com.gumdropgala.engine

import kotlin.math.max

sealed class ObjectiveStatus {
    abstract val current: Int
    abstract val target: Int
    abstract val done: Boolean

    data class Score(override val current: Int, override val target: Int, override val done: Boolean) : ObjectiveStatus()
    data class Collect(val type: Int, override val current: Int, override val target: Int, override val done: Boolean) : ObjectiveStatus()
    data class Jelly(override val current: Int, override val target: Int, override val done: Boolean) : ObjectiveStatus()
    data class MakeSpecial(override val current: Int, override val target: Int, override val done: Boolean) : ObjectiveStatus()
}

class Stats { var maxCascade = 0; var turns = 0; var shuffles = 0 }
class Progress { val collected = IntArray(7); var specialsMade = 0 }

private class PlanCreated(val key: Int, val special: Int)

private class Plan(
    val cells: List<Int>, val effects: List<Effect>, val triggered: Int, val created: List<PlanCreated>,
    val groupsInfo: List<GroupInfo>, val bonus: Int, val converted: List<ConvertedCandy>, val combo: Boolean
)

private class Expansion(val cells: List<Int>, val effects: List<Effect>, val triggered: Int)

/**
 * One level's rules and state. Contains no Android code, no timers and no rendering.
 * Every turn ([swap], [hammer], [shuffleBooster]) is a lazy [Sequence] of [Event]s: the caller pulls one event,
 * plays its animation, then pulls the next. Board state advances only when events are pulled, so logic and
 * animation can never overlap, and input is rejected unless [state] is [State.IDLE].
 */
class Game(val level: LevelConfig, seed: Int = level.id * 7919 + 13, layout: List<String>? = null, private val sugarCrush: Boolean = false) {
    private val rng = Rng(seed)
    private val colors = level.colors
    private var nextId = 1

    var state: State = State.IDLE; private set
    var moves: Int = level.moves; private set
    var score: Int = 0; private set
    var result: GameResult? = null; private set
    var stars: Int = 0; private set
    var bonus: Int = 0; private set
    val grid: Grid = makeGrid()
    val jelly: Array<IntArray> = Array(H) { IntArray(W) }
    val progress = Progress()
    val stats = Stats()

    init {
        level.jelly.forEach { jelly[it.r][it.c] = it.layers }
        if (layout != null) loadLayout(layout) else generate()
    }

    private fun newCandy(type: Int, r: Int, c: Int, special: Int = Special.NONE) = Candy(nextId++, type, special, r, c)

    /** Test helper layout: '.' plain filler, 'B' bomb, otherwise a digit plus optional H/V/W. */
    private fun loadLayout(rows: List<String>) {
        for (r in 0 until H) {
            val toks = (rows.getOrNull(r) ?: "").trim().split(Regex("\\s+"))
            for (c in 0 until W) {
                val t = toks.getOrNull(c) ?: "."
                grid[r][c] = when {
                    t == "." -> newCandy((c + 2 * r) % 4, r, c)
                    t == "B" -> newCandy(BOMB_TYPE, r, c, Special.BOMB)
                    else -> {
                        val type = t[0] - '0'
                        val sp = when (t.getOrNull(1)) { 'H' -> Special.STRIPE_H; 'V' -> Special.STRIPE_V; 'W' -> Special.WRAPPED; else -> Special.NONE }
                        newCandy(type, r, c, sp)
                    }
                }
            }
        }
    }

    private fun randType(): Int = rng.nextInt(colors)

    private fun generate() {
        for (attempt in 0 until 80) {
            val g = grid
            for (r in 0 until H) for (c in 0 until W) {
                var t: Int
                var guard = 0
                do {
                    t = randType(); guard++
                } while (guard < 30 && (
                        (c >= 2 && g[r][c - 1] != null && g[r][c - 2] != null && g[r][c - 1]!!.type == t && g[r][c - 2]!!.type == t) ||
                        (r >= 2 && g[r - 1][c] != null && g[r - 2][c] != null && g[r - 1][c]!!.type == t && g[r - 2][c]!!.type == t)))
                g[r][c] = newCandy(t, r, c)
            }
            if (findRuns(g).isEmpty() && hasValidMove(g)) return
        }
        // Fallback pattern, guaranteed match-free, with one forced valid move.
        val m = max(4, colors)
        for (r in 0 until H) for (c in 0 until W) grid[r][c] = newCandy((c + 2 * r) % m, r, c)
        grid[3][3]!!.type = 0; grid[3][4]!!.type = 0; grid[2][5]!!.type = 0
        grid[3][5]!!.type = 1
        grid[3][2]!!.type = 2; grid[3][1]!!.type = 3
        grid[4][2]!!.type = 3; grid[4][3]!!.type = 1; grid[2][3]!!.type = 1
        if (findRuns(grid).isNotEmpty()) shuffleInPlace()
    }

    fun toText(): String = grid.joinToString("\n") { row ->
        row.joinToString(" ") { c ->
            if (c == null) ".." else if (c.special == Special.BOMB) "BB" else c.type.toString() + listOf("", "h", "v", "w")[c.special]
        }
    }

    fun allCandies(): List<Candy> {
        val o = ArrayList<Candy>(W * H)
        for (r in 0 until H) for (c in 0 until W) grid[r][c]?.let { o.add(it) }
        return o
    }

    /* ----- objectives ----- */
    fun jellyLeft(): Int { var n = 0; for (r in 0 until H) for (c in 0 until W) if (jelly[r][c] > 0) n++; return n }

    fun objectiveStatus(): List<ObjectiveStatus> = level.objectives.map { o ->
        when (o) {
            is Objective.Score -> ObjectiveStatus.Score(minOf(score, o.target), o.target, score >= o.target)
            is Objective.Collect -> { val cur = progress.collected[o.type]; ObjectiveStatus.Collect(o.type, minOf(cur, o.target), o.target, cur >= o.target) }
            is Objective.MakeSpecial -> ObjectiveStatus.MakeSpecial(minOf(progress.specialsMade, o.target), o.target, progress.specialsMade >= o.target)
            is Objective.Jelly -> { val total = level.jelly.size; val left = jellyLeft(); ObjectiveStatus.Jelly(total - left, total, left == 0) }
        }
    }

    fun isComplete(): Boolean = objectiveStatus().all { it.done }
    fun starsFor(s: Int): Int = if (s >= level.stars[2]) 3 else if (s >= level.stars[1]) 2 else 1

    /* ----- queries ----- */
    fun isValidSwap(a: Pos, b: Pos): Boolean = isValidSwapOn(grid, a, b)
    fun findMoves(): List<Move> = findAllMoves(grid, false)
    fun hasValidMove(): Boolean = hasValidMove(grid)
    fun findGroups(): List<Group> = findGroups(grid)

    /** Best-looking move for the hint animation: prefers combos and big or special-making matches. */
    fun findHint(): Move? {
        val mv = findMoves()
        if (mv.isEmpty()) return null
        var best = mv[0]
        var bestScore = -1
        for (m in mv) {
            val ca = grid[m.a.r][m.a.c]
            val cb = grid[m.b.r][m.b.c]
            var s = if (isCombo(ca, cb)) 100 else 0
            swapCells(grid, m.a, m.b)
            findGroups(grid).forEach { g -> s += g.size + (if (g.kind != GroupKind.NONE) 10 else 0) }
            swapCells(grid, m.a, m.b)
            if (s > bestScore) { bestScore = s; best = m }
        }
        return best
    }

    /* ----- special-effect expansion: every cell and every special is processed once, so chains cannot loop ----- */
    private fun mostCommonType(): Int {
        val cnt = IntArray(7)
        allCandies().forEach { if (it.special != Special.BOMB) cnt[it.type]++ }
        var best = 0
        for (t in 1 until 6) if (cnt[t] > cnt[best]) best = t
        return best
    }

    private fun expand(seed: Collection<Int>, protectedSet: Set<Int>?, pre: Set<Int>?, effectsIn: List<Effect>?): Expansion {
        val out = LinkedHashSet<Int>()
        val effects = ArrayList<Effect>(effectsIn ?: emptyList())
        val triggered = HashSet<Int>(pre ?: emptySet())
        var triggeredCount = 0
        val queue = ArrayList<Int>(seed)
        while (queue.isNotEmpty()) {
            val k = queue.removeAt(queue.size - 1)
            if (out.contains(k) || (protectedSet != null && protectedSet.contains(k))) continue
            val r = rowOf(k)
            val c = colOf(k)
            val cand = grid[r][c] ?: continue
            out.add(k)
            if (cand.special != Special.NONE && !triggered.contains(cand.id)) {
                triggered.add(cand.id); triggeredCount++
                when (cand.special) {
                    Special.STRIPE_H -> { effects.add(Effect.Row(r, c)); for (x in 0 until W) queue.add(key(r, x)) }
                    Special.STRIPE_V -> { effects.add(Effect.Col(r, c)); for (y in 0 until H) queue.add(key(y, c)) }
                    Special.WRAPPED -> {
                        effects.add(Effect.Blast(r, c, 1))
                        for (y in r - 1..r + 1) for (x in c - 1..c + 1) if (inBounds(y, x)) queue.add(key(y, x))
                    }
                    Special.BOMB -> {
                        val t = mostCommonType()
                        val targets = ArrayList<Int>()
                        for (y in 0 until H) for (x in 0 until W) {
                            val o = grid[y][x]
                            if (o != null && o.special != Special.BOMB && o.type == t) { queue.add(key(y, x)); targets.add(key(y, x)) }
                        }
                        effects.add(Effect.Bomb(r, c, t, targets))
                    }
                }
            }
        }
        return Expansion(out.toList(), effects, triggeredCount)
    }

    /* ----- plans ----- */
    private fun spotFor(group: Group, preferred: List<Int>): Int? {
        if (group.kind == GroupKind.NONE) return null
        fun ok(k: Int): Boolean { val c = grid[rowOf(k)][colOf(k)]; return c != null && c.special == Special.NONE }
        for (p in preferred) if (group.cells.contains(p) && ok(p)) return p
        val inter = group.intersection
        if (group.kind == GroupKind.WRAPPED && inter != null && ok(inter)) return inter
        val lc = group.longest.cells
        val mid = lc[lc.size / 2]
        if (ok(mid)) return mid
        for (k in lc) if (ok(k)) return k
        for (k in group.cells) if (ok(k)) return k
        return null
    }

    private fun groupBonus(g: Group): Int = when (g.kind) { GroupKind.BOMB -> 300; GroupKind.WRAPPED -> 200; GroupKind.STRIPED -> 120; else -> 0 }

    private fun planFromGroups(groups: List<Group>, preferred: List<Int>): Plan {
        val matched = LinkedHashSet<Int>()
        val created = ArrayList<PlanCreated>()
        val protectedSet = HashSet<Int>()
        val infos = ArrayList<GroupInfo>()
        var bonusSum = 0
        for (grp in groups) {
            val spot = spotFor(grp, preferred)
            var special: Int? = null
            if (spot != null) {
                special = when (grp.kind) { GroupKind.BOMB -> Special.BOMB; GroupKind.WRAPPED -> Special.WRAPPED; else -> grp.stripeDir }
                created.add(PlanCreated(spot, special)); protectedSet.add(spot)
            }
            matched.addAll(grp.cells)
            bonusSum += groupBonus(grp)
            infos.add(GroupInfo(grp.type, grp.cells.toList(), grp.size, grp.shape, grp.kind, spot, special))
        }
        val ex = expand(matched, protectedSet, null, null)
        return Plan(ex.cells, ex.effects, ex.triggered, created, infos, bonusSum, emptyList(), false)
    }

    private fun comboPlan(pa: Pos, pb: Pos): Plan {
        val ca = grid[pa.r][pa.c]!!
        val cb = grid[pb.r][pb.c]!!
        val pre = HashSet<Int>(listOf(ca.id, cb.id))
        val seed = LinkedHashSet<Int>(listOf(key(pa.r, pa.c), key(pb.r, pb.c)))
        val effects = ArrayList<Effect>()
        val converted = ArrayList<ConvertedCandy>()
        fun addRow(r: Int) { for (x in 0 until W) seed.add(key(r, x)) }
        fun addCol(c: Int) { for (y in 0 until H) seed.add(key(y, c)) }
        val stripe = { s: Int -> s == Special.STRIPE_H || s == Special.STRIPE_V }
        if (ca.special == Special.BOMB && cb.special == Special.BOMB) {
            for (y in 0 until H) for (x in 0 until W) seed.add(key(y, x))
            effects.add(Effect.BombAll(pb.r, pb.c))
        } else if (ca.special == Special.BOMB || cb.special == Special.BOMB) {
            val bomb = if (ca.special == Special.BOMB) ca else cb
            val other = if (bomb === ca) cb else ca
            val bp = if (bomb === ca) pa else pb
            val t = other.type
            val targets = ArrayList<Int>()
            for (y in 0 until H) for (x in 0 until W) {
                val o = grid[y][x]
                if (o != null && o.special != Special.BOMB && o.type == t) targets.add(key(y, x))
            }
            if (other.special != Special.NONE) {
                pre.remove(other.id) // the partner fires too, along with every converted candy
                for (k in targets) {
                    val o = grid[rowOf(k)][colOf(k)]!!
                    if (o.special == Special.NONE) {
                        o.special = if (other.special == Special.WRAPPED) Special.WRAPPED else (if (rng.next() < 0.5) Special.STRIPE_H else Special.STRIPE_V)
                        converted.add(ConvertedCandy(o.id, o.r, o.c, o.special))
                    }
                }
                effects.add(Effect.BombConvert(bp.r, bp.c, t, targets))
            } else effects.add(Effect.Bomb(bp.r, bp.c, t, targets))
            seed.addAll(targets)
        } else if (stripe(ca.special) && stripe(cb.special)) {
            addRow(pb.r); addCol(pb.c)
            effects.add(Effect.Row(pb.r, pb.c)); effects.add(Effect.Col(pb.r, pb.c))
        } else if (ca.special == Special.WRAPPED && cb.special == Special.WRAPPED) {
            for (y in pb.r - 2..pb.r + 2) for (x in pb.c - 2..pb.c + 2) if (inBounds(y, x)) seed.add(key(y, x))
            effects.add(Effect.Blast(pb.r, pb.c, 2))
        } else { // striped + wrapped: three rows and three columns
            for (d in -1..1) { if (inBounds(pb.r + d, 0)) addRow(pb.r + d); if (inBounds(0, pb.c + d)) addCol(pb.c + d) }
            effects.add(Effect.BigCross(pb.r, pb.c))
        }
        val ex = expand(seed, null, pre, effects)
        return Plan(ex.cells, ex.effects, ex.triggered + 1, emptyList(), emptyList(), 500, converted, true)
    }

    /* ----- clearing, gravity, refill ----- */
    private class ClearResult(val cleared: List<ClearedCandy>, val created: List<CreatedSpecial>, val gain: Int)

    private fun applyClear(plan: Plan, cascade: Int): ClearResult {
        val cleared = ArrayList<ClearedCandy>()
        for (k in plan.cells) {
            val r = rowOf(k)
            val c = colOf(k)
            val cand = grid[r][c] ?: continue
            cleared.add(ClearedCandy(cand.id, r, c, cand.type, cand.special))
            grid[r][c] = null
            if (cand.type < BOMB_TYPE) progress.collected[cand.type]++
            if (jelly[r][c] > 0) jelly[r][c]--
        }
        val created = ArrayList<CreatedSpecial>()
        for (cr in plan.created) {
            val r = rowOf(cr.key)
            val c = colOf(cr.key)
            val cand = grid[r][c] ?: continue
            cand.special = cr.special
            if (cr.special == Special.BOMB) cand.type = BOMB_TYPE
            progress.specialsMade++
            created.add(CreatedSpecial(cand.id, r, c, cr.special, cand.type))
        }
        val gain = scoreForClear(cleared.size, cascade, plan.triggered) + plan.bonus
        score += gain
        return ClearResult(cleared, created, gain)
    }

    private fun gravity(): List<FallMove> {
        val out = ArrayList<FallMove>()
        for (c in 0 until W) {
            var wr = H - 1
            for (r in H - 1 downTo 0) {
                val cand = grid[r][c] ?: continue
                if (r != wr) { grid[wr][c] = cand; grid[r][c] = null; out.add(FallMove(cand.id, c, r, wr)); cand.r = wr }
                wr--
            }
        }
        return out
    }

    private fun refill(): List<Spawn> {
        val out = ArrayList<Spawn>()
        for (c in 0 until W) {
            var n = 0
            while (n < H && grid[n][c] == null) n++
            for (r in 0 until n) {
                val cand = newCandy(randType(), r, c)
                grid[r][c] = cand
                out.add(Spawn(cand.id, cand.type, c, r - n, r))
            }
        }
        return out
    }

    /* ----- turn pipeline ----- */
    private fun resolve(first: Plan?, preferredIn: List<Int>): Sequence<Event> = sequence {
        var pending = first
        var preferred = preferredIn
        var cascade = 0
        while (true) {
            val plan: Plan
            val p = pending
            if (p != null) { plan = p; pending = null } else {
                val groups = findGroups(grid)
                if (groups.isEmpty()) break
                plan = planFromGroups(groups, preferred)
            }
            preferred = emptyList()
            cascade++
            if (cascade > 1) { state = State.CASCADING; yield(CascadeStep(cascade)) }
            state = State.MATCHING
            yield(Matched(plan.groupsInfo, cascade))
            state = State.CLEARING
            val res = applyClear(plan, cascade)
            stats.maxCascade = max(stats.maxCascade, cascade)
            yield(Cleared(res.cleared, res.created, plan.converted, plan.effects, res.gain, cascade, plan.combo, plan.triggered, score))
            val falls = gravity()
            if (falls.isNotEmpty()) { state = State.FALLING; yield(Fall(falls)) }
            val spawns = refill()
            if (spawns.isNotEmpty()) { state = State.REFILLING; yield(Refill(spawns)) }
        }
    }

    private fun finishTurn(): Sequence<Event> = sequence {
        if (isComplete()) {
            if (sugarCrush && moves > 0) yieldAll(sugarCrushSequence())
            yieldAll(end(GameResult.WIN)); return@sequence
        }
        if (moves <= 0) { yieldAll(end(GameResult.LOSE)); return@sequence }
        if (!hasValidMove(grid)) yieldAll(shuffle("deadlock"))
        state = State.IDLE
    }

    /**
     * Candy-Crush-style bonus round: each leftover move turns a random plain candy into a striped candy,
     * then they fire one after another (each spends a move, so the flat move bonus is not paid twice).
     */
    private fun sugarCrushSequence(): Sequence<Event> = sequence {
        val plain = ArrayList<Candy>()
        for (r in 0 until H) for (c in 0 until W) grid[r][c]?.let { if (it.special == Special.NONE && it.type < BOMB_TYPE) plain.add(it) }
        val n = minOf(moves, 8, plain.size)
        if (n <= 0) return@sequence
        val picked = ArrayList<Candy>()
        repeat(n) { val i = rng.nextInt(plain.size); picked.add(plain.removeAt(i)) }
        val created = picked.map { cd ->
            cd.special = if (rng.next() < 0.5) Special.STRIPE_H else Special.STRIPE_V
            CreatedSpecial(cd.id, cd.r, cd.c, cd.special, cd.type)
        }
        score += 250 * n // same per-move reward as the flat bonus, so stars aren't harder to earn
        yield(SugarCrush(created, moves))
        for (cd in picked) {
            if (grid[cd.r][cd.c] !== cd) continue // already swept away by an earlier blast
            moves--
            val ex = expand(listOf(key(cd.r, cd.c)), null, null, null)
            val plan = Plan(ex.cells, ex.effects, ex.triggered, emptyList(), emptyList(), 0, emptyList(), false)
            yieldAll(resolve(plan, emptyList()))
        }
        moves = 0 // anything unspent is folded into the bonus round
    }

    private fun end(res: GameResult): Sequence<Event> = sequence {
        result = res
        if (res == GameResult.WIN) {
            bonus = moves * 250
            score += bonus
            stars = starsFor(score)
        }
        state = State.GAME_OVER
        yield(Ended(res, bonus, stars, score))
    }

    /** Player swap. Pull every event before starting another turn. */
    fun swap(a: Pos, b: Pos): Sequence<Event> = sequence {
        if (state != State.IDLE) { yield(Rejected(state.name)); return@sequence }
        state = State.SWAPPING
        if (!isValidSwap(a, b)) {
            yield(Invalid(a, b))
            state = State.IDLE
            return@sequence
        }
        val ca = grid[a.r][a.c]!!
        val cb = grid[b.r][b.c]!!
        val combo = isCombo(ca, cb)
        swapCells(grid, a, b)
        moves--; stats.turns++
        yield(Swapped(a, b, ca.id, cb.id, combo))
        val first = if (combo) comboPlan(a, b) else null
        yieldAll(resolve(first, listOf(key(a.r, a.c), key(b.r, b.c))))
        yieldAll(finishTurn())
    }

    /** Hammer booster: smashes one candy and costs no move. */
    fun hammer(r: Int, c: Int): Sequence<Event> = sequence {
        if (state != State.IDLE) { yield(Rejected(state.name)); return@sequence }
        if (!inBounds(r, c) || grid[r][c] == null) { yield(Rejected("empty")); return@sequence }
        state = State.MATCHING
        val ex = expand(listOf(key(r, c)), null, null, null)
        val plan = Plan(ex.cells, ex.effects, ex.triggered, emptyList(), emptyList(), 0, emptyList(), false)
        yieldAll(resolve(plan, emptyList()))
        yieldAll(finishTurn())
    }

    fun addMoves(n: Int) {
        moves += n
        if (state == State.GAME_OVER && result == GameResult.LOSE) { result = null; state = State.IDLE }
    }

    /* ----- shuffle: always leaves a match-free board with at least one valid move ----- */
    private fun formsRun(r: Int, c: Int, type: Int, special: Int): Boolean {
        if (special == Special.BOMB) return false
        fun same(y: Int, x: Int): Boolean { val o = grid[y][x]; return o != null && o.special != Special.BOMB && o.type == type }
        return (c >= 2 && same(r, c - 1) && same(r, c - 2)) || (r >= 2 && same(r - 1, c) && same(r - 2, c))
    }

    private fun shuffleInPlace() {
        val cands = allCandies()
        for (attempt in 0 until 60) {
            val pool = ArrayList(cands)
            for (i in pool.size - 1 downTo 1) { val j = rng.nextInt(i + 1); val t = pool[i]; pool[i] = pool[j]; pool[j] = t }
            for (r in 0 until H) for (c in 0 until W) grid[r][c] = null
            for (r in 0 until H) for (c in 0 until W) {
                var pick = -1
                for (j in pool.indices) if (!formsRun(r, c, pool[j].type, pool[j].special)) { pick = j; break }
                if (pick < 0) pick = 0
                val cd = pool.removeAt(pick)
                cd.r = r; cd.c = c; grid[r][c] = cd
            }
            for (rep in 0 until 150) {
                val runs = findRuns(grid)
                if (runs.isEmpty()) break
                val run = runs[rng.nextInt(runs.size)]
                val k = run.cells[rng.nextInt(run.cells.size)]
                val k2 = rng.nextInt(W * H)
                if (k == k2) continue
                val a = Pos(rowOf(k), colOf(k))
                val b = Pos(rowOf(k2), colOf(k2))
                val before = runs.size
                swapCells(grid, a, b)
                if (findRuns(grid).size > before) swapCells(grid, a, b)
            }
            if (findRuns(grid).isEmpty() && hasValidMove(grid)) return
        }
        // Last resort: recolour every non-bomb candy so that no placement forms a run.
        for (attempt in 0 until 500) {
            for (r in 0 until H) for (c in 0 until W) {
                val cd = grid[r][c]!!
                if (cd.special == Special.BOMB) continue
                val order = (0 until colors).toMutableList()
                for (i in order.size - 1 downTo 1) { val j = rng.nextInt(i + 1); val t = order[i]; order[i] = order[j]; order[j] = t }
                cd.type = order.firstOrNull { !formsRun(r, c, it, cd.special) } ?: order[0]
            }
            if (findRuns(grid).isEmpty() && hasValidMove(grid)) return
        }
    }

    private fun shuffle(reason: String): Sequence<Event> = sequence {
        val before = HashMap<Int, Pos>()
        allCandies().forEach { before[it.id] = Pos(it.r, it.c) }
        state = State.CASCADING
        shuffleInPlace()
        stats.shuffles++
        val mv = allCandies().map { c -> val b = before[c.id]!!; ShuffleMove(c.id, b.r, b.c, c.r, c.c, c.type) }
        yield(Shuffled(mv, reason))
        state = State.IDLE
    }

    /** Shuffle booster entry point; respects the state machine. */
    fun shuffleBooster(): Sequence<Event> = sequence {
        if (state != State.IDLE) { yield(Rejected(state.name)); return@sequence }
        yieldAll(shuffle("booster"))
    }

    /** Used by tests to exercise the end-of-turn logic on arbitrary boards. */
    internal fun finishTurnForTest(): Sequence<Event> = finishTurn()
    internal fun shuffleForTest(reason: String): Sequence<Event> = shuffle(reason)
    internal fun gravityForTest(): List<FallMove> = gravity()
    internal fun refillForTest(): List<Spawn> = refill()
    internal fun setStateForTest(s: State) { state = s }
}

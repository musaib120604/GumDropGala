package com.gumdropgala.engine

class Run(val dir: Char, val type: Int, val cells: List<Int>)

class Group(
    val type: Int, val cells: List<Int>, val runs: List<Run>, val kind: GroupKind, val shape: String,
    val hasH: Boolean, val hasV: Boolean, val maxRun: Int, val longest: Run, val intersection: Int?, val stripeDir: Int
) {
    val size: Int get() = cells.size
}

data class Move(val a: Pos, val b: Pos)

private fun matchable(c: Candy?): Boolean = c != null && c.special != Special.BOMB

/** Every straight line of 3+ same-coloured candies. Colour bombs never match. */
fun findRuns(g: Grid): List<Run> {
    val runs = ArrayList<Run>()
    for (r in 0 until H) {
        var c = 0
        while (c < W) {
            val a = g[r][c]
            if (!matchable(a)) { c++; continue }
            var e = c + 1
            while (e < W && matchable(g[r][e]) && g[r][e]!!.type == a!!.type) e++
            if (e - c >= 3) runs.add(Run('h', a!!.type, (c until e).map { key(r, it) }))
            c = e
        }
    }
    for (c in 0 until W) {
        var r = 0
        while (r < H) {
            val a = g[r][c]
            if (!matchable(a)) { r++; continue }
            var e = r + 1
            while (e < H && matchable(g[e][c]) && g[e][c]!!.type == a!!.type) e++
            if (e - r >= 3) runs.add(Run('v', a!!.type, (r until e).map { key(it, c) }))
            r = e
        }
    }
    return runs
}

/** Merges overlapping runs (union-find) and classifies each merged group's shape and reward. */
fun findGroups(g: Grid): List<Group> {
    val runs = findRuns(g)
    if (runs.isEmpty()) return emptyList()
    val parent = IntArray(runs.size) { it }
    fun find(x: Int): Int { var i = x; while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i] }; return i }
    val owner = HashMap<Int, Int>()
    runs.forEachIndexed { i, run ->
        for (k in run.cells) {
            val o = owner[k]
            if (o != null) { val a = find(i); val b = find(o); if (a != b) parent[a] = b } else owner[k] = i
        }
    }
    val byRoot = LinkedHashMap<Int, ArrayList<Run>>()
    runs.forEachIndexed { i, run -> byRoot.getOrPut(find(i)) { ArrayList() }.add(run) }
    val groups = ArrayList<Group>()
    for (rs in byRoot.values) {
        val cells = LinkedHashSet<Int>()
        rs.forEach { cells.addAll(it.cells) }
        val hasH = rs.any { it.dir == 'h' }
        val hasV = rs.any { it.dir == 'v' }
        var longest = rs[0]
        rs.forEach { if (it.cells.size > longest.cells.size) longest = it }
        val maxRun = longest.cells.size
        var shape = "line" + minOf(5, maxRun)
        var intersection: Int? = null
        if (hasH && hasV) {
            val cnt = LinkedHashMap<Int, IntArray>()
            for (r in rs) for (k in r.cells) {
                val e = cnt.getOrPut(k) { IntArray(2) }
                if (r.dir == 'h') e[0]++ else e[1]++
            }
            for ((k, e) in cnt) if (e[0] > 0 && e[1] > 0) { intersection = k; break }
            if (intersection != null) {
                val hr = rs.first { it.dir == 'h' && it.cells.contains(intersection) }
                val vr = rs.first { it.dir == 'v' && it.cells.contains(intersection) }
                val endH = hr.cells.first() == intersection || hr.cells.last() == intersection
                val endV = vr.cells.first() == intersection || vr.cells.last() == intersection
                shape = if (endH && endV) "L" else "T"
            }
        }
        val kind = when {
            maxRun >= 5 -> GroupKind.BOMB
            hasH && hasV -> GroupKind.WRAPPED
            maxRun == 4 -> GroupKind.STRIPED
            else -> GroupKind.NONE
        }
        groups.add(
            Group(rs[0].type, cells.toList(), rs, kind, shape, hasH, hasV, maxRun, longest, intersection,
                if (longest.dir == 'h') Special.STRIPE_H else Special.STRIPE_V)
        )
    }
    groups.sortBy { gr -> gr.cells.minOrNull() ?: 0 }
    return groups
}

fun isCombo(a: Candy?, b: Candy?): Boolean =
    a != null && b != null && (a.special == Special.BOMB || b.special == Special.BOMB || (a.special != 0 && b.special != 0))

fun swapCells(g: Grid, a: Pos, b: Pos) {
    val ca = g[a.r][a.c]
    val cb = g[b.r][b.c]
    g[a.r][a.c] = cb
    g[b.r][b.c] = ca
    if (cb != null) { cb.r = a.r; cb.c = a.c }
    if (ca != null) { ca.r = b.r; ca.c = b.c }
}

fun isValidSwapOn(g: Grid, a: Pos, b: Pos): Boolean {
    if (!inBounds(a.r, a.c) || !inBounds(b.r, b.c)) return false
    if (Math.abs(a.r - b.r) + Math.abs(a.c - b.c) != 1) return false
    val ca = g[a.r][a.c]
    val cb = g[b.r][b.c]
    if (ca == null || cb == null) return false
    if (isCombo(ca, cb)) return true
    swapCells(g, a, b)
    val ok = findRuns(g).isNotEmpty()
    swapCells(g, a, b)
    return ok
}

fun findAllMoves(g: Grid, firstOnly: Boolean = false): List<Move> {
    val out = ArrayList<Move>()
    for (r in 0 until H) for (c in 0 until W) {
        for (d in 0..1) {
            val a = Pos(r, c)
            val b = if (d == 0) Pos(r, c + 1) else Pos(r + 1, c)
            if (isValidSwapOn(g, a, b)) { out.add(Move(a, b)); if (firstOnly) return out }
        }
    }
    return out
}

fun hasValidMove(g: Grid): Boolean = findAllMoves(g, true).isNotEmpty()

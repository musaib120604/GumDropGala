package com.gumdropgala

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.gumdropgala.engine.LEVEL_COUNT
import org.json.JSONObject
import java.time.LocalDate

const val MAX_LIVES = 5
const val LIFE_REGEN_MS = 12L * 60L * 1000L

class GameSettings {
    var music = 0.55f
    var sfx = 0.8f
    var vibrate = true
    var reduceMotion = false
    var symbols = false
    var bigText = false
}

class Profile {
    var name = "Sweet Tooth"
    var avatar = 0
    var played = 0
    var wins = 0
    var losses = 0
    var totalScore = 0L
    var bestCascade = 0
    var specials = 0
}

enum class Booster { HAMMER, SHUFFLE, MOVES }

/** Everything that persists between sessions. */
class SaveData {
    var coins = 150
    var lives = MAX_LIVES
    var livesAt = 0L
    var unlocked = 1
    val stars = IntArray(LEVEL_COUNT + 2)
    val boosters = IntArray(3).also { it[0] = 2; it[1] = 2; it[2] = 2 }
    val settings = GameSettings()
    var dailyLast = ""
    var dailyStreak = 0
    val profile = Profile()

    fun boost(b: Booster): Int = boosters[b.ordinal]

    /** Adds lives that have regenerated since the last call. */
    fun tickLives(now: Long = System.currentTimeMillis()) {
        if (lives >= MAX_LIVES) { livesAt = 0; return }
        if (livesAt == 0L) livesAt = now + LIFE_REGEN_MS
        while (lives < MAX_LIVES && now >= livesAt) { lives++; livesAt += LIFE_REGEN_MS }
        if (lives >= MAX_LIVES) livesAt = 0
    }

    fun spendLife(now: Long = System.currentTimeMillis()) {
        tickLives(now)
        if (lives > 0) { lives--; if (livesAt == 0L) livesAt = now + LIFE_REGEN_MS }
    }

    fun refillLives() { lives = MAX_LIVES; livesAt = 0 }

    fun totalStars(): Int = stars.sum()
    fun levelsWon(): Int = stars.count { it > 0 }

    /* ----- daily gift: 7-day streak ----- */
    class DailyState(val canClaim: Boolean, val dayIndex: Int)

    fun dailyState(today: LocalDate = LocalDate.now()): DailyState {
        val t = today.toString()
        val y = today.minusDays(1).toString()
        if (dailyLast == t) return DailyState(false, dailyStreak % 7) // next day to show after today's claim
        val streak = if (dailyLast == y) dailyStreak else 0
        return DailyState(true, streak % 7)
    }

    fun claimDaily(today: LocalDate = LocalDate.now()): DailyReward? {
        val st = dailyState(today)
        if (!st.canClaim) return null
        val reward = DAILY_REWARDS[st.dayIndex]
        val y = today.minusDays(1).toString()
        dailyStreak = (if (dailyLast == y) dailyStreak else 0) + 1
        dailyLast = today.toString()
        coins += reward.coins
        if (reward.booster != null) boosters[reward.booster.ordinal] += reward.boosterCount
        if (reward.fullLives) refillLives()
        return reward
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("coins", coins).put("lives", lives).put("livesAt", livesAt).put("unlocked", unlocked)
        o.put("stars", stars.joinToString(","))
        o.put("boosters", boosters.joinToString(","))
        o.put("dailyLast", dailyLast).put("dailyStreak", dailyStreak)
        val s = JSONObject()
        s.put("music", settings.music.toDouble()).put("sfx", settings.sfx.toDouble()).put("vibrate", settings.vibrate)
        s.put("reduceMotion", settings.reduceMotion).put("symbols", settings.symbols).put("bigText", settings.bigText)
        o.put("settings", s)
        val p = JSONObject()
        p.put("name", profile.name).put("avatar", profile.avatar).put("played", profile.played).put("wins", profile.wins)
        p.put("losses", profile.losses).put("totalScore", profile.totalScore).put("bestCascade", profile.bestCascade).put("specials", profile.specials)
        o.put("profile", p)
        return o.toString()
    }

    companion object {
        fun fromJson(text: String?): SaveData {
            val d = SaveData()
            if (text.isNullOrEmpty()) return d
            try {
                val o = JSONObject(text)
                d.coins = o.optInt("coins", d.coins).coerceAtLeast(0)
                d.lives = o.optInt("lives", d.lives).coerceIn(0, MAX_LIVES)
                d.livesAt = o.optLong("livesAt", 0L)
                d.unlocked = o.optInt("unlocked", 1).coerceIn(1, LEVEL_COUNT)
                o.optString("stars", "").split(",").forEachIndexed { i, v -> if (i < d.stars.size) d.stars[i] = v.toIntOrNull()?.coerceIn(0, 3) ?: 0 }
                o.optString("boosters", "").split(",").forEachIndexed { i, v -> if (i < 3) d.boosters[i] = v.toIntOrNull()?.coerceAtLeast(0) ?: d.boosters[i] }
                d.dailyLast = o.optString("dailyLast", "")
                d.dailyStreak = o.optInt("dailyStreak", 0)
                o.optJSONObject("settings")?.let { s ->
                    d.settings.music = s.optDouble("music", 0.55).toFloat().coerceIn(0f, 1f)
                    d.settings.sfx = s.optDouble("sfx", 0.8).toFloat().coerceIn(0f, 1f)
                    d.settings.vibrate = s.optBoolean("vibrate", true)
                    d.settings.reduceMotion = s.optBoolean("reduceMotion", false)
                    d.settings.symbols = s.optBoolean("symbols", false)
                    d.settings.bigText = s.optBoolean("bigText", false)
                }
                o.optJSONObject("profile")?.let { p ->
                    d.profile.name = p.optString("name", d.profile.name).take(14).ifBlank { "Sweet Tooth" }
                    d.profile.avatar = p.optInt("avatar", 0).coerceIn(0, 5)
                    d.profile.played = p.optInt("played", 0)
                    d.profile.wins = p.optInt("wins", 0)
                    d.profile.losses = p.optInt("losses", 0)
                    d.profile.totalScore = p.optLong("totalScore", 0L)
                    d.profile.bestCascade = p.optInt("bestCascade", 0)
                    d.profile.specials = p.optInt("specials", 0)
                }
            } catch (e: Exception) {
                return SaveData() // corrupt save: start fresh
            }
            return d
        }
    }
}

class DailyReward(val coins: Int, val booster: Booster?, val boosterCount: Int, val fullLives: Boolean)

val DAILY_REWARDS = listOf(
    DailyReward(50, null, 0, false), DailyReward(0, Booster.HAMMER, 1, false), DailyReward(100, null, 0, false),
    DailyReward(0, Booster.SHUFFLE, 1, false), DailyReward(150, null, 0, false), DailyReward(0, Booster.MOVES, 2, false),
    DailyReward(300, null, 0, true)
)

class SaveStore(private val ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("gumdrop_gala", Context.MODE_PRIVATE)

    fun load(): SaveData {
        val d = SaveData.fromJson(prefs.getString("save_v1", null))
        if (prefs.getString("save_v1", null) == null) {
            d.settings.reduceMotion = try {
                Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            } catch (e: Exception) { false }
        }
        d.tickLives()
        return d
    }

    fun save(d: SaveData) { prefs.edit().putString("save_v1", d.toJson()).apply() }
}

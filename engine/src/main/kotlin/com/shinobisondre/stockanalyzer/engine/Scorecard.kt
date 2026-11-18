package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.roundToInt

enum class GuessKind(val title: String) {
    PICK("Picks"), QUICK("Quick wins"), RANGE("Lows & highs"),
}

/**
 * One thing the app said, saved the moment it said it, so it can be checked later.
 *  PICK  — "Good to buy" (expect +1) / "Stay away" (−1) / "Keep an eye on it" (0); checked after [days] sessions.
 *  QUICK — "Sell before the close / Don't buy" (−1) or "Keep it / Buy now" (+1); checked at the next day's close
 *          against that day's close.
 *  RANGE — likely/wide low & high for the next [days] sessions; checked against the actual lows/highs.
 */
data class Guess(
    val kind: GuessKind,
    val symbol: String,
    val name: String,
    /** Market date (yyyy-MM-dd) of the last bar when the guess was made. */
    val date: String,
    val madeAt: Long,
    val price: Double,
    val label: String,
    val expect: Int,
    val days: Int,
    val because: String = "",
    val likelyLow: Double? = null,
    val likelyHigh: Double? = null,
    val wideLow: Double? = null,
    val wideHigh: Double? = null,
) {
    val key: String get() = "${kind.name}|$symbol|$date|$days"

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "kind" to kind.name, "symbol" to symbol, "name" to name, "date" to date, "madeAt" to madeAt.toDouble(), "price" to price,
        "label" to label, "expect" to expect.toDouble(), "days" to days.toDouble(), "because" to because,
        "likelyLow" to likelyLow, "likelyHigh" to likelyHigh, "wideLow" to wideLow, "wideHigh" to wideHigh,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): Guess? = runCatching {
            fun d(k: String) = (m[k] as? Number)?.toDouble()
            Guess(GuessKind.valueOf(m["kind"] as String), m["symbol"] as String, (m["name"] as? String) ?: (m["symbol"] as String),
                m["date"] as String, d("madeAt")!!.toLong(), d("price")!!, (m["label"] as? String) ?: "", d("expect")?.toInt() ?: 0,
                d("days")?.toInt() ?: 1, (m["because"] as? String) ?: "", d("likelyLow"), d("likelyHigh"), d("wideLow"), d("wideHigh"))
        }.getOrNull()
    }
}

/** A guess next to what actually happened. [right] is null while waiting, or when the guess didn't pick a side. */
data class Checked(
    val guess: Guess,
    val done: Boolean,
    /** Sessions that have passed since the guess. */
    val passed: Int,
    val endPrice: Double?,
    val changePct: Double?,
    val lowSeen: Double?,
    val highSeen: Double?,
    val right: Boolean?,
    val text: String,
)

data class ScoreLine(val title: String, val n: Int, val right: Int, val avgChangePct: Double?, val text: String) {
    val rate: Double get() = if (n > 0) right.toDouble() / n else Double.NaN
}

data class Scoreboard(val lines: Map<GuessKind, List<ScoreLine>>, val waiting: Map<GuessKind, Int>, val checked: Map<GuessKind, Int>)

object Scorecard {
    const val MAX = 4000

    /** Keep only the first guess per kind+symbol+day ("what it thought first"), newest [MAX]. */
    fun add(all: List<Guess>, new: List<Guess>): List<Guess> {
        val keys = all.mapTo(HashSet()) { it.key }
        val add = new.filter { keys.add(it.key) }
        return if (add.isEmpty()) all else (all + add).sortedBy { it.madeAt }.takeLast(MAX)
    }

    private fun pct(v: Double) = "${if (v >= 0) "+" else "−"}${"%.1f".format(java.util.Locale.US, abs(v))}%"

    /** Grade [g] with completed daily candles of its symbol. */
    fun check(g: Guess, s: Series): Checked {
        var i = s.indexOfDate(g.date)
        if (i < 0) { i = (s.last downTo 0).firstOrNull { s.dates[it] <= g.date } ?: return Checked(g, false, 0, null, null, null, null, null, "Waiting for prices.") }
        val passed = s.last - i
        when (g.kind) {
            GuessKind.QUICK -> {
                if (passed < 1) return Checked(g, false, 0, null, null, null, null, null, "Checked after tomorrow's close.")
                val base = s.close[i]; val next = s.close[i + 1]; val ch = (next / base - 1) * 100
                val right = when { g.expect > 0 -> next > base; g.expect < 0 -> next < base; else -> null }
                val verb = if (ch >= 0) "rose" else "fell"
                val text = when (right) {
                    true -> "Right — the next day it $verb ${pct(ch).drop(1)}."
                    false -> "Wrong — the next day it $verb ${pct(ch).drop(1)}."
                    null -> "The next day it $verb ${pct(ch).drop(1)}."
                }
                return Checked(g, true, passed, next, ch, s.low[i + 1], s.high[i + 1], right, text)
            }
            GuessKind.PICK -> {
                val end = minOf(i + g.days, s.last)
                val ch = (s.close[end] / g.price - 1) * 100
                val done = passed >= g.days
                val right = if (!done) null else when { g.expect > 0 -> ch > 0; g.expect < 0 -> ch < 0; else -> null }
                val text = if (!done) "So far ${pct(ch)} after $passed of ${g.days} days."
                else when (right) {
                    true -> "Right — ${pct(ch)} after ${g.days} trading days."
                    false -> "Wrong — ${pct(ch)} after ${g.days} trading days."
                    null -> "${pct(ch)} after ${g.days} trading days."
                }
                return Checked(g, done, passed, s.close[end], ch, null, null, right, text)
            }
            GuessKind.RANGE -> {
                if (passed < 1) return Checked(g, false, 0, null, null, null, null, null, "Starts tomorrow.")
                val end = minOf(i + g.days, s.last)
                var lo = Double.MAX_VALUE; var hi = 0.0
                for (k in i + 1..end) { lo = minOf(lo, s.low[k]); hi = maxOf(hi, s.high[k]) }
                val done = passed >= g.days
                val inside = g.wideLow != null && g.wideHigh != null && lo >= g.wideLow && hi <= g.wideHigh
                val ch = (s.close[end] / g.price - 1) * 100
                val parts = mutableListOf("Low ${Fmt.price(lo)}, high ${Fmt.price(hi)}")
                g.likelyLow?.let { parts += if (lo <= it) "reached the likely low" else "stayed above the likely low" }
                g.likelyHigh?.let { parts += if (hi >= it) "reached the likely high" else "stayed below the likely high" }
                val text = (if (done) "" else "So far ($passed of ${g.days} days): ") + parts.joinToString(", ") + "." +
                    if (done) (if (inside) " Stayed inside the outer range ✓" else " Broke out of the outer range ✗") else ""
                return Checked(g, done, passed, s.close[end], ch, lo, hi, if (done) inside else null, text)
            }
        }
    }

    fun board(checked: List<Checked>): Scoreboard {
        val done = checked.filter { it.done }
        val lines = mutableMapOf<GuessKind, List<ScoreLine>>()
        // Picks: each tier vs the average of everything the app looked at.
        run {
            val p = done.filter { it.guess.kind == GuessKind.PICK }
            val allAvg = p.mapNotNull { it.changePct }.takeIf { it.isNotEmpty() }?.average()
            val out = mutableListOf<ScoreLine>()
            for ((label, exp) in listOf("Good to buy" to 1, "Stay away for now" to -1, "Keep an eye on it" to 0)) {
                val g = p.filter { it.guess.expect == exp }
                if (g.isEmpty()) continue
                val avg = g.mapNotNull { it.changePct }.average()
                val right = if (exp == 0) g.count { (it.changePct ?: 0.0) > 0 } else g.count { it.right == true }
                val days = g.first().guess.days
                val what = when (exp) { 1 -> "went up"; -1 -> "went down"; else -> "went up" }
                val vs = allAvg?.let { " (all stocks it looked at: ${pct(it)})" } ?: ""
                out += ScoreLine(label, g.size, right, avg,
                    "$right of ${g.size} $what after $days trading days (${(right * 100.0 / g.size).roundToInt()}%). Average ${pct(avg)}$vs.")
            }
            if (out.isNotEmpty()) lines[GuessKind.PICK] = out
        }
        // Quick wins: did the next day go the way it said?
        run {
            val q = done.filter { it.guess.kind == GuessKind.QUICK && it.guess.expect != 0 }
            val out = mutableListOf<ScoreLine>()
            for ((label, exp) in listOf("Sell / don't buy" to -1, "Keep / buy" to 1)) {
                val g = q.filter { it.guess.expect == exp }
                if (g.isEmpty()) continue
                val right = g.count { it.right == true }
                val avg = g.mapNotNull { it.changePct }.average()
                val what = if (exp < 0) "were lower" else "were higher"
                out += ScoreLine(label, g.size, right, avg,
                    "$right of ${g.size} $what the next day (${(right * 100.0 / g.size).roundToInt()}%). Average next day ${pct(avg)}.")
            }
            if (out.isNotEmpty()) lines[GuessKind.QUICK] = out
        }
        // Ranges: inside the outer band (~80% aim) and how often the likely low/high were reached (~50% aim).
        run {
            val r = done.filter { it.guess.kind == GuessKind.RANGE }
            val out = mutableListOf<ScoreLine>()
            for ((days, label) in Outlook.HORIZONS) {
                val g = r.filter { it.guess.days == days }
                if (g.isEmpty()) continue
                val inside = g.count { it.right == true }
                val lowHit = g.count { c -> c.guess.likelyLow != null && c.lowSeen != null && c.lowSeen <= c.guess.likelyLow }
                val highHit = g.count { c -> c.guess.likelyHigh != null && c.highSeen != null && c.highSeen >= c.guess.likelyHigh }
                fun pc(x: Int) = (x * 100.0 / g.size).roundToInt()
                out += ScoreLine("$label ranges", g.size, inside, null,
                    "Stayed inside the outer range $inside of ${g.size} times (${pc(inside)}% — aim 80%). " +
                        "Reached the likely low ${pc(lowHit)}% and the likely high ${pc(highHit)}% of the time (aim about 50%).")
            }
            if (out.isNotEmpty()) lines[GuessKind.RANGE] = out
        }
        return Scoreboard(lines,
            GuessKind.values().associateWith { k -> checked.count { it.guess.kind == k && !it.done } },
            GuessKind.values().associateWith { k -> done.count { it.guess.kind == k } })
    }
}

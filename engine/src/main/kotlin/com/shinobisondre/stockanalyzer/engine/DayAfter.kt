package com.shinobisondre.stockanalyzer.engine

import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str
import kotlin.math.roundToInt

/** What happened after one kind of big up-day (from 20 years of daily bars, see trainer). */
data class DayStat(
    val n: Int,
    /** Share of cases where the NEXT session closed above today's close. */
    val pUpNext: Double,
    val meanNextPct: Double,
    /** Share of cases that opened higher the next morning, and the average gap. */
    val pGapUp: Double,
    val meanGapPct: Double,
    val pUp5: Double,
    val mean5Pct: Double,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("n" to n, "pUpNext" to pUpNext, "meanNextPct" to meanNextPct, "pGapUp" to pGapUp,
        "meanGapPct" to meanGapPct, "pUp5" to pUp5, "mean5Pct" to mean5Pct)

    companion object {
        fun fromMap(m: Map<String, Any?>) = DayStat(m["n"].num()?.toInt() ?: 0, m["pUpNext"].num() ?: 0.5, m["meanNextPct"].num() ?: 0.0,
            m["pGapUp"].num() ?: 0.5, m["meanGapPct"].num() ?: 0.0, m["pUp5"].num() ?: 0.5, m["mean5Pct"].num() ?: 0.0)
    }
}

class DayAfterTable(val base: DayStat, val buckets: Map<String, DayStat>)

class DayAfterBook(val generatedAt: String, val dataTo: String, val us: DayAfterTable, val oslo: DayAfterTable?) {
    fun table(symbol: String): DayAfterTable = if (symbol.uppercase().endsWith(".OL") && oslo != null) oslo else us

    fun toJson(): String = MiniJson.pretty(linkedMapOf(
        "generatedAt" to generatedAt, "dataTo" to dataTo,
        "us" to tableMap(us), "oslo" to oslo?.let { tableMap(it) },
    ))

    private fun tableMap(t: DayAfterTable) = linkedMapOf("base" to t.base.toMap(), "buckets" to t.buckets.mapValues { it.value.toMap() })

    companion object {
        fun fromJson(text: String): DayAfterBook {
            val m = MiniJson.parse(text).obj()
            fun t(o: Map<String, Any?>) = DayAfterTable(DayStat.fromMap(o["base"].obj()), o["buckets"].obj().mapValues { DayStat.fromMap(it.value.obj()) })
            return DayAfterBook(m["generatedAt"].str() ?: "?", m["dataTo"].str() ?: "?", t(m["us"].obj()), m["oslo"]?.let { if (it is Map<*, *>) t(it.obj()) else null })
        }
    }
}

enum class EodLean(val title: String, val tone: Tone) {
    SELL_BY_CLOSE("Lean: take profit by the close", Tone.SELL),
    HOLD_OVERNIGHT("Lean: holding overnight has paid", Tone.BUY),
    NO_EDGE("No measurable edge — your call", Tone.NEUTRAL),
}

data class EodAdvice(val lean: EodLean, val stat: DayStat, val base: DayStat, val bucketLabel: String, val lines: List<String>)

/** The same answer in a few plain words: what to do, and one "because". */
data class PlainCall(val action: String, val because: String, val tone: Tone)

/**
 * "It's up a lot today — sell before the close or hold?" answered with what
 * actually happened after similar days, bucketed by:
 *   m = size of today's move (3–5 %, 5–8 %, 8–12 %, ≥ 12 %)
 *   v = volume vs the 20-day average (< 1.5×, 1.5–3×, ≥ 3×)
 *   c = where it sits in today's range (faded < 0.5, middle, near the high ≥ 0.8)
 *   t = above / below its 200-day average
 * The most specific bucket with enough history (n ≥ 300) is used.
 */
object DayAfter {
    const val MIN_MOVE_PCT = 3.0
    private const val MIN_N = 300

    fun moveBucket(pct: Double): Int? = when { pct < MIN_MOVE_PCT -> null; pct < 5 -> 0; pct < 8 -> 1; pct < 12 -> 2; else -> 3 }
    fun volBucket(rvol: Double?): Int = when { rvol == null -> 0; rvol < 1.5 -> 0; rvol < 3 -> 1; else -> 2 }
    fun clvBucket(clv: Double?): Int = when { clv == null -> 1; clv < 0.5 -> 0; clv < 0.8 -> 1; else -> 2 }

    fun key(m: Int?, v: Int?, c: Int?, t: Int?) = listOf(m, v, c, t).joinToString("|") { it?.toString() ?: "*" }

    /** All 16 wildcard keys a day belongs to (used by the trainer). */
    fun keysFor(m: Int, v: Int, c: Int, t: Int): List<String> {
        val out = ArrayList<String>(16)
        for (mask in 0 until 16) out += key(if (mask and 1 != 0) null else m, if (mask and 2 != 0) null else v, if (mask and 4 != 0) null else c, if (mask and 8 != 0) null else t)
        return out
    }

    private fun describe(m: Int, v: Int?, c: Int?, t: Int?): String {
        val parts = mutableListOf(listOf("+3–5%", "+5–8%", "+8–12%", "+12% or more")[m])
        v?.let { parts += listOf("normal volume", "1.5–3× volume", "3×+ volume")[it] }
        c?.let { parts += listOf("faded from the high", "mid-range", "near the day's high")[it] }
        t?.let { parts += if (it == 1) "above its 200-day" else "below its 200-day" }
        return parts.joinToString(", ")
    }

    fun lookup(table: DayAfterTable, movePct: Double, rvol: Double?, clv: Double?, above200: Boolean?): Pair<String, DayStat>? {
        val m = moveBucket(movePct) ?: return null
        val v = volBucket(rvol); val c = clvBucket(clv); val t = above200?.let { if (it) 1 else 0 }
        val chain = listOf(
            Triple(v, c, t), Triple(v, c, null), Triple(null, c, t), Triple(v, null, null), Triple(null, c, null), Triple(null, null, null),
        )
        for ((vv, cc, tt) in chain) {
            val st = table.buckets[key(m, vv, cc, tt)] ?: continue
            if (st.n >= MIN_N) return describe(m, vv, cc, tt) to st
        }
        return null
    }

    /**
     * The lean compares next-day behaviour after similar days with an ordinary day.
     * Thresholds are deliberately wide: the base rate for "next day higher" is
     * ~52%, and a lean needs both the odds and the average to point the same way.
     */
    fun advise(table: DayAfterTable, movePct: Double, rvol: Double?, clv: Double?, above200: Boolean?, costPct: Double): EodAdvice? {
        val (label, st) = lookup(table, movePct, rvol, clv, above200) ?: return null
        val b = table.base
        val lean = when {
            st.meanNextPct < -0.10 && st.pUpNext < b.pUpNext - 0.03 -> EodLean.SELL_BY_CLOSE
            st.meanNextPct > 0.20 && st.pUpNext > b.pUpNext + 0.02 -> EodLean.HOLD_OVERNIGHT
            else -> EodLean.NO_EDGE
        }
        val p = { x: Double -> "${(x * 100).roundToInt()}%" }
        val lines = mutableListOf(
            "After ${st.n.fmtThousands()} similar days ($label), the next session closed higher ${p(st.pUpNext)} of the time (ordinary day: ${p(b.pUpNext)}), average ${pct(st.meanNextPct, 2)}.",
            "Next morning it opened higher ${p(st.pGapUp)} of the time (average gap ${pct(st.meanGapPct, 2)}). Five sessions later it was higher ${p(st.pUp5)} of the time (average ${pct(st.mean5Pct, 1)}).",
        )
        when (lean) {
            EodLean.SELL_BY_CLOSE -> lines += "Days like this have tended to give some back — locking in today's gain has been the better bet on average."
            EodLean.HOLD_OVERNIGHT -> lines += "Days like this have tended to keep going for at least another session."
            EodLean.NO_EDGE -> lines += "Holding overnight vs selling at the close has been close to a coin flip after days like this."
        }
        if (st.meanNextPct < costPct) lines += "Chasing it now for a next-day exit: the average next-day move (${pct(st.meanNextPct, 2)}) is below a round trip's cost (~${pct(costPct, 1).removePrefix("+")})."
        return EodAdvice(lean, st, b, label, lines)
    }

    /**
     * Plain-English version for the Quick wins tab.
     * [owned]: you hold it (sell or keep?) vs. you don't (buy or not?).
     * [closeTime]: local closing time while the market is open ("22:00"), null when it's closed.
     */
    fun plain(a: EodAdvice?, owned: Boolean, costPct: Double, closeTime: String?): PlainCall {
        val sellWhen = if (closeTime != null) "before $closeTime today" else "at tomorrow's open"
        if (a == null) return PlainCall(if (owned) "Up to you" else "Don't chase it",
            "There aren't enough past jumps this big to say what usually happens next.", Tone.NEUTRAL)
        val up = (a.stat.pUpNext * 100).roundToInt(); val down = 100 - up
        val avg = a.stat.meanNextPct
        fun p(x: Double) = "%.1f".format(kotlin.math.abs(x))
        return when (a.lean) {
            EodLean.SELL_BY_CLOSE ->
                if (owned) PlainCall("Sell $sellWhen", "After jumps like this, the price was lower the next day $down times out of 100.", Tone.SELL)
                else PlainCall("Don't buy now", "After jumps like this, the price was lower the next day $down times out of 100.", Tone.SELL)
            EodLean.HOLD_OVERNIGHT ->
                if (owned) PlainCall("Keep it — don't sell today", "After jumps like this, the price kept rising the next day $up times out of 100.", Tone.BUY)
                else if (avg > costPct) PlainCall("Buy now, sell tomorrow", "After jumps like this, the price kept rising the next day $up times out of 100 (about +${p(avg)}% on average).", Tone.BUY)
                else PlainCall("Don't buy — gain too small", "It usually rises a bit more the next day (about +${p(avg)}%), but fees of about ${p(costPct)}% eat that up.", Tone.NEUTRAL)
            EodLean.NO_EDGE ->
                if (owned) PlainCall("Up to you — no clear pattern", "After jumps like this, the price was higher the next day $up times out of 100 — close to a coin flip. Selling now locks in today's gain.", Tone.NEUTRAL)
                else PlainCall("Don't chase it", "After jumps like this, the next day is close to a coin flip (up $up times out of 100), and fees make it a losing bet on average.", Tone.NEUTRAL)
        }
    }

    /** Close-location value: 0 = at the day's low, 1 = at the high. */
    fun clv(last: Double, low: Double, high: Double): Double? = if (high > low) ((last - low) / (high - low)).coerceIn(0.0, 1.0) else null
}

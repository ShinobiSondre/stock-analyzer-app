package com.shinobisondre.stockanalyzer.engine

import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.str

/** One executed trade from a broker contract note ("sluttseddel"). */
data class Trade(
    val noteNo: String,
    val date: String,          // trade date, yyyy-MM-dd
    val time: String,          // first fill, HH:mm:ss (exchange/Nordnet local time)
    val buy: Boolean,
    val code: String,          // Nordnet's short name, e.g. NVDA, NHY, VWS
    val isin: String,
    val name: String,
    val quantity: Double,
    val price: Double,         // average fill price in [currency]
    val currency: String,
    val market: String,
    val fx: Double?,           // currency → NOK on the note
    val amountNok: Double?,    // quantity × price in NOK
    val feeNok: Double,        // brokerage + fees
    val totalNok: Double?,     // paid (buy) / received (sell)
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("noteNo" to noteNo, "date" to date, "time" to time, "buy" to buy, "code" to code, "isin" to isin,
        "name" to name, "quantity" to quantity, "price" to price, "currency" to currency, "market" to market, "fx" to fx,
        "amountNok" to amountNok, "feeNok" to feeNok, "totalNok" to totalNok)

    companion object {
        fun fromMap(m: Map<String, Any?>) = Trade(m["noteNo"].str() ?: "", m["date"].str() ?: "", m["time"].str() ?: "", m["buy"] == true,
            m["code"].str() ?: "", m["isin"].str() ?: "", m["name"].str() ?: "", m["quantity"].num() ?: 0.0, m["price"].num() ?: 0.0,
            m["currency"].str() ?: "NOK", m["market"].str() ?: "", m["fx"].num(), m["amountNok"].num(), m["feeNok"].num() ?: 0.0, m["totalNok"].num())
    }
}

/** What the trades add up to for one security. */
data class LedgerPosition(
    val isin: String,
    val code: String,
    val name: String,
    val currency: String,
    val market: String,
    val quantity: Double,
    /** Average cost per share (Nordnet's GAV method) in the trading currency, and in NOK incl. fees. */
    val avgCost: Double?,
    val avgCostNok: Double?,
    val firstBuy: String?,
    val lastTrade: String,
    val realizedNok: Double,
    val trades: List<Trade>,
) {
    val open: Boolean get() = quantity > 1e-6
}

/**
 * Reads Nordnet's transaction export (a PDF of contract notes, as text) and turns it into
 * trades and positions. Works on text from pdftotext/PDFBox with or without column layout.
 */
object NordnetNotes {
    private val NUM = """\d{1,3}(?:[  ]\d{3})*,\d+|\d+,\d+"""

    /** "9 907,14" / "20,2600" → number. */
    fun nok(s: String): Double? = s.replace(" ", "").replace(" ", "").replace(',', '.').toDoubleOrNull()

    fun parse(text: String): List<Trade> {
        val notes = text.split(Regex("SLUTTSEDDEL")).drop(1)
        val out = mutableListOf<Trade>()
        val prevNos = Regex("""Nr\s+(\d{6,})""").findAll(text).map { it.range.first to it.groupValues[1] }.toList()
        var cursor = text.indexOf("SLUTTSEDDEL")
        for (note in notes) {
            // The note number is printed just above the title.
            val no = prevNos.lastOrNull { it.first < cursor }?.second ?: ""
            cursor = text.indexOf("SLUTTSEDDEL", cursor + 1).let { if (it < 0) text.length else it }
            parseNote(note, no)?.let { out += it }
        }
        return out.withIndex().distinctBy { (i, t) -> if (t.noteNo.isBlank()) "#$i" else t.noteNo + t.isin }.map { it.value }.sortedWith(compareBy({ it.date }, { it.time }))
    }

    private fun parseNote(n: String, noteNo: String): Trade? {
        val lines = n.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val date = Regex("""Handelsdag:\s*(\d{2})\.(\d{2})\.(\d{4})""").find(n)?.let { "${it.groupValues[3]}-${it.groupValues[2]}-${it.groupValues[1]}" } ?: return null
        val buy = when {
            Regex("har vi kjøpt").containsMatchIn(n) -> true
            Regex("har vi solgt").containsMatchIn(n) -> false
            else -> return null
        }
        val sec = lines.firstNotNullOfOrNull { Regex("""^(.+?)\s+\(ISIN\s+([A-Z]{2}[A-Z0-9]{9}\d)\)\s+-\s+(.+)$""").find(it) } ?: return null
        val code = sec.groupValues[1].trim(); val isin = sec.groupValues[2]; val name = sec.groupValues[3].trim()
        val fillRe = Regex("""^([\d.]+)\s+($NUM)\s+([A-Z]{3})\s+(.+?)\s+(\d{2}:\d{2}:\d{2})(?:\.\d+)?$""")
        val fills = lines.mapNotNull { fillRe.find(it) }
        if (fills.isEmpty()) return null
        var qty = 0.0; var cost = 0.0
        for (f in fills) { val q = f.groupValues[1].toDoubleOrNull() ?: continue; val p = nok(f.groupValues[2]) ?: continue; qty += q; cost += q * p }
        if (qty <= 0) return null
        val first = fills.first()
        val fx = Regex("""Valutakurs\s+[A-Z]{3}/NOK\s+($NUM)""").find(n)?.let { nok(it.groupValues[1]) }
        fun amount(label: String) = Regex("""$label\s*\(NOK\)\s+(-?(?:$NUM))""").find(n)?.let { nok(it.groupValues[1]) }
        val fee = (amount("Kurtasje") ?: 0.0) + (amount("Avgift") ?: 0.0)
        val total = amount("Å betale") ?: amount("Til gode")
        return Trade(noteNo, date, first.groupValues[5].let { if (it == "00:00:00") "" else it }, buy, code, isin, name, qty, cost / qty,
            first.groupValues[3], first.groupValues[4].trim(), fx, amount("Beløp"), fee, total)
    }

    /** Running average cost per security, like Nordnet's GAV: buys raise the average, sells keep it and book a gain/loss. */
    fun ledger(trades: List<Trade>): List<LedgerPosition> = trades.groupBy { it.isin }.map { (isin, ts) ->
        val sorted = ts.sortedWith(compareBy({ it.date }, { it.time }))
        var qty = 0.0; var costCcy = 0.0; var costNok = 0.0; var realized = 0.0; var firstBuy: String? = null
        for (t in sorted) {
            if (t.buy) {
                if (qty <= 1e-9) firstBuy = t.date
                qty += t.quantity
                costCcy += t.quantity * t.price
                costNok += t.totalNok ?: ((t.amountNok ?: 0.0) + t.feeNok)
            } else {
                val q = minOf(t.quantity, qty)
                if (qty > 1e-9 && q > 0) {
                    val avgNok = costNok / qty
                    realized += (t.totalNok ?: ((t.amountNok ?: 0.0) - t.feeNok)) * (q / t.quantity) - avgNok * q
                    costCcy -= costCcy / qty * q
                    costNok -= avgNok * q
                    qty -= q
                }
                if (qty <= 1e-9) { qty = 0.0; costCcy = 0.0; costNok = 0.0 }
            }
        }
        val last = sorted.last()
        LedgerPosition(isin, last.code, last.name, last.currency, last.market, qty,
            if (qty > 1e-9) costCcy / qty else null, if (qty > 1e-9) costNok / qty else null, firstBuy, last.date, realized, sorted)
    }.sortedByDescending { it.lastTrade }

    /** Best guess at the Yahoo Finance ticker from Nordnet's code and market. */
    fun yahooTicker(p: LedgerPosition): String? {
        val m = p.market.uppercase()
        val code = p.code.replace(' ', '-')
        return when {
            p.isin.startsWith("CA") && m.contains("US EXCHANGE") && code == "T" -> "TU" // Telus trades as TU in New York
            m.contains("US EXCHANGE") || m.contains("NASDAQ") || m.contains("NYSE") -> code
            m.contains("OSLO") || m.contains("EURONEXT") -> "$code.OL"
            m.contains("COPENHAGEN") -> "$code.CO"
            m.contains("STOCKHOLM") -> "$code.ST"
            m.contains("HELSINKI") -> "$code.HE"
            m.contains("XETRA") -> "$code.DE"
            else -> null // funds: matched by name/ISIN in the app
        }
    }
}

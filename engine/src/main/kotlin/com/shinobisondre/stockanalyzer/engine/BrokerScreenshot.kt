package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.roundToLong

/** One line of OCR text with its bounding box (pixels). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx: Double get() = (left + right) / 2.0
    val cy: Double get() = (top + bottom) / 2.0
    val h: Int get() = (bottom - top).coerceAtLeast(1)
}

/** A holding read off a broker screenshot. Every number is optional — the app fills gaps and the user reviews. */
data class ParsedHolding(
    val name: String,
    val quantity: Double?,
    val avgCost: Double?,
    val avgCostCurrency: String?,
    val marketValue: Double?,
    val valueCurrency: String?,
    val lastPrice: Double?,
    val lastCurrency: String?,
    val returnPct: Double?,
    val evidence: String,
) {
    /**
     * Shares, read directly or derived from value ÷ (latest price × FX). Nordnet's
     * holdings list shows only Value (kr) and Latest (instrument currency), so the
     * count is usually derived. [fxLastToValue] converts 1 unit of the price
     * currency into the value currency (1.0 when they are the same).
     */
    fun shares(fxLastToValue: Double?): Double? {
        quantity?.let { return it }
        val v = marketValue ?: return null
        val p = lastPrice ?: return null
        val fx = fxLastToValue ?: return null
        if (p <= 0 || fx <= 0) return null
        val q = v / (p * fx)
        val r = q.roundToLong().toDouble()
        // Values are rounded to whole kroner on screen, so allow ~1.5% slack before calling it fractional.
        return if (r >= 1 && abs(q - r) / r < 0.015) r else (q * 100).roundToLong() / 100.0
    }

    /** Average cost per share in the price currency. Return % includes FX, so this reproduces the broker's own P/L at today's rate. */
    fun impliedAvgCost(): Double? {
        avgCost?.let { return it }
        val p = lastPrice ?: return null
        val r = returnPct ?: return null
        if (r <= -99.0) return null
        return p / (1 + r / 100)
    }
}

/**
 * Turns OCR lines from a Nordnet (or similar broker) portfolio screenshot into
 * holdings. Handles three layouts:
 *  • a table with a header row (Navn · Antall · GAV · Siste · Markedsverdi …),
 *  • the app's stacked rows (name on one line, numbers under / beside it,
 *    optionally "10 stk" or small labels above the values),
 *  • labels inline with values ("Antall 10", "GAV 123,45").
 * Numbers in Norwegian/Swedish/Danish format ("1 234,56", "−3,2 %") and English
 * format ("1,234.56") are both understood.
 */
object BrokerScreenshot {

    enum class Field { NAME, QTY, AVG, LAST, VALUE, RETURN, TODAY, WEIGHT, OTHER }

    private val LABELS: List<Pair<Regex, Field>> = listOf(
        Regex("^(antall|antal|ant\\.?|beholdning|stk|st\\.?|quantity|qty|shares|units|kpl|määrä)$") to Field.QTY,
        Regex("^(gav|snittkurs|snittpris|anskaffelseskurs|anskaffelsespris|kjøpskurs|kjøpspris|inköpskurs|inkøbskurs|købskurs|anskaffelsesværdi|avg\\.? ?price|average price|avg cost|average cost|cost basis|keskihinta)$") to Field.AVG,
        Regex("^(siste|kurs|senast|seneste|last|latest|last price|latest price|price|viimeisin|nåværende kurs|siste kurs)$") to Field.LAST,
        Regex("^(markedsverdi|verdi|marknadsvärde|värde|markedsværdi|værdi|value|market value|markkina-arvo|arvo)$") to Field.VALUE,
        Regex("^(avkastning|utveckling|afkast|return|tuotto|avk\\.?|avkastning %|avkastning kr)$") to Field.RETURN,
        Regex("^(i dag|idag|i dag %|today|i dag kr|idag %)$") to Field.TODAY,
        Regex("^(vekting|andel|weight|allocation|vekt|fordeling)$") to Field.WEIGHT,
        Regex("^(navn|namn|navn/ticker|name|instrument|verdipapir|värdepapper|værdipapir|aksje|aktie)$") to Field.NAME,
    )

    /** UI words that are never a holding's name. */
    private val NOISE = setOf(
        "portefølje", "portfolio", "portfölj", "portefølje", "beholdning", "beholdninger", "innehav", "oversikt", "overview", "totalt", "total",
        "sum", "kontanter", "saldo", "kjøpekraft", "köpkraft", "børshandlede", "börshandlade", "fond", "fonder", "aksjer", "aktier", "konto",
        "aksjesparekonto", "investeringskonto", "ask", "logg inn", "utvikling", "ordre", "ordrer", "transaksjoner", "hjem", "søk", "marked",
        "mer", "sorter", "filtrer", "vis", "alle", "nok", "usd", "sek", "eur", "dkk", "gbp", "kr", "stk", "markedsverdi", "avkastning",
        "i dag", "idag", "siste", "gav", "antall", "verdi", "vekting", "andel", "kurs", "handle", "kjøp", "selg", "watchlist", "favoritter",
        "nordnet", "aksje- og fondskonto", "depot", "verdipapirer", "egne lister", "nyheter", "varsler", "avkastning i dag", "total avkastning",
        "urealisert", "realisert", "endring", "dagens endring", "positions", "holdings", "equities", "shares", "cash", "buying power",
        "details", "transactions", "stocks", "funds", "etfs", "certificates", "bonds", "sort", "overview", "watchlists", "discover",
        "feed", "market", "detaljer", "transaksjoner", "sorter", "oversikt", "aksjelister", "oppdag", "fonder", "aktier", "innehav",
    )
    /** Account names and similar headings ("Investeringskonto Zero", "Aksjesparekonto"). */
    private val NOISE_PARTS = listOf("konto", "account", "depot", "pension", "pensjon", "sparing")

    private val CURRENCIES = setOf("NOK", "SEK", "DKK", "EUR", "USD", "GBP", "CHF", "CAD", "JPY", "KR")

    data class Num(val value: Double, val percent: Boolean, val currency: String?, val cx: Double, val cy: Double, val qtyMark: Boolean, var label: Field? = null)

    // A number: optional sign, digits with optional thousands groups, optional decimals.
    private val NUM = Regex("""(?<![\p{L}\d])([+\-−–]?\s?\d{1,3}(?:[    .,']\d{3})+(?:[.,]\d+)?|[+\-−–]?\s?\d+(?:[.,]\d+)?)(\s?%)?""")

    /** Parses a number in Nordic or English format. "1 234,56" → 1234.56, "1,234.56" → 1234.56, "12,5" → 12.5. */
    fun parseNumber(raw: String, decimalMark: Char = ','): Double? {
        var s = raw.trim().replace('−', '-').replace('–', '-').replace(" ", " ").replace(" ", " ").replace(" ", " ").replace("'", " ")
        val neg = s.startsWith("-")
        s = s.trimStart('+', '-').trim().replace(" ", "")
        if (s.isEmpty()) return null
        val lastComma = s.lastIndexOf(','); val lastDot = s.lastIndexOf('.')
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 -> if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            // A single comma is the decimal mark in Nordic format and a thousands separator in English format.
            lastComma >= 0 -> if (decimalMark == '.' || s.count { it == ',' } > 1) s.replace(",", "") else s.replace(',', '.')
            lastDot >= 0 -> if (s.count { it == '.' } > 1 || (decimalMark == ',' && s.length - lastDot - 1 == 3)) s.replace(".", "") else s
            else -> s
        }
        val v = normalized.toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    private fun labelOf(text: String): Field? {
        val t = text.trim().lowercase().trimEnd(':').replace(Regex("\\s+"), " ")
        return LABELS.firstOrNull { it.first.matches(t) }?.second
    }

    private fun letters(s: String) = s.count { it.isLetter() }

    private fun isNameLike(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3 || letters(t) < 3) return false
        val low = t.lowercase().trimEnd(':', '.')
        if (low in NOISE || labelOf(t) != null) return false
        if (NOISE_PARTS.any { low.contains(it) }) return false
        if (CURRENCIES.contains(t.uppercase())) return false
        // Mostly letters (allow "Class A", "(The)", "ASA", "& Co", digits in names like "3M").
        val digits = t.count { it.isDigit() }
        if (digits > letters(t)) return false
        if (Regex("^[+\\-−]?\\d").containsMatchIn(t) && letters(t) < 4) return false
        if (t.contains('%')) return false
        if (Regex("\\b(kl\\.|\\d{1,2}:\\d{2})").containsMatchIn(low)) return false // timestamps
        return true
    }

    /** Splits a line into a leading name part and the numbers after it. */
    private fun split(line: OcrLine, decimalMark: Char): Pair<String?, List<Num>> {
        val text = line.text
        val nums = mutableListOf<Num>()
        val upper = text.uppercase()
        val currency = CURRENCIES.firstOrNull { Regex("\\b$it\\b").containsMatchIn(upper) }?.let { if (it == "KR") "NOK" else it }
        val qtyWord = Regex("^\\s*(stk|st|pcs|shares|aksjer|aktier|units)\\b", RegexOption.IGNORE_CASE)
        var firstNumAt = -1
        for (m in NUM.findAll(text)) {
            val v = parseNumber(m.groupValues[1], decimalMark) ?: continue
            if (firstNumAt < 0) firstNumAt = m.range.first
            val frac = if (text.isNotEmpty()) (m.range.first + m.range.last) / 2.0 / text.length else 0.5
            val qtyMark = qtyWord.containsMatchIn(text.substring((m.range.last + 1).coerceAtMost(text.length)))
            nums += Num(v, m.groupValues[2].isNotBlank(), currency, line.left + frac * (line.right - line.left), line.cy, qtyMark)
        }
        val head = if (firstNumAt > 0) text.substring(0, firstNumAt).trim() else if (firstNumAt < 0) text.trim() else ""
        val name = head.takeIf { isNameLike(it) }
        return name to nums
    }

    fun parse(lines: List<OcrLine>): List<ParsedHolding> {
        val clean = lines.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.top }, { it.left }))
        if (clean.isEmpty()) return emptyList()
        val width = clean.maxOf { it.right }.coerceAtLeast(1)
        val medH = clean.map { it.h }.sorted()[clean.size / 2].toDouble()
        val decimalMark = decimalMarkOf(clean.map { it.text })

        // 1) Header rows: ≥2 column labels at the same height.
        val labelLines = clean.mapNotNull { l -> labelOf(l.text)?.let { l to it } }
        val headers = labelLines.groupBy { (l, _) -> (l.cy / (medH * 0.8)).toInt() }.values.filter { g -> g.map { it.second }.distinct().size >= 2 }
            .map { g -> g.first().first.cy to g.map { (l, f) -> f to l.cx } }
            .sortedBy { it.first }
        val headerLineSet = headers.flatMap { (cy, _) -> labelLines.filter { abs(it.first.cy - cy) < medH * 0.8 }.map { it.first } }.toSet()

        // 2) Name lines (left half of the screen, or a name prefix of a mixed line).
        data class Row(val name: String, val line: OcrLine, val inline: List<Num>)
        val rows = mutableListOf<Row>()
        val otherNums = mutableListOf<Pair<OcrLine, List<Num>>>()
        for (l in clean) {
            if (l in headerLineSet) continue
            val (name, nums) = split(l, decimalMark)
            if (name != null && l.left < width * 0.62) rows += Row(name, l, nums) else if (nums.isNotEmpty()) otherNums += l to nums
        }
        // Two name candidates on the same row (e.g. text inside a logo next to the name): keep the bigger text.
        val merged = mutableListOf<Row>()
        for (r in rows.sortedBy { it.line.cy }) {
            val prev = merged.lastOrNull()
            if (prev != null && abs(prev.line.cy - r.line.cy) < 0.6 * maxOf(prev.line.h, r.line.h)) {
                val keepNew = r.line.h * letters(r.name) > prev.line.h * letters(prev.name)
                if (keepNew) merged[merged.size - 1] = r.copy(inline = r.inline + prev.inline) else merged[merged.size - 1] = prev.copy(inline = prev.inline + r.inline)
            } else merged += r
        }
        rows.clear(); rows += merged
        if (rows.isEmpty()) return emptyList()

        // Stacked "label above value" pairs: a label line directly above a number at a similar x.
        val stackedLabels = labelLines.filter { it.first !in headerLineSet }

        // 3) Assign numbers to the row whose band they fall in.
        val out = mutableListOf<ParsedHolding>()
        for ((idx, row) in rows.withIndex()) {
            val top = row.line.top - medH * 0.6
            val bottom = if (idx + 1 < rows.size) rows[idx + 1].line.top - medH * 0.4 else row.line.bottom + medH * 3.5
            val nums = (row.inline + otherNums.filter { (l, _) -> l.cy in top..bottom }.flatMap { it.second }).toMutableList()
            if (nums.isEmpty()) continue
            // Labels: from the nearest header row above each number, then inline/stacked labels.
            for (n in nums) {
                if (n.qtyMark && !n.percent) n.label = Field.QTY
                val header = headers.lastOrNull { it.first < n.cy }?.second
                if (n.label == null && header != null) {
                    val nearest = header.minByOrNull { abs(it.second - n.cx) }
                    if (nearest != null && abs(nearest.second - n.cx) < width * 0.12) n.label = nearest.first
                }
                if (n.label == null) {
                    val above = stackedLabels.filter { (l, _) -> l.cy < n.cy && n.cy - l.cy < medH * 2.2 && abs(l.cx - n.cx) < width * 0.12 && l.cy >= top }
                        .maxByOrNull { it.first.cy }
                    if (above != null) n.label = above.second
                }
            }
            // Inline labels inside the same OCR line ("Antall 10", "GAV 123,45 USD").
            val bandLines = (listOf(row.line) + otherNums.filter { (l, _) -> l.cy in top..bottom }.map { it.first }) +
                clean.filter { it.cy in top..bottom && it != row.line }
            for (l in bandLines) {
                val low = l.text.lowercase()
                for ((re, f) in LABELS) {
                    val word = Regex("(?i)\\b(" + re.pattern.removePrefix("^(").removeSuffix(")$") + ")\\b[:\\s]*([+\\-−]?[\\d][\\d \\u00A0.,]*)")
                    word.find(low)?.let { m ->
                        val v = parseNumber(m.groupValues.last().trim(), decimalMark)
                        if (v != null) nums.firstOrNull { abs(it.value - v) < 1e-9 && it.label == null }?.label = f
                    }
                }
            }

            fun pick(f: Field) = nums.firstOrNull { it.label == f && !it.percent }
            var qty = pick(Field.QTY)?.value
            val avg = pick(Field.AVG)
            val last = pick(Field.LAST)
            var value = pick(Field.VALUE)
            val ret = nums.firstOrNull { it.label == Field.RETURN && it.percent }?.value ?: nums.firstOrNull { it.percent && it.label == null }?.value

            // Algebra: shares × price ≈ value among unlabeled numbers.
            val plain = nums.filter { !it.percent && it.label != Field.WEIGHT && it.label != Field.TODAY && it.label != Field.RETURN && it.value > 0 }
            if (qty == null || value == null) {
                var best: Triple<Double, Double, Double>? = null; var bestErr = 0.03
                for (a in plain) for (b in plain) for (c in plain) {
                    if (a === b || a === c || b === c) continue
                    if (c.value < a.value || c.value < b.value) continue
                    val err = abs(a.value * b.value - c.value) / c.value
                    val aInt = abs(a.value - a.value.roundToLong()) < 1e-6
                    if (err < bestErr && aInt) { bestErr = err; best = Triple(a.value, b.value, c.value) }
                }
                best?.let { (q, _, v) -> if (qty == null) qty = q; if (value == null) value = plain.first { it.value == v } }
            }
            if (value == null) value = plain.filter { it.label == null && it.currency != null }.maxByOrNull { it.value }
            // Shares from value ÷ price are derived later by ParsedHolding.shares(fx), which knows the exchange rate.
            out += ParsedHolding(
                name = cleanName(row.name),
                quantity = qty,
                avgCost = avg?.value, avgCostCurrency = avg?.currency,
                marketValue = value?.value, valueCurrency = value?.currency,
                lastPrice = last?.value, lastCurrency = last?.currency, returnPct = ret,
                evidence = nums.joinToString(" · ") { (it.label?.name?.lowercase()?.let { l -> "$l " } ?: "") + fmt(it.value) + (if (it.percent) "%" else "") + (it.currency?.let { c -> " $c" } ?: "") },
            )
        }
        return out.distinctBy { it.name.lowercase() }
    }

    /**
     * Decides whether this screenshot writes decimals with "." (English UI: "295.77", "28,293 kr")
     * or "," (Norwegian UI: "295,77", "28 293 kr") from numbers that can only be read one way.
     */
    fun decimalMarkOf(texts: List<String>): Char {
        var dot = 0; var comma = 0
        val dotDec = Regex("""\d\.\d{1,2}(?!\d)"""); val commaDec = Regex("""\d,\d{1,2}(?!\d)""")
        val dotGroup = Regex("""\d\.\d{3},\d"""); val commaGroup = Regex("""\d,\d{3}\.\d""")
        for (t in texts) {
            dot += dotDec.findAll(t).count() + commaGroup.findAll(t).count()
            comma += commaDec.findAll(t).count() + dotGroup.findAll(t).count()
        }
        return if (dot > comma) '.' else ','
    }

    /** Strips share-class noise for searching ("Airbnb, Inc. - Class A" → "Airbnb Class A"). */
    fun cleanName(name: String): String = name
        .replace(Regex("\\(The\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[,.]?\\s+-\\s+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim().trimEnd(',', '.', '-')

    /** Query variants for a ticker search, most specific first. */
    fun searchQueries(name: String): List<String> {
        val base = cleanName(name)
        val noSuffix = base.replace(Regex("(?i)\\b(inc|incorporated|corp|corporation|co|company|ltd|limited|plc|asa|ab|a/s|as|sa|nv|se|ag|holdings?|group)\\b\\.?,?"), "")
            .replace(Regex("(?i)\\b(class|cl\\.?|ser\\.?|series)\\s+[a-c]\\b"), "")
            .replace(Regex("(?i)\\b(american depositary shares|ads|adr|sponsored)\\b"), "")
            .replace(Regex("\\s{2,}"), " ").trim().trimEnd(',', '.', '-')
        val firstTwo = noSuffix.split(' ').take(2).joinToString(" ")
        return listOf(base, noSuffix, firstTwo).map { it.trim() }.filter { it.length >= 2 }.distinct()
    }
}

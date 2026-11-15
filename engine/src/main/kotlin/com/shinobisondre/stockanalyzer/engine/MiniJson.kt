package com.shinobisondre.stockanalyzer.engine

/**
 * Tiny dependency-free JSON reader/writer so the engine (and the trainer)
 * stay plain Kotlin. Objects → LinkedHashMap, arrays → List, numbers → Double.
 */
object MiniJson {

    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); require(i == s.length) { "Trailing data at $i" }; v }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $i")
            }
        }
        fun expect(w: String) { require(s.startsWith(w, i)) { "Expected $w at $i" }; i += w.length }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                require(s[i] == ':') { "Expected : at $i" }; i++
                m[k] = value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> error("Expected , or } at $i") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> error("Expected , or ] at $i") }
            }
        }
        fun str(): String {
            require(s[i] == '"') { "Expected string at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> error("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(st, i).toDouble()
        }
    }

    fun write(v: Any?): String = StringBuilder().also { w(it, v, 0, false) }.toString()
    fun pretty(v: Any?): String = StringBuilder().also { w(it, v, 0, true) }.toString()

    private fun w(sb: StringBuilder, v: Any?, ind: Int, pretty: Boolean) {
        fun nl(n: Int) { if (pretty) { sb.append('\n'); repeat(n) { sb.append("  ") } } }
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong())
                else sb.append(d)
            }
            is String -> { sb.append('"'); esc(sb, v); sb.append('"') }
            is DoubleArray -> w(sb, v.toList(), ind, pretty)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    nl(ind + 1)
                    sb.append('"'); esc(sb, k.toString()); sb.append("\":")
                    if (pretty) sb.append(' ')
                    w(sb, x, ind + 1, pretty)
                }
                if (!first) nl(ind)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                val flat = pretty && v.all { it == null || it is Number || it is Boolean }
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (!flat) nl(ind + 1)
                    w(sb, x, ind + 1, pretty && !flat)
                }
                if (!first && !flat) nl(ind)
                sb.append(']')
            }
            else -> { sb.append('"'); esc(sb, v.toString()); sb.append('"') }
        }
    }

    private fun esc(sb: StringBuilder, s: String) {
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }

    // ── Typed accessors ──
    @Suppress("UNCHECKED_CAST")
    fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    fun Any?.list(): List<Any?> = this as? List<Any?> ?: emptyList()
    fun Any?.num(): Double? = (this as? Number)?.toDouble()
    fun Any?.str(): String? = this as? String
    fun Any?.doubles(): DoubleArray = list().map { (it as? Number)?.toDouble() ?: Double.NaN }.toDoubleArray()
}

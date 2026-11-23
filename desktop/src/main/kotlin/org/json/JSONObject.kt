package org.json

/**
 * Minimal org.json replacement (the desktop JDK has none): objects of strings, numbers, booleans and nested objects,
 * which is all the save file uses.
 */
class JSONException(msg: String) : Exception(msg)

class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(text: String) : this() {
        val p = Parser(text); val v = p.value()
        p.end()
        if (v !is Map<*, *>) throw JSONException("not an object")
        @Suppress("UNCHECKED_CAST") map.putAll(v as Map<String, Any?>)
    }

    fun put(k: String, v: Any?): JSONObject { map[k] = v; return this }
    fun optInt(k: String, def: Int): Int = (map[k] as? Number)?.toInt() ?: def
    fun optLong(k: String, def: Long): Long = (map[k] as? Number)?.toLong() ?: def
    fun optDouble(k: String, def: Double): Double = (map[k] as? Number)?.toDouble() ?: def
    fun optBoolean(k: String, def: Boolean): Boolean = (map[k] as? Boolean) ?: def
    fun optString(k: String, def: String): String = (map[k] as? String) ?: def
    fun optJSONObject(k: String): JSONObject? = when (val v = map[k]) {
        is JSONObject -> v
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (a, b) -> o.map[a as String] = b } }
        else -> null
    }

    override fun toString(): String = buildString { write(this, map) }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is JSONObject -> write(sb, v.map)
            is Map<*, *> -> { sb.append('{'); var first = true; for ((k, x) in v) { if (!first) sb.append(','); first = false; str(sb, k.toString()); sb.append(':'); write(sb, x) }; sb.append('}') }
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Float, is Double -> { val d = (v as Number).toDouble(); sb.append(if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()) }
            is Number -> sb.append(v.toString())
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\"); c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun end() { ws(); if (i != s.length) throw JSONException("trailing data") }
        fun value(): Any? {
            ws(); if (i >= s.length) throw JSONException("eof")
            return when (val c = s[i]) {
                '{' -> obj()
                '"' -> string()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw JSONException("bad char $c")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw JSONException("bad literal"); i += w.length; return v }
        fun number(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw JSONException("bad number")
        }
        fun string(): String {
            i++; val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JSONException("unterminated string")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = string(); ws()
                if (s[i++] != ':') throw JSONException("expected :")
                m[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw JSONException("expected , or }") }
            }
        }
    }
}

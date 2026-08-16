package cn.enaium.mahonoto

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A small JavaScript-expression evaluator covering the subset used by
 * 24层魔塔's event conditions / values:
 *   - namespace variables: status:xxx, item:xxx, flag:xxx, global:xxx,
 *     switch:xxx, temp:xxx, buff:xxx, enemy:id.field, blockId:x,y
 *   - arithmetic + - * / %, comparisons, && || !, parens, unary minus
 *   - string literals with single/double quotes, + string concat
 *   - core.* helper calls used by the game data
 */
class Expr(private val game: Game) {

    sealed class V {
        object VNull : V()
        data class VNum(val v: Double) : V()
        data class VStr(val v: String) : V()
        data class VBool(val v: Boolean) : V()
    }

    private val ops = setOf("&&", "||", "==", "!=", ">=", "<=", ">", "<", "+", "-", "*", "/", "%", "(", ")", "!", ",")

    /** Evaluates an expression string to a JS-ish value. */
    fun eval(src: String): V {
        val toks = tokenize(src)
        val p = P(toks)
        p.skipWs()
        return p.expr(0)
    }

    fun evalBool(src: String): Boolean = truthy(eval(src))
    fun evalNum(src: String): Double = num(eval(src))
    fun evalStr(src: String): String = str(eval(src))

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace()) { i++; continue }
            if (c == '"' || c == '\'') {
                val quote = c
                val sb = StringBuilder()
                i++
                while (i < s.length && s[i] != quote) {
                    if (s[i] == '\\' && i + 1 < s.length) {
                        sb.append(s[i + 1]); i += 2
                    } else { sb.append(s[i]); i++ }
                }
                i++
                out.add("\"" + sb.toString() + "\"")
                continue
            }
            if (c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit())) {
                var j = i
                while (j < s.length && (s[j].isDigit() || s[j] == '.' || s[j] == 'e' || s[j] == 'E' ||
                            ((s[j] == '+' || s[j] == '-') && j > i && (s[j - 1] == 'e' || s[j - 1] == 'E')))) j++
                out.add(s.substring(i, j))
                i = j
                continue
            }
            var matched = false
            for (op in ops.sortedByDescending { it.length }) {
                if (s.startsWith(op, i)) {
                    if (op == "-") {
                        val prev = out.lastOrNull()
                        val isBinary = prev != null &&
                            (prev[0].isDigit() || prev[0] == '.' || prev[0] == ')' || prev.startsWith("\""))
                        out.add(if (isBinary) "-" else "~")
                        i += 1; matched = true; break
                    }
                    out.add(op); i += op.length; matched = true; break
                }
            }
            if (matched) continue
            // identifier / namespace token
            var j = i
            while (j < s.length) {
                val ch = s[j]
                if (ch.isLetterOrDigit() || ch == ':' || ch == '：' || ch == '_' || ch == '.' || ch == '$' ||
                    ch == '[' || ch == ']' || ch == '\'' || ch == '，' || ch.code > 127) j++
                else break
            }
            if (j == i) throw IllegalStateException("unexpected char '$c' in expr: $s")
            out.add(s.substring(i, j))
            i = j
        }
        return out
    }

    private fun truthy(v: V): Boolean = when (v) {
        is V.VNull -> false
        is V.VBool -> v.v
        is V.VNum -> v.v != 0.0
        is V.VStr -> v.v.isNotEmpty() && v.v != "0" && v.v.lowercase() != "false" && v.v.lowercase() != "null"
    }

    private fun num(v: V): Double = when (v) {
        is V.VNum -> v.v
        is V.VBool -> if (v.v) 1.0 else 0.0
        is V.VStr -> v.v.toDoubleOrNull() ?: 0.0
        is V.VNull -> 0.0
    }

    private fun str(v: V): String = when (v) {
        is V.VStr -> v.v
        is V.VNum -> if (v.v == kotlin.math.floor(v.v)) v.v.toLong().toString() else v.v.toString()
        is V.VBool -> if (v.v) "true" else "false"
        is V.VNull -> ""
    }

    private inner class P(val toks: List<String>) {
        var i = 0
        fun skipWs() {}

        fun peek(): String? = toks.getOrNull(i)

        fun expr(minPrec: Int): V {
            var lhs = unary()
            while (true) {
                val op = peek() ?: break
                val prec = when (op) {
                    "||" -> 1
                    "&&" -> 2
                    "==", "!=", ">", "<", ">=", "<=" -> 3
                    "+", "-" -> 4
                    "*", "/", "%" -> 5
                    else -> -1
                }
                if (prec < 0 || prec < minPrec) break
                i++
                val rhs = expr(prec + 1)
                lhs = apply(op, lhs, rhs)
            }
            return lhs
        }

        fun unary(): V {
            val t = peek() ?: return V.VNull
            when (t) {
                "!" -> { i++; return V.VBool(!truthy(unary())) }
                "~" -> { i++; return V.VNum(-num(unary())) }
                "(" -> { i++; val v = expr(0); if (peek() == ")") i++; return v }
            }
            if (t.startsWith("\"")) { i++; return V.VStr(t.substring(1, t.length - 1)) }
            return atom()
        }

        fun apply(op: String, l: V, r: V): V {
            return when (op) {
                "||" -> V.VBool(truthy(l) || truthy(r))
                "&&" -> V.VBool(truthy(l) && truthy(r))
                "==" -> V.VBool(equalsJs(l, r))
                "!=" -> V.VBool(!equalsJs(l, r))
                ">" -> V.VBool(num(l) > num(r))
                "<" -> V.VBool(num(l) < num(r))
                ">=" -> V.VBool(num(l) >= num(r))
                "<=" -> V.VBool(num(l) <= num(r))
                "+" -> if (l is V.VStr || r is V.VStr) V.VStr(str(l) + str(r)) else V.VNum(num(l) + num(r))
                "-" -> V.VNum(num(l) - num(r))
                "*" -> V.VNum(num(l) * num(r))
                "/" -> V.VNum(num(l) / num(r))
                "%" -> V.VNum(num(l) % num(r))
                else -> V.VNull
            }
        }

        private fun equalsJs(l: V, r: V): Boolean = when {
            l is V.VNum && r is V.VNum -> l.v == r.v
            l is V.VStr && r is V.VStr -> l.v == r.v
            l is V.VBool || r is V.VBool -> truthy(l) == truthy(r)
            l is V.VNull || r is V.VNull -> l is V.VNull && r is V.VNull
            else -> num(l) == num(r)
        }

        fun atom(): V {
            val t = peek() ?: return V.VNull
            i++
            // function call: core.xxx(...)
            if (t.startsWith("core.") && peek() == "(") {
                i++ // (
                val args = ArrayList<V>()
                while (true) {
                    val p = peek()
                    if (p == null || p == ")") break
                    args.add(expr(0))
                    if (peek() == ",") i++ else break
                }
                if (peek() == ")") i++
                return call(t.substring(5), args)
            }
            // core.status.hero.xxx property reads
            if (t.startsWith("core.status.hero.")) {
                val prop = t.substring("core.status.hero.".length)
                return when (prop) {
                    "isBattling" -> V.VBool(game.battle != null)
                    "hp" -> V.VNum(game.getStatus("hp"))
                    "atk" -> V.VNum(game.getStatus("atk"))
                    "def" -> V.VNum(game.getStatus("def"))
                    "lv" -> V.VNum(game.getStatus("lv"))
                    "money" -> V.VNum(game.getStatus("money"))
                    "exp" -> V.VNum(game.getStatus("exp"))
                    else -> V.VNull
                }
            }
            // "main.xxx" references (levelChoose length etc.)
            if (t.startsWith("main.")) {
                if (peek() == "(") i++
                return when (t) {
                    "main.levelChoose" -> V.VNull
                    else -> V.VNull
                }
            }
            return when (t) {
                "true" -> V.VBool(true)
                "false" -> V.VBool(false)
                "null" -> V.VNull
                else -> t.toDoubleOrNull()?.let { V.VNum(it) } ?: variable(t)
            }
        }

        fun variable(t: String): V {
            for (ns in listOf("status", "item", "flag", "global", "switch", "temp", "buff", "enemy", "blockId", "blockNumber", "blockCls")) {
                if (t == ns || t.startsWith("$ns:") || t.startsWith("$ns：")) {
                    val rest = t.substringAfter(':', t.substringAfter('：', "")).trim()
                    if (t == ns || rest.isEmpty()) return V.VNull
                    return when (ns) {
                        "status" -> V.VNum(game.getStatus(rest))
                        "item" -> V.VNum(game.itemCount(rest).toDouble())
                        "flag" -> flagValue(rest)
                        "global" -> V.VNum(game.getGlobal(rest))
                        "switch" -> flagValue(game.eventPrefix + "@" + rest)
                        "temp" -> flagValue("@temp@" + rest)
                        "buff" -> V.VNum(game.getBuff(rest))
                        "enemy" -> {
                            val field = rest.substringAfterLast('.')
                            val id = rest.substringBeforeLast('.')
                            V.VNum(game.getEnemyField(id, field))
                        }
                        "blockId" -> {
                            val xy = rest.split(",")
                            if (xy.size == 2) V.VNum(if (game.getBlockId(xy[0].trim().toInt(), xy[1].trim().toInt()) != null) 1.0 else 0.0)
                            else V.VNull
                        }
                        "blockNumber" -> V.VNull
                        "blockCls" -> V.VNull
                        else -> V.VNull
                    }
                }
            }
            return V.VNum(0.0)
        }

        fun flagValue(name: String): V {
            val v = game.getFlag(name) ?: return V.VNull
            return when (v) {
                is JsonPrimitive -> when {
                    v.isString -> V.VStr(v.content)
                    v.content == "true" -> V.VBool(true)
                    v.content == "false" -> V.VBool(false)
                    else -> v.content.toDoubleOrNull()?.let { V.VNum(it) } ?: V.VNull
                }
                else -> V.VNull
            }
        }

        fun call(name: String, args: List<V>): V {
            fun arg(i: Int): Double = if (i < args.size) num(args[i]) else 0.0
            fun argStr(i: Int): String = if (i < args.size) str(args[i]) else ""
            return when (name) {
                "getFlag", "getGlobal" -> flagValue(argStr(0))
                "hasFlag" -> V.VBool(game.getFlag(argStr(0)) != null)
                "getStatus" -> V.VNum(game.getStatus(argStr(0)))
                "itemCount" -> V.VNum(game.itemCount(argStr(0)).toDouble())
                "hasItem" -> V.VBool(game.itemCount(argStr(0)) > 0)
                "hasEquip" -> V.VBool(game.hasEquip(argStr(0)))
                "getBlock" -> V.VBool(game.getBlock(arg(0).toInt(), arg(1).toInt(), argStr(2).ifEmpty { null }) != null)
                "getBlockCls" -> {
                    val b = game.getBlock(arg(0).toInt(), arg(1).toInt())
                    V.VStr(b?.def?.cls ?: "")
                }
                "getBlockId" -> {
                    val b = game.getBlock(arg(0).toInt(), arg(1).toInt())
                    V.VStr(b?.def?.id ?: "none")
                }
                "canUseQuickShop" -> {
                    game.getShop(argStr(0)) ?: return V.VNull
                    V.VNull // can open
                }
                "isShopVisited" -> V.VBool(false)
                "isReplaying" -> V.VBool(false)
                "isMoving" -> V.VBool(game.heroMoving)
                "getRealStatus" -> V.VNum(game.getStatus(argStr(0)))
                "getHeroLoc" -> V.VNum(0.0)
                "turnDirection" -> V.VNull
                "getEnemyValue" -> V.VNum(0.0)
                else -> V.VNull
            }
        }
    }

    /** Replaces ${...} blocks with evaluated values (replaceText). */
    fun replaceText(text: String): String {
        var out = text
        var guard = 0
        while (out.contains("\${") && guard++ < 10) {
            val start = out.indexOf("\${")
            var end = start + 2
            var depth = 0
            while (end < out.length) {
                when (out[end]) {
                    '{' -> depth++
                    '}' -> if (depth == 0) break else depth--
                }
                end++
            }
            if (end >= out.length) break
            val expr = out.substring(start + 2, end)
            val value = runCatching { eval(expr) }.getOrElse { V.VNull }
            out = out.substring(0, start) + str(value) + out.substring(end + 1)
        }
        return out
    }

    /** Converts an evaluated value back to a JSON element (for setValue etc.). */
    fun toJson(v: V): JsonElement = when (v) {
        is V.VNum -> JsonPrimitive(v.v)
        is V.VStr -> JsonPrimitive(v.v)
        is V.VBool -> JsonPrimitive(v.v)
        else -> JsonNull
    }
}

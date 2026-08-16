package cn.enaium.mahonoto

import cn.enaium.mahonoto.Fio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Lenient parser for the converted h5mota JSON data files. */
val Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    allowTrailingComma = true
}

fun readJsonFile(path: String): JsonElement? {
    val text = Fio.readText(path) ?: return null
    return runCatching { Json.parseToJsonElement(text) }.getOrNull()
}

// ============================ accessors ============================

fun JsonElement.str(key: String): String? = (this as? JsonObject)?.get(key)?.asString()
fun JsonElement.str(key: String, def: String): String = str(key) ?: def
fun JsonElement.num(key: String): Double? = (this as? JsonObject)?.get(key)?.asDouble()
fun JsonElement.num(key: String, def: Double): Double = num(key) ?: def
fun JsonElement.int(key: String): Int? = (this as? JsonObject)?.get(key)?.asInt()
fun JsonElement.int(key: String, def: Int): Int = int(key) ?: def
fun JsonElement.bool(key: String): Boolean? = (this as? JsonObject)?.get(key)?.asBool()
fun JsonElement.bool(key: String, def: Boolean): Boolean = bool(key) ?: def
fun JsonElement.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
fun JsonElement.has(key: String): Boolean = this is JsonObject && containsKey(key)
fun JsonElement.isNull(key: String): Boolean = (this as? JsonObject)?.get(key) == null || (this as? JsonObject)?.get(key) is JsonNull

fun JsonElement.asString(): String? = when (this) {
    is JsonPrimitive -> if (this == JsonNull) null else content
    else -> null
}

fun JsonElement.asDouble(): Double? = when (this) {
    is JsonPrimitive -> doubleOrNull
    else -> null
}

fun JsonElement.asBool(): Boolean? = when (this) {
    is JsonPrimitive -> booleanOrNull
    else -> null
}

fun JsonElement.asInt(): Int? = when (this) {
    is JsonPrimitive -> intOrNull
    else -> null
}

fun JsonElement.asObj(): JsonObject? = this as? JsonObject
fun JsonElement.asArr(): JsonArray? = this as? JsonArray

/** Raw string value whatever the type is (numbers/bools converted like JS). */
fun JsonElement.anyStr(key: String): String? {
    val v = (this as? JsonObject)?.get(key) ?: return null
    return (v as? JsonPrimitive)?.content
}

val JsonElement?.isPresent: Boolean get() = this != null && this != JsonNull

// ============================ builders ============================

fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject {
    val o = LinkedHashMap<String, JsonElement>()
    for ((k, v) in pairs) {
        o[k] = jsonElement(v)
    }
    return JsonObject(o)
}

fun jsonElement(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> JsonPrimitive(v)
    is Float -> JsonPrimitive(v.toDouble())
    is String -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to jsonElement(value) })
    is List<*> -> JsonArray(v.map { jsonElement(it) })
    is Iterable<*> -> JsonArray(v.map { jsonElement(it) })
    is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
    else -> JsonNull
}

fun List<JsonElement>.asJsonArray(): JsonArray = JsonArray(this)

/** Normalizes a value to a JsonElement (numbers/bools/strings). */
fun JsonElement.toPrimitive(): JsonPrimitive = when (this) {
    is JsonPrimitive -> this
    is JsonNull -> JsonNull
    else -> JsonNull
}

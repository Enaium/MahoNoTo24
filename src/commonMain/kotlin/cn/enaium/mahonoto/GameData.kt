package cn.enaium.mahonoto

import cn.enaium.mahonoto.Fio
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ============================ typed data models ============================

@Serializable
data class EnemyDef(
    var name: String = "",
    var hp: Double = 0.0,
    var atk: Double = 0.0,
    var def: Double = 0.0,
    var money: Double = 0.0,
    var exp: Double = 0.0,
    val special: JsonElement = JsonNull,   // number or number-array
    var point: Double? = null,
    val n: Int? = null,
    val vampire: Double? = null,
    val add: Boolean? = null,
    val fixedDamage: Double? = null,
    val breakArmor: Double? = null,
    val purify: Double? = null,
    val counterAttack: Double? = null,
    val notBomb: Boolean? = null,
    val atkValue: Double? = null,
    val defValue: Double? = null,
    val range: Int? = null,
    val zone: Double? = null,
    val zoneSquare: Boolean? = null,
    val repulse: Double? = null,
    val laser: Double? = null,
    val haloRange: Int? = null,
    val haloSquare: Boolean? = null,
    val haloAdd: Boolean? = null,
    val hpBuff: Double? = null,
    val atkBuff: Double? = null,
    val defBuff: Double? = null,
    val beforeBattle: JsonElement = JsonNull,
    val afterBattle: JsonElement = JsonNull,
    val bigImage: String? = null,
    val faceIds: JsonElement = JsonNull,
)

@Serializable
data class ItemDef(
    val cls: String = "items",
    var name: String = "",
    val text: String? = null,
    val itemEffect: String? = null,
    val itemEffectTip: String? = null,
    val useItemEffect: String? = null,
    val canUseItemEffect: JsonElement = JsonNull,
    val useItemEvent: JsonElement = JsonNull,
    val equip: JsonElement = JsonNull,
    val hideInToolbox: Boolean? = null,
    val hideInReplay: Boolean? = null,
)

@Serializable
data class DoorInfoDef(
    val time: Int? = null,
    val openSound: String? = null,
    val closeSound: String? = null,
    val keys: Map<String, Int> = emptyMap(),
    val afterOpenDoor: JsonElement = JsonNull,
)

/** One entry of maps.json (tile number -> block definition). */
@Serializable
data class BlockEventDef(
    val cls: String = "terrains",
    val id: String = "none",
    var name: String? = null,
    val canBreak: Boolean? = null,
    val animate: Int? = null,
    val trigger: String? = null,
    val script: String? = null,
    val canPass: Boolean? = null,
    val height: Int? = null,
    val doorInfo: DoorInfoDef? = null,
    val cannotIn: JsonElement = JsonNull,
    val cannotOut: JsonElement = JsonNull,
    val faceIds: JsonElement = JsonNull,
    val event: JsonElement = JsonNull,
    val bigImage: String? = null,
)

@Serializable
data class ChangeFloorDef(
    val floorId: String = "",
    val stair: String? = null,
    val loc: JsonElement = JsonNull,
    val direction: String? = null,
    val time: Double? = null,
)

/** One floor definition (floors JSON files). */
@Serializable
data class FloorDef(
    val floorId: String = "",
    val title: String = "",
    var name: String = "",
    val width: Int = 13,
    val height: Int = 13,
    val bgm: String? = null,
    val defaultGround: String = "ground",
    val ratio: Int = 1,
    val canFlyTo: Boolean = true,
    val canFlyFrom: Boolean = true,
    val canUseQuickShop: Boolean = true,
    val cannotViewMap: Boolean = false,
    val upFloor: JsonElement = JsonNull,
    val downFloor: JsonElement = JsonNull,
    val flyPoint: JsonElement = JsonNull,
    val map: List<List<Int>> = emptyList(),
    val events: Map<String, JsonElement> = emptyMap(),
    val changeFloor: Map<String, ChangeFloorDef> = emptyMap(),
    val firstArrive: JsonElement = JsonNull,
    val eachArrive: JsonElement = JsonNull,
    val parallelDo: JsonElement = JsonNull,
    val afterBattle: Map<String, JsonElement> = emptyMap(),
    val afterGetItem: Map<String, JsonElement> = emptyMap(),
    val afterOpenDoor: Map<String, JsonElement> = emptyMap(),
    val beforeBattle: Map<String, JsonElement> = emptyMap(),
    val cannotMove: Map<String, JsonElement> = emptyMap(),
    val cannotMoveIn: Map<String, JsonElement> = emptyMap(),
    val autoEvent: Map<String, JsonElement> = emptyMap(),
    val images: List<JsonElement> = emptyList(),
) {
    fun upFloorXY(): IntArray? = (upFloor as? JsonArray)?.ints()
    fun downFloorXY(): IntArray? = (downFloor as? JsonArray)?.ints()
    fun flyPointXY(): IntArray? = (flyPoint as? JsonArray)?.ints()

    fun eventList(x: Int, y: Int): List<JsonElement>? = when (val e = events["$x,$y"]) {
        null, is JsonNull -> null
        is JsonArray -> e.toList()
        else -> listOf(e)
    }

    fun changeFloorAt(x: Int, y: Int): ChangeFloorDef? = changeFloor["$x,$y"]

    fun firstArriveList(): List<JsonElement> =
        if (firstArrive is JsonArray) firstArrive.toList() else emptyList()

    fun eachArriveList(): List<JsonElement> =
        if (eachArrive is JsonArray) eachArrive.toList() else emptyList()
    fun afterBattleAt(x: Int, y: Int): JsonElement? = afterBattle["$x,$y"]
    fun afterGetItemAt(x: Int, y: Int): JsonElement? = afterGetItem["$x,$y"]
    fun afterOpenDoorAt(x: Int, y: Int): JsonElement? = afterOpenDoor["$x,$y"]
    fun beforeBattleAt(x: Int, y: Int): JsonElement? = beforeBattle["$x,$y"]
    fun cannotMoveAt(x: Int, y: Int): List<String>? = dirList(cannotMove["$x,$y"])
    fun cannotMoveInAt(x: Int, y: Int): List<String>? = dirList(cannotMoveIn["$x,$y"])
}

private fun dirList(v: JsonElement?): List<String>? {
    if (v == null || v is JsonNull) return null
    return when (v) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> listOf(v.content)
        else -> null
    }
}

private fun JsonArray.ints(): IntArray = mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.toIntArray()

@Serializable
data class ShopDef(
    val id: String = "",
    val text: String = "",
    val textInList: String = "",
    val mustEnable: Boolean = false,
    val disablePreview: Boolean = false,
    val choices: List<ShopChoice> = emptyList(),
)

@Serializable
data class ShopChoice(
    val text: String = "",
    val icon: String? = null,
    val color: JsonElement = JsonNull,
    val condition: String? = null,
    val need: String? = null,
    val action: JsonElement = JsonNull,
)

@Serializable
data class HeroLocDef(val direction: String = "down", val x: Int = 0, val y: Int = 0)

@Serializable
data class HeroItemsDef(
    val constants: Map<String, Int> = emptyMap(),
    val tools: Map<String, Int> = emptyMap(),
    val equips: Map<String, Int> = emptyMap(),
)

@Serializable
data class HeroInitDef(
    val image: String? = null,
    val animate: Boolean = false,
    var name: String = "",
    val lv: Int = 1,
    val hpmax: Int = 9999,
    var hp: Int = 1000,
    val manamax: Int = -1,
    val mana: Int = 0,
    var atk: Int = 10,
    var def: Int = 10,
    val mdef: Int = 0,
    var money: Int = 0,
    var exp: Int = 0,
    val equipment: List<JsonElement> = emptyList(),
    val items: HeroItemsDef = HeroItemsDef(),
    val loc: HeroLocDef = HeroLocDef(),
    val flags: Map<String, JsonElement> = emptyMap(),
    val followers: List<JsonElement> = emptyList(),
    val steps: Int = 0,
)

@Serializable
data class FirstDataDef(
    val title: String = "",
    var name: String = "",
    val version: String = "",
    val floorId: String = "MT0",
    val hero: HeroInitDef = HeroInitDef(),
    val startCanvas: JsonElement = JsonNull,
    val startText: JsonElement = JsonNull,
    val shops: List<ShopDef> = emptyList(),
    val levelUp: JsonElement = JsonNull,
)

@Serializable
data class MainDef(
    val floorIds: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val tilesets: List<String> = emptyList(),
    val animates: List<String> = emptyList(),
    val bgms: List<String> = emptyList(),
    val sounds: List<String> = emptyList(),
    val fonts: List<String> = emptyList(),
    val nameMap: Map<String, String> = emptyMap(),
    val levelChoose: List<JsonElement> = emptyList(),
    val equipName: List<String> = emptyList(),
    val startBgm: String? = null,
    val styles: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class HeroDirDef(val loc: Int = 0, val stop: Int = 0, val leftFoot: Int = 1, val rightFoot: Int = 3)

@Serializable
data class HeroIconDef(
    val down: HeroDirDef = HeroDirDef(),
    val left: HeroDirDef = HeroDirDef(loc = 1),
    val right: HeroDirDef = HeroDirDef(loc = 2),
    val up: HeroDirDef = HeroDirDef(loc = 3),
    val width: Int = 32,
    val height: Int = 48,
)

// ============================ loader ============================

/**
 * Loads all h5mota game data for 24层魔塔 from the assets dir (converted JSON).
 */
class GameData(val root: String) {

    lateinit var mainData: MainDef private set
    lateinit var firstData: FirstDataDef private set
    lateinit var values: Map<String, Double> private set
    lateinit var flags: JsonObject private set
    lateinit var shops: LinkedHashMap<String, ShopDef> private set
    lateinit var floorIds: List<String> private set

    // tile number -> block definition (maps.json)
    var blocksByNumber: MutableMap<Int, BlockEventDef> = LinkedHashMap()
    lateinit var enemies: LinkedHashMap<String, EnemyDef> private set
    lateinit var items: LinkedHashMap<String, ItemDef> private set
    lateinit var icons: Map<String, JsonElement> private set
    lateinit var heroIcon: HeroIconDef private set

    lateinit var floors: LinkedHashMap<String, FloorDef> private set
    val commonEvents = LinkedHashMap<String, List<JsonElement>>()

    val soundNameMap: Map<String, String> by lazy { mainData.nameMap }

    private inline fun <reified T> loadTable(file: String): T? {
        val el = readJsonFile("$root/$file") ?: return null
        return runCatching { Json.decodeFromJsonElement<T>(el) }.getOrNull()
    }

    fun load() {
        val data = readJsonFile("$root/data.json")?.asObj() ?: error("missing data.json")
        mainData = Json.decodeFromJsonElement(
            data["main"] ?: JsonObject(emptyMap())
        )
        firstData = Json.decodeFromJsonElement(
            data["firstData"] ?: JsonObject(emptyMap())
        )
        values = (data["values"] as? JsonObject)?.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        } ?: emptyMap()
        flags = data["flags"] as? JsonObject ?: JsonObject(emptyMap())
        shops = LinkedHashMap()
        (firstData.shops).forEach { shops[it.id] = it }
        floorIds = mainData.floorIds

        blocksByNumber = LinkedHashMap()
        readJsonFile("$root/maps.json")?.asObj()?.forEach { (k, v) ->
            k.toIntOrNull()?.let { n ->
                blocksByNumber[n] = Json.decodeFromJsonElement(v)
            }
        }

        enemies = LinkedHashMap()
        readJsonFile("$root/enemys.json")?.asObj()?.forEach { (k, v) ->
            enemies[k] = Json.decodeFromJsonElement(v)
        }

        items = LinkedHashMap()
        readJsonFile("$root/items.json")?.asObj()?.forEach { (k, v) ->
            items[k] = Json.decodeFromJsonElement(v)
        }

        icons = readJsonFile("$root/icons.json")?.asObj()?.mapValues { (_, v) -> v } ?: emptyMap()
        val heroEl = icons["hero"]
        heroIcon = if (heroEl != null) {
            runCatching { Json.decodeFromJsonElement<HeroIconDef>(heroEl) }.getOrElse { HeroIconDef() }
        } else HeroIconDef()

        floors = LinkedHashMap()
        val floorDir = Fio.listDir("$root/floors") ?: error("missing floors dir")
        for (name in floorDir.sorted()) {
            if (!name.endsWith(".json")) continue
            val el = readJsonFile("$root/floors/$name") ?: continue
            val floor = runCatching { Json.decodeFromJsonElement<FloorDef>(el) }.getOrNull() ?: continue
            if (floor.floorId in floors) continue // root floors win over duplicates
            floors[floor.floorId] = floor
        }

        // common events (events.json) — used by the in-game help menu etc.
        commonEvents.clear()
        readJsonFile("$root/events.json")?.asObj()?.obj("commonEvent")?.forEach { (k, v) ->
            if (v is JsonArray) commonEvents[k] = v.toList()
        }
    }

    fun enemyIdToNumber(id: String): Int {
        for ((n, def) in blocksByNumber) {
            if (def.id == id) return n
        }
        return 0
    }

    /** Block "cls" for an id (for icon sheets). */
    fun clsOf(id: String): String? {
        for ((cls, table) in icons) {
            if (cls == "hero") continue
            val t = table as? JsonObject ?: continue
            if (t.containsKey(id)) return cls
        }
        return null
    }
}

/** A block definition plus computed per-instance state. */
class BlockDef(val number: Int, val event: BlockEventDef) {
    val cls: String get() = event.cls
    val id: String get() = event.id
    val height: Int get() = event.height ?: 32
    val doorInfo: DoorInfoDef? get() = event.doorInfo
    val script: String? get() = event.script
    val cannotIn: List<String> get() = dirs(event.cannotIn)
    val cannotOut: List<String> get() = dirs(event.cannotOut)

    private fun dirs(v: JsonElement): List<String> = when (v) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> listOf(v.content)
        else -> emptyList()
    }

    val noPass: Boolean by lazy {
        val canPass = event.canPass
        when {
            canPass != null -> !canPass
            else -> cls != "items"
        }
    }

    /** Enemies/items get default triggers. */
    val effectiveTrigger: String? get() {
        event.trigger?.let { if (it != "null") return it }
        if (cls.startsWith("enemy")) return "battle"
        if (cls == "items") return "getItem"
        return null
    }

    val isEnemy: Boolean get() = cls.startsWith("enemy")
}

/** A cell in the current floor: block + runtime state. */
class Block(
    val x: Int,
    val y: Int,
    var number: Int,
    var def: BlockDef?,
    var disable: Boolean = false,
    var opacity: Double? = null,
) {
    var hidden = false
}

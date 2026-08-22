package cn.enaium.mahonoto

import cn.enaium.mahonoto.Audio
import cn.enaium.mahonoto.Fio
import cn.enaium.mahonoto.TextRenderer
import cn.enaium.sdl.SDLKeycode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random

/**
 * Game core for the h5mota port of 24层魔塔.
 * Faithfully implements the engine behavior used by this game's data:
 * floor/block state, movement, battles (classic animated + turbo),
 * the event machine, shops, book/fly/toolbox panels, win/lose, save/load.
 */
class Game(
    val data: GameData,
    val assets: Assets,
    val audio: Audio,
    val text: TextRenderer,
) {
    val expr by lazy { Expr(this) }

    // ============================ screens ============================
    enum class Screen { TITLE, GAME, GAME_OVER }

    var screen = Screen.TITLE

    // ============================ hero state ============================
    val hero = LinkedHashMap<String, Double>()  // lv hp hpmax mana manamax atk def mdef money exp steps
    val items = LinkedHashMap<String, Int>()     // item counts
    val equips = LinkedHashMap<Int, String>()    // equip slot -> itemId
    val flags = LinkedHashMap<String, JsonElement>()
    val values = LinkedHashMap<String, Double>()
    var hard = ""
    val visitedFloors = LinkedHashSet<String>()
    val shopVisited = LinkedHashSet<String>()

    var locX = 0
    var locY = 0
    var direction = "down"

    val enemyData = LinkedHashMap<String, EnemyDef>() // mutable enemy templates (setEnemy)

    // ============================ floor state ============================
    var floorId = ""
    private val cellsByFloor = HashMap<String, HashMap<String, Block>>()
    private val floorSeen = HashSet<String>()

    var lockControl = false

    // ============================ movement ============================
    var heroMoving = false
    var moveT = 0.0
    var moveFromX = 0
    var moveFromY = 0
    var moveToX = 0
    var moveToY = 0
    var heroLeg = false
    private var heldDir: String? = null
    private var holdT = 0.0
    var steps = 0

    // ============================ event machine ============================
    class Frame(var todo: ArrayDeque<JsonElement>, val total: List<JsonElement>, val condition: String?, var x: Int?, var y: Int?)

    val frames = ArrayDeque<Frame>()
    var eventX: Int? = null
    var eventY: Int? = null
    val eventPrefix: String get() = "$floorId@${eventX ?: "x"}@${eventY ?: "y"}"

    // ============================ UI state ============================
    enum class Panel { TEXT, CHOICES, CONFIRM, BOOK, FLY, TOOLBOX, SAVE, LOAD, HELP, RANK, INPUT, SETTINGS }

    var panel: Panel? = null
    var panelData = LinkedHashMap<String, Any>()
    var panelSelection = 0
    var textLine = 0
    var typewriterPos = 0
    var typewriterT = 0.0
    var textTitle: String? = null
    var textIcon: String? = null
    var textLines: List<String> = emptyList()
    var textAttribute = TextAttr()

    class TextAttr {
        var position = "center"
        var offset = 0
        var title = intArrayOf(255, 215, 0, 1)
        var background = intArrayOf(0, 0, 0, 85) // 0.85
        var textColor = intArrayOf(255, 255, 255, 1)
        var titlefont = 22
        var textfont = 16
        var lineHeight = 22
        var bold = false
        var time = 0
        var letterSpacing = 0
        var animateTime = 0
    }

    var tip: Tip? = null
    class Tip(val text: String, val iconId: String?, var t: Double, var stage: Int)

    // ============================ battle ============================
    class BattleInfo {
        var enemyId = ""
        var x = 0
        var y = 0
        var monHp = 0.0
        var monAtk = 0.0
        var monDef = 0.0
        var atkTimes = 1
        var firstAttack = false
        var heroHp = 0.0
        var animFrame = 0
        var animT = 0.0
        var active = false
        var victory = false
    }

    var battle: BattleInfo? = null

    // ============================ floor banner / door anims ============================
    var banner: Banner? = null
    class Banner(val title: String, var phase: Int, var t: Double)

    class DoorAnim(val x: Int, val y: Int, val floor: String, var frame: Double, var t: Double)

    val doorAnims = ArrayList<DoorAnim>()

    // ============================ misc ============================
    var gameOverTitle: String? = null
    var gameOverTicks = 0
    var played = false
    var showStatusBar = false
    private var rng = Random(0x5EED)
    var bgmKey: String? = null
    var pendingIntroStart = false
    var pendingAsync: (() -> Unit)? = null
    var waitData: JsonObject? = null
    var pendingBattle: (() -> Unit)? = null
    val titleUiActions = mutableListOf<JsonElement>()
    var gameOverActive = false

    // ============================ init ============================

    fun startNewGame(hardMode: String = "", startFloor: String? = data.firstData.floorId) {
        hard = hardMode
        val init = data.firstData.hero
        hero.clear()
        hero["lv"] = init.lv.toDouble()
        hero["hp"] = init.hp.toDouble()
        hero["hpmax"] = init.hpmax.toDouble()
        hero["mana"] = init.mana.toDouble()
        hero["manamax"] = init.manamax.toDouble()
        hero["atk"] = init.atk.toDouble()
        hero["def"] = init.def.toDouble()
        hero["mdef"] = init.mdef.toDouble()
        hero["money"] = init.money.toDouble()
        hero["exp"] = init.exp.toDouble()
        hero["steps"] = init.steps.toDouble()
        items.clear()
        equips.clear()
        flags.clear()
        values.clear()
        data.values.forEach { (k, v) -> values[k] = v }
        data.flags.forEach { (k, v) -> flags[k] = v }
        visitedFloors.clear()
        shopVisited.clear()
        enemyData.clear()
        data.enemies.forEach { (k, v) -> enemyData[k] = v.copy() }
        // hero init items
        init.items.constants.forEach { (id, n) -> if (n > 0) items[id] = n }
        init.items.tools.forEach { (id, n) -> if (n > 0) items[id] = n }
        init.items.equips.forEach { (id, n) -> if (n > 0) items[id] = n }
        locX = init.loc.x
        locY = init.loc.y
        direction = init.loc.direction
        frames.clear()
        panel = null
        battle = null
        banner = null
        tip = null
        lockControl = false
        heroMoving = false
        heldDir = null
        screen = Screen.GAME
        played = true
        // change to starting floor
        floorId = ""
        cellsByFloor.clear()
        floorSeen.clear()
        if (startFloor != null) {
            changeFloorTo(startFloor, null, locX to locY, 0)
        }
        audio.stopAll()
    }

    // ============================ status helpers ============================

    fun getStatus(name: String): Double = when (name) {
        "x" -> locX.toDouble()
        "y" -> locY.toDouble()
        "direction" -> 0.0
        else -> hero[name] ?: 0.0
    }

    fun setStatus(name: String, value: Double) {
        when (name) {
            "x" -> locX = value.toInt()
            "y" -> locY = value.toInt()
            else -> hero[name] = value
        }
    }

    fun getBuff(name: String): Double =
        (flags["__" + name + "_buff__"] as? JsonPrimitive)?.doubleOrNull ?: 1.0

    fun getRealStatus(name: String): Double = floor(getStatus(name) * getBuff(name)).toDouble()

    fun getFlag(name: String): JsonElement? = flags[name]

    fun getFlagNum(name: String, def: Double = 0.0): Double =
        when (val v = flags[name]) {
            is JsonPrimitive -> v.doubleOrNull ?: if (v.isString) v.content.toDoubleOrNull() ?: def else def
            else -> def
        }

    fun getFlagBool(name: String, def: Boolean = false): Boolean =
        when (val v = flags[name]) {
            is JsonPrimitive -> v.booleanOrNull ?: (v.content != "false" && v.content != "0" && v.content.isNotEmpty())
            else -> def
        }

    fun getGlobal(name: String): Double = 0.0

    fun setFlag(name: String, value: JsonElement?) {
        if (value == null || value is JsonNull) flags.remove(name) else flags[name] = value
    }

    fun itemCount(id: String): Int = items[id] ?: 0

    fun hasEquip(id: String): Boolean = equips.values.contains(id)

    fun getEnemyField(id: String, field: String): Double {
        val e = enemyDef(id)
        return when (field) {
            "hp" -> e.hp
            "atk" -> e.atk
            "def" -> e.def
            "money" -> e.money
            "exp" -> e.exp
            "point" -> e.point ?: 0.0
            else -> 0.0
        }
    }

    fun enemyDef(id: String): EnemyDef = enemyData[id] ?: EnemyDef()

    fun getShop(id: String): ShopDef? = data.shops[id]

    fun getEquip(slot: Int): String? = equips[slot]

    // ============================ blocks / maps ============================

    fun cellsOf(floor: String): HashMap<String, Block> =
        cellsByFloor.getOrPut(floor) {
            val floorDef = data.floors[floor] ?: return@getOrPut HashMap()
            val out = HashMap<String, Block>()
            for (y in floorDef.map.indices) {
                val row = floorDef.map[y]
                for (x in row.indices) {
                    val n = row[x]
                    if (n == 0) continue
                    out["$x,$y"] = initBlock(x, y, n, floor)
                }
            }
            out
        }

    private fun initBlock(x: Int, y: Int, number: Int, floor: String): Block {
        val def = data.blocksByNumber[number]?.let { BlockDef(number, it) }
        val block = Block(x, y, number, def)
        // default disabled from floor events (enable:false) — check the raw
        // event object, not the unwrapped action list
        val rawEv = data.floors[floor]?.events?.get("$x,$y") as? JsonObject
        if (rawEv?.has("enable") == true && rawEv.bool("enable") == false) block.disable = true
        return block
    }

    fun getBlock(x: Int, y: Int, floor: String? = null): Block? {
        val f = floor ?: floorId
        if (f.isEmpty()) return null
        val b = cellsOf(f)["$x,$y"] ?: return null
        return if (b.disable) null else b
    }

    fun getBlockId(x: Int, y: Int, floor: String? = null): String? =
        getBlock(x, y, floor)?.def?.id

    fun getBlockCls(x: Int, y: Int, floor: String? = null): String? =
        getBlock(x, y, floor)?.def?.cls

    fun setBlock(number: Any, x: Int, y: Int, floor: String? = null) {
        val f = floor ?: floorId
        val n = when (number) {
            is Int -> number
            is String -> data.blocksByNumber.entries.firstOrNull { it.value.id == number }?.key ?: return
            is JsonPrimitive -> number.content.toIntOrNull()
                ?: data.blocksByNumber.entries.firstOrNull { it.value.id == number.content }?.key
                ?: return
            else -> return
        }
        val cells = cellsOf(f)
        val existing = cells["$x,$y"]
        val def = data.blocksByNumber[n]?.let { BlockDef(n, it) }
        if (existing != null) {
            existing.number = n
            existing.def = def
            existing.disable = false
            existing.hidden = false
        } else {
            cells["$x,$y"] = Block(x, y, n, def)
        }
    }

    fun removeBlock(x: Int, y: Int, floor: String? = null) {
        val f = floor ?: floorId
        cellsOf(f)["$x,$y"] = Block(x, y, 0, null)
    }

    fun hideBlock(x: Int, y: Int, floor: String? = null) {
        val f = floor ?: floorId
        cellsOf(f)["$x,$y"]?.disable = true
    }

    fun showBlock(x: Int, y: Int, floor: String? = null) {
        val f = floor ?: floorId
        cellsOf(f)["$x,$y"]?.disable = false
    }

    fun searchBlock(id: String, floor: String? = null): List<Pair<Int, Int>> {
        val f = floor ?: floorId
        val out = ArrayList<Pair<Int, Int>>()
        for ((key, b) in cellsOf(f)) {
            if (!b.disable && b.def?.id == id) {
                val xy = key.split(",")
                out.add(xy[0].toInt() to xy[1].toInt())
            }
        }
        return out
    }

    fun countAliveEnemies(floor: String? = null): Int {
        var n = 0
        for (f in if (floor != null) listOf(floor) else data.floorIds) {
            for ((_, b) in cellsOf(f)) {
                if (!b.disable && b.def != null && (b.def!!.cls == "enemys" || b.def!!.cls == "enemy48")) n++
            }
        }
        return n
    }

    fun hasVisitedFloor(id: String): Boolean = id in visitedFloors

    fun thisMapRatio(): Int = data.floors[floorId]?.ratio ?: 1

    // ============================ movement ============================

    val scan = mapOf(
        "up" to (0 to -1), "down" to (0 to 1), "left" to (-1 to 0), "right" to (1 to 0),
    )

    fun turnBack(dir: String): String = when (dir) {
        "up" -> "down"; "down" -> "up"; "left" -> "right"; "right" -> "left"; else -> dir
    }

    fun nextX(): Int = locX + (scan[direction]?.first ?: 0)
    fun nextY(): Int = locY + (scan[direction]?.second ?: 0)

    fun canMoveHero(x: Int? = null, y: Int? = null, dir: String? = null): Boolean {
        val cx = x ?: locX
        val cy = y ?: locY
        val d = dir ?: direction
        val floor = data.floors[floorId] ?: return false
        // 1. cannotMove table
        if ((floor.cannotMoveAt(cx, cy) ?: emptyList()).contains(d)) return false
        val delta = scan[d] ?: return false
        val nx = cx + delta.first
        val ny = cy + delta.second
        if (nx < 0 || ny < 0 || nx >= floor.width || ny >= floor.height) return false
        // 2. cannotMoveIn of target (reversed direction)
        if ((floor.cannotMoveInAt(nx, ny) ?: emptyList()).contains(turnBack(d))) return false
        // 3. cannotOut of current cell
        if (blockDirections(cx, cy, "cannotOut").contains(d)) return false
        // 4. cannotIn of next cell
        if (blockDirections(nx, ny, "cannotIn").contains(turnBack(d))) return false
        return true
    }

    private fun blockDirections(x: Int, y: Int, which: String): List<String> {
        val b = getBlock(x, y) ?: return emptyList()
        return if (which == "cannotOut") b.def?.cannotOut ?: emptyList() else b.def?.cannotIn ?: emptyList()
    }

    fun noPass(x: Int, y: Int): Boolean {
        val b = getBlock(x, y) ?: return false
        return b.def?.noPass ?: false
    }

    fun startMove(dir: String) {
        if (lockControl || screen != Screen.GAME || panel != null || battle != null) return
        heldDir = dir
        direction = dir
        holdT = 0.0
        tryStep()
    }




    fun stopMove() {
        heldDir = null
        holdT = 0.0
        if (!heroMoving) direction = heldDir ?: direction
    }

    private fun tryStep() {
        if (heroMoving || lockControl || panel != null || battle != null) return
        val dir = heldDir ?: return
        direction = dir
        if (!canMoveHero()) return
        startStep(dir)
    }

    /** Begins a step into [dir]; blocked (noPass) cells are triggered in place. */
    private fun startStep(dir: String) {
        val delta = scan[dir]!!
        val tx = locX + delta.first
        val ty = locY + delta.second
        if (noPass(tx, ty)) {
            // trigger the blocked cell without moving; keep holding the
            // direction through doors/events so the walk resumes afterwards
            trigger(tx, ty)
            if (battle != null || panel != null) stopMove()
            return
        }
        moveFromX = locX
        moveFromY = locY
        moveToX = tx
        moveToY = ty
        moveT = 0.0
        heroMoving = true
    }

    private fun finishStep() {
        locX = moveToX
        locY = moveToY
        heroMoving = false
        heroLeg = !heroLeg
        moveOneStep()
        // Continue walking without an idle frame between tiles: while the
        // direction is still held (past the auto-walk threshold) and nothing
        // interrupted the walk (event/panel/battle/game-over), chain the next
        // step immediately so the hero flows over the floor instead of
        // pausing one frame per tile. Taps still walk exactly one cell.
        if (heroMoving || lockControl || panel != null || battle != null || frames.isNotEmpty()) return
        if (gameOverActive || screen != Screen.GAME) return
        val dir = heldDir ?: return
        if (holdT < 300.0) return
        direction = dir
        if (!canMoveHero()) return
        startStep(dir)
    }

    /** Called when the hero arrives at a cell. */
    private fun moveOneStep() {
        steps++
        hero["steps"] = steps.toDouble()
        // poison tick
        if (getFlagBool("poison")) {
            val dmg = values["poisonDamage"] ?: 10.0
            hero["hp"] = (hero["hp"] ?: 0.0) - dmg
            if ((hero["hp"] ?: 0.0) <= 0) {
                hero["hp"] = 0.0
                lose("中毒身亡")
                return
            }
        }
        trigger(locX, locY)
    }

    // ============================ trigger ============================

    fun trigger(x: Int, y: Int) {
        if (gameOverActive) return
        if (frames.isNotEmpty() || panel != null) return
        val block = getBlock(x, y) ?: return
        val def = block.def ?: return

        // contact event list (floor events table)
        val floorEvents = data.floors[floorId]?.eventList(x, y)
        if (floorEvents != null && floorEvents.isNotEmpty()) {
            startEvents(floorEvents, x, y)
            return
        }
        // changeFloor table (stairs)
        val cf = data.floors[floorId]?.changeFloorAt(x, y)
        if (cf != null) {
            changeFloorFromEvent(cf)
            return
        }
        val trigger = def.effectiveTrigger ?: return
        doSystemEvent(trigger, x, y)
    }

    fun doSystemEvent(trigger: String, x: Int, y: Int) {
        when (trigger) {
            "battle" -> {
                val id = getBlockId(x, y) ?: return
                sysBattle(id, x, y)
            }
            "openDoor" -> sysOpenDoor(x, y)
            "getItem" -> sysGetItem(x, y)
            "changeFloor" -> {
                val cf = data.floors[floorId]?.changeFloorAt(x, y) ?: return
                changeFloorFromEvent(cf)
            }
            "action" -> {
                val block = getBlock(x, y) ?: return
                val eventData = block.def?.event?.event ?: return
                startEvents(wrapList(eventData), x, y)
            }
            else -> Unit
        }
    }

    fun sysGetItem(x: Int, y: Int) {
        val id = getBlockId(x, y) ?: return
        getItem(id, 1, x, y)
    }

    fun getItem(id: String, num: Int = 1, x: Int?, y: Int?, isGentleClick: Boolean = false) {
        val item = data.items[id] ?: return
        if (x != null && y != null) removeBlock(x, y)
        applyItemEffect(id, num)
        val cls = item.cls
        val sb = StringBuilder("获得 ")
        sb.append(item.name.ifEmpty { id })
        if (num > 1) sb.append("x").append(num)
        if (cls == "items" && num == 1) sb.append(item.itemEffectTip ?: "")
        items[id] = itemCount(id) + num
        // sound
        playSound(if (cls == "constants" || id == "superPotion") "constants.mp3" else "item.mp3")
        drawTip(sb.toString(), id)
        // afterGetItem
        if (x != null && y != null) {
            data.floors[floorId]?.afterGetItemAt(x, y)?.let { after ->
                startEvents(wrapList(after), x, y)
            }
        }
    }

    private fun wrapList(v: JsonElement): List<JsonElement> = if (v is JsonArray) v.toList() else listOf(v)

    /** Evaluates an itemEffect expression string (core.status.hero.xxx op= value). */
    fun applyItemEffect(id: String, num: Int) {
        val item = data.items[id] ?: return
        val effect = item.itemEffect ?: return
        repeat(num) {
            for (stmt in effect.split(';', '\n')) {
                val s = stmt.trim()
                if (s.isEmpty()) continue
                val m = Regex("core\\.status\\.hero\\.(\\w+)\\s*(\\+=|-=|\\*=|/=)\\s*(.+)").find(s)
                if (m != null) {
                    val field = m.groupValues[1]
                    val op = m.groupValues[2]
                    val rhs = evalEffectExpr(m.groupValues[3])
                    val cur = hero[field] ?: 0.0
                    hero[field] = when (op) {
                        "+=" -> cur + rhs
                        "-=" -> cur - rhs
                        "*=" -> cur * rhs
                        "/=" -> if (rhs == 0.0) cur else cur / rhs
                        else -> cur
                    }
                    continue
                }
                val add = Regex("core\\.addItem\\('([^']+)'\\s*,\\s*([^)]+)\\)").find(s)
                if (add != null) {
                    val itemId = add.groupValues[1]
                    val n = evalEffectExpr(add.groupValues[2]).toInt()
                    items[itemId] = itemCount(itemId) + n
                    continue
                }
                val add2 = Regex("core\\.addItem\\('([^']+)'\\)").find(s)
                if (add2 != null) {
                    items[add2.groupValues[1]] = itemCount(add2.groupValues[1]) + 1
                    continue
                }
                val setItem = Regex("core\\.setItem\\('([^']+)'\\s*,\\s*([^)]+)\\)").find(s)
                if (setItem != null) {
                    items[setItem.groupValues[1]] = evalEffectExpr(setItem.groupValues[2]).toInt()
                    continue
                }
            }
        }
    }

    private fun evalEffectExpr(s: String): Double {
        var e = s.trim()
        e = Regex("core\\.values\\.(\\w+)").replace(e) { m ->
            (values[m.groupValues[1]] ?: 0.0).toString()
        }
        e = e.replace("core.status.thisMap.ratio", thisMapRatio().toString())
        e = e.replace("core.status.floorId", "\"$floorId\"")
        return runCatching { expr.evalNum(e) }.getOrElse { 0.0 }
    }

    // ============================ doors ============================

    fun sysOpenDoor(x: Int, y: Int) {
        openDoor(x, y, true)
    }

    fun openDoor(x: Int, y: Int, needKey: Boolean) {
        val block = getBlock(x, y) ?: return
        val def = block.def ?: return
        val doorInfo = def.doorInfo ?: return
        // keys
        if (needKey) {
            for ((keyName, keyValue) in doorInfo.keys) {
                if (itemCount(keyName) < keyValue) {
                    playSound("操作失败")
                    drawTip("你的${data.items[keyName]?.name ?: "钥匙"}不足！", null)
                    return
                }
            }
            for ((keyName, keyValue) in doorInfo.keys) {
                items[keyName] = itemCount(keyName) - keyValue
            }
        }
        playSound(doorInfo.openSound ?: "door.mp3")
        // opening animation: remove block, then afterOpenDoor
        val b = getBlock(x, y) ?: return
        b.hidden = true
        b.disable = true
        doorAnims.add(DoorAnim(x, y, floorId, 0.0, 0.0))
        // afterOpenDoor events
        data.floors[floorId]?.afterOpenDoorAt(x, y)?.let { after ->
            startEvents(wrapList(after), x, y)
        }
    }

    fun closeDoorAction(x: Int, y: Int) {
        val b = cellsOf(floorId)["$x,$y"]
        if (b != null && b.def != null && b.disable) {
            b.disable = false
            b.hidden = false
        }
    }

    // ============================ battle ============================

    fun hasSpecial(enemy: EnemyDef, code: Int): Boolean {
        return when (val special = enemy.special) {
            is JsonPrimitive -> special.intOrNull == code
            is JsonArray -> special.any { (it as? JsonPrimitive)?.intOrNull == code }
            else -> false
        }
    }

    class EnemyInfo(
        val hp: Double,
        val atk: Double,
        val def: Double,
        val money: Double,
        val exp: Double,
        val point: Double,
        val special: JsonElement,
        val guards: List<IntArray>,
    )

    fun getEnemyInfo(enemyId: String, heroOverride: EnemyDef?, x: Int, y: Int): EnemyInfo {
        val enemy = enemyDef(enemyId)
        val heroAtk = getRealStatus("atk")
        val heroDef = getRealStatus("def")
        var monHp = enemy.hp
        var monAtk = enemy.atk
        var monDef = enemy.def
        // 模仿 10
        if (hasSpecial(enemy, 10)) {
            monAtk = heroAtk
            monDef = heroDef
        }
        // 坚固 3
        if (hasSpecial(enemy, 3)) {
            if (monDef < heroAtk - 1) monDef = heroAtk - 1
        }
        // 光环 25 / 支援 26
        var hpBuff = 0.0
        var atkBuff = 0.0
        var defBuff = 0.0
        val guards = ArrayList<IntArray>()
        for ((key, b) in cellsOf(floorId)) {
            if (b.disable || b.def == null) continue
            if (key == "$x,$y") continue
            val benemy = enemyDef(b.def!!.id)
            if (benemy.name.isEmpty() && b.def!!.cls != "enemys") continue
            if (hasSpecial(benemy, 25)) {
                val bx = key.substringBefore(",").toInt()
                val by = key.substringAfter(",").toInt()
                val range = benemy.haloRange
                if (range != null && abs(bx - x) + abs(by - y) > range) continue
                hpBuff += benemy.hpBuff ?: 0.0
                atkBuff += benemy.atkBuff ?: 0.0
                defBuff += benemy.defBuff ?: 0.0
            }
            if (hasSpecial(benemy, 26)) {
                val bx = key.substringBefore(",").toInt()
                val by = key.substringAfter(",").toInt()
                if (max(abs(bx - x), abs(by - y)) <= 1) {
                    guards.add(intArrayOf(bx, by))
                }
            }
        }
        monHp *= (1 + hpBuff / 100)
        monAtk *= (1 + atkBuff / 100)
        monDef *= (1 + defBuff / 100)
        return EnemyInfo(
            hp = floor(monHp), atk = floor(monAtk), def = floor(monDef),
            money = enemy.money, exp = enemy.exp, point = enemy.point ?: 0.0,
            special = enemy.special, guards = guards,
        )
    }

    class DamageInfo(
        val monHp: Double, val monAtk: Double, val monDef: Double,
        val initDamage: Double, val perDamage: Double, val heroPerDamage: Double,
        val turn: Int, val damage: Double,
    )

    /** Port of functions.js getDamageInfo. */
    fun getDamageInfo(enemyId: String, heroOverride: EnemyDef?, x: Int, y: Int): DamageInfo? {
        val enemy = enemyDef(enemyId)
        val heroHp = max(0.0, getRealStatus("hp"))
        val heroAtk = max(0.0, getRealStatus("atk"))
        val heroDef = max(0.0, getRealStatus("def"))
        val heroMdef = max(0.0, getRealStatus("mdef"))
        val heroLv = getRealStatus("lv")
        val originHp = max(0.0, getStatus("hp"))
        val originAtk = max(0.0, getStatus("atk"))
        val originDef = max(0.0, getStatus("def"))

        val info = getEnemyInfo(enemyId, heroOverride, x, y)
        var monHp = info.hp
        var monAtk = info.atk
        var monDef = info.def
        val specialOf: (Int) -> Boolean = { code ->
            when (val special = info.special) {
                is JsonPrimitive -> special.intOrNull == code
                is JsonArray -> special.any { (it as? JsonPrimitive)?.intOrNull == code }
                else -> false
            }
        }

        // 无敌 20
        if (specialOf(20) && itemCount("cross") == 0) return null

        var initDamage = 0.0
        // 吸血 11
        if (specialOf(11)) {
            val vampire = enemy.vampire ?: 0.0
            val vampireDamage = floor(heroHp * vampire)
            if (enemy.add == true) monHp += vampireDamage
            initDamage += vampireDamage
        }
        var perDamage = monAtk - heroDef
        // 魔攻 2
        if (specialOf(2)) perDamage = monAtk
        if (perDamage < 0) perDamage = 0.0
        // 连击
        when {
            specialOf(4) -> perDamage *= 2
            specialOf(5) -> perDamage *= 3
            specialOf(6) -> perDamage *= (enemy.n ?: 4).toDouble()
        }
        // 先攻 1
        if (specialOf(1)) initDamage += perDamage
        // 破甲 7
        if (specialOf(7)) initDamage += floor((enemy.breakArmor ?: (values["breakArmor"] ?: 0.9)) * heroDef)
        // 净化 9
        if (specialOf(9)) initDamage += floor((enemy.purify ?: (values["purify"] ?: 3.0)) * heroMdef)

        // 反击 8
        var counterDamage = 0.0
        if (specialOf(8)) {
            counterDamage = floor((enemy.counterAttack ?: (values["counterAttack"] ?: 0.1)) * heroAtk)
        }

        val heroPerDamage = max(heroAtk - monDef, 0.0)
        if (heroPerDamage <= 0) return null

        val M = ceil(monHp / heroPerDamage).toInt()
        var turn = M

        // guards (支援 26) — none placed in this game, but keep the recursion
        var extraTurns = 0
        if (info.guards.isNotEmpty()) {
            for (g in info.guards) {
                val id = getBlockId(g[0], g[1]) ?: continue
                val guardHero = EnemyDef(hp = originHp, atk = originAtk, def = originDef, money = 0.0, exp = 0.0, special = JsonNull)
                val guardInfo = getDamageInfo(id, guardHero, x, y) ?: return null
                initDamage += guardInfo.damage
                extraTurns += turn - guardInfo.turn
            }
        }

        // 暴击期望
        val critEnabled = getFlagBool("开启暴击")
        var expectedTurns: Double
        if (!critEnabled) {
            expectedTurns = (M - 1).toDouble()
        } else {
            val v = min(heroLv / 200.0, 1.0)
            if (v <= 0) {
                expectedTurns = (M - 1).toDouble()
            } else {
                expectedTurns = (1 / (1 + v).pow(2)) * (M * (1 + v) + v * (1 - (-v).pow(M))) - 1
                if (expectedTurns < 0) expectedTurns = 0.0
            }
        }

        var damage = initDamage + (expectedTurns + extraTurns) * perDamage + M * counterDamage - heroMdef
        if (!getFlagBool("enableNegativeDamage")) {
            if (damage < 0) damage = 0.0
        }
        if (specialOf(17)) damage += getFlagNum("hatred")
        if (specialOf(22)) damage += enemy.fixedDamage ?: 0.0

        return DamageInfo(
            monHp = round(monHp), monAtk = round(monAtk), monDef = round(monDef),
            initDamage = initDamage, perDamage = perDamage, heroPerDamage = heroPerDamage,
            turn = turn, damage = round(damage),
        )
    }

    fun canBattle(id: String, x: Int, y: Int): Boolean {
        val d = getDamageInfo(id, null, x, y) ?: return false
        return d.damage < (hero["hp"] ?: 0.0)
    }

    fun sysBattle(id: String, x: Int, y: Int) {
        val before: MutableList<JsonElement> = mutableListOf()
        data.floors[floorId]?.beforeBattleAt(x, y)?.let { before.addAll(wrapList(it)) }
        enemyDef(id).beforeBattle.let { if (it !is JsonNull && it != null) before.addAll(wrapList(it)) }
        if (before.isNotEmpty()) {
            before.add(jsonObj("type" to "battle", "x" to x, "y" to y))
            startEvents(before, x, y)
            return
        }
        battle(id, x, y, false)
    }

    fun battle(id: String, x: Int, y: Int, force: Boolean) {
        if (!canBattle(id, x, y) && !force && frames.isEmpty()) {
            stopSound()
            playSound("操作失败")
            val d = getDamageInfo(id, null, x, y)
            drawTip(
                if (d == null || (hero["hp"] ?: 0.0) <= d.damage) "你打不过此怪物！" else "你的血量不够无暴击计算的预期伤害，无法开战！",
                id
            )
            return
        }
        if (getFlagBool("战斗动画") && frames.isEmpty()) {
            startAnimatedBattle(id, x, y)
        } else {
            afterBattle(id, x, y, true)
        }
    }

    fun startAnimatedBattle(id: String, x: Int, y: Int) {
        val enemy = enemyDef(id)
        val info = getEnemyInfo(id, null, x, y)
        val b = BattleInfo()
        b.enemyId = id
        b.x = x
        b.y = y
        b.monHp = info.hp
        b.monAtk = info.atk
        b.monDef = info.def
        b.atkTimes = 1
        if (hasSpecial(enemy, 4)) b.atkTimes = 2
        if (hasSpecial(enemy, 5)) b.atkTimes = 3
        if (hasSpecial(enemy, 6)) b.atkTimes = enemy.n ?: 4
        b.firstAttack = hasSpecial(enemy, 1)

        // pre-battle damage
        var hp = hero["hp"] ?: 0.0
        if (hasSpecial(enemy, 7)) hp -= floor((enemy.breakArmor ?: (values["breakArmor"] ?: 0.9)) * getRealStatus("def"))
        if (hasSpecial(enemy, 9)) hp -= floor((enemy.purify ?: (values["purify"] ?: 3.0)) * getRealStatus("mdef"))
        if (hasSpecial(enemy, 11)) {
            val vd = floor(hp * (enemy.vampire ?: 0.0))
            hp -= vd
            if (enemy.add == true) b.monHp += vd
        }
        if (hasSpecial(enemy, 22)) hp -= enemy.fixedDamage ?: 0.0
        hero["hp"] = hp
        b.heroHp = hp
        if (hp <= 0) {
            lose("战斗失败")
            return
        }
        battle = b
        lockControl = true
        // schedule battle actions
        val interval = getFlagNum("回合间隔", 375.0)
        val heroAtkAct = battleHeroAttackAction(id, interval)
        val enemyAtkAct = battleEnemyAttackAction(id, interval)
        val battleEvent: MutableList<JsonElement> = mutableListOf()
        if (b.firstAttack) {
            battleEvent.addAll(enemyAtkAct)
            battleEvent.addAll(heroAtkAct)
        } else {
            battleEvent.addAll(heroAtkAct)
            battleEvent.addAll(enemyAtkAct)
        }
        val dataArr = mutableListOf<JsonElement>()
        dataArr.add(jsonObj("type" to "sleep", "time" to 50))
        dataArr.addAll(battleEvent)
        insertAction(JsonArray(listOf<JsonElement>(jsonObj("type" to "while", "condition" to "core.status.hero.isBattling", "data" to dataArr))), x, y)
        runMachine()
    }

    private fun battleHeroAttackAction(id: String, interval: Double): List<JsonElement> {
        val enemy = enemyDef(id)
        val info = getEnemyInfo(id, null, battle?.x ?: locX, battle?.y ?: locY)
        val monDef = info.def
        val atk = getRealStatus("atk")
        val isCrit = rng.nextInt(200) < getRealStatus("lv").toInt()
        val damage = max(0.0, atk - monDef) * (if (isCrit) 2.0 else 1.0)
        return listOf(
            jsonObj("type" to "playSound", "name" to (if (isCrit) "暴击" else "攻击")),
            battleFuncAction("battleHeroHit", damage),
            jsonObj("type" to "sleep", "time" to interval),
        )
    }

    private fun battleEnemyAttackAction(id: String, interval: Double): List<JsonElement> {
        val enemy = enemyDef(id)
        val info = getEnemyInfo(id, null, battle?.x ?: locX, battle?.y ?: locY)
        val monAtk = info.atk
        val heroDef = getRealStatus("def")
        val actions = mutableListOf<JsonElement>()
        val times = battle?.atkTimes ?: 1
        repeat(times) {
            var damage = max(0.0, monAtk - heroDef)
            if (hasSpecial(enemy, 2)) damage = monAtk
            actions.add(jsonObj("type" to "playSound", "name" to (if (damage > 0) "受伤" else "格挡")))
            actions.add(battleFuncAction("battleEnemyHit", damage))
            actions.add(jsonObj("type" to "sleep", "time" to interval))
        }
        return actions
    }

    private fun battleFuncAction(kind: String, damage: Double): JsonElement =
        jsonObj(
            "type" to "battleHit",
            "side" to (if (kind == "battleHeroHit") "hero" else "enemy"),
            "damage" to damage,
        )

    fun battleHeroHit(damage: Double) {
        val b = battle ?: return
        b.monHp = max(0.0, b.monHp - damage)
        if (b.monHp <= 0) {
            // victory
            if (b.victory) return
            b.victory = true
            val loss = (hero["hp"] ?: 0.0) - b.heroHp
            hero["hp"] = b.heroHp
            hideBlock(b.x, b.y)
            afterBattle(b.enemyId, b.x, b.y, false)
            battle = null
            unlockControl()
        }
    }

    fun battleEnemyHit(damage: Double) {
        val b = battle ?: return
        b.heroHp -= damage
        if (b.heroHp <= 0) {
            b.heroHp = 0.0
            playSound("游戏失败")
            battle = null
            lose("战斗失败")
        }
    }

    /** Port of functions.js afterBattle. */
    fun afterBattle(enemyId: String, x: Int, y: Int, dr: Boolean) {
        val enemy = enemyDef(enemyId)
        if (dr) {
            playSound("攻击")
            val damageInfo = getDamageInfo(enemyId, null, x, y)
            val damage = damageInfo?.damage
            if (damage == null || damage >= (hero["hp"] ?: 0.0)) {
                hero["hp"] = 0.0
                lose("战斗失败")
                return
            }
            hero["hp"] = (hero["hp"] ?: 0.0) - damage
        }

        var money = enemy.money
        if (itemCount("coin") > 0) money *= 2
        if (getFlagBool("curse")) money = 0.0
        hero["money"] = (hero["money"] ?: 0.0) + money

        var exp = enemy.exp
        if (getFlagBool("curse")) exp = 0.0
        hero["exp"] = (hero["exp"] ?: 0.0) + exp

        val name = enemy.name.ifEmpty { enemyId }
        var hint = "打败 $name"
        val sbi = flags["statusBarItems"] as? JsonArray
        if (sbi != null) {
            val ids = sbi.mapNotNull { (it as? JsonPrimitive)?.content }
            if (ids.contains("enableMoney")) hint += "，金币+${money.toLong()}"
            if (ids.contains("enableExp")) hint += "，经验+${exp.toLong()}"
        }

        val todo = mutableListOf<JsonElement>()
        if (dr) {
            drawTip(hint, enemyId)
        } else {
            val popup = "得到金币数 ${money.toLong()} 经验值 ${exp.toLong()} ！"
            todo.add(jsonObj("type" to "playSound", "name" to "战斗胜利"))
            todo.add(jsonObj("type" to "tip", "text" to popup))
        }

        // debuffs
        if (hasSpecial(enemy, 12)) triggerDebuff("get", "poison")
        if (hasSpecial(enemy, 13)) triggerDebuff("get", "weak")
        if (hasSpecial(enemy, 14)) triggerDebuff("get", "curse")
        if (hasSpecial(enemy, 17)) setFlag("hatred", JsonPrimitive(floor(getFlagNum("hatred") / 2)))
        if (hasSpecial(enemy, 19)) hero["hp"] = 1.0
        if (hasSpecial(enemy, 21)) {
            hero["atk"] = max(0.0, (hero["atk"] ?: 0.0) - (enemy.atkValue ?: 0.0))
            hero["def"] = max(0.0, (hero["def"] ?: 0.0) - (enemy.defValue ?: 0.0))
        }
        setFlag("hatred", JsonPrimitive(getFlagNum("hatred") + (values["hatred"] ?: 2.0)))

        // afterBattle events
        data.floors[floorId]?.afterBattleAt(x, y)?.let { todo.addAll(wrapList(it)) }
        if (enemy.afterBattle !is JsonNull && enemy.afterBattle != null) todo.addAll(wrapList(enemy.afterBattle))

        if (todo.isNotEmpty()) {
            startEvents(todo, x, y)
        }

        // remove / hide the enemy
        if (getBlock(x, y) != null) {
            if (hasSpecial(enemy, 23)) hideBlock(x, y) else removeBlock(x, y)
        }
    }

    // ============================ debuffs ============================

    fun triggerDebuff(action: String, type: Any) {
        val types: List<String> = if (type is JsonArray) type.mapNotNull { (it as? JsonPrimitive)?.content } else listOf(type.toString())
        var changed = false
        for (t in types) {
            when (t) {
                "poison" -> if (action == "get") {
                    if (!getFlagBool("poison")) { setFlag("poison", JsonPrimitive(true)); changed = true }
                } else if (getFlagBool("poison")) {
                    setFlag("poison", null); changed = true
                }
                "weak" -> {
                    val had = getFlagBool("weak")
                    if (action == "get") {
                        if (!had) {
                            setFlag("weak", JsonPrimitive(true))
                            val weakValue = values["weakValue"] ?: 20.0
                            if (weakValue >= 1) {
                                hero["atk"] = max(0.0, (hero["atk"] ?: 0.0) - weakValue)
                                hero["def"] = max(0.0, (hero["def"] ?: 0.0) - weakValue)
                            }
                            changed = true
                        }
                    } else if (had) {
                        setFlag("weak", null)
                        val weakValue = values["weakValue"] ?: 20.0
                        if (weakValue >= 1) {
                            hero["atk"] = (hero["atk"] ?: 0.0) + weakValue
                            hero["def"] = (hero["def"] ?: 0.0) + weakValue
                        }
                        changed = true
                    }
                }
                "curse" -> if (action == "get") {
                    if (!getFlagBool("curse")) { setFlag("curse", JsonPrimitive(true)); changed = true }
                } else if (getFlagBool("curse")) {
                    setFlag("curse", null); changed = true
                }
            }
        }
        if (changed && action == "remove") playSound("回血")
    }

    // ============================ event machine ============================

    fun startEvents(list: List<JsonElement>, x: Int?, y: Int?) {
        val l = ArrayList(list)
        l.add(label())
        frames.addFirst(Frame(ArrayDeque(l), l, "false", x, y))
        eventX = x
        eventY = y
        lockControl = true
        runMachine()
    }

    fun insertAction(action: JsonElement, x: Int?, y: Int?) {
        val list = if (action is JsonArray) action.toList() else listOf(action)
        if (frames.isEmpty()) {
            startEvents(list, x, y)
            return
        }
        val top = frames.first()
        top.todo.addAll(0, list)
        top.x = x
        top.y = y
        eventX = x
        eventY = y
    }

    private fun label(): JsonElement = jsonObj("type" to "_label")

    /** Processes pending events; suspends on input/async actions. */
    fun runMachine() {
        if (screen != Screen.GAME && screen != Screen.TITLE) return
        var guard = 0
        while (frames.isNotEmpty() && guard++ < 100000) {
            // pop finished frames
            while (frames.isNotEmpty() && frames.first().todo.isEmpty()) {
                val f = frames.removeFirst()
                if (f.condition != null && f.condition != "false") {
                    if (expr.evalBool(f.condition)) {
                        frames.addFirst(Frame(ArrayDeque(f.total), f.total, f.condition, f.x, f.y))
                        break
                    }
                }
                if (frames.isEmpty()) {
                    onEventsDone()
                    return
                }
                continue
            }
            if (frames.isEmpty()) {
                onEventsDone()
                return
            }
            val frame = frames.first()
            val act = frame.todo.removeFirst()
            if (act is JsonPrimitive) {
                if (act.isString) {
                    if (suspendText(act.content)) return
                    continue
                }
                continue
            }
            if (act !is JsonObject) continue
            val type = act.str("type") ?: continue
            if (type == "_label" || type == "comment") continue
            if (act.bool("_disabled") == true) continue
            val suspended = execAction(act, frame)
            if (suspended) return
        }
    }

    private fun onEventsDone() {
        eventX = null
        eventY = null
        unlockControl()
        // tip 不在此清除，交由 updateTip 自然淡出
        if (pendingIntroStart) {
            pendingIntroStart = false
            val floor = data.firstData.floorId
            changeFloorTo(floor, null, locX to locY, 0)
        } else if (screen == Screen.TITLE && !played) {
            // startCanvas finished: begin the game
            pendingIntroStart = true
            runStartText()
        }
    }

    fun unlockControl() {
        if (battle != null) return
        if (panel != null) return
        lockControl = false
    }

    /** Executes one action. Returns true if the machine must suspend. */
    private fun execAction(act: JsonObject, frame: Frame): Boolean {
        val type = act.str("type") ?: return false
        when (type) {
            "text" -> return suspendText(act.str("text") ?: "")
            "autoText" -> {
                suspendText(act.str("text") ?: "", auto = true, time = act.num("time", 3000.0))
                return true
            }
            "tip" -> {
                drawTip(expr.replaceText(act.str("text") ?: ""), act.str("icon"))
                return false
            }
            "setValue" -> {
                execSetValue(act)
                if ((hero["hp"] ?: 0.0) <= 0 && act.bool("norefresh") != true) {
                    hero["hp"] = 0.0
                    lose()
                    return true
                }
                return false
            }
            "playSound" -> {
                playSound(act.str("name"))
                return false
            }
            "stopSound" -> { stopSound(); return false }
            "playBgm" -> {
                playBgm(act.str("name"))
                return false
            }
            "pauseBgm" -> { pauseBgm(); return false }
            "resumeBgm" -> { resumeBgm(); return false }
            "setVolume" -> return false
            "if" -> {
                val cond = act.str("condition") ?: "false"
                val branch = if (expr.evalBool(cond)) act["true"] else act["false"]
                if (branch != null) insertAction(branch, frame.x, frame.y)
                return false
            }
            "switch" -> {
                val key = expr.evalStr(act.str("condition") ?: "")
                val cases = act.arr("caseList")?.toList() ?: emptyList()
                val list = mutableListOf<JsonElement>()
                for (c in cases) {
                    val co = c as? JsonObject ?: continue
                    if (co.bool("_disabled") == true) continue
                    val cond = co.str("case")
                    if (cond == "default" || expr.evalStr(cond ?: "") == key) {
                        co["action"]?.let { list.addAll(wrapList(it)) }
                        if (co.bool("nobreak") != true) break
                    }
                }
                insertAction(JsonArray(list), frame.x, frame.y)
                return false
            }
            "while" -> {
                val cond = act.str("condition") ?: "false"
                if (expr.evalBool(cond)) {
                    val list = ArrayList(wrapList(act["data"] ?: JsonNull))
                    list.add(label())
                    frames.addFirst(Frame(ArrayDeque(list), list, cond, frame.x, frame.y))
                }
                return false
            }
            "dowhile" -> {
                val cond = act.str("condition") ?: "false"
                val list = ArrayList(wrapList(act["data"] ?: JsonNull))
                list.add(label())
                frames.addFirst(Frame(ArrayDeque(list), list, cond, frame.x, frame.y))
                return false
            }
            "break" -> {
                var n = act.int("n", 1)
                while (n-- > 0 && frames.size > 1) frames.removeFirst()
                return false
            }
            "continue" -> {
                var n = act.int("n", 1)
                while (n-- > 1 && frames.size > 1) frames.removeFirst()
                if (frames.size > 1) {
                    val top = frames.first()
                    if (top.condition != null && top.condition != "false") {
                        if (expr.evalBool(top.condition)) top.todo = ArrayDeque(top.total)
                        else frames.removeFirst()
                    }
                }
                return false
            }
            "exit" -> {
                frames.clear()
                return false
            }
            "ops" -> {
                // 操作数组：依次执行 data 中的每个子动作（复用商店购买同款动作系统）
                act.arr("data")?.toList()?.let { insertAction(JsonArray(it), frame.x, frame.y) }
                return false
            }
            "battleHit" -> {
                val dmg = act.num("damage") ?: 0.0
                if (act.str("side") == "hero") battleHeroHit(dmg) else battleEnemyHit(dmg)
                return false
            }
            "changeFloor" -> {
                changeFloorFromEvent(
                    ChangeFloorDef(
                        floorId = act.str("floorId") ?: "",
                        stair = act.str("stair"),
                        loc = act["loc"] ?: JsonNull,
                        direction = act.str("direction"),
                        time = act.num("time"),
                    )
                )
                return false
            }
            "changePos" -> {
                val loc = act.arr("loc")
                if (loc != null && loc.size >= 2) {
                    locX = loc[0].asDouble()?.toInt() ?: locX
                    locY = loc[1].asDouble()?.toInt() ?: locY
                }
                act.str("direction")?.let { direction = it }
                return false
            }
            "setBlock" -> {
                val locs = getLoc2D(act)
                for ((lx, ly) in locs) {
                    setBlock(act["number"] ?: JsonPrimitive(0), lx, ly, act.str("floorId") ?: floorId)
                }
                return false
            }
            "show" -> {
                val locs = getLoc2D(act)
                for ((lx, ly) in locs) showBlock(lx, ly, act.str("floorId") ?: floorId)
                return false
            }
            "hide" -> {
                val locs = getLoc2D(act)
                val remove = act.bool("remove") == true
                for ((lx, ly) in locs) {
                    if (remove) removeBlock(lx, ly, act.str("floorId") ?: floorId)
                    else hideBlock(lx, ly, act.str("floorId") ?: floorId)
                }
                return false
            }
            "openDoor" -> {
                val locs = getLoc2D(act)
                for ((lx, ly) in locs) openDoor(lx, ly, act.bool("needKey") ?: true)
                return false
            }
            "closeDoor" -> {
                val locs = getLoc2D(act)
                for ((lx, ly) in locs) closeDoorAction(lx, ly)
                return false
            }
            "openShop" -> {
                openShopAction(act.str("id") ?: "")
                return true
            }
            "choices" -> {
                if (openChoices(act)) return true
                return false
            }
            "confirm" -> {
                openConfirm(act)
                return true
            }
            "input" -> {
                openInput(act.str("text") ?: "")
                return true
            }
            "sleep" -> {
                schedule(act.num("time", 0.0).toLong()) { runMachine() }
                return true
            }
            "waitAsync" -> return false
            "wait" -> {
                // startCanvas wait: keyboard cases; suspended until key
                waitData = act
                return true
            }
            "showStatusBar" -> { showStatusBar = true; return false }
            "hideStatusBar" -> { showStatusBar = false; return false }
            "setText" -> {
                applySetText(act)
                return false
            }
            "setCurtain" -> return false
            "win" -> {
                win(act.str("reason"), act.bool("norank") == true, act.bool("noexit") == true)
                return true
            }
            "lose" -> {
                lose(act.str("reason"))
                return true
            }
            "setEnemy" -> {
                execSetEnemy(act)
                return false
            }
            "setEnemyOnPoint" -> return false
            "addFlag" -> {
                val name = expr.replaceText(act.str("name") ?: "")
                val value = expr.evalNum(act.str("value") ?: "0")
                setFlag(name, JsonPrimitive(getFlagNum(name) + value))
                return false
            }
            "setGlobalFlag" -> {
                val name = act.str("name") ?: return false
                flags[name] = expr.toJson(expr.eval(act.str("value") ?: "0"))
                return false
            }
            "setGlobalValue" -> {
                values[act.str("name") ?: ""] = expr.evalNum(act.str("value") ?: "0")
                return false
            }
            "setHeroIcon" -> return false
            "insert" -> {
                val name = act.str("name")
                val common = data.commonEvents[name]
                if (common != null) {
                    insertAction(JsonArray(common.toMutableList()), frame.x, frame.y)
                }
                return false
            }
            "callBook" -> {
                openBook()
                return true
            }
            "callSave" -> { openSavePanel(); return true }
            "callLoad" -> { openLoadPanel(); return true }
            "previewUI" -> {
                act.arr("action")?.toList()?.let { titleUiActions.addAll(it) }
                return false
            }
            "drawSelector", "clearMap", "setAttribute", "fillRect", "strokeRect",
            "fillText", "fillBoldText", "drawImage", "drawIcon", "drawBackground",
            "drawTextContent", "drawLine", "drawArrow", "fillPolygon" ->
                return false
            "battle" -> {
                val x = act.num("x")?.toInt() ?: frame.x ?: locX
                val y = act.num("y")?.toInt() ?: frame.y ?: locY
                val id = act.str("id") ?: getBlockId(x, y) ?: return false
                if (frames.size > 1) {
                    pendingBattle = { battle(id, x, y, false) }
                    return true
                }
                battle(id, x, y, false)
                return false
            }
            else -> return false
        }
    }

    fun getLoc2D(act: JsonObject): List<Pair<Int, Int>> {
        val loc = act["loc"]
        return when (loc) {
            is JsonArray -> {
                if (loc.size == 2 && loc[0] is JsonPrimitive && loc[0].asDouble() != null) {
                    listOf(loc[0].asDouble()!!.toInt() to loc[1].asDouble()!!.toInt())
                } else {
                    loc.mapNotNull { row ->
                        val r = row as? JsonArray ?: return@mapNotNull null
                        if (r.size >= 2) (r[0].asDouble()?.toInt() ?: 0) to (r[1].asDouble()?.toInt() ?: 0) else null
                    }
                }
            }
            null, is JsonNull -> {
                // no loc: operate on the current event position
                val x = act.int("x") ?: eventX
                val y = act.int("y") ?: eventY
                if (x != null && y != null) listOf(x to y) else emptyList()
            }
            else -> emptyList()
        }
    }

    fun applySetText(act: JsonObject) {
        act.str("position")?.let { textAttribute.position = it }
        act.int("offset")?.let { textAttribute.offset = it }
        act.bool("bold")?.let { textAttribute.bold = it }
        act.int("lineHeight")?.let { textAttribute.lineHeight = it }
        act.int("time")?.let { textAttribute.time = it }
        act.int("letterSpacing")?.let { textAttribute.letterSpacing = it }
        act.arr("title")?.let { c -> if (c.size >= 3) textAttribute.title = intArrayOf(c[0].asDouble()!!.toInt(), c[1].asDouble()!!.toInt(), c[2].asDouble()!!.toInt(), c[3].asDouble()?.toInt() ?: 1) }
        act.arr("background")?.let { c -> if (c.size >= 3) textAttribute.background = intArrayOf(c[0].asDouble()!!.toInt(), c[1].asDouble()!!.toInt(), c[2].asDouble()!!.toInt(), (c[3].asDouble()?.toInt() ?: 85)) }
        act.arr("text")?.let { c -> if (c.size >= 3) textAttribute.textColor = intArrayOf(c[0].asDouble()!!.toInt(), c[1].asDouble()!!.toInt(), c[2].asDouble()!!.toInt(), c[3].asDouble()?.toInt() ?: 1) }
    }

    fun execSetValue(act: JsonObject) {
        val name = act.str("name") ?: return
        val operator = act.str("operator")
        val valueStr = act.str("value") ?: "0"
        val evaluated = expr.eval(valueStr)
        val statusNames = setOf(
            "status:atk", "status:def", "status:hp", "status:money", "status:exp",
            "status:lv", "status:mana", "status:hpmax", "status:mdef",
        )
        val current = when {
            name in statusNames -> getStatus(name.substringAfter(":"))
            name.startsWith("item:") -> itemCount(name.substringAfter("item:")).toDouble()
            name.startsWith("flag:") -> getFlagNum(name.substringAfter("flag:"))
            else -> 0.0
        }
        val value = when (evaluated) {
            is Expr.V.VNum -> evaluated.v
            is Expr.V.VBool -> if (evaluated.v) 1.0 else 0.0
            is Expr.V.VStr -> evaluated.v.toDoubleOrNull() ?: 0.0
            is Expr.V.VNull -> Double.NaN
        }
        val result = when (operator) {
            "+=" -> current + value
            "-=" -> current - value
            "*=" -> current * value
            "/=" -> if (value == 0.0) current else current / value
            "min=" -> min(current, value)
            "max=" -> max(current, value)
            else -> value
        }
        applyValueName(name, if (result.isNaN()) null else result)
    }

    fun applyValueName(name: String, value: Double?) {
        when {
            name.startsWith("status:") -> {
                if (value == null) return
                val field = name.substringAfter(":")
                when (field) {
                    "x" -> locX = value.toInt()
                    "y" -> locY = value.toInt()
                    else -> hero[field] = value
                }
            }
            name.startsWith("item:") -> {
                val id = name.substringAfter(":")
                if (value == null) {
                    items[id] = 0
                } else {
                    val cur = itemCount(id)
                    if (value > cur) getItem(id, (value - cur).toInt(), null, null)
                    else items[id] = value.toInt()
                }
            }
            name.startsWith("flag:") -> {
                if (value == null) setFlag(name.substringAfter(":"), null)
                else setFlag(name.substringAfter(":"), jsonElement(value))
            }
            name.startsWith("switch:") -> {
                if (value == null) setFlag("$eventPrefix@${name.substringAfter(":")}", null)
                else setFlag("$eventPrefix@${name.substringAfter(":")}", jsonElement(value))
            }
            name.startsWith("temp:") -> {
                if (value == null) setFlag("@temp@${name.substringAfter(":")}", null)
                else setFlag("@temp@${name.substringAfter(":")}", jsonElement(value))
            }
            name.startsWith("global:") -> {
                if (value != null) values[name.substringAfter(":")] = value as? Double ?: 0.0
            }
            name.startsWith("buff:") -> {
                if (value != null) setFlag("__${name.substringAfter(":")}_buff__", jsonElement(value))
            }
        }
    }

    fun execSetEnemy(act: JsonObject) {
        val id = act.str("id") ?: return
        val field = act.str("name") ?: return
        val valueStr = act.str("value") ?: "0"
        val value = expr.evalNum(valueStr)
        val enemy = enemyData[id] ?: return
        when (field) {
            "hp" -> enemy.hp = value
            "atk" -> enemy.atk = value
            "def" -> enemy.def = value
            "money" -> enemy.money = value
            "exp" -> enemy.exp = value
            "point" -> enemy.point = value
        }
    }

    // ============================ text / choices / confirm ============================

    /** Wraps [content] to lines that fit [maxWidth] at [sizePx] (like the engine). */
    fun wrapText(content: String, sizePx: Int, maxWidth: Int): List<String> {
        val out = mutableListOf<String>()
        for (rawLine in content.split('\n')) {
            var cur = ""
            for (c in rawLine) {
                val test = cur + c
                if (cur.isNotEmpty() && this.text.measure(test, sizePx) > maxWidth) {
                    out.add(cur)
                    cur = c.toString()
                } else {
                    cur = test
                }
            }
            out.add(cur)
        }
        return out
    }

    fun suspendText(raw: String, auto: Boolean = false, time: Double = 3000.0): Boolean {
        val content = expr.replaceText(raw)
        val titleInfo = parseTitle(content)
        val validWidth = 402 - (if (titleInfo.icon != null) 62 else 25) - 12
        textLines = wrapText(titleInfo.content, textAttribute.textfont, validWidth)
        textLine = 0
        textTitle = titleInfo.title
        textIcon = titleInfo.icon
        typewriterPos = 0
        typewriterT = 0.0
        panel = Panel.TEXT
        panelSelection = 0
        panelData["auto"] = auto
        panelData["autoTime"] = time
        panelData["startT"] = 0.0
        return true
    }

    class TitleInfo(val title: String?, val icon: String?, val content: String)

    fun parseTitle(content: String): TitleInfo {
        // \t[title,icon] or [icon]
        var s = content
        val m = Regex("^(\\\\t|\\t)\\[([^\\],]+(,[^\\]]+)?)\\]").find(s)
        if (m != null) {
            val inside = m.groupValues[2]
            val parts = inside.split(',', limit = 2)
            val icon = parts.last().trim()
            val title = if (parts.size == 2) parts[0].trim() else null
            s = s.substring(m.range.last + 1)
            return TitleInfo(
                title,
                if (icon == "null" || icon.isEmpty()) null else icon,
                stripTextMarkers(s),
            )
        }
        return TitleInfo(null, null, stripTextMarkers(s))
    }

    /** Strips \b[position] / \r[color] text markers before rendering. */
    private fun stripTextMarkers(s: String): String =
        s.replace(Regex("\\\\b\\[[^\\]]*\\]"), "")
            .replace(Regex("\\\\r\\[[^\\]]*\\]"), "")
            .replace(Regex("\\\\i\\[[^\\]]*\\]"), "")

    fun advanceText() {
        if (panel != Panel.TEXT) return
        if (textLine < textLines.size - 1) {
            textLine++
            typewriterPos = 0
            typewriterT = 0.0
            playSound("cursor.mp3")
        } else {
            panel = null
            playSound("cursor.mp3")
            runMachine()
        }
    }

    fun openChoices(act: JsonObject): Boolean {
        val choices = act.arr("choices")?.toList() ?: return false
        val filtered = choices.filter { c ->
            val co = c as? JsonObject ?: return@filter true
            if (co.bool("_disabled") == true) return@filter false
            val cond = co.str("condition")
            if (cond == null || cond.isEmpty()) true
            else runCatching { expr.evalBool(cond) }.getOrElse { true }
        }
        if (filtered.isEmpty()) return false
        panel = Panel.CHOICES
        panelSelection = 0
        panelData["text"] = act.str("text") ?: ""
        panelData["choices"] = filtered
        panelData["width"] = act.num("width", 246.0)
        playSound("打开界面")
        return true
    }

    fun selectChoice(index: Int) {
        if (panel != Panel.CHOICES) return
        val choices = panelData["choices"] as? List<JsonElement> ?: return
        if (index < 0 || index >= choices.size) return
        val choice = choices[index] as? JsonObject ?: return
        // need check
        val need = choice.str("need")
        if (need != null && need.isNotEmpty() && !expr.evalBool(need)) {
            playSound("操作失败")
            return
        }
        playSound("确定")
        panel = null
        choice["action"]?.let { if (it != JsonNull) insertAction(it, eventX, eventY) }
        runMachine()
    }

    fun openConfirm(act: JsonObject) {
        panel = Panel.CONFIRM
        panelSelection = if (act.bool("default") == true) 0 else 1
        panelData["text"] = act.str("text") ?: ""
        panelData["yes"] = act["yes"] ?: JsonNull
        panelData["no"] = act["no"] ?: JsonNull
        playSound("打开界面")
    }

    fun confirmChoice(index: Int) {
        if (panel != Panel.CONFIRM) return
        val yes = panelData["yes"]
        val no = panelData["no"]
        panel = null
        playSound("确定")
        val action = if (index == 0) yes else no
        if (action != null && action != JsonNull) {
            insertAction(action as JsonElement, eventX, eventY)
        }
        runMachine()
    }

    fun openInput(hint: String) {
        panel = Panel.INPUT
        panelData["hint"] = hint
        panelData["buf"] = ""
    }

    fun submitInput(value: Int) {
        panel = null
        setFlag("input", JsonPrimitive(value))
        runMachine()
    }

    // ============================ shops ============================

    fun openShopAction(id: String) {
        val shop = data.shops[id] ?: return
        // shop as choices
        val choices = shop.choices.map { c ->
            jsonObj(
                "text" to c.text,
                "icon" to c.icon,
                "need" to c.need,
                "action" to c.action,
            )
        }
        val act = jsonObj(
            "type" to "choices",
            "text" to shop.text,
            "choices" to (choices as Any),
        ) as JsonObject
        openChoices(act)
    }

    // ============================ panels ============================

    fun openBook() {
        if (itemCount("book") == 0) {
            playSound("操作失败")
            drawTip("你没有圣光徽！")
            return
        }
        panel = Panel.BOOK
        panelSelection = 0
        playSound("打开界面")
    }

    fun closeBook() {
        if (panel == Panel.BOOK) {
            panel = null
            playSound("取消")
        }
    }

    fun openFly() {
        if (itemCount("fly") == 0 && !getFlagBool("fly")) {
            playSound("操作失败")
            drawTip("你没有风之罗盘！")
            return
        }
        val floor = data.floors[floorId]
        if (floor != null && !floor.canFlyFrom) {
            playSound("操作失败")
            drawTip("当前楼层无法使用风之罗盘！")
            return
        }
        panel = Panel.FLY
        panelSelection = data.floorIds.indexOf(floorId)
        playSound("打开界面")
    }

    fun flyTo(index: Int) {
        if (panel != Panel.FLY) return
        if (index < 0 || index >= data.floorIds.size) return
        val toId = data.floorIds[index]
        val fromId = floorId
        val from = data.floors[fromId]
        val to = data.floors[toId]
        if (to == null) return
        if (!to.canFlyTo || !hasVisitedFloor(toId)) {
            playSound("操作失败")
            drawTip("无法传送到该楼层！")
            return
        }
        if (from != null && !from.canFlyFrom) {
            playSound("操作失败")
            return
        }
        playSound("飞行器")
        panel = null
        var tx = locX
        var ty = locY
        val fromIndex = data.floorIds.indexOf(fromId)
        val stair = if (fromIndex <= index) "downFloor" else "upFloor"
        when {
            to.flyPointXY() != null -> { val p = to.flyPointXY()!!; tx = p[0]; ty = p[1] }
            stair == "downFloor" && to.downFloorXY() != null -> { val p = to.downFloorXY()!!; tx = p[0]; ty = p[1] }
            stair == "upFloor" && to.upFloorXY() != null -> { val p = to.upFloorXY()!!; tx = p[0]; ty = p[1] }
            else -> {
                val stairs = searchBlock(if (stair == "downFloor") "downFloor" else "upFloor", toId)
                if (stairs.isNotEmpty()) { tx = stairs[0].first; ty = stairs[0].second }
            }
        }
        changeFloorTo(toId, null, tx to ty, 0)
    }

    fun openToolbox() {
        panel = Panel.TOOLBOX
        panelSelection = 0
        playSound("打开界面")
    }

    fun useToolboxItem(index: Int) {
        if (panel != Panel.TOOLBOX) return
        val ids = toolboxItems()
        if (index < 0 || index >= ids.size) return
        val id = ids[index]
        panel = null
        useItem(id)
    }

    fun toolboxItems(): List<String> = items.keys
        .filter { !(data.items[it]?.hideInToolbox == true) }
        .sorted()

    fun useItem(id: String) {
        val item = data.items[id] ?: return
        val cls = item.cls
        when (id) {
            "fly" -> { openFly(); return }
            "book" -> { openBook(); return }
        }
        val useEffect = item.useItemEffect
        if (useEffect != null) {
            when (id) {
                "pickaxe" -> usePickaxe()
                "bomb" -> useBomb()
                "poisonWine" -> { triggerDebuff("remove", "poison"); items[id] = itemCount(id) - 1 }
                "weakWine" -> { triggerDebuff("remove", "weak"); items[id] = itemCount(id) - 1 }
                "curseWine" -> { triggerDebuff("remove", "curse"); items[id] = itemCount(id) - 1 }
                "superWine" -> {
                    triggerDebuff("remove", JsonArray(listOf(JsonPrimitive("poison"), JsonPrimitive("weak"), JsonPrimitive("curse"))))
                    items[id] = itemCount(id) - 1
                }
                "jumpShoes" -> {
                    playSound("跳跃")
                    val delta = scan[direction]!!
                    val tx = locX + delta.first * 2
                    val ty = locY + delta.second * 2
                    val floor = data.floors[floorId]
                    if (tx in 0 until (floor?.width ?: 13) && ty in 0 until (floor?.height ?: 13) && getBlock(tx, ty) == null) {
                        locX = tx
                        locY = ty
                        items[id] = itemCount(id) - 1
                    } else {
                        playSound("操作失败")
                        items[id] = itemCount(id) - 1
                        drawTip("当前无法使用跳跃靴")
                    }
                }
                "upFly" -> {
                    val idx = data.floorIds.indexOf(floorId)
                    if (idx < data.floorIds.size - 1) {
                        items[id] = itemCount(id) - 1
                        changeFloorTo(data.floorIds[idx + 1], null, locX to locY, 0)
                    } else {
                        playSound("操作失败")
                        items[id] = itemCount(id) - 1
                    }
                }
                "downFly" -> {
                    val idx = data.floorIds.indexOf(floorId)
                    if (idx > 0) {
                        items[id] = itemCount(id) - 1
                        changeFloorTo(data.floorIds[idx - 1], null, locX to locY, 0)
                    } else {
                        playSound("操作失败")
                        items[id] = itemCount(id) - 1
                    }
                }
                "freezeBadge" -> {
                    val delta = scan[direction]!!
                    val tx = locX + delta.first
                    val ty = locY + delta.second
                    if (getBlockId(tx, ty) == "lava") {
                        removeBlock(tx, ty)
                        playSound("打开界面")
                        items[id] = itemCount(id) - 1
                    } else {
                        playSound("操作失败")
                        items[id] = itemCount(id) - 1
                        drawTip("当前无法使用冰冻徽章")
                    }
                }
                "earthquake" -> {
                    val toRemove = cellsOf(floorId).values.filter { !it.disable && it.def?.event?.canBreak == true }
                    toRemove.forEach { removeBlock(it.x, it.y) }
                    playSound("炸弹")
                    items[id] = itemCount(id) - 1
                }
                "bigKey" -> {
                    val doors = searchBlock("yellowDoor")
                    if (doors.isNotEmpty()) {
                        items[id] = itemCount(id) - 1
                        doors.forEach { openDoor(it.first, it.second, false) }
                    } else {
                        playSound("操作失败")
                        items[id] = itemCount(id) - 1
                    }
                }
                else -> drawTip("当前无法使用${item.name.ifEmpty { id }}")
            }
            return
        }
        // tools with useItemEvent
        if (item.useItemEvent !is JsonNull && item.useItemEvent != null) {
            startEvents(wrapList(item.useItemEvent), null, null)
            return
        }
        drawTip("当前无法使用${item.name.ifEmpty { id }}")
    }

    private fun usePickaxe() {
        val delta = scan[direction]!!
        val tx = locX + delta.first
        val ty = locY + delta.second
        val block = getBlock(tx, ty)
        val canBreak = block != null && block.def?.event?.canBreak == true
        if (canBreak) {
            removeBlock(tx, ty)
            playSound("破墙镐")
            items["pickaxe"] = itemCount("pickaxe") - 1
            drawTip("破墙镐使用成功", "pickaxe")
        } else {
            playSound("操作失败")
            items["pickaxe"] = itemCount("pickaxe") - 1
            drawTip("当前无法使用破墙镐", "pickaxe")
        }
    }

    private fun useBomb() {
        val delta = scan[direction]!!
        val tx = locX + delta.first
        val ty = locY + delta.second
        val block = getBlock(tx, ty)
        val id = block?.def?.id
        val enemy = id?.let { enemyDef(it) }
        val canBomb = block != null && block.def!!.cls.startsWith("enemy") && enemy != null && !(enemy.name.isNotEmpty() && enemy.notBomb == true) && !(enemy.notBomb == true)
        if (canBomb) {
            playSound("炸弹")
            items["bomb"] = itemCount("bomb") - 1
            removeBlock(tx, ty)
            drawTip("炸弹使用成功", "bomb")
        } else {
            playSound("操作失败")
            items["bomb"] = itemCount("bomb") - 1
            drawTip("当前无法使用炸弹", "bomb")
        }
    }

    // ============================ save / load ============================

    fun saveDir(): String = assets.assetsDir() + "/../saves"

    fun openSavePanel() {
        panel = Panel.SAVE
        panelSelection = 0
        panelData["slots"] = saveSlots()
        playSound("打开界面")
    }

    fun openLoadPanel() {
        panel = Panel.LOAD
        panelSelection = 0
        panelData["slots"] = saveSlots()
        playSound("打开界面")
    }

    private fun saveSlots(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (i in 1..6) {
            val text = Fio.readText("${saveDir()}/slot$i.h5s") ?: ""
            if (text.isNotEmpty()) {
                val line = text.substringBefore('\n')
                out.add(i to line)
            }
        }
        return out
    }

    fun doSave(slot: Int) {
        val sb = StringBuilder()
        sb.appendLine("${data.firstData.title} | ${floorName()} | ${hero["lv"]?.toInt() ?: 1}级")
        sb.appendLine(saveJson())
        Fio.writeText("${saveDir()}/slot$slot.h5s", sb.toString())
        playSound("存档")
        panel = null
        drawTip("存档成功")
    }

    fun doLoad(slot: Int) {
        val text = Fio.readText("${saveDir()}/slot$slot.h5s") ?: return
        val json = text.substringAfter('\n')
        loadJson(json)
        playSound("读档")
        panel = null
    }

    private fun saveJson(): String {
        val heroObj = JsonObject(hero.mapValues { (_, v) -> JsonPrimitive(v) })
        val itemsObj = JsonObject(items.mapValues { (_, v) -> JsonPrimitive(v) })
        val flagsObj = JsonObject(flags.toMap())
        val valuesObj = JsonObject(values.mapValues { (_, v) -> JsonPrimitive(v) })
        val visitedArr = JsonArray(visitedFloors.map { JsonPrimitive(it) })
        val shopsArr = JsonArray(shopVisited.map { JsonPrimitive(it) })
        val enemyDiff = JsonObject(
            enemyData.mapNotNull { (id, e) ->
                val base = data.enemies[id] ?: return@mapNotNull null
                val diff = mutableMapOf<String, JsonElement>()
                if (e.hp != base.hp) diff["hp"] = JsonPrimitive(e.hp)
                if (e.atk != base.atk) diff["atk"] = JsonPrimitive(e.atk)
                if (e.def != base.def) diff["def"] = JsonPrimitive(e.def)
                if (e.money != base.money) diff["money"] = JsonPrimitive(e.money)
                if (e.exp != base.exp) diff["exp"] = JsonPrimitive(e.exp)
                if (diff.isEmpty()) null else id to JsonObject(diff)
            }.toMap()
        )
        val cellsObj = JsonObject(
            data.floorIds.mapNotNull { f ->
                val cells = cellsByFloor[f] ?: return@mapNotNull null
                val floorDef = data.floors[f]
                val dirty = LinkedHashMap<String, JsonElement>()
                for (b in cells.values) {
                    val origDisabled = (floorDef?.events?.get("${b.x},${b.y}") as? JsonObject)
                        ?.let { it["enable"]?.let { e -> e is JsonPrimitive && e.content == "false" } }
                        ?: false
                    if (b.number != originalNumber(f, b.x, b.y) || b.disable != origDisabled) {
                        dirty["${b.x},${b.y}"] = jsonObj("n" to b.number, "d" to b.disable)
                    }
                }
                if (dirty.isEmpty()) null else f to JsonObject(dirty)
            }.toMap()
        )
        val save = jsonObj(
            "floorId" to floorId,
            "hard" to hard,
            "x" to locX,
            "y" to locY,
            "dir" to direction,
            "hero" to heroObj,
            "items" to itemsObj,
            "flags" to flagsObj,
            "values" to valuesObj,
            "visited" to visitedArr,
            "shops" to shopsArr,
            "enemy" to enemyDiff,
            "cells" to cellsObj,
        )
        return save.toString()
    }

    private fun originalNumber(floor: String, x: Int, y: Int): Int {
        val row = data.floors[floor]?.map?.getOrNull(y) ?: return 0
        return row.getOrNull(x) ?: 0
    }

    fun loadJson(json: String) {
        val o = runCatching { Json.parseToJsonElement(json).asObj() }.getOrNull() ?: return
        // reset game state first (no floor change yet)
        startNewGame(o.str("hard") ?: "", null)
        (o.obj("hero") ?: JsonObject(emptyMap())).forEach { (k, v) ->
            (v as? JsonPrimitive)?.doubleOrNull?.let { hero[k] = it }
        }
        (o.obj("items") ?: JsonObject(emptyMap())).forEach { (k, v) ->
            (v as? JsonPrimitive)?.intOrNull?.let { items[k] = it }
        }
        (o.obj("flags") ?: JsonObject(emptyMap())).forEach { (k, v) -> flags[k] = v }
        (o.obj("values") ?: JsonObject(emptyMap())).forEach { (k, v) ->
            (v as? JsonPrimitive)?.doubleOrNull?.let { values[k] = it }
        }
        o.arr("visited")?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let { v -> visitedFloors.add(v) } }
        o.arr("shops")?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let { v -> shopVisited.add(v) } }
        (o.obj("enemy") ?: JsonObject(emptyMap())).forEach { (id, v) ->
            val e = enemyData[id] ?: return@forEach
            val diff = v.asObj() ?: return@forEach
            (diff["hp"] as? JsonPrimitive)?.doubleOrNull?.let { e.hp = it }
            (diff["atk"] as? JsonPrimitive)?.doubleOrNull?.let { e.atk = it }
            (diff["def"] as? JsonPrimitive)?.doubleOrNull?.let { e.def = it }
            (diff["money"] as? JsonPrimitive)?.doubleOrNull?.let { e.money = it }
            (diff["exp"] as? JsonPrimitive)?.doubleOrNull?.let { e.exp = it }
        }
        // cells: rebuild the full original floor, then apply the saved changes
        cellsByFloor.clear()
        floorSeen.clear()
        floorId = ""
        (o.obj("cells") ?: JsonObject(emptyMap())).forEach { (f, v) ->
            val cells = cellsOf(f) // full map from the floor data
            (v.asObj() ?: return@forEach).forEach { (xy, info) ->
                val pos = xy.split(",")
                if (pos.size == 2) {
                    val block = initBlock(pos[0].toInt(), pos[1].toInt(), 0, f)
                    val i = info.asObj()
                    val n = i?.int("n") ?: 0
                    block.number = n
                    block.def = if (n == 0) null else data.blocksByNumber[n]?.let { BlockDef(n, it) }
                    block.disable = i?.bool("d") == true
                    cells[xy] = block
                }
            }
        }
        locX = o.int("x", 0)
        locY = o.int("y", 0)
        direction = o.str("dir") ?: "down"
        frames.clear()
        panel = null
        // a loaded game always shows the status bar (startNewGame resets it)
        showStatusBar = true
        changeFloorTo(o.str("floorId") ?: "MT0", null, locX to locY, 0, fromLoad = true)
    }

    // ============================ floor changing ============================

    fun changeFloorFromEvent(cf: ChangeFloorDef) {
        var target = cf.floorId.ifEmpty { floorId }
        val idx = data.floorIds.indexOf(floorId)
        when (target) {
            ":next" -> target = data.floorIds.getOrElse(idx + 1) { floorId }
            ":before" -> target = data.floorIds.getOrElse(idx - 1) { floorId }
            ":now" -> target = floorId
        }
        val stair = cf.stair
        val loc = cf.loc as? JsonArray
        var tx: Int? = null
        var ty: Int? = null
        if (loc != null && loc.size >= 2) {
            tx = loc[0].asDouble()?.toInt()
            ty = loc[1].asDouble()?.toInt()
        }
        val time = cf.time ?: 0.0
        cf.direction?.let { direction = it }
        changeFloorTo(target, stair, if (tx != null && ty != null) tx to ty else null, time.toLong())
    }

    fun changeFloorTo(
        target: String,
        stair: String?,
        loc: Pair<Int, Int>?,
        time: Long,
        fromLoad: Boolean = false,
    ) {
        if (target.isEmpty() || data.floors[target] == null) return
        var tx = loc?.first ?: locX
        var ty = loc?.second ?: locY
        if (stair != null) {
            val floor = data.floors[target]
            when (stair) {
                ":now" -> { tx = locX; ty = locY }
                ":symmetry" -> { tx = (floor?.width ?: 13) - 1 - locX; ty = (floor?.height ?: 13) - 1 - locY }
                else -> {
                    when {
                        stair == "downFloor" && floor?.downFloorXY() != null -> { val p = floor.downFloorXY()!!; tx = p[0]; ty = p[1] }
                        stair == "upFloor" && floor?.upFloorXY() != null -> { val p = floor.upFloorXY()!!; tx = p[0]; ty = p[1] }
                        else -> {
                            val stairs = searchBlock(stair, target)
                            if (stairs.isNotEmpty()) { tx = stairs[0].first; ty = stairs[0].second }
                        }
                    }
                }
            }
        }
        playSound("上下楼")
        floorId = target
        locX = tx
        locY = ty
        if (!fromLoad) visitedFloors.add(target)
        if (!floorSeen.contains(target)) {
            floorSeen.add(target)
            val events = mutableListOf<JsonElement>()
            events.addAll(data.floors[target]?.eachArriveList() ?: emptyList())
            events.addAll(data.floors[target]?.firstArriveList() ?: emptyList())
            if (events.isNotEmpty()) {
                startEvents(events, null, null)
            }
        } else {
            val each = data.floors[target]?.eachArriveList() ?: emptyList()
            if (each.isNotEmpty()) startEvents(each, null, null)
        }
        banner = Banner(data.floors[target]?.name ?: target, 0, 0.0)
        playFloorBgm(target)
    }

    fun playFloorBgm(floor: String) {
        val bgm = data.floors[floor]?.bgm ?: return
        playBgm(bgm)
    }

    fun floorName(): String = data.floors[floorId]?.name ?: ""

    // ============================ audio ============================

    fun playSound(name: String?) {
        if (name == null) return
        var key = name ?: return
        data.soundNameMap[key]?.let { key = it }
        if (key.endsWith(".mp3") || key.endsWith(".wav")) key = key.substringBeforeLast('.')
        audio.playSfx(key)
    }

    fun stopSound() {
        audio.stopAll()
    }

    fun playBgm(name: String?) {
        if (name == null) return
        var key = name ?: return
        data.soundNameMap[key]?.let { key = it }
        if (key.endsWith(".mp3") || key.endsWith(".wav")) key = key.substringBeforeLast('.')
        if (key != bgmKey) {
            bgmKey = key
            audio.playBgm(key)
        }
    }

    fun pauseBgm() {
        audio.playBgm(null)
    }

    fun resumeBgm() {
        bgmKey?.let { audio.playBgm(it) }
    }

    // ============================ tips ============================

    fun drawTip(text: String, iconId: String? = null) {
        tip = Tip(expr.replaceText(text), iconId, 0.0, 1)
    }

    // ============================ win / lose ============================

    fun win(reason: String?, norank: Boolean, noexit: Boolean) {
        frames.clear()
        panel = null
        val reasonText = expr.replaceText(reason ?: "恭喜通关")
        gameOverTitle = reasonText
        gameOverActive = true
        screen = Screen.GAME_OVER
        gameOverTicks = 0
        audio.stopAll()
    }

    fun lose(reason: String? = null) {
        frames.clear()
        panel = null
        hero["hp"] = 0.0
        gameOverTitle = reason ?: "你死了"
        gameOverActive = true
        screen = Screen.GAME_OVER
        gameOverTicks = 0
        audio.stopAll()
    }

    fun restart() {
        gameOverActive = false
        screen = Screen.TITLE
        frames.clear()
        panel = null
        audio.stopAll()
        showTitle()
    }

    // ============================ input ============================

    fun onKeyDown(keycode: Int) {
        when (screen) {
            Screen.TITLE -> onTitleKey(keycode)
            Screen.GAME -> onGameKey(keycode)
            Screen.GAME_OVER -> {
                if (keycode == SDLKeycode.ESCAPE || keycode == SDLKeycode.SPACE || keycode == SDLKeycode.RETURN) {
                    restart()
                }
            }
        }
    }

    fun onKeyUp(keycode: Int) {
        when (keycode) {
            SDLKeycode.UP -> if (heldDir == "up") stopMove()
            SDLKeycode.DOWN -> if (heldDir == "down") stopMove()
            SDLKeycode.LEFT -> if (heldDir == "left") stopMove()
            SDLKeycode.RIGHT -> if (heldDir == "right") stopMove()
        }
    }

    // ============================ mouse ============================

    fun onMouseDown(x: Int, y: Int) {
        if (screen == Screen.TITLE) {
            onTitleMouse(x, y)
            return
        }
        if (screen != Screen.GAME) return
        // toolbar clicks
        if (y >= 427 + 4 && y < 427 + 36 && x >= 178 && x < 178 + 396) {
            val i = (x - 178) / 44
            if (i in 0..8) {
                when (i) {
                    0 -> openBook()
                    1 -> openFly()
                    2 -> openHelp()
                    3 -> openToolbox()
                    4 -> drawTip("虚拟键盘仅移动端可用")
                    5 -> openQuickShop()
                    6 -> openSavePanel()
                    7 -> openLoadPanel()
                    8 -> openSettings()
                }
                return
            }
        }
        // choices / confirm clicks
        when (panel) {
            Panel.CHOICES -> clickChoices(x, y)
            Panel.CONFIRM -> clickConfirm(x, y)
            Panel.BOOK, Panel.FLY, Panel.TOOLBOX, Panel.SAVE, Panel.LOAD -> {
                // simple click to confirm selection
                if (x in 189..589 && y in 43..218) {
                    when (panel) {
                        Panel.BOOK -> closeBook()
                        Panel.FLY -> flyTo(panelSelection)
                        Panel.TOOLBOX -> useToolboxItem(panelSelection)
                        Panel.SAVE -> doSave(panelSelection + 1)
                        Panel.LOAD -> doLoad(panelSelection + 1)
                        else -> Unit
                    }
                }
            }
            else -> Unit
        }
    }

    private fun onTitleMouse(x: Int, y: Int) {
        // the menu box at window (245..395, 261..403)
        if (x in 245..395 && y in 261..403) {
            val row = ((y - 261) / 40).coerceIn(0, 2)
            setFlag("selection", JsonPrimitive(row))
            setFlag("keycode", JsonPrimitive(13))
            resumeTitleWait()
        }
    }

    fun clickChoices(x: Int, y: Int) {
        // choices rows: the panel geometry is computed in the renderer; use a
        // simple approximation: the box is centered, rows at ~(416-height)/2+56+32i
        val n = ((panelData["choices"] as? List<JsonElement>)?.size ?: 0)
        if (n == 0) return
        val height = 32 * (n + 2)
        var bottom = 416 / 2 + height / 2
        if (n % 2 == 0) bottom += 16
        val choiceTop = bottom - height + 56
        val oy = mapOriginY()
        val local = y - oy
        if (local in choiceTop - 20 until choiceTop + 32 * n) {
            val row = (local - (choiceTop - 20)) / 32
            if (row in 0 until n) selectChoice(row)
        }
    }

    fun clickConfirm(x: Int, y: Int) {
        val oy = mapOriginY()
        val local = y - oy
        val height = 50 + ((panelData["text"] as? String ?: "").split('\n').size) * 30 + 35 + 15
        val top = (416 - height) / 2
        val bottom = top + height
        if (local in bottom - 50 until bottom) {
            if (x < mapOriginX() + 208) confirmChoice(0) else confirmChoice(1)
        }
    }

    fun mapOriginX(): Int = 181
    fun mapOriginY(): Int = 11

    fun openQuickShop() {
        if (data.shops.isEmpty()) {
            playSound("操作失败")
            drawTip("本游戏没有快捷商店！")
            return
        }
        openShopAction("keyShop2")
    }

    fun openHelp() {
        panel = Panel.HELP
        panelSelection = 0
        playSound("打开界面")
    }

    private fun onTitleKey(keycode: Int) {
        // panels opened from the title menu (load / save)
        if (panel != null) {
            when (panel) {
                Panel.SAVE, Panel.LOAD -> {
                    when (keycode) {
                        SDLKeycode.UP -> { panelSelection = (panelSelection - 1).coerceIn(0, 5); playSound("光标移动") }
                        SDLKeycode.DOWN -> { panelSelection = (panelSelection + 1).coerceIn(0, 5); playSound("光标移动") }
                        SDLKeycode.SPACE, SDLKeycode.RETURN -> {
                            if (panel == Panel.SAVE) doSave(panelSelection + 1) else doLoad(panelSelection + 1)
                        }
                        SDLKeycode.ESCAPE, SDLKeycode.X -> { panel = null; playSound("取消") }
                    }
                }
                else -> Unit
            }
            return
        }
        when (keycode) {
            SDLKeycode.UP -> {
                setFlag("keycode", JsonPrimitive(38))
                resumeTitleWait()
            }
            SDLKeycode.DOWN -> {
                setFlag("keycode", JsonPrimitive(40))
                resumeTitleWait()
            }
            SDLKeycode.SPACE, SDLKeycode.RETURN, SDLKeycode.C -> {
                setFlag("keycode", JsonPrimitive(13))
                resumeTitleWait()
            }
        }
    }

    private fun resumeTitleWait() {
        if (screen != Screen.TITLE) return
        val wait = waitData
        if (wait == null) {
            runMachine()
            return
        }
        waitData = null
        val keycode = getFlagNum("keycode").toInt()
        // __action_wait_afterGet
        val todo = mutableListOf<JsonElement>()
        var stop = false
        var found = false
        for (one in wait.arr("data")?.toList() ?: emptyList()) {
            val o = one as? JsonObject ?: continue
            if (o.bool("_disabled") == true || stop) continue
            if (o.str("case") == "keyboard") {
                val codes = o.str("keycode")?.split(",") ?: emptyList()
                for (c in codes) {
                    if (keycode.toString() == c.trim()) {
                        found = true
                        o["action"]?.let { todo.addAll(wrapList(it)) }
                        if (o.bool("break") == true) stop = true
                    }
                }
            }
        }
        if (found) {
            insertAction(JsonArray(todo), null, null)
        }
        runMachine()
    }

    fun showTitle() {
        screen = Screen.TITLE
        frames.clear()
        panel = null
        played = false
        pendingIntroStart = false
        titleUiActions.clear()
        // like the engine's resetGame: hero state exists while the title runs
        startNewGame("", null)
        screen = Screen.TITLE
        played = false
        startEvents(data.firstData.startCanvas.let { if (it is JsonArray) it.toList() else emptyList() }, null, null)
        playBgm("title.wav")
    }

    fun runStartText() {
        startNewGame("", null)
        screen = Screen.GAME
        frames.clear()
        panel = null
        val startText = data.firstData.startText
        startEvents(if (startText is JsonArray) startText.toList() else emptyList(), null, null)
    }

    private fun onGameKey(keycode: Int) {
        when (panel) {
            Panel.TEXT -> {
                when (keycode) {
                    SDLKeycode.SPACE, SDLKeycode.RETURN, SDLKeycode.C -> advanceText()
                }
                return
            }
            Panel.CHOICES, Panel.CONFIRM -> {
                when (keycode) {
                    SDLKeycode.UP -> {
                        val n = choiceCount()
                        panelSelection = (panelSelection - 1 + n) % n
                        playSound("光标移动")
                    }
                    SDLKeycode.DOWN -> {
                        val n = choiceCount()
                        panelSelection = (panelSelection + 1) % n
                        playSound("光标移动")
                    }
                    SDLKeycode.LEFT, SDLKeycode.RIGHT -> {
                        if (panel == Panel.CONFIRM) {
                            panelSelection = 1 - panelSelection
                            playSound("光标移动")
                        }
                    }
                    SDLKeycode.SPACE, SDLKeycode.RETURN, SDLKeycode.C -> {
                        if (panel == Panel.CHOICES) selectChoice(panelSelection) else confirmChoice(panelSelection)
                    }
                    SDLKeycode.ESCAPE, SDLKeycode.X -> {
                        if (panel == Panel.CHOICES) {
                            // close the panel (shop exit etc.)
                            panel = null
                            playSound("取消")
                            runMachine()
                        } else {
                            confirmChoice(1)
                        }
                    }
                }
                return
            }
            Panel.BOOK -> {
                when (keycode) {
                    SDLKeycode.UP, SDLKeycode.LEFT -> { panelSelection--; playSound("光标移动") }
                    SDLKeycode.DOWN, SDLKeycode.RIGHT -> { panelSelection++; playSound("光标移动") }
                    SDLKeycode.ESCAPE, SDLKeycode.X, SDLKeycode.SPACE, SDLKeycode.RETURN -> closeBook()
                }
                return
            }
            Panel.FLY -> {
                when (keycode) {
                    SDLKeycode.UP -> { panelSelection--; playSound("光标移动") }
                    SDLKeycode.DOWN -> { panelSelection++; playSound("光标移动") }
                    SDLKeycode.SPACE, SDLKeycode.RETURN -> flyTo(panelSelection)
                    SDLKeycode.ESCAPE, SDLKeycode.X -> { panel = null; playSound("取消") }
                }
                return
            }
            Panel.TOOLBOX -> {
                val n = max(1, toolboxItems().size)
                when (keycode) {
                    SDLKeycode.UP, SDLKeycode.LEFT -> { panelSelection = (panelSelection - 1 + n) % n; playSound("光标移动") }
                    SDLKeycode.DOWN, SDLKeycode.RIGHT -> { panelSelection = (panelSelection + 1) % n; playSound("光标移动") }
                    SDLKeycode.SPACE, SDLKeycode.RETURN -> useToolboxItem(panelSelection)
                    SDLKeycode.ESCAPE, SDLKeycode.X -> { panel = null; playSound("取消") }
                }
                return
            }
            Panel.SAVE, Panel.LOAD -> {
                when (keycode) {
                    SDLKeycode.UP -> { panelSelection = (panelSelection - 1).coerceIn(0, 5); playSound("光标移动") }
                    SDLKeycode.DOWN -> { panelSelection = (panelSelection + 1).coerceIn(0, 5); playSound("光标移动") }
                    SDLKeycode.SPACE, SDLKeycode.RETURN -> {
                        if (panel == Panel.SAVE) doSave(panelSelection + 1) else doLoad(panelSelection + 1)
                    }
                    SDLKeycode.ESCAPE, SDLKeycode.X -> { panel = null; playSound("取消") }
                }
                return
            }
            Panel.INPUT -> {
                when (keycode) {
                    in SDLKeycode.KEY_0_START..SDLKeycode.KEY_0_END -> {
                        panelData["buf"] = (panelData["buf"] as? String ?: "") + (keycode - SDLKeycode.KEY_0_START)
                    }
                    SDLKeycode.BACKSPACE -> {
                        val buf = panelData["buf"] as? String ?: ""
                        if (buf.isNotEmpty()) panelData["buf"] = buf.dropLast(1)
                    }
                    SDLKeycode.RETURN -> submitInput((panelData["buf"] as? String ?: "").toIntOrNull() ?: 0)
                    SDLKeycode.ESCAPE -> submitInput(0)
                }
                return
            }
            Panel.RANK -> {
                when (keycode) {
                    SDLKeycode.UP -> { panelSelection--; playSound("光标移动") }
                    SDLKeycode.DOWN -> { panelSelection++; playSound("光标移动") }
                    SDLKeycode.SPACE, SDLKeycode.RETURN -> selectRank()
                    SDLKeycode.ESCAPE, SDLKeycode.X -> { panel = null; win("", false, true) }
                }
                return
            }
            Panel.SETTINGS -> {
                when (keycode) {
                    SDLKeycode.UP -> { panelSelection--; playSound("光标移动") }
                    SDLKeycode.DOWN -> { panelSelection++; playSound("光标移动") }
                    SDLKeycode.SPACE, SDLKeycode.RETURN -> settingsConfirm()
                    SDLKeycode.ESCAPE, SDLKeycode.X -> { panel = null; playSound("取消") }
                }
                return
            }
            Panel.HELP -> {
                when (keycode) {
                    SDLKeycode.UP -> { panelSelection = (panelSelection + 3) % 4; playSound("光标移动") }
                    SDLKeycode.DOWN -> { panelSelection = (panelSelection + 1) % 4; playSound("光标移动") }
                    SDLKeycode.SPACE, SDLKeycode.RETURN -> {
                        when (panelSelection) {
                            0 -> { panel = null; drawTip("方向键 移动 / 按住连续行走") }
                            1 -> { panel = null; drawTip("X 怪物图鉴  G 楼层传送  T 道具栏") }
                            2 -> { panel = null; drawTip("S 存档  D 读档  V 快捷商店  F 技能") }
                            3 -> { panel = null; playSound("取消") }
                        }
                    }
                    SDLKeycode.ESCAPE, SDLKeycode.X -> { panel = null; playSound("取消") }
                }
                return
            }
            else -> Unit
        }
        // Esc: settings
        if (keycode == SDLKeycode.ESCAPE) {
            if (panel == null && frames.isEmpty() && battle == null && !lockControl) {
                openSettings()
            }
            return
        }
        if (lockControl || panel != null || battle != null || frames.isNotEmpty()) return
        when (keycode) {
            SDLKeycode.UP -> startMove("up")
            SDLKeycode.DOWN -> startMove("down")
            SDLKeycode.LEFT -> startMove("left")
            SDLKeycode.RIGHT -> startMove("right")
            SDLKeycode.X -> openBook()
            SDLKeycode.G -> openFly()
            SDLKeycode.T -> openToolbox()
            SDLKeycode.S -> openSavePanel()
            SDLKeycode.D -> openLoadPanel()
            SDLKeycode.V -> openQuickShop()
            SDLKeycode.F -> {
                if (itemCount("skill1") > 0) {
                    val on = getFlagNum("skill") != 1.0
                    setFlag("skill", JsonPrimitive(if (on) 1 else 0))
                    setFlag("skillName", JsonPrimitive(if (on) "二倍斩" else "无"))
                    playSound("确定")
                }
            }
            in SDLKeycode.KEY_0_START + 1..SDLKeycode.KEY_0_START + 4 -> useToolKey(keycode - SDLKeycode.KEY_0_START)
        }
    }

    private fun choiceCount(): Int {
        return when (panel) {
            Panel.CHOICES -> ((panelData["choices"] as? List<JsonElement>)?.size ?: 1)
            Panel.CONFIRM -> 2
            else -> 1
        }
    }

    private fun useToolKey(n: Int) {
        when (n) {
            1 -> if ("pickaxe" in items) useItem("pickaxe")
            2 -> if ("bomb" in items) useItem("bomb")
            3 -> if ("centerFly" in items) {
                applyItemEffect("centerFly", 1)
                items["centerFly"] = itemCount("centerFly") - 1
                playSound("item.mp3")
                drawTip("获得 小飞羽 等级提升一级 ！", "centerFly")
            }
            4 -> {
                val first = toolboxItems().firstOrNull { it in setOf("icePickaxe", "freezeBadge", "earthquake", "upFly", "downFly", "jumpShoes", "lifeWand", "poisonWine", "weakWine", "curseWine", "superWine") }
                if (first != null) useItem(first)
            }
        }
    }

    fun openSettings() {
        panel = Panel.SETTINGS
        panelSelection = 0
        playSound("打开界面")
    }

    fun settingsConfirm() {
        when (panelSelection) {
            0 -> setFlag("移动音效", JsonPrimitive(!getFlagBool("移动音效", true)))
            1 -> setFlag("显示详细信息", JsonPrimitive(!getFlagBool("显示详细信息")))
            else -> panel = null
        }
        playSound("确定")
    }

    fun openRankPanel(ranks: List<String>) {
        panel = Panel.RANK
        panelSelection = 0
        panelData["ranks"] = ranks
    }

    fun selectRank() {
        if (panel != Panel.RANK) return
        val ranks = panelData["ranks"] as? List<String> ?: return
        val index = panelSelection
        if (index < 0 || index >= ranks.size) return
        val rank = ranks[index]
        setFlag("rankType", JsonPrimitive(rank))
        when (rank) {
            "最高攻击" -> hero["hp"] = hero["atk"] ?: 0.0
            "最高防御" -> hero["hp"] = hero["def"] ?: 0.0
            "最高攻防和" -> {
                val a = hero["atk"] ?: 0.0
                val d = hero["def"] ?: 0.0
                hero["hp"] = (a + d) + (1e12 - 1e6 * (a + d))
            }
            "最高等级" -> hero["hp"] = hero["lv"] ?: 0.0
            "最多金币" -> hero["hp"] = hero["money"] ?: 0.0
            "最多经验" -> hero["hp"] = hero["exp"] ?: 0.0
            else -> Unit
        }
        panel = null
        win("24层结局（$rank）", true, false)
    }

    // ============================ update ============================

    fun update(dt: Long) {
        if (screen == Screen.GAME) {
            audio.update()
            tickTimers(dt)
            updateMovement(dt)
            updateTypewriter(dt)
            updateTip(dt)
            updateBanner(dt)
            updateBattleAnim(dt)
            updateDoorAnims(dt)
            if (battle != null && frames.isEmpty()) {
                // battle resolved inside actions; nothing extra needed
            }
            pendingBattle?.let { p ->
                pendingBattle = null
                p()
            }
        } else if (screen == Screen.GAME_OVER) {
            gameOverTicks += dt.toInt()
        }
    }

    private fun updateMovement(dt: Long) {
        // Accumulate the hold time while the direction is down, mid-step
        // included, so a held key chains steps without an idle frame
        // (startMove resets the timer each key press).
        if (heldDir != null) holdT += dt
        if (heroMoving) {
            val speed = values["moveSpeed"] ?: 100.0
            moveT += dt / speed
            if (moveT >= 1.0) {
                finishStep()
            }
        } else if (heldDir != null && !lockControl && panel == null && battle == null && frames.isEmpty()) {
            // one immediate step happens in startMove; the continuous walk
            // only starts after the key has been held for a while
            if (holdT >= 300.0) {
                tryStep()
            }
        }
    }

    private fun updateTypewriter(dt: Long) {
        if (panel != Panel.TEXT) return
        val auto = panelData["auto"] as? Boolean == true
        val time = (panelData["autoTime"] as? Double) ?: 3000.0
        val current = textLines.getOrNull(textLine) ?: return
        if (textAttribute.time > 0 && typewriterPos < current.length) {
            typewriterT += dt
            val step = max(1.0, textAttribute.time / 20.0)
            if (typewriterT >= step) {
                typewriterT = 0.0
                typewriterPos++
            }
        } else {
            typewriterPos = current.length
        }
        if (auto) {
            panelData["startT"] = (panelData["startT"] as? Double ?: 0.0) + dt
            if ((panelData["startT"] as Double) >= time) {
                advanceText()
            }
        }
    }

    private fun updateTip(dt: Long) {
        val t = tip ?: return
        t.t += dt
        if (t.t < 300) t.stage = 1
        else if (t.t < 4000) t.stage = 2
        else if (t.t < 4400) t.stage = 3
        else tip = null
    }

    private fun updateBanner(dt: Long) {
        val b = banner ?: return
        when (b.phase) {
            0 -> {
                b.t += dt
                if (b.t >= 250) { b.phase = 1; b.t = 0.0 }
            }
            1 -> {
                b.t += dt
                if (b.t >= 120) { b.phase = 2; b.t = 0.0 }
            }
            2 -> {
                b.t += dt
                if (b.t >= 130) banner = null
            }
        }
    }

    private fun updateBattleAnim(dt: Long) {
        val b = battle ?: return
        if (!b.active) return
        b.animT += dt
        val interval = (values["animateSpeed"] ?: 300.0) - 50
        if (b.animT >= interval) {
            b.animT = 0.0
            b.animFrame = (b.animFrame + 1) % 4
        }
    }

    private fun updateDoorAnims(dt: Long) {
        val it = doorAnims.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.t += dt
            d.frame = d.t / 40.0
            if (d.frame >= 4) it.remove()
        }
    }

    fun schedule(ms: Long, cb: () -> Unit) {
        pendingTimer = cb
        pendingTimerAt = nowMs + ms
    }

    var pendingTimer: (() -> Unit)? = null
    var pendingTimerAt = 0L
    var nowMs = 0L

    fun tickTimers(dt: Long) {
        nowMs += dt
        val t = pendingTimer
        if (t != null && nowMs >= pendingTimerAt) {
            pendingTimer = null
            t()
        }
    }
}

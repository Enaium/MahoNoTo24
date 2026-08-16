package cn.enaium.mahonoto

import cn.enaium.mahonoto.Assets
import cn.enaium.mahonoto.GameData
import cn.enaium.mahonoto.Game
import cn.enaium.mahonoto.Renderer
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowFlags

const val SCREEN_WIDTH = 640
const val SCREEN_HEIGHT = 480

fun runGame(assetsDir: String, testMode: Boolean = false, fullTest: Boolean = false) {
    SDL.setMainReady()

    if (testMode) {
        SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
        SDL.setHint("SDL_RENDER_DRIVER", "software")
    }

    if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.AUDIO or SDLInitFlags.EVENTS)) {
        error("SDL_Init failed: ${SDL.error()}")
    }

    val window = SDL.createWindow(
        title = "24层魔塔",
        width = SCREEN_WIDTH,
        height = SCREEN_HEIGHT,
    )
    val renderer = SDL.createRenderer(window)

    // ---- load the h5mota game data + resources ----
    val data = GameData(assetsDir)
    data.load()

    val assets = Assets(assetsDir)
    assets.setRenderer(renderer)

    val audio = Audio(assetsDir)
    audio.load()
    audio.initStreams()

    val text = TextRenderer(renderer)
    text.load("$assetsDir/fonts")

    val game = Game(data, assets, audio, text)
    val render = Renderer(game, assets, text, renderer)
    game.showTitle()

    val lastTick = longArrayOf(SDL.getTicks().toLong())
    var running = true

    val test = if (testMode && !fullTest) TestScript(game) else null
    val playthrough = if (testMode && fullTest) PlaythroughTest(game) else null

    while (running) {
        val now = SDL.getTicks().toLong()
        val dt = (now - lastTick[0]).toInt()
        lastTick[0] = now

        while (true) {
            val event = SDL.pollEvent() ?: break
            when (event) {
                is SDLEvent.Quit -> running = false
                is SDLEvent.Window ->
                    if (event.type == cn.enaium.sdl.SDLWindowEventType.CLOSE_REQUESTED) running = false
                is SDLEvent.Key -> {
                    if (event.down) {
                        if (!event.repeat) game.onKeyDown(event.keycode)
                    } else {
                        game.onKeyUp(event.keycode)
                    }
                }
                is SDLEvent.MouseButton -> {
                    if (event.down) game.onMouseDown(event.x.toInt(), event.y.toInt())
                }
                else -> Unit
            }
        }

        game.update(dt.toLong())
        render.update(dt.toLong())

        renderer.drawColor = SDLColor(0, 0, 0)
        renderer.clear()

        render.render()

        renderer.present()
        SDL.delay(16)

        if (test != null) {
            if (test.takeShot()) {
                val surf = renderer.renderReadPixels(null)
                if (surf != null) {
                    val name = "shot_${test.shotCount()}"
                    surf.saveBMP("$assetsDir/../shots/$name.bmp")
                    println("saved $name.bmp")
                    surf.close()
                }
            }
            if (test.tick(game, dt)) {
                running = false
            }
        }
        if (playthrough != null) {
            if (playthrough.takeShot()) {
                val surf = renderer.renderReadPixels(null)
                if (surf != null) {
                    val name = "full_${playthrough.shotCount()}"
                    surf.saveBMP("$assetsDir/../shots/$name.bmp")
                    println("saved $name.bmp")
                    surf.close()
                }
            }
            if (playthrough.tick(dt)) {
                running = false
            }
        }
    }

    text.close()
    assets.close()
    audio.close()
    renderer.close()
    window.close()
    SDL.quit()
}

/**
 * Full playthrough test driver: gives the hero infinite stats/keys and
 * auto-walks through the floors (BFS to the stairs), advancing dialogues,
 * to verify the game can be cleared (21层结局 and 24层结局).
 * Run with `--test --full`.
 */
class PlaythroughTest(private val game: Game) {
    private var frames = 0
    private var phase = 0
    private var shot = 0
    private var pendingShot = false
    private var path = ArrayDeque<Pair<Int, Int>>()
    private var lastFloor = ""
    private var targetFloor: String? = null
    private var cheatApplied = false
    private var finalFloors = ArrayDeque<String>()
    private var shotsDone = 0

    fun shotCount(): Int = shot

    fun takeShot(): Boolean {
        if (pendingShot) {
            pendingShot = false
            return true
        }
        return false
    }

    private fun screenshot() {
        shot++
        pendingShot = true
    }

    private fun key(k: Int) {
        game.onKeyDown(k)
        game.onKeyUp(k)
    }

    fun tick(dt: Int): Boolean {
        frames++
        when (phase) {
            0 -> { // title -> start
                if (frames == 1) screenshot()
                if (frames > 40) { key(32); phase = 1; frames = 0 }
            }
            7 -> return autoPlay()
            1 -> { // intro confirm -> 取消 (skip)
                if (frames > 40) {
                    key(SDLKeycode.RIGHT)
                    key(32)
                    phase = 2
                    frames = 0
                }
            }
            2 -> { // mode choices -> 极速模式 (instant battles)
                if (frames > 40) {
                    key(SDLKeycode.DOWN)
                    key(32)
                    phase = 3
                    frames = 0
                }
            }
            3 -> { // tip text -> advance; apply cheats
                if (frames == 10) applyCheats()
                if (frames > 40) { key(32); phase = 4; frames = 0 }
            }
            4 -> return autoPlay()
        }
        return false
    }

    private fun applyCheats() {
        game.hero["hp"] = 999999.0
        game.hero["hpmax"] = 999999.0
        game.hero["atk"] = 9999.0
        game.hero["def"] = 9999.0
        game.items["yellowKey"] = 99
        game.items["blueKey"] = 99
        game.items["redKey"] = 99
        game.items["book"] = 1
        game.items["fly"] = 1
        game.items["cross"] = 1
        game.items["skill1"] = 1
        game.items["wand"] = 1
        game.setFlag("战斗动画", kotlinx.serialization.json.JsonPrimitive(false))
        game.setFlag("显示详细信息", kotlinx.serialization.json.JsonPrimitive(true))
        game.setFlag("16", kotlinx.serialization.json.JsonPrimitive(1))
        game.setFlag("22", kotlinx.serialization.json.JsonPrimitive(1))
        game.data.floorIds.forEach { game.visitedFloors.add(it) }
        // take the hidden-floor wands so the MT22 fairy opens the seal
        game.removeBlock(5, 6, "MT23w")
        game.removeBlock(7, 6, "MT23e")
        // enable the MT20 flower door to floor 21 (normally opened by the fairy quest)
        game.showBlock(6, 8, "MT20")
        cheatApplied = true
    }

    /** Is a cell passable for the BFS? (items/enemies/doors/stairs pass) */
    private fun passable(x: Int, y: Int): Boolean {
        val floor = game.data.floors[game.floorId] ?: return false
        if (x < 0 || y < 0 || x >= floor.width || y >= floor.height) return false
        val b = game.getBlock(x, y)
        if (b == null) return true
        val def = b.def ?: return true
        if (def.cls == "items") return true
        if (def.cls.startsWith("enemy")) return true
        if (def.doorInfo != null && def.effectiveTrigger == "openDoor") return true
        if (def.id == "upFloor" || def.id == "downFloor") return true
        if (def.script != null) return true
        return !def.noPass
    }

    private fun computePath(): Boolean {
        val floor = game.data.floors[game.floorId] ?: return false
        if (floor.changeFloor.isEmpty()) return false
        val myIdx = game.data.floorIds.indexOf(game.floorId)
        // prefer the changeFloor cell that moves the hero forward
        val prefer = floor.changeFloor.mapNotNull { (key, cf) ->
            val to = when (cf.floorId) {
                ":next" -> game.data.floorIds.getOrNull(myIdx + 1)
                ":before" -> game.data.floorIds.getOrNull(myIdx - 1)
                else -> cf.floorId
            }
            if (to == null) null else (key to game.data.floorIds.indexOf(to))
        }
        // BFS to every reachable changeFloor cell, keep the best target
        val start = game.locX to game.locY
        val queue = ArrayDeque<Pair<Int, Int>>()
        val prev = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        queue.add(start)
        prev[start] = start
        val reachable = ArrayList<Pair<Int, Int>>()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (floor.changeFloor.containsKey("${cur.first},${cur.second}")) {
                // stairs are dead ends: record but do not expand through them
                reachable.add(cur)
                continue
            }
            for (d in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                val nx = cur.first + d.first
                val ny = cur.second + d.second
                val np = nx to ny
                if (np !in prev && passable(nx, ny)) {
                    prev[np] = cur
                    queue.add(np)
                }
            }
        }
        if (reachable.isEmpty()) return false
        var target: Pair<Int, Int>? = null
        var bestIdx = -1
        for (r in reachable) {
            val idx = prefer.firstOrNull { it.first == "${r.first},${r.second}" }?.second ?: myIdx
            if (idx > bestIdx) {
                bestIdx = idx
                target = r
            }
        }
        if (target == null) return false
        println("PT BFS floor=" + game.floorId + " hero=" + game.locX + "," + game.locY + " target=" + target)
        // reconstruct the path: from the cell next to the hero up to the target
        val rev = ArrayDeque<Pair<Int, Int>>()
        var cur: Pair<Int, Int>? = target
        while (cur != null && cur != start) {
            rev.addLast(cur)
            cur = prev[cur]
        }
        path = ArrayDeque()
        while (rev.isNotEmpty()) path.addLast(rev.removeLast())
        return path.isNotEmpty()
    }

    private var holdKey: Int? = null

    private var lastLog = ""
    private fun autoPlay(): Boolean {
        val log = game.floorId + "@" + game.locX + "," + game.locY + " p=" + game.panel + " lock=" + game.lockControl + " ph=" + phase + " t=" + frames + " path=" + path.take(6) + " held=" + holdKey
        if (log != lastLog) { lastLog = log; println("PT " + log) }
        // win detection
        if (game.screen == Game.Screen.GAME_OVER) {
            println("PLAYTHROUGH WIN: " + (game.gameOverTitle ?: "?"))
            screenshot()
            return true
        }
        // floor change: recompute the path
        if (lastFloor != game.floorId) {
            println("PT FLOOR " + lastFloor + " -> " + game.floorId + " path=" + path.size + " hero=" + game.locX + "," + game.locY)
            lastFloor = game.floorId
            path.clear()
            releaseHold()
        }
        // dialogue/panel advancing
        when (game.panel) {
            Game.Panel.TEXT, Game.Panel.CHOICES, Game.Panel.CONFIRM -> {
                if (frames % 30 == 0) key(32)
                return false
            }
            Game.Panel.BOOK -> { key(SDLKeycode.X); return false }
            Game.Panel.FLY -> { key(SDLKeycode.G); return false }
            Game.Panel.TOOLBOX -> { key(SDLKeycode.T); return false }
            Game.Panel.SAVE, Game.Panel.LOAD -> { key(SDLKeycode.ESCAPE); return false }
            Game.Panel.SETTINGS, Game.Panel.HELP -> { key(SDLKeycode.ESCAPE); return false }
            else -> Unit
        }
        if (game.lockControl || game.battle != null || game.heroMoving) return false
        // special floors: the hidden quest
        if (game.floorId == "MT20") {
            // walk to the flower door at (6,8) -> MT21
            if (game.locX != 6 || game.locY != 8) {
                bfsTo(6, 8)
            }
        }
        if (game.floorId == "MT21") {
            if (game.getBlock(6, 2) == null && game.cellsOf("MT21")["6,2"] == null) {
                // the vampire is dead: fly to MT22 for the hidden quest
                openFlyTo("MT22")
                return false
            }
        }
        if (game.floorId == "MT22" || game.floorId == "MT_1") {
            // fly to the final arena
            openFlyTo("MT_1")
            return false
        }
        // the normal walk
        if (path.isEmpty()) {
            val floor = game.data.floors[game.floorId] ?: return false
            val cf = floor.changeFloorAt(game.locX, game.locY)
            if (cf != null) {
                // the hero is standing on the stairs: trigger the change directly
                game.changeFloorFromEvent(cf)
                return false
            }
            computePath()
            return false
        }
        val next = path.first()
        if (game.locX == next.first && game.locY == next.second) {
            path.removeFirst()
            releaseHold()
            return false
        }
        // press the direction toward the next cell (re-press every few frames
        // so battles/doors that clear the held direction don't stall the walk)
        val k = directionKey(next)
        if (holdKey != k || frames % 5 == 0) {
            releaseHold()
            holdKey = k
            game.onKeyDown(k)
        }
        return false
    }

    /** BFS path from the hero to (tx, ty). */
    private fun bfsTo(tx: Int, ty: Int) {
        val start = game.locX to game.locY
        if (start == (tx to ty)) { path.clear(); return }
        val queue = ArrayDeque<Pair<Int, Int>>()
        val prev = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        queue.add(start)
        prev[start] = start
        var found = false
        while (queue.isNotEmpty() && !found) {
            val cur = queue.removeFirst()
            for (d in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                val np = (cur.first + d.first) to (cur.second + d.second)
                if (np !in prev && passable(np.first, np.second)) {
                    prev[np] = cur
                    if (np == (tx to ty)) { found = true; break }
                    queue.add(np)
                }
            }
        }
        if (!found) return
        val rev = ArrayDeque<Pair<Int, Int>>()
        var cur: Pair<Int, Int>? = tx to ty
        while (cur != null && cur != start) {
            rev.addLast(cur)
            cur = prev[cur]
        }
        path = ArrayDeque()
        while (rev.isNotEmpty()) path.addLast(rev.removeLast())
    }

    fun tipTextForTest(): String? = game.tip?.text

    private fun releaseHold() {
        holdKey?.let { game.onKeyUp(it) }
        holdKey = null
    }

    private fun directionKey(target: Pair<Int, Int>): Int {
        val dx = target.first - game.locX
        val dy = target.second - game.locY
        return when {
            dx > 0 -> SDLKeycode.RIGHT
            dx < 0 -> SDLKeycode.LEFT
            dy > 0 -> SDLKeycode.DOWN
            else -> SDLKeycode.UP
        }
    }

    private fun openFlyTo(floorId: String) {
        game.openFly()
        val idx = game.data.floorIds.indexOf(floorId)
        if (idx >= 0) game.panelSelection = idx
        key(32)
    }

    private fun nextQuestFloor(): String {
        // MT22 (fairy opens MT_1) -> MT_1
        return when (game.floorId) {
            "MT22" -> "MT_1"
            else -> "MT22"
        }
    }
}

/**
 * Headless test driver: scripted key presses + screenshot dumps to BMPs.
 * Drives the game: title -> intro -> mode choice -> prologue dialogue ->
 * MT0 -> MT1 items/battle.
 */
class TestScript(private val game: Game) {
    private var state = 0
    private var frames = 0
    private var shot = 0
    private var pendingShot = false

    fun shotCount(): Int = shot

    fun takeShot(): Boolean {
        if (pendingShot) {
            pendingShot = false
            return true
        }
        return false
    }

    private fun screenshot() {
        shot++
        pendingShot = true
    }

    private fun key(k: Int) {
        game.onKeyDown(k)
        game.onKeyUp(k)
    }

    fun tick(game: Game, dt: Int): Boolean {
        frames++
        when (state) {
            0 -> { // title screen: test UP/DOWN selection
                if (frames == 1) screenshot()
                if (frames == 30) key(SDLKeycode.DOWN)
                if (frames == 50) key(SDLKeycode.DOWN)
                if (frames == 70) screenshot() // selection should be at 读取存档
                if (frames == 90) key(SDLKeycode.UP)
                if (frames == 110) key(SDLKeycode.UP) // selection back to 开始游戏
                if (frames == 130) screenshot()
                if (frames > 150) {
                    key(32) // 开始游戏
                    state = 1
                    frames = 0
                }
            }
            1 -> { // intro confirm 是否观看片头: select 取消 (skip intro)
                if (frames == 10) screenshot()
                if (frames > 40) {
                    key(SDLKeycode.RIGHT) // 取消
                    key(32)
                    state = 2
                    frames = 0
                }
            }
            2 -> { // mode choices 经典模式/极速模式
                if (frames == 10) screenshot()
                if (frames > 40) {
                    key(32) // 经典模式
                    state = 3
                    frames = 0
                }
            }
            3 -> { // post-choice tip text
                if (frames == 10) screenshot()
                if (frames > 40) {
                    key(32)
                    state = 4
                    frames = 0
                }
            }
            4 -> { // walk up through MT0 to the stairs (fairy pre-moved to 5,9)
                if (frames == 30) key(SDLKeycode.UP)
                if (frames == 110) key(SDLKeycode.UP)
                if (frames == 190) key(SDLKeycode.UP)
                if (frames == 270) key(SDLKeycode.UP)
                if (frames == 350) key(SDLKeycode.UP)
                if (frames == 430) key(SDLKeycode.UP)
                if (frames == 510) key(SDLKeycode.UP)
                if (frames == 590) key(SDLKeycode.UP)
                if (frames == 670) key(SDLKeycode.UP) // (6,1) stairs -> MT1
                if (frames == 700) screenshot()
                if (frames > 780) {
                    state = 5
                    frames = 0
                }
            }
            5 -> { // MT1: hold-direction legs to the green slime (6,1)
                // hold up: opens the red door, reaches (6,8) (wall above)
                // hold right: to (11,8); hold up: to (11,1); hold left: to the slime
                val legs = listOf(
                    30 to SDLKeycode.UP,
                    210 to SDLKeycode.RIGHT,
                    390 to SDLKeycode.UP,
                    570 to SDLKeycode.LEFT,
                )
                for ((t, k) in legs) {
                    if (frames == t) game.onKeyDown(k)
                    if (frames == t + 60) game.onKeyUp(k)
                }
                if (frames == 700) screenshot() // battle panel
                if (frames > 800) {
                    state = 6
                    frames = 0
                }
            }
            6 -> { // classic animated battle (green slime: ~6 turns)
                if (frames == 150) screenshot() // battle in progress
                if (frames == 500) screenshot() // battle over
                if (frames > 700) {
                    state = 7
                    frames = 0
                }
            }
            7 -> {
                if (frames == 20) screenshot() // after battle
                if (frames > 100) return true
            }
        }
        return false
    }
}

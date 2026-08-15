package cn.enaium.mahonoto

import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowFlags

const val SCREEN_WIDTH = 570
const val SCREEN_HEIGHT = 410
const val GRID_X = 207
const val GRID_Y = 47
const val CELL = 32

fun runGame(assetsDir: String, testMode: Boolean = false) {
    SDL.setMainReady()

    if (testMode) {
        SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
        SDL.setHint("SDL_RENDER_DRIVER", "software")
    }

    if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.AUDIO or SDLInitFlags.EVENTS)) {
        error("SDL_Init failed: ${SDL.error()}")
    }

    val window = SDL.createWindow(
        title = "魔塔 - sdl-kmp port",
        width = SCREEN_WIDTH,
        height = SCREEN_HEIGHT,
    )
    val renderer = SDL.createRenderer(window)

    val assets = Assets(assetsDir)
    assets.scan()
    assets.setRenderer(renderer)

    val audio = Audio(assets)
    audio.load()
    audio.initStreams()

    val text = TextRenderer(assets, renderer)
    text.load("$assetsDir/fonts")

    val game = GameState(assets, text, audio)
    game.init()
    game.screen = GameState.Screen.TITLE

    val titleBg = assets.textureFromFile("$assetsDir/title.png")

    val lastTick = longArrayOf(SDL.getTicks().toLong())
    var running = true

    val test = if (testMode) TestScript(game) else null

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
                is SDLEvent.Key ->
                    if (event.down && !event.repeat) game.onKeyDown(event.keycode)
                else -> Unit
            }
        }

        game.update(dt)

        renderer.drawColor = SDLColor(0, 0, 0)
        renderer.clear()

        renderGame(renderer, assets, text, game, titleBg)

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
    }

    text.close()
    titleBg.close()
    assets.close()
    audio.close()
    renderer.close()
    window.close()
    SDL.quit()
}

// =====================================================================
//  Rendering
// =====================================================================

private fun renderGame(
    renderer: SDLRenderer,
    assets: Assets,
    text: TextRenderer,
    game: GameState,
    titleBg: SDLTexture,
) {
    when (game.screen) {
        GameState.Screen.TITLE -> renderTitle(renderer, game, titleBg)
        GameState.Screen.HELP -> renderHelp(renderer, assets, text, game)
        GameState.Screen.GAME -> renderMap(renderer, assets, text, game)
        GameState.Screen.GAME_OVER -> renderGameOver(renderer, text)
    }
}

private fun renderTitle(renderer: SDLRenderer, game: GameState, titleBg: SDLTexture) {
    // the real title screen (main timeline frame 239): logo + baked menu text
    renderer.drawTexture(titleBg, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
    // selection cursor over the three baked menu rows (y centers 261 / 316 / 370)
    val y = when (game.titleChoice) {
        0 -> 261
        1 -> 316
        2 -> 370
        else -> 261
    }
    drawArrow(renderer, 205, y - 6)
}

private fun renderHelp(renderer: SDLRenderer, assets: Assets, text: TextRenderer, game: GameState) {
    val page = (game.helpPage + 1).coerceIn(1, 4)
    val tex = assets.textureFromFile("${assets.assetsDir()}/sprites/DefineSprite_699/$page.png")
    renderer.drawTexture(tex, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
    text.drawCentered("第 $page / 4 页   空格 下一页", SCREEN_WIDTH / 2, 396, 0.5f, SDLColor(200, 200, 200))
}

private fun renderGameOver(renderer: SDLRenderer, text: TextRenderer) {
    renderer.drawColor = SDLColor(0, 0, 0)
    renderer.fillRect(SDLRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
    text.drawCentered("游 戏 结 束", SCREEN_WIDTH / 2, 150, 1f, SDLColor(255, 255, 255))
    text.drawCentered("感谢游玩 魔塔", SCREEN_WIDTH / 2, 220, 0.5f, SDLColor(200, 200, 200))
    text.drawCentered("按 ESC 返回标题", SCREEN_WIDTH / 2, 280, 0.5f, SDLColor(150, 150, 150))
}

private fun renderMap(renderer: SDLRenderer, assets: Assets, text: TextRenderer, game: GameState) {
    // base display: 13x18 grid (original layout)
    for (i in 0 until 13) {
        for (j in 0 until 18) {
            val name = if (i in 1..11 && j in 6..16) "mt_00" else "mt_29"
            val tex = assets.texture(name) ?: continue
            renderer.drawTexture(tex, 15 + j * 32, 15 + i * 32)
        }
    }

    // line tiles
    val t0 = game.nowLine
    for (i in 0 until 11) {
        for (j in 0 until 11) {
            val t = game.tile(t0, i, j)
            if (t == 0) continue
            if (t == 97 || t == 98 || t == 99) continue // player spot
            val tex = assets.texture(baseName(t)) ?: continue
            val cx = GRID_X + j * 32
            val cy = GRID_Y + i * 32
            if (t == 2 || t == 3 || t == 4 || t == 5) {
                // doors: align the sprite's content center with the cell center
                val (ax, ay) = doorAnchor(assets, baseName(t))
                renderer.drawTexture(tex, cx + 16 - ax, cy + 16 - ay)
            } else {
                renderer.drawTexture(tex, cx, cy)
            }
        }
    }

    // door open animation (the door tile is removed from the map on step)
    if (game.openRoom == 1 && game.doorType != 0) {
        val name = baseName(game.doorType)
        val frames = if (game.doorType == 115) 5 else 10
        val frame = ((game.doorTicks / 70).toInt() + 1).coerceAtMost(frames)
        val tex = assets.texture(name, frame) ?: assets.texture(name)
        if (tex != null) {
            val cx = GRID_X + game.lastY * 32
            val cy = GRID_Y + game.lastX * 32
            if (game.doorType == 2 || game.doorType == 3 || game.doorType == 4 || game.doorType == 5) {
                val (ax, ay) = doorAnchor(assets, name)
                renderer.drawTexture(tex, cx + 16 - ax, cy + 16 - ay)
            } else {
                renderer.drawTexture(tex, cx, cy)
            }
        }
    }

    // player
    val man = assets.texture("mt_99", 1 + game.playerDir * 17 + game.playerFrame)
    if (man != null) {
        renderer.drawTexture(man, GRID_X + game.nowYid * 32, GRID_Y + game.nowXid * 32)
    }

    // panels
    renderPanel(renderer, assets, text, game)

    // line banner (floor transition fade)
    if (game.fadeDir != 0) {
        renderLineBanner(renderer, text, game)
    }

    // dialogs and popups
    if (game.displayText == 1) renderTextPopup(renderer, assets, text, game)
    if (game.displayKill == 1) renderKillDialog(renderer, assets, text, game)
    if (game.displayBuy == 1) renderBuy(renderer, assets, text, game)
    if (game.displayOther == 1) renderOtherList(renderer, assets, game)
    if (game.displayJump in 1..3) renderJump(renderer, assets, text, game)
    if (game.displayList in 1..2) renderList(renderer, assets, text, game)
    renderSay(renderer, assets, game)
}

/** Content center (frame 1) of a door sprite, used to align doors on their cell. */
private fun doorAnchor(assets: Assets, name: String): Pair<Int, Int> {
    val img = assets.decodePng(name, 1)
    return if (img != null) contentCenter(img) else (16 to 16)
}

private fun baseName(t: Int): String = when {
    t < 10 -> "mt_0$t"
    t == 115 -> "mt_15"
    t == 120 -> "mt_20"
    t == 119 || t == 129 || t == 139 -> "mt_00"
    else -> "mt_$t"
}

/** Samples the background color of a sprite row (local coords). */
private fun sampleRow(assets: Assets, name: String, frame: Int, x: Int, y: Int): SDLColor {
    val img = assets.decodePng(name, frame)
    return if (img != null) {
        val c = img.sampleAverage(x - 4, y, x + 4, y + 1)
        SDLColor(c[0], c[1], c[2])
    } else {
        SDLColor(80, 80, 80)
    }
}

private fun renderPanel(renderer: SDLRenderer, assets: Assets, text: TextRenderer, game: GameState) {
    // life panel on the left side — text at 0.5x (~14px, like the original)
    val lifeTex = assets.texture("mt_other_01") ?: return
    renderer.drawTexture(lifeTex, 30, 30)
    val lifeLabels = arrayOf("等级", "生命", "攻击", "防御", "金币", "经验")
    val lifeValues = arrayOf(game.nowLife.toString(), game.nowHp.toString(), game.nowGong.toString(), game.nowFang.toString(), game.nowMoney.toString(), game.nowMp.toString())
    val lifeYs = intArrayOf(19, 61, 84, 106, 129, 152)
    val lifeHs = intArrayOf(26, 18, 18, 18, 18, 18)
    for (i in lifeLabels.indices) {
        val bg = sampleRow(assets, "mt_other_01", 1, 62, lifeYs[i] + 8)
        renderer.fillRect(SDLRect(34, 30 + lifeYs[i], 116, lifeHs[i]), bg)
        text.draw(lifeLabels[i], 43, 30 + lifeYs[i], 0.5f, SDLColor(0, 0, 0))
        text.draw(lifeValues[i], 140 - text.measure(lifeValues[i], 0.5f), 30 + lifeYs[i], 0.5f, SDLColor(0, 0, 0))
    }
    // keys panel below the life panel
    val keysTex = assets.texture("mt_other_02") ?: return
    renderer.drawTexture(keysTex, 30, 210)
    val keysRows = listOf(
        Triple("黄钥匙", game.nowYellow.toString(), 12),
        Triple("蓝钥匙", game.nowBlue.toString(), 40),
        Triple("红钥匙", game.nowRed.toString(), 79),
    )
    for ((label, value, y) in keysRows) {
        val bg = sampleRow(assets, "mt_other_02", 1, 62, y + 8)
        renderer.fillRect(SDLRect(34, 210 + y, 116, 18), bg)
        text.draw(label, 43, 210 + y, 0.5f, SDLColor(0, 0, 0))
        text.draw(value, 128 - text.measure(value, 0.5f), 210 + y, 0.5f, SDLColor(0, 0, 0))
        text.draw("个", 142, 210 + y, 0.5f, SDLColor(0, 0, 0))
    }
    // floor row
    val floorBg = sampleRow(assets, "mt_other_02", 1, 62, 118)
    renderer.fillRect(SDLRect(34, 210 + 112, 116, 18), floorBg)
    text.draw("第", 43, 210 + 112, 0.5f, SDLColor(0, 0, 0))
    text.draw(game.nowLine.toString(), 118 - text.measure(game.nowLine.toString(), 0.5f), 210 + 112, 0.5f, SDLColor(0, 0, 0))
    text.draw("层", 124, 210 + 112, 0.5f, SDLColor(0, 0, 0))
}

private fun renderLineBanner(renderer: SDLRenderer, text: TextRenderer, game: GameState) {
    val alpha = (game.lineFade.coerceIn(0f, 1f) * 220).toInt()
    renderer.blendMode = SDLBlendMode.BLEND
    renderer.drawColor = SDLColor(0, 0, 0, alpha)
    renderer.fillRect(SDLRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
    renderer.blendMode = SDLBlendMode.NONE
    text.drawCentered(game.lineName(), SCREEN_WIDTH / 2, 192, 1f, SDLColor(255, 255, 255))
}

private fun renderTextPopup(renderer: SDLRenderer, assets: Assets, text: TextRenderer, game: GameState) {
    val box = assets.loadSprite("mt_other_03", 8) ?: return
    // canvas 573x70, content ~6..563 x 1..66, center (284, 33); drawn at (290,150)
    renderer.drawTexture(box.texture, 290 - 284, 150 - 33)
    renderer.drawColor = SDLColor(80, 80, 80)
    renderer.fillRect(SDLRect(290 - 95, 150 - 33 + 22, 190, 26))
    text.drawCentered(game.textMessage, 290, 150 - 33 + 25, 0.5f, SDLColor(255, 255, 255))
}

private fun renderKillDialog(renderer: SDLRenderer, assets: Assets, text: TextRenderer, game: GameState) {
    val box = assets.loadSprite("mt_other_04", 1) ?: return
    // canvas 556x245, content 0..555 x 2..243, center (278, 122); drawn at (290,150)
    val dx = 290 - 278
    val dy = 150 - 122
    renderer.drawTexture(box.texture, dx, dy)
    val bg = SDLColor(96, 96, 96)
    val left = dx + 115
    val right = dx + 340
    val rows = listOf(33, 63, 89)
    for (r in rows) {
        renderer.fillRect(SDLRect(left, dy + r, 170, 17), bg)
        renderer.fillRect(SDLRect(right, dy + r, 150, 17), bg)
    }
    // cover the baked default digits of the 4th row as well
    renderer.fillRect(SDLRect(left, dy + 117, 170, 15), bg)
    renderer.fillRect(SDLRect(right, dy + 117, 150, 15), bg)
    val labels = listOf("生命值", "攻击力", "防御力")
    val values = listOf(game.killLeftHp, game.killLeftGong, game.killLeftFang)
    val rvalues = listOf(game.killRightHp, game.killRightGong, game.killRightFang)
    for (k in 0 until 3) {
        text.draw(labels[k], left, dy + rows[k], 0.5f, SDLColor(255, 255, 255))
        text.draw(values[k].toString(), left + 165 - text.measure(values[k].toString(), 0.5f), dy + rows[k], 0.5f, SDLColor(255, 255, 255))
        text.draw(labels[k], right, dy + rows[k], 0.5f, SDLColor(255, 255, 255))
        text.draw(rvalues[k].toString(), right + 140 - text.measure(rvalues[k].toString(), 0.5f), dy + rows[k], 0.5f, SDLColor(255, 255, 255))
    }
    // monster and player images, centered in the dialog's image slots
    val bossTex = assets.texture("mt_${40 + game.nowBossId}")
    if (bossTex != null) {
        renderer.drawTexture(bossTex, dx + 21, dy + 41, 64, 64)
    }
    val manTex = assets.texture("mt_99", 1)
    if (manTex != null) {
        renderer.drawTexture(manTex, dx + 465, dy + 41, 64, 64)
    }
}

private fun renderBuy(renderer: SDLRenderer, assets: Assets, text: TextRenderer, game: GameState) {
    val frame = game.buyFrame.coerceIn(1, 9)
    val box = assets.loadSprite("mt_buy", frame) ?: return
    // canvas 349x209, content 140..348 x 0..208, center (244, 104); drawn at (380,207)
    val dx = 380 - 244
    val dy = 207 - 104
    renderer.drawTexture(box.texture, dx, dy)
    if (frame in 2..7) {
        drawArrow(renderer, dx + 14, dy + 60 + (game.buyCase - 1) * 28)
    }
}

private fun drawArrow(renderer: SDLRenderer, x: Int, y: Int) {
    renderer.drawColor = SDLColor(255, 0, 0)
    for (i in 0 until 12) {
        renderer.drawLine(x + i, y + i / 2, x + i, y + 12 - i / 2)
    }
}

private fun renderOtherList(renderer: SDLRenderer, assets: Assets, game: GameState) {
    val frame = game.otherFrame.coerceIn(1, 6)
    val box = assets.loadSprite("mt_other_list", frame) ?: return
    // canvas 543x127, content 120..541 x 0..125, center (330, 62); drawn at (360,207)
    val dx = 360 + 120 - 330
    val dy = 207 - 62
    renderer.drawTexture(box.texture, dx, dy)
}

private fun renderJump(renderer: SDLRenderer, assets: Assets, text: TextRenderer, game: GameState) {
    val box = assets.loadSprite("mt_jump", 1) ?: return
    // canvas 577x296, content 300..576 x 0..295, center (438, 147); drawn at (367,207)
    val dx = 367 + 300 - 438
    val dy = 207 - 147
    renderer.drawTexture(box.texture, dx, dy)
    val bg = sampleRow(assets, "mt_jump", 1, 400, 285)
    renderer.fillRect(SDLRect(dx + 10, dy + 10, 260, 250), bg)
    for (i in 0 until 20) {
        val col = i / 7
        val row = i % 7
        val x = dx + 20 + col * 88
        val y = dy + 24 + row * 32
        text.draw("第 ${i + 1} 层", x, y, 0.5f, SDLColor(0, 0, 0))
        if (i + 1 == game.jumpSelection) {
            drawArrow(renderer, x - 16, y + 6)
        }
    }
    text.drawCentered("按 2/8 选择  空格 确认  J 取消", dx + 130, dy + 272, 0.5f, SDLColor(0, 0, 0))
}

private fun renderList(renderer: SDLRenderer, assets: Assets, text: TextRenderer, game: GameState) {
    val box = assets.loadSprite("mt_list", 1) ?: return
    // canvas 356x356 full content, center (178, 178); drawn at (367,207)
    val dx = 367 - 178
    val dy = 207 - 178
    renderer.drawTexture(box.texture, dx, dy)
    val bg = SDLColor(70, 70, 70)
    renderer.fillRect(SDLRect(dx + 8, dy + 8, 340, 340), bg)
    val header = listOf("名称" to 60, "生命" to 130, "攻击" to 185, "防御" to 240, "金·经" to 290)
    for ((h, x) in header) {
        text.draw(h, dx + x, dy + 24, 0.5f, SDLColor(255, 255, 255))
    }
    for ((k, t) in game.monsterList.withIndex()) {
        val b = game.boss[t - 40] ?: continue
        val ry = dy + 58 + k * 38
        val face = assets.texture("mt_$t")
        if (face != null) renderer.drawTexture(face, dx + 24, ry, 32, 32)
        text.draw(b.name, dx + 60, ry + 12, 0.5f, SDLColor(255, 255, 255))
        var hp = b.hp
        when (t) {
            60 -> hp += 100
            52 -> hp += 300
            50 -> hp += game.nowHp / 3
            57 -> hp += game.nowHp / 2
        }
        text.draw(hp.toString(), dx + 130 + 24 - text.measure(hp.toString(), 0.5f), ry + 12, 0.5f, SDLColor(255, 255, 255))
        text.draw(b.gong.toString(), dx + 185 + 24 - text.measure(b.gong.toString(), 0.5f), ry + 12, 0.5f, SDLColor(255, 255, 255))
        text.draw(b.fang.toString(), dx + 240 + 24 - text.measure(b.fang.toString(), 0.5f), ry + 12, 0.5f, SDLColor(255, 255, 255))
        text.draw("${b.money}·${b.exp}", dx + 290, ry + 12, 0.5f, SDLColor(255, 255, 255))
    }
    text.drawCentered("按 L 关闭", dx + 178, dy + 332, 0.5f, SDLColor(200, 200, 200))
}

private fun renderSay(renderer: SDLRenderer, assets: Assets, game: GameState) {
    val idx = game.sayDialog ?: return
    if (idx < 0 || game.displaySay[idx] != 1) return
    val (name, frame) = when (idx) {
        0 -> if (game.sayStage >= 1) "mt_say_01" to 32 else "mt_say_01" to 1
        1 -> if (game.sayStage >= 1) "mt_say_02" to 10 else "mt_say_02" to 1
        2 -> if (game.sayStage >= 1) "mt_say_03" to 10 else "mt_say_03" to 1
        3 -> if (game.sayStage >= 1) "mt_say_04" to 19 else "mt_say_04" to 1
        6 -> "mt_say_06" to 1
        7 -> "mt_say_07" to 1
        8 -> when (game.sayStage) {
            1 -> "mt_say_08" to 8
            2 -> "mt_say_08" to 11
            else -> "mt_say_08" to 1
        }
        else -> return
    }
    val box = assets.loadSprite(name, frame) ?: return
    val img = assets.decodePng(name, frame) ?: return
    // content-centered at (380, 207)
    val (cx, cy) = contentCenter(img)
    renderer.drawTexture(box.texture, 380 - cx, 207 - cy)
}

/** Computes the center of the opaque content bounding box. */
private fun contentCenter(img: PngDecoder.PngImage): Pair<Int, Int> {
    var minX = img.width
    var minY = img.height
    var maxX = -1
    var maxY = -1
    for (y in 0 until img.height) {
        var rowHas = false
        for (x in 0 until img.width) {
            if ((img.rgba[(y * img.width + x) * 4 + 3].toInt() and 0xFF) > 40) {
                rowHas = true
                if (x < minX) minX = x
                if (x > maxX) maxX = x
            }
        }
        if (rowHas) {
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    if (maxX < 0) return img.width / 2 to img.height / 2
    return (minX + maxX) / 2 to (minY + maxY) / 2
}

/**
 * Headless test driver: scripted key presses + screenshot dumps to BMPs.
 * Used to verify the game logic and rendering without a display.
 */
class TestScript(private val game: GameState) {
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

    private fun key(k: Int) = game.onKeyDown(k)

    fun tick(game: GameState, dt: Int): Boolean {
        frames++
        when (state) {
            0 -> { // title
                if (frames == 1) screenshot()
                if (frames > 40) {
                    key(SDLKeycode.SPACE)
                    game.listFlag = 1
                    game.jumpFlag = 1
                    state = 1
                    frames = 0
                }
            }
            1 -> { // walk up to the sister
                if (frames == 20) key(SDLKeycode.UP)
                if (frames == 40) screenshot() // sister dialog
                if (frames > 60) {
                    key(SDLKeycode.SPACE) // keys
                    state = 2
                    frames = 0
                }
            }
            2 -> {
                if (frames == 10) screenshot()
                if (frames > 40) {
                    key(SDLKeycode.SPACE) // sister leaves
                    state = 3
                    frames = 0
                }
            }
            3 -> {
                if (frames == 10) screenshot()
                if (frames > 40) {
                    key(SDLKeycode.UP)
                    state = 4
                    frames = 0
                }
            }
            4 -> { // walk up to the stairs (9 steps, door at (7,5) opens)
                if (frames == 20) key(SDLKeycode.UP)
                if (frames == 80) key(SDLKeycode.UP)
                if (frames == 140) key(SDLKeycode.UP)
                if (frames == 200) key(SDLKeycode.UP)
                if (frames == 260) key(SDLKeycode.UP)
                if (frames == 320) key(SDLKeycode.UP)
                if (frames == 380) key(SDLKeycode.UP)
                if (frames == 440) key(SDLKeycode.UP)
                if (frames == 500) key(SDLKeycode.UP)
                if (frames == 560) screenshot() // line transition or 1F
                if (frames > 620) {
                    state = 5
                    frames = 0
                }
            }
            5 -> { // 1F: grab items left of the arrival (wait out popups)
                if (frames == 40) key(SDLKeycode.LEFT)
                if (frames == 140) key(SDLKeycode.LEFT)
                if (frames == 240) key(SDLKeycode.LEFT)
                if (frames == 260) screenshot() // items/popups
                if (frames > 320) {
                    state = 6
                    frames = 0
                }
            }
            6 -> { // walk to the (0,7) monster: right 1, up 3 (red door), right 5, up 7, left 3
                if (frames == 40) key(SDLKeycode.RIGHT)
                if (frames == 130) key(SDLKeycode.UP)
                if (frames == 220) key(SDLKeycode.UP)
                if (frames == 310) key(SDLKeycode.UP)
                if (frames == 400) key(SDLKeycode.RIGHT)
                if (frames == 490) key(SDLKeycode.RIGHT)
                if (frames == 580) key(SDLKeycode.RIGHT)
                if (frames == 670) key(SDLKeycode.RIGHT)
                if (frames == 760) key(SDLKeycode.RIGHT)
                if (frames == 850) key(SDLKeycode.UP)
                if (frames == 940) key(SDLKeycode.UP)
                if (frames == 1030) key(SDLKeycode.UP)
                if (frames == 1120) key(SDLKeycode.UP)
                if (frames == 1210) key(SDLKeycode.UP)
                if (frames == 1300) key(SDLKeycode.UP)
                if (frames == 1390) key(SDLKeycode.UP)
                if (frames == 1480) key(SDLKeycode.LEFT)
                if (frames == 1570) key(SDLKeycode.LEFT)
                if (frames == 1660) key(SDLKeycode.LEFT)
                if (frames == 1750) key(SDLKeycode.LEFT)
                if (frames == 1840) key(SDLKeycode.LEFT)
                if (frames == 1950) screenshot() // kill dialog mid-battle
                if (frames == 2100) screenshot() // after battle
                if (frames > 2200) {
                    state = 7
                    frames = 0
                }
            }
            7 -> {
                if (frames == 20) screenshot() // reward text
                if (frames > 80) {
                    key(SDLKeycode.L) // monster list on 1F
                    state = 8
                    frames = 0
                }
            }
            8 -> {
                if (frames == 10) screenshot() // list
                if (frames > 80) {
                    key(SDLKeycode.L)
                    state = 9
                    frames = 0
                }
            }
            9 -> {
                if (frames == 10) key(SDLKeycode.J) // jump panel
                if (frames == 20) screenshot()
                if (frames > 80) {
                    key(0x38) // '8': move selection
                    state = 10
                    frames = 0
                }
            }
            10 -> {
                if (frames == 10) screenshot()
                if (frames > 60) {
                    key(SDLKeycode.SPACE) // confirm jump to line 1
                    state = 11
                    frames = 0
                }
            }
            11 -> {
                if (frames == 20) screenshot() // after jump
                if (frames > 80) {
                    key(SDLKeycode.J) // cancel
                    state = 12
                    frames = 0
                }
            }
            12 -> {
                if (frames == 10) screenshot()
                if (frames > 60) return true
            }
        }
        return false
    }
}

/**
 * Headless test driver: scripted key presses + screenshot dumps to BMPs.
 * Used to verify the game logic and rendering without a display.
 */

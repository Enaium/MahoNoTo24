package cn.enaium.mahonoto

import cn.enaium.mahonoto.TextRenderer
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLTexture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min

/** Draws a texture at (x, y) scaled to (w, h). */
private fun SDLRenderer.drawTexture(tex: SDLTexture, x: Int, y: Int, w: Int = 0, h: Int = 0) {
    val dw = if (w == 0) tex.size.x.toInt() else w
    val dh = if (h == 0) tex.size.y.toInt() else h
    renderTexture(tex, dst = SDLFRect(x.toFloat(), y.toFloat(), dw.toFloat(), dh.toFloat()))
}

/** Fills a float rect with the current draw color. */
private fun SDLRenderer.fillRect(rect: SDLFRect) {
    fillRect(cn.enaium.sdl.SDLRect(rect.x.toInt(), rect.y.toInt(), rect.width.toInt(), rect.height.toInt()))
}

/** Fills a float rect with a color. */
private fun SDLRenderer.fillRect(rect: SDLFRect, color: SDLColor) {
    drawColor = color
    fillRect(cn.enaium.sdl.SDLRect(rect.x.toInt(), rect.y.toInt(), rect.width.toInt(), rect.height.toInt()))
}

/**
 * Renders the whole game: title screen, map, status bar, toolbar,
 * dialogs/panels, battles and the game-over screen.
 * Window is 640x480; the game group (554x463) is centered like the engine.
 */
class Renderer(
    val game: Game,
    val assets: Assets,
    val text: TextRenderer,
    private val renderer: SDLRenderer,
) {
    companion object {
        const val W = 640
        const val H = 480
        const val GROUP_X = 43
        const val GROUP_Y = 8
        const val BAR_W = 132
        const val BAR_H = 460
        const val MAP_X = 135
        const val MAP_Y = 0
        const val MAP_ORIGIN_X = 138
        const val MAP_ORIGIN_Y = 3
        const val TOOL_X = 135
        const val TOOL_Y = 419
        const val TOOL_H = 41
        // Dialog panels are centered on the 640px window (center 320):
        // mapX() + 208 is the map area center (389), so a panel origin of
        // 320 - 208 keeps ox + 208 == 320.
        const val PANEL_X = 112
    }

    private val borderColor = intArrayOf(250, 118, 0)
    private var boxAnimateT = 0.0
    private var blinkT = 0.0

    fun update(dt: Long) {
        boxAnimateT += dt
        blinkT += dt
    }

    // ============================ main ============================

    fun render() {
        renderer.drawColor = SDLColor(0, 0, 0)
        renderer.clear()
        when (game.screen) {
            Game.Screen.TITLE -> renderTitle()
            Game.Screen.GAME -> renderGame()
            Game.Screen.GAME_OVER -> renderGameOver()
        }
    }

    // ============================ title ============================

    private fun renderTitle() {
        assets.imageTexture("images", "title.png")?.let {
            renderer.drawTexture(it, 0, 0, W, H)
        }
        // The startCanvas coordinates place the menu box at uievent x 230..380
        // (center 305); shift the whole layer so the menu is centered in the
        // window (center 320). The y stays at the map area origin.
        val ox = 320 - 305
        val oy = GROUP_Y + MAP_ORIGIN_Y
        // previewUI actions
        for (act in game.titleUiActions) {
            val a = act as? JsonObject ?: continue
            when (a.str("type")) {
                "fillRect" -> {
                    val x = (a.num("x") ?: 0.0).toInt()
                    val y = (a.num("y") ?: 0.0).toInt()
                    val w = (a.num("width") ?: 0.0).toInt()
                    val h = (a.num("height") ?: 0.0).toInt()
                    renderer.fillRect(SDLFRect((ox + x).toFloat(), (oy + y).toFloat(), w.toFloat(), h.toFloat()), styleToColor(a.arr("style")))
                }
                "fillBoldText" -> {
                    val xRaw = a.str("x") ?: ""
                    val x = if (xRaw.contains("__PIXELS__")) 320 else (a.num("x")?.toInt() ?: 0)
                    val y = a.num("y")?.toInt() ?: 0
                    val t = game.expr.replaceText(a.str("text") ?: "")
                    val font = a.str("font") ?: ""
                    val size = Regex("(\\d+)px").find(font)?.groupValues?.get(1)?.toInt() ?: 25
                    val color = styleToColor(a.arr("style"))
                    val stroke = styleToColor(a.arr("strokeStyle"), SDLColor(0, 0, 0))
                    drawStroked(t, ox + x, oy + y, size, color, stroke, centered = true)
                }
                else -> Unit
            }
        }
        // selector: sized from the current menu item's text so it centers
        val sel = game.getFlagNum("selection").toInt()
        val items = game.titleUiActions.mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            if (o.str("type") != "fillBoldText") return@mapNotNull null
            val t = game.expr.replaceText(o.str("text") ?: "")
            if (t.isBlank()) return@mapNotNull null
            val size = Regex("(\\d+)px").find(o.str("font") ?: "")?.groupValues?.get(1)?.toInt() ?: 25
            val y = o.num("y")?.toInt() ?: 0
            if (y < 250) return@mapNotNull null // the menu box starts at y=250
            MenuItem(t, o.num("x")?.toInt() ?: 0, y, size)
        }
        val item = items.getOrNull(sel)
        if (item != null) {
            drawMenuSelector(item.text, item.size, ox + item.x, oy + item.y)
        } else {
            drawWindowSelector(ox + 245, oy + 261 + 40 * sel, 120, 40)
        }
        // panels opened from the title menu (load / save)
        if (game.panel != null) {
            when (game.panel) {
                Game.Panel.SAVE -> renderSaveLoadPanel(true)
                Game.Panel.LOAD -> renderSaveLoadPanel(false)
                else -> Unit
            }
        }
    }

    private class MenuItem(val text: String, val x: Int, val y: Int, val size: Int)

    private fun styleToColor(style: JsonArray?, def: SDLColor = SDLColor(255, 255, 255)): SDLColor {
        if (style == null || style.size < 3) return def
        val r = style[0].asDouble()?.toInt() ?: 255
        val g = style[1].asDouble()?.toInt() ?: 255
        val b = style[2].asDouble()?.toInt() ?: 255
        val a = style.getOrNull(3)?.asDouble()?.toFloat() ?: 1f
        return SDLColor(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), (a * 255).toInt())
    }

    // ============================ game ============================

    private fun renderGame() {
        renderStatusBar()
        renderMap()
        renderToolbar()
        renderOverlays()
    }

    private fun mapX(): Int = GROUP_X + MAP_ORIGIN_X
    private fun mapY(): Int = GROUP_Y + MAP_ORIGIN_Y

    private fun renderMap() {
        val ox = mapX()
        val oy = mapY()
        // gameDraw border
        renderer.drawColor = SDLColor(borderColor[0], borderColor[1], borderColor[2])
        renderer.fillRect(SDLFRect((GROUP_X + MAP_X).toFloat(), (GROUP_Y + MAP_Y).toFloat(), 419f, 419f))
        // ground pattern background
        fillGround(ox, oy, 416, 416)
        val floor = game.data.floors[game.floorId] ?: return
        // blocks
        val animFrame = ((boxAnimateT / (game.values["animateSpeed"] ?: 300.0)).toInt() % 4)
        for ((key, b) in game.cellsOf(game.floorId)) {
            if (b.disable) continue
            val def = b.def ?: continue
            val xy = key.split(",")
            val x = xy[0].toInt()
            val y = xy[1].toInt()
            if (def.cls == "autotile") continue
            // decorative tiles (lava/star/water) loop their 4 frames
            val frame = if (def.cls == "animates" && def.id in setOf("star", "lava", "blueLava", "water", "fire")) animFrame else 0
            drawBlockInfo(def, x, y, ox, oy, frame)
        }
        // door opening animations
        for (d in game.doorAnims) {
            if (d.floor != game.floorId) continue
            val block = game.cellsOf(game.floorId)["${d.x},${d.y}"] ?: continue
            val def = block.def ?: continue
            drawBlockInfo(def, d.x, d.y, ox, oy, min(3, d.frame.toInt()))
        }
        // hero
        drawHero(ox, oy)
        // map info layer (显伤): enemy damage + rewards + item effects
        drawMapInfo(ox, oy)
    }

    private fun drawBlockInfo(def: BlockDef, x: Int, y: Int, ox: Int, oy: Int, frame: Int) {
        val data = game.data
        val image = when (def.cls) {
            "animates" -> "animates"
            "terrains" -> "terrains"
            "items" -> "items"
            "enemys" -> "enemys"
            "npcs" -> "npcs"
            "enemy48" -> "enemy48"
            "npc48" -> "npc48"
            else -> return
        }
        val table = data.icons[image] as? JsonObject ?: return
        val posY = (table[def.id] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val height = def.height
        val frames = if (def.cls == "enemys" || def.cls == "npcs") 2 else 4
        val posX = frame.coerceIn(0, frames - 1)
        val px = ox + x * 32
        val py = oy + y * 32
        // bottom 32px
        assets.region("materials", "$image.png", posX * 32, posY * height + height - 32, 32, 32)?.let {
            renderer.drawTexture(it, px, py, 32, 32)
        }
        if (height > 32) {
            assets.region("materials", "$image.png", posX * 32, posY * height, 32, height - 32)?.let {
                renderer.drawTexture(it, px, py + 32 - height, 32, height - 32)
            }
        }
    }

    /** Map info layer (显伤): enemy damage numbers, enemy money/exp rewards and item effects. */
    private fun drawMapInfo(ox: Int, oy: Int) {
        val show = game.itemCount("book") > 0 && game.getFlagBool("显示详细信息")
        val hp = game.hero["hp"] ?: 0.0
        for ((key, b) in game.cellsOf(game.floorId)) {
            if (b.disable || b.def == null) continue
            val def = b.def!!
            val xy = key.split(",")
            val ex = xy[0].toInt()
            val ey = xy[1].toInt()
            when {
                def.cls.startsWith("enemy") -> {
                    if (!show) continue
                    val enemy = game.enemyDef(def.id) ?: continue
                    // rewards: money and exp gained from killing this enemy (numbers only)
                    drawStroked(
                        (enemy.money * (if (game.itemCount("coin") > 0) 2 else 1)).toLong().toString(),
                        ox + ex * 32 + 1, oy + ey * 32 + 8, 11, SDLColor(255, 220, 0), SDLColor(0, 0, 0),
                    )
                    drawStroked(
                        enemy.exp.toLong().toString(),
                        ox + ex * 32 + 1, oy + ey * 32 + 19, 11, SDLColor(255, 255, 255), SDLColor(0, 0, 0),
                    )
                    // predicted battle damage
                    val info = game.getDamageInfo(def.id, null, ex, ey)
                    val (text, color) = if (info == null) {
                        "???" to SDLColor(255, 34, 34)
                    } else {
                        val d = info.damage
                        val c = when {
                            d <= 0 -> SDLColor(17, 255, 17)
                            d < hp / 3 -> SDLColor(255, 255, 255)
                            d < hp * 2 / 3 -> SDLColor(255, 255, 0)
                            d < hp -> SDLColor(255, 153, 51)
                            else -> SDLColor(255, 34, 34)
                        }
                        formatBigNumber(d) to c
                    }
                    drawStroked(text, ox + ex * 32 + 1, oy + ey * 32 + 29, 11, color, SDLColor(0, 0, 0))
                }
                def.cls == "items" -> {
                    // the hp/atk/def gained from picking up this item (numbers only)
                    val tip = (game.data.items[def.id]?.itemEffectTip ?: "").trim()
                    val num = Regex("[0-9]+").find(tip)?.value ?: ""
                    if (num.isNotEmpty()) {
                        drawStroked(num, ox + ex * 32 + 1, oy + ey * 32 + 8, 11, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
                    }
                }
            }
        }
    }

    private fun drawHero(ox: Int, oy: Int) {
        // The hero sheet is 4 rows (down/left/right/up) x 4 columns of
        // 32x32 cells; rows are pitched 33px (a 1px gap between rows).
        val dir = game.direction
        val loc = when (dir) {
            "down" -> 0
            "left" -> 1
            "right" -> 2
            "up" -> 3
            else -> 0
        }
        val frame: Int
        var offX = 0.0
        var offY = 0.0
        if (game.heroMoving) {
            frame = if (game.heroLeg) 1 else 3
            val progress = min(1.0, game.moveT)
            // use the actual step delta (the held direction may have
            // changed mid-step, e.g. turning into a wall while walking)
            val dx = (game.moveToX - game.moveFromX).toDouble()
            val dy = (game.moveToY - game.moveFromY).toDouble()
            offX = dx * progress * 32
            offY = dy * progress * 32
        } else {
            frame = 0
        }
        val sx = frame * 32
        val sy = loc * 33
        val x = ox + game.locX * 32 + offX
        val y = oy + game.locY * 32 + offY
        assets.region("images", "hero.png", sx, sy, 32, 32)?.let {
            renderer.drawTexture(it, x.toInt(), y.toInt(), 32, 32)
        }
    }

    private fun fillGround(x: Int, y: Int, w: Int, h: Int) {
        val tex = assets.region("materials", "ground.png", 0, 0, 32, 32) ?: return
        var gy = y
        while (gy < y + h) {
            var gx = x
            while (gx < x + w) {
                renderer.drawTexture(tex, gx, gy, 32, 32)
                gx += 32
            }
            gy += 32
        }
    }

    // ============================ status bar ============================

    private fun renderStatusBar() {
        val sx = GROUP_X
        val sy = GROUP_Y
        fillGround(sx, sy, BAR_W, BAR_H)
        // border top + left
        renderer.drawColor = SDLColor(borderColor[0], borderColor[1], borderColor[2])
        renderer.fillRect(SDLFRect(sx.toFloat(), sy.toFloat(), 3f, BAR_H.toFloat()))
        renderer.fillRect(SDLFRect(sx.toFloat(), sy.toFloat(), BAR_W.toFloat(), 3f))
        if (!game.showStatusBar) return

        val cx = sx + 3
        val cy = sy + 3

        fun stroke(t: String, x: Int, y: Int, size: Int, color: SDLColor = SDLColor(255, 255, 255)) {
            drawStroked(t, cx + x, cy + y, size, color, SDLColor(0, 0, 0))
        }

        val useCrit = game.getFlagBool("开启暴击")
        // 1. icon + level
        drawIcon(2, cx + 6, cy + 9, 25, 25)
        stroke(formatBigNumber(game.getRealStatus("lv")), 52, 30, 18)
        stroke("级", 100, 30, 18)

        // 2. stats
        val stats = mutableListOf<Pair<String, String>>()
        stats.add("生命" to formatBigNumber(game.getRealStatus("hp")))
        stats.add("攻击" to formatBigNumber(game.getRealStatus("atk")))
        stats.add("防御" to formatBigNumber(game.getRealStatus("def")))
        if (useCrit) stats.add("暴击" to (min(game.getRealStatus("lv") / 2, 100.0).toInt().toString() + "%"))
        stats.add("金币" to formatBigNumber(game.getRealStatus("money")))
        stats.add("经验" to formatBigNumber(game.getRealStatus("exp")))

        var y = 80
        val lineHeight = 30
        for ((label, value) in stats) {
            stroke(label, 6, y, 18)
            stroke(value, 66, y, 18)
            y += lineHeight
        }
        // 3. keys
        val keyY = y + 10
        val keyPad = if (useCrit) 35 else 50
        drawKey(cx, cy, "yellowKey.png", game.itemCount("yellowKey"), keyY, SDLColor(255, 204, 170))
        drawKey(cx, cy, "blueKey.png", game.itemCount("blueKey"), keyY + keyPad, SDLColor(170, 170, 221))
        drawKey(cx, cy, "redKey.png", game.itemCount("redKey"), keyY + keyPad * 2, SDLColor(255, 136, 136))
        // 4. floor name
        stroke(game.floorName(), 30, 410, 18)
    }

    private fun drawKey(cx: Int, cy: Int, img: String, count: Int, y: Int, color: SDLColor) {
        assets.imageTexture("images", img)?.let {
            renderer.drawTexture(it, cx + 10, cy + y, 32, 32)
        }
        drawStroked(setTwoDigits(count), cx + 47, cy + y + 24, 18, color, SDLColor(0, 0, 0))
        drawStroked("个", cx + 92, cy + y + 24, 18, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
    }

    private fun drawIcon(index: Int, x: Int, y: Int, w: Int, h: Int) {
        assets.region("materials", "icons.png", 0, index * 32, 32, 32)?.let {
            renderer.drawTexture(it, x, y, w, h)
        }
    }

    // ============================ toolbar ============================

    private fun renderToolbar() {
        val tx = GROUP_X + TOOL_X
        val ty = GROUP_Y + TOOL_Y
        fillGround(tx, ty, 419, TOOL_H)
        renderer.drawColor = SDLColor(borderColor[0], borderColor[1], borderColor[2])
        renderer.fillRect(SDLFRect(tx.toFloat(), ty.toFloat(), 419f, 3f))
        // tools: book fly help toolbox keyboard shop save load settings
        val toolIcons = intArrayOf(10, 11, 35, 12, 13, 14, 15, 16, 17)
        for ((i, idx) in toolIcons.withIndex()) {
            drawIcon(idx, tx + 3 + i * 44, ty + 4, 32, 32)
        }
        // hard mode label
        if (game.hard.isNotEmpty()) {
            drawStroked(game.hard, tx + 3 + 9 * 44, ty + 10, 16, SDLColor(255, 0, 0), SDLColor(0, 0, 0))
        }
    }

    // ============================ overlays ============================

    private fun renderOverlays() {
        val ox = mapX()
        val oy = mapY()
        // floor banner covers the whole game group
        game.banner?.let { b ->
            val alpha = when (b.phase) {
                0 -> (b.t / 250.0).coerceIn(0.0, 1.0)
                1 -> 1.0
                else -> (1.0 - b.t / 130.0).coerceIn(0.0, 1.0)
            }
            renderer.blendMode = SDLBlendMode.BLEND
            renderer.drawColor = SDLColor(0, 0, 0, (alpha * 255).toInt())
            renderer.fillRect(SDLFRect(GROUP_X.toFloat(), GROUP_Y.toFloat(), 554f, 463f))
            drawStroked(b.title, GROUP_X + 277, GROUP_Y + 220, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true, alpha = alpha.toFloat())
            renderer.blendMode = SDLBlendMode.NONE
        }
        // tip
        game.tip?.let { t ->
            val opacity = when (t.stage) {
                1 -> (t.t / 300.0).coerceIn(0.0, 1.0)
                2 -> 1.0
                else -> (1.0 - (t.t - 4000) / 400.0).coerceIn(0.0, 1.0)
            }
            renderer.blendMode = SDLBlendMode.BLEND
            val width = 26 + text.measure(t.text, 16)
            val drawX = GROUP_X + 5
            val drawY = GROUP_Y + 5
            renderer.drawColor = SDLColor(0, 0, 0, (opacity * 255).toInt())
            renderer.fillRect(SDLFRect(drawX.toFloat(), drawY.toFloat(), (width + 20).toFloat(), 42f))
            if (t.iconId != null) {
                blockIconTexture(t.iconId)?.let {
                    renderer.drawTexture(it, drawX + 10, drawY + 10, 32, 32)
                }
                drawStroked(t.text, drawX + 45, drawY + 33, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0), alpha = opacity.toFloat())
            } else {
                drawStroked(t.text, drawX + 21, drawY + 33, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0), alpha = opacity.toFloat())
            }
            renderer.blendMode = SDLBlendMode.NONE
        }
        // battle
        game.battle?.let { renderBattle(it, ox, oy) }
        // panels
        when (game.panel) {
            Game.Panel.TEXT -> renderTextPanel()
            Game.Panel.CHOICES -> renderChoicesPanel()
            Game.Panel.CONFIRM -> renderConfirmPanel()
            Game.Panel.BOOK -> renderBookPanel()
            Game.Panel.FLY -> renderFlyPanel()
            Game.Panel.TOOLBOX -> renderToolboxPanel()
            Game.Panel.SAVE -> renderSaveLoadPanel(true)
            Game.Panel.LOAD -> renderSaveLoadPanel(false)
            Game.Panel.INPUT -> renderInputPanel()
            Game.Panel.SETTINGS -> renderSettingsPanel()
            Game.Panel.RANK -> renderRankPanel()
            else -> Unit
        }
    }

    private fun blockIconTexture(id: String): SDLTexture? {
        val data = game.data
        val cls = data.clsOf(id) ?: return null
        val image = when (cls) {
            "animates" -> "animates"
            "terrains" -> "terrains"
            "items" -> "items"
            "enemys" -> "enemys"
            "npcs" -> "npcs"
            "enemy48" -> "enemy48"
            "npc48" -> "npc48"
            else -> return null
        }
        val table = data.icons[image] as? JsonObject ?: return null
        val posY = (table[id] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val height = if (cls == "enemy48" || cls == "npc48") 48 else 32
        return assets.region("materials", "$image.png", 0, posY * height, 32, height)
    }

    // ============================ text panel ============================

    private fun renderTextPanel() {
        val ox = mapX()
        val oy = mapY()
        val lines = game.textLines
        val line = lines.getOrNull(game.textLine) ?: return
        val attr = game.textAttribute
        val title = game.textTitle
        val icon = game.textIcon
        // geometry (engine _drawTextBox)
        val left = 7
        val right = 409
        val paddingLeft = if (icon != null) 62 else 25
        val validWidth = (right - left) - paddingLeft - 12
        var contentH = lines.size * attr.lineHeight
        var height = 45 + contentH
        if (title != null) height += attr.titlefont + 5
        if (icon != null) {
            height = max(height, iconHeight(icon) + if (title != null) 50 else 30)
        }
        var top = (416 - height) / 2
        if (attr.position == "down") top = 416 - height - 5 - attr.offset
        if (attr.position == "up") top = 5 + attr.offset
        val bottom = top + height

        drawWindowSkin(ox + left, oy + top, right - left, height)
        val contentLeft = left + paddingLeft
        // icon portrait
        if (icon != null) {
            val iconH = iconHeight(icon)
            val imageTop = top + (if (title != null) 40 else 15)
            strokeRect(ox + left + 15 - 1, oy + imageTop - 1, 34, iconH + 2, SDLColor(221, 221, 221), 2)
            val cls = game.data.clsOf(icon)
            if (cls == "npcs" || cls == "enemys") {
                val frames = if (cls == "enemys" || cls == "npcs") 2 else 4
                val frame = ((boxAnimateT / 250.0).toInt() % frames)
                assets.region("materials", "${sheetName(cls)}.png", frame * 32, iconsRow(icon) * iconH, 32, iconH)?.let {
                    renderer.drawTexture(it, ox + left + 15, oy + imageTop, 32, iconH)
                }
            } else {
                iconTexture(icon)?.let {
                    renderer.drawTexture(it, ox + left + 15, oy + imageTop, 32, iconH)
                }
            }
        }
        // title
        if (title != null) {
            drawStroked(title, ox + left + (if (icon != null) 60 else 15), oy + top + 8 + attr.titlefont, attr.titlefont,
                SDLColor(attr.title[0], attr.title[1], attr.title[2]), SDLColor(0, 0, 0))
        }
        // content lines with typewriter
        var ty = top + (if (title != null) 15 + attr.titlefont + 5 else 15)
        val shown = line.take(game.typewriterPos)
        drawStroked(shown, ox + contentLeft, oy + ty, attr.textfont,
            SDLColor(attr.textColor[0], attr.textColor[1], attr.textColor[2]), SDLColor(0, 0, 0))
        // next cursor (blinking)
        if (((blinkT / 500.0).toInt() % 2) == 0) {
            val cursorX = ox + (left + right) / 2
            val cursorY = oy + bottom - 20
            renderer.drawColor = SDLColor(attr.textColor[0], attr.textColor[1], attr.textColor[2])
            renderer.fillRect(SDLFRect(cursorX.toFloat(), cursorY.toFloat(), 2f, 2f))
            renderer.fillRect(SDLFRect((cursorX + 4).toFloat(), cursorY.toFloat(), 2f, 2f))
            renderer.fillRect(SDLFRect((cursorX + 6).toFloat(), (cursorY + 2).toFloat(), 2f, 2f))
            renderer.fillRect(SDLFRect((cursorX + 8).toFloat(), (cursorY + 4).toFloat(), 2f, 2f))
            renderer.fillRect(SDLFRect((cursorX + 10).toFloat(), (cursorY + 6).toFloat(), 2f, 2f))
        }
    }

    private fun iconHeight(id: String): Int {
        val cls = game.data.clsOf(id)
        return if (cls == "enemy48" || cls == "npc48") 48 else 32
    }

    private fun sheetName(cls: String): String = when (cls) {
        "animates" -> "animates"
        "terrains" -> "terrains"
        "items" -> "items"
        "enemys" -> "enemys"
        "npcs" -> "npcs"
        "enemy48" -> "enemy48"
        "npc48" -> "npc48"
        else -> "items"
    }

    private fun iconsRow(id: String): Int {
        val cls = game.data.clsOf(id) ?: return 0
        val table = game.data.icons[cls] as? JsonObject ?: return 0
        return (table[id] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    }

    private fun iconTexture(id: String): SDLTexture? {
        if (id == "hero") return assets.region("images", "hero.png", 0, 0, 32, 32)
        val cls = game.data.clsOf(id)
        if (cls == null) return null
        return assets.region("materials", "${sheetName(cls)}.png", 0, iconsRow(id) * iconHeight(id), 32, iconHeight(id))
    }

    // ============================ choices / confirm ============================

    private fun renderChoicesPanel() {
        val ox = PANEL_X
        val oy = mapY()
        val titleInfo = game.parseTitle(game.expr.replaceText(game.panelData["text"] as? String ?: ""))
        val choices = game.panelData["choices"] as? List<JsonElement> ?: return
        val width = 246
        var w = width
        for (c in choices) {
            val co = c as? JsonObject ?: continue
            val t = game.expr.replaceText(co.str("text") ?: "")
            var cw = text.measure(t, 17)
            if (co.str("icon") != null) cw += 28
            w = max(w, cw + 30)
        }
        w = min(w, 396)
        val left = (416 - w) / 2
        val contentLeft = left + (if (titleInfo.icon != null) 60 else 15)
        val validWidth = (left + w) - contentLeft - 10
        val titleLines = game.wrapText(titleInfo.content, 15, validWidth)
        val length = choices.size
        var height = 32 * (length + 2)
        var bottom = 416 / 2 + height / 2
        if (length % 2 == 0) bottom += 16
        var choiceTop = bottom - height + 56
        var headHeight = 0
        if (titleInfo.content.isNotEmpty()) {
            if (titleInfo.title != null) headHeight += 25
            headHeight += titleLines.size * 20
            height += headHeight
            if (bottom - height <= 32) {
                val offset = headHeight / 64
                bottom += 32 * offset
                choiceTop += 32 * offset
            }
        }
        val top = bottom - height

        drawWindowSkin(ox + left, oy + top, w, height)
        // title
        if (titleInfo.content.isNotEmpty()) {
            var contentTop = top + 21
            if (titleInfo.title != null) {
                contentTop = top + 41
                drawStroked(titleInfo.title, ox + left + w / 2, oy + top + 27, 19,
                    SDLColor(255, 215, 0), SDLColor(0, 0, 0), centered = true)
            }
            for ((i, line) in titleLines.withIndex()) {
                drawStroked(line, ox + contentLeft, oy + contentTop + i * 20, 15,
                    SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            }
        }
        // choices
        for ((i, c) in choices.withIndex()) {
            val co = c as? JsonObject ?: continue
            val t = game.expr.replaceText(co.str("text") ?: "")
            val need = co.str("need")
            val disabled = need != null && need.isNotEmpty() && !game.expr.evalBool(need)
            val color = if (disabled) SDLColor(153, 153, 153) else SDLColor(255, 255, 255)
            var cx2 = ox + 208 - text.measure(t, 17) / 2
            val icon = co.str("icon")
            if (icon != null) {
                iconTexture(icon)?.let {
                    renderer.drawTexture(it, ox + 208 - text.measure(t, 17) / 2 - 30, oy + choiceTop + 32 * i - 17, 22, 22)
                }
                cx2 += 14
            }
            drawStroked(t, cx2, oy + choiceTop + 32 * i, 17, color, SDLColor(0, 0, 0))
        }
        // selector
        if (choices.isNotEmpty() && game.panelSelection in choices.indices) {
            val co = choices[game.panelSelection] as? JsonObject ?: return
            val t = game.expr.replaceText(co.str("text") ?: "")
            val textCenter = ox + 208 + (if (co.str("icon") != null) 14 else 0)
            drawMenuSelector(t, 17, textCenter, oy + choiceTop + 32 * game.panelSelection)
        }
    }

    private fun renderConfirmPanel() {
        val ox = PANEL_X
        val oy = mapY()
        val t = game.expr.replaceText(game.panelData["text"] as? String ?: "")
        val lines = t.split('\n')
        val width = 246
        val left = (416 - width) / 2
        val height = 50 + lines.size * 30 + 35 + 15
        val top = (416 - height) / 2
        val bottom = top + height
        drawWindowSkin(ox + left, oy + top, width, height)
        for ((i, l) in lines.withIndex()) {
            drawStroked(l, ox + 208, oy + top + 50 + i * 30, 19, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
        }
        drawStroked("确定", ox + 208 - 38, oy + bottom - 35, 17, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
        drawStroked("取消", ox + 208 + 38, oy + bottom - 35, 17, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
        val label = if (game.panelSelection == 0) "确定" else "取消"
        drawMenuSelector(label, 17, ox + 208 + (76 * game.panelSelection - 38), oy + bottom - 35)
    }

    // ============================ book ============================

    private fun renderBookPanel() {
        val ox = mapX()
        val oy = mapY()
        // background
        fillGround(ox, oy, 416, 416)
        renderer.blendMode = SDLBlendMode.BLEND
        renderer.drawColor = SDLColor(0, 0, 0, 153)
        renderer.fillRect(SDLFRect(ox.toFloat(), oy.toFloat(), 416f, 416f))
        renderer.blendMode = SDLBlendMode.NONE

        val floor = game.floorId
        val enemies = LinkedHashMap<String, String>()
        for ((_, b) in game.cellsOf(floor)) {
            if (b.disable) continue
            val def = b.def ?: continue
            if (def.cls == "enemys" || def.cls == "enemy48") enemies[def.id] = def.id
        }
        val enemyList = enemies.values.toList()
        if (enemyList.isEmpty()) {
            drawStroked("本层无怪物", ox + 208, oy + 222, 50, SDLColor(153, 153, 153), SDLColor(0, 0, 0), centered = true)
            drawStroked("返回游戏", ox + 370, oy + 403, 15, SDLColor(221, 221, 221), SDLColor(0, 0, 0), centered = true)
            return
        }
        val total = enemyList.size
        if (game.panelSelection < 0) game.panelSelection = total - 1
        if (game.panelSelection >= total) game.panelSelection = 0
        val perPage = 6
        val start = (game.panelSelection / perPage) * perPage
        val pageEnemies = enemyList.subList(start, min(total, start + perPage))
        val perHeight = (416 - 32 - 12) / perPage
        for ((i, id) in pageEnemies.withIndex()) {
            val top = perHeight * i + 12
            val selected = (start + i) == game.panelSelection
            val enemy = game.enemyDef(id)
            // box
            strokeRect(ox + 22, oy + top + (perHeight - 42) / 2, 42, 42, SDLColor(221, 221, 221), 2)
            val cls = game.data.clsOf(id)
            val sheet = sheetName(cls ?: "enemys")
            val row = iconsRow(id)
            val h = if (cls == "enemy48") 48 else 32
            assets.region("materials", "$sheet.png", 0, row * h, 32, h)?.let {
                renderer.drawTexture(it, ox + 27, oy + top + (perHeight - 42) / 2 + 5, 32, 32)
            }
            // name
            drawStroked(enemy.name.ifEmpty { id }, ox + 64, oy + top + 10, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            // stats
            val info = game.getEnemyInfo(id, null, 0, 0)
            drawStroked("生命 ${formatBigNumber(info.hp)}", ox + 160, oy + top + 10, 14, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            drawStroked("攻击 ${formatBigNumber(info.atk)}", ox + 250, oy + top + 10, 14, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            drawStroked("防御 ${formatBigNumber(info.def)}", ox + 330, oy + top + 10, 14, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            drawStroked("金币 ${formatBigNumber(info.money)}", ox + 160, oy + top + 34, 14, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            drawStroked("经验 ${formatBigNumber(info.exp)}", ox + 250, oy + top + 34, 14, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            if (selected) {
                strokeRoundRect(ox + 10, oy + top + 1, 416 - 20, perHeight, 10, SDLColor(255, 215, 0))
            }
        }
        val page = start / perPage
        val totalPage = (total + perPage - 1) / perPage
        drawStroked("${page + 1} / $totalPage", ox + 208, oy + 395, 14, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
        drawStroked("返回游戏", ox + 370, oy + 403, 15, SDLColor(221, 221, 221), SDLColor(0, 0, 0), centered = true)
    }

    // ============================ fly ============================

    private fun renderFlyPanel() {
        val ox = PANEL_X
        val oy = mapY()
        val floorIds = game.data.floorIds
        val left = 48
        val width = 320
        val rows = 5
        val cols = 2
        val height = 32 * (rows + 2)
        val bottom = 416 / 2 + height / 2
        val top = bottom - height
        drawWindowSkin(ox + left, oy + top, width, height)
        drawStroked("风之罗盘", ox + 208, oy + top + 27, 19, SDLColor(255, 215, 0), SDLColor(0, 0, 0), centered = true)
        val perPage = rows * cols
        val page = game.panelSelection / perPage
        val start = page * perPage
        for (i in start until min(floorIds.size, start + perPage)) {
            val id = floorIds[i]
            val col = (i - start) % cols
            val row = (i - start) / cols
            val name = game.data.floors[id]?.name ?: id
            val visited = game.hasVisitedFloor(id)
            val current = id == game.floorId
            val x = left + 40 + col * 130
            val y = top + 56 + row * 32
            val color = when {
                current -> SDLColor(255, 215, 0)
                visited -> SDLColor(255, 255, 255)
                else -> SDLColor(110, 110, 110)
            }
            val t = "${i + 1}  $name"
            // center the text in the grid cell
            val cx = x + 65
            drawStroked(t, ox + cx, oy + y, 16, color, SDLColor(0, 0, 0), centered = true)
            if (i == game.panelSelection) {
                drawMenuSelector(t, 16, ox + cx, oy + y)
            }
        }
        drawStroked("G/X 关闭", ox + 208, oy + bottom - 18, 14, SDLColor(221, 221, 221), SDLColor(0, 0, 0), centered = true)
    }

    // ============================ toolbox ============================

    private fun renderToolboxPanel() {
        val ox = PANEL_X
        val oy = mapY()
        val ids = game.toolboxItems()
        val left = 60
        val width = 296
        val shown = ids.subList(0, min(ids.size, 12))
        val height = 32 * (shown.size + 2)
        val top = (416 - height) / 2
        drawWindowSkin(ox + left, oy + top, width, height)
        drawStroked("道具栏", ox + 208, oy + top + 27, 19, SDLColor(255, 215, 0), SDLColor(0, 0, 0), centered = true)
        for ((i, id) in shown.withIndex()) {
            val item = game.data.items[id]
            val name = item?.name ?: id
            val count = game.itemCount(id)
            val y = top + 56 + i * 32
            iconTexture(id)?.let {
                renderer.drawTexture(it, ox + left + 20, oy + y, 26, 26)
            }
            drawStroked(name, ox + left + 60, oy + y + 6, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            drawStroked("x$count", ox + left + width - 40, oy + y + 6, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            if (i == game.panelSelection) {
                // the row content spans from the item icon to the end of the
                // count text, so the box width cannot come from a single
                // string: measure the span (icon x .. xN end) + 20, x - 10
                val t = "$name x$count"
                val spanLeft = ox + left + 20
                val spanRight = ox + left + width - 40 + text.measure("x$count", 16)
                val w = spanRight - spanLeft + 20
                val h = text.measureHeight(t, 16) + 10
                drawWindowSelector(spanLeft - 10, oy + y + 6 - h / 2, w, h)
            }
        }
        drawStroked("X 关闭", ox + 208, oy + top + height - 18, 14, SDLColor(221, 221, 221), SDLColor(0, 0, 0), centered = true)
    }

    // ============================ save / load ============================

    private fun renderSaveLoadPanel(isSave: Boolean) {
        val ox = PANEL_X
        val oy = mapY()
        val slots = game.panelData["slots"] as? List<Pair<Int, String>> ?: emptyList()
        val left = 80
        val width = 256
        val height = 32 * 8
        val top = (416 - height) / 2
        drawWindowSkin(ox + left, oy + top, width, height)
        drawStroked(if (isSave) "存 档" else "读 档", ox + 208, oy + top + 27, 19, SDLColor(255, 215, 0), SDLColor(0, 0, 0), centered = true)
        for (i in 0 until 6) {
            val y = top + 52 + i * 32
            val info = slots.firstOrNull { it.first == i + 1 }?.second
            val label = if (info != null) info else "（空）"
            val color = if (info != null) SDLColor(255, 255, 255) else SDLColor(130, 130, 130)
            val t = "${i + 1}. $label"
            drawStroked(t, ox + 208, oy + y + 6, 16, color, SDLColor(0, 0, 0), centered = true)
            if (i == game.panelSelection) {
                drawMenuSelector(t, 16, ox + 208, oy + y + 6)
            }
        }
        drawStroked("X 关闭", ox + 208, oy + top + height - 18, 14, SDLColor(221, 221, 221), SDLColor(0, 0, 0), centered = true)
    }

    // ============================ input ============================

    private fun renderInputPanel() {
        val ox = PANEL_X
        val oy = mapY()
        val hint = game.panelData["hint"] as? String ?: ""
        val buf = game.panelData["buf"] as? String ?: ""
        val left = 70
        val width = 276
        val height = 130
        val top = (416 - height) / 2
        drawWindowSkin(ox + left, oy + top, width, height)
        drawStroked(hint, ox + left + 15, oy + top + 20, 15, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
        renderer.drawColor = SDLColor(30, 30, 30)
        renderer.fillRect(SDLFRect((ox + left + 15).toFloat(), (oy + top + 55).toFloat(), (width - 30).toFloat(), 28f))
        drawStroked(buf, ox + left + 20, oy + top + 58, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
        drawStroked("确定", ox + 208 - 38, oy + top + 102, 15, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
        drawStroked("取消", ox + 208 + 38, oy + top + 102, 15, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
    }

    // ============================ settings ============================

    private fun renderSettingsPanel() {
        val ox = PANEL_X
        val oy = mapY()
        val options = listOf(
            "移动音效 [${if (game.getFlagBool("移动音效", true)) "开" else "关"}]",
            "详细显伤 [${if (game.getFlagBool("显示详细信息")) "开" else "关"}]",
            "返回游戏",
        )
        val left = 100
        val width = 216
        val height = 32 * (options.size + 2)
        val top = (416 - height) / 2
        drawWindowSkin(ox + left, oy + top, width, height)
        for ((i, o) in options.withIndex()) {
            drawStroked(o, ox + 208, oy + top + 56 + i * 32, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
            if (i == game.panelSelection) {
                drawMenuSelector(o, 16, ox + 208, oy + top + 56 + i * 32)
            }
        }
    }

    // ============================ help ============================

    private fun renderHelpPanel() {
        val ox = PANEL_X
        val oy = mapY()
        val options = listOf(
            "方向键：移动（按住连续行走）",
            "X 怪物图鉴 / G 楼层传送 / T 道具栏",
            "S 存档 / D 读档 / V 快捷商店 / F 技能",
            "返回游戏",
        )
        val left = 70
        val width = 276
        val height = 32 * (options.size + 2)
        val top = (416 - height) / 2
        drawWindowSkin(ox + left, oy + top, width, height)
        drawStroked("帮 助", ox + 208, oy + top + 27, 19, SDLColor(255, 215, 0), SDLColor(0, 0, 0), centered = true)
        for ((i, o) in options.withIndex()) {
            drawStroked(o, ox + 208, oy + top + 56 + i * 32, 14, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
            if (i == game.panelSelection) {
                drawMenuSelector(o, 14, ox + 208, oy + top + 56 + i * 32)
            }
        }
    }

    // ============================ rank ============================

    private fun renderRankPanel() {
        val ox = PANEL_X
        val oy = mapY()
        val ranks = game.panelData["ranks"] as? List<String> ?: emptyList()
        val left = 80
        val width = 256
        val shown = ranks.subList(0, min(ranks.size, 12))
        val height = 32 * (shown.size + 2)
        val top = (416 - height) / 2
        drawWindowSkin(ox + left, oy + top, width, height)
        drawStroked("选择要提交的榜单！", ox + 208, oy + top + 27, 16, SDLColor(255, 215, 0), SDLColor(0, 0, 0), centered = true)
        for ((i, r) in shown.withIndex()) {
            drawStroked(r, ox + 208, oy + top + 56 + i * 32, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
            if (i == game.panelSelection) {
                drawMenuSelector(r, 16, ox + 208, oy + top + 56 + i * 32)
            }
        }
    }

    // ============================ battle ============================

    private fun renderBattle(b: Game.BattleInfo, ox: Int, oy: Int) {
        val bx = 8
        val by = 32
        val w = 400
        val h = 175
        // background: gray + ground pattern
        renderer.drawColor = SDLColor(128, 128, 128)
        renderer.fillRect(SDLFRect((ox + bx).toFloat(), (oy + by).toFloat(), w.toFloat(), h.toFloat()))
        fillGround(ox + bx, oy + by, w, h)
        // border
        renderer.drawColor = SDLColor(204, 102, 0)
        renderer.fillRect(SDLFRect((ox + bx).toFloat(), (oy + by).toFloat(), w.toFloat(), 4f))
        renderer.fillRect(SDLFRect((ox + bx).toFloat(), (oy + by + h - 4).toFloat(), w.toFloat(), 4f))
        renderer.fillRect(SDLFRect((ox + bx).toFloat(), (oy + by).toFloat(), 4f, h.toFloat()))
        renderer.fillRect(SDLFRect((ox + bx + w - 4).toFloat(), (oy + by).toFloat(), 4f, h.toFloat()))

        val size = 70
        val bx2 = w - 8 - size
        // hero box (right)
        assets.region("images", "hero.png", 0, 0, 32, 32)?.let {
            renderer.drawTexture(it, ox + bx + bx2, oy + by + 20, size, size)
        }
        strokeRect(ox + bx + bx2, oy + by + 20, size, size, SDLColor(204, 102, 0), 3)
        // enemy box (left) with animation
        val cls = game.data.clsOf(b.enemyId) ?: "enemys"
        val sheet = sheetName(cls)
        val row = iconsRow(b.enemyId)
        val eh = if (cls == "enemy48" || cls == "npc48") 48 else 32
        val frames = if (cls == "enemys" || cls == "npcs") 2 else 4
        val frame = b.animFrame % frames
        assets.region("materials", "$sheet.png", frame * 32, row * eh, 32, eh)?.let {
            renderer.drawTexture(it, ox + bx + 8, oy + by + 20, size, size)
        }
        strokeRect(ox + bx + 8, oy + by + 20, size, size, SDLColor(204, 102, 0), 3)
        // names
        drawStroked("怪物", ox + bx + 8 + size / 2, oy + by + 20 + size + 30, 30, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
        drawStroked("勇士", ox + bx + bx2 + size / 2, oy + by + 20 + size + 30, 30, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
        // VS
        assets.imageTexture("images", "VS.png")?.let {
            renderer.drawTexture(it, ox + bx + 159, oy + by + 51, 81, 73)
        }
        // horizontal lines
        for (i in 0 until 3) {
            val y = by + 35 + i * 40
            renderer.drawColor = SDLColor(255, 255, 255)
            renderer.fillRect(SDLFRect((ox + bx + 8 + size + 5).toFloat(), (oy + y).toFloat(), 65f, 2f))
            renderer.fillRect(SDLFRect((ox + bx + bx2 - 5 - 65).toFloat(), (oy + y).toFloat(), 65f, 2f))
        }
        // status
        val labels = listOf("hp" to "生命值", "atk" to "攻击力", "def" to "防御力")
        val monStats = mapOf(
            "hp" to formatBigNumber(b.monHp), "atk" to formatBigNumber(b.monAtk), "def" to formatBigNumber(b.monDef),
        )
        val heroStats = mapOf(
            "hp" to formatBigNumber(b.heroHp), "atk" to formatBigNumber(game.getRealStatus("atk")), "def" to formatBigNumber(game.getRealStatus("def")),
        )
        for ((i, pair) in labels.withIndex()) {
            val y = by + 35 + i * 40
            drawStroked("${pair.second}：", ox + bx + 8 + size + 5, oy + y - 2, 11, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            drawStroked(monStats[pair.first] ?: "0", ox + bx + 8 + size + 5 + 65, oy + y + 6, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            drawStroked("：${pair.second}", ox + bx + bx2 - 5, oy + y - 2, 11, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
            drawStroked(heroStats[pair.first] ?: "0", ox + bx + bx2 - 5 - 65, oy + y + 6, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0))
        }
        // victory text
        if (b.victory) {
            drawStroked("战斗胜利", ox + bx + w / 2, oy + by + h / 2, 65, SDLColor(255, 74, 79), SDLColor(255, 204, 204), centered = true)
            val enemy = game.enemyDef(b.enemyId)
            if (enemy != null) {
                val money = (enemy.money * (if (game.itemCount("coin") > 0) 2 else 1)).toLong()
                val exp = enemy.exp.toLong()
                drawStroked("获得 金币+$money 经验+$exp", ox + bx + w / 2, oy + by + h / 2 + 55, 16, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
            }
        }
    }

    // ============================ game over ============================

    private fun renderGameOver() {
        renderer.drawColor = SDLColor(0, 0, 0)
        renderer.fillRect(SDLFRect(0f, 0f, W.toFloat(), H.toFloat()))
        val title = game.gameOverTitle ?: "游戏结束"
        drawStroked(title, 320, 170, 40, SDLColor(255, 255, 255), SDLColor(0, 0, 0), centered = true)
        drawStroked("按 ESC 返回标题画面", 320, 300, 20, SDLColor(200, 200, 200), SDLColor(0, 0, 0), centered = true)
    }

    // ============================ window skin / selector ============================

    private fun drawWindowSkin(x: Int, y: Int, w: Int, h: Int) {
        val skin = "winskin.png"
        // background 128x128 center stretched
        assets.region("images", skin, 0, 0, 128, 128)?.let {
            renderer.blendMode = SDLBlendMode.BLEND
            renderer.drawColor = SDLColor(255, 255, 255, 217)
            renderer.drawTexture(it, x + 2, y + 2, w - 4, h - 4)
            renderer.blendMode = SDLBlendMode.NONE
        }
        // corners
        assets.region("images", skin, 128, 0, 16, 16)?.let { renderer.drawTexture(it, x, y, 16, 16) }
        assets.region("images", skin, 176, 0, 16, 16)?.let { renderer.drawTexture(it, x + w - 16, y, 16, 16) }
        assets.region("images", skin, 128, 48, 16, 16)?.let { renderer.drawTexture(it, x, y + h - 16, 16, 16) }
        assets.region("images", skin, 176, 48, 16, 16)?.let { renderer.drawTexture(it, x + w - 16, y + h - 16, 16, 16) }
        // top/bottom edges
        var dx = 16
        while (dx < w - 16) {
            val seg = min(32, w - 16 - dx)
            assets.region("images", skin, 144, 0, seg, 16)?.let { renderer.drawTexture(it, x + dx, y, seg, 16) }
            assets.region("images", skin, 144, 48, seg, 16)?.let { renderer.drawTexture(it, x + dx, y + h - 16, seg, 16) }
            dx += 32
        }
        // left/right edges
        var dy = 16
        while (dy < h - 16) {
            val seg = min(32, h - 16 - dy)
            assets.region("images", skin, 128, 16, 16, seg)?.let { renderer.drawTexture(it, x, y + dy, 16, seg) }
            assets.region("images", skin, 176, 16, 16, seg)?.let { renderer.drawTexture(it, x + w - 16, y + dy, 16, seg) }
            dy += 32
        }
    }

    /**
     * Draws the selection box sized from [str] metrics and centered on the
     * text at (cx, cy): the box grows +20px wide / +10px tall, so its origin
     * is shifted by -10 / -5 to keep it centered on the text.
     */
    private fun drawMenuSelector(str: String, size: Int, cx: Int, cy: Int) {
        val w = text.measure(str, size) + 20
        val h = text.measureHeight(str, size) + 10
        drawWindowSelector(cx - w / 2, cy - h / 2, w, h)
    }

    private fun drawWindowSelector(x: Int, y: Int, w: Int, h: Int) {
        val skin = "winskin.png"
        assets.region("images", skin, 130, 66, 28, 28)?.let {
            renderer.drawTexture(it, x + 2, y + 2, w - 4, h - 4)
        }
        assets.region("images", skin, 128, 64, 2, 2)?.let { renderer.drawTexture(it, x, y, 2, 2) }
        assets.region("images", skin, 158, 64, 2, 2)?.let { renderer.drawTexture(it, x + w - 2, y, 2, 2) }
        assets.region("images", skin, 128, 94, 2, 2)?.let { renderer.drawTexture(it, x, y + h - 2, 2, 2) }
        assets.region("images", skin, 158, 94, 2, 2)?.let { renderer.drawTexture(it, x + w - 2, y + h - 2, 2, 2) }
        assets.region("images", skin, 130, 64, 28, 2)?.let { renderer.drawTexture(it, x + 2, y, w - 4, 2) }
        assets.region("images", skin, 130, 94, 28, 2)?.let { renderer.drawTexture(it, x + 2, y + h - 2, w - 4, 2) }
        assets.region("images", skin, 128, 66, 2, 28)?.let { renderer.drawTexture(it, x, y + 2, 2, h - 4) }
        assets.region("images", skin, 158, 66, 2, 28)?.let { renderer.drawTexture(it, x + w - 2, y + 2, 2, h - 4) }
    }

    private fun strokeRect(x: Int, y: Int, w: Int, h: Int, color: SDLColor, width: Int) {
        renderer.drawColor = color
        for (i in 0 until width) {
            renderer.fillRect(SDLFRect((x + i).toFloat(), (y + i).toFloat(), (w - i * 2).toFloat(), width.toFloat()))
            renderer.fillRect(SDLFRect((x + i).toFloat(), (y + h - i - width).toFloat(), (w - i * 2).toFloat(), width.toFloat()))
            renderer.fillRect(SDLFRect((x + i).toFloat(), (y + i).toFloat(), width.toFloat(), (h - i * 2).toFloat()))
            renderer.fillRect(SDLFRect((x + w - i - width).toFloat(), (y + i).toFloat(), width.toFloat(), (h - i * 2).toFloat()))
        }
    }

    private fun strokeRoundRect(x: Int, y: Int, w: Int, h: Int, r: Int, color: SDLColor) {
        strokeRect(x, y, w, h, color, 2)
    }

    // ============================ text helpers ============================

    fun drawStroked(
        textStr: String,
        x: Int,
        y: Int,
        size: Int,
        color: SDLColor,
        stroke: SDLColor,
        centered: Boolean = false,
        alpha: Float = 1f,
    ) {
        var dx = x
        if (centered) dx = x - text.measure(textStr, size) / 2
        if (alpha < 0.99f) {
            renderer.blendMode = SDLBlendMode.BLEND
        }
        text.drawStroked(textStr, dx, y, size, color, stroke, alpha)
        if (alpha < 0.99f) {
            renderer.blendMode = SDLBlendMode.NONE
        }
    }

    fun formatBigNumber(x: Double): String {
        val v = kotlin.math.floor(x)
        val abs = kotlin.math.abs(v)
        if (abs < 1e4) return v.toLong().toString()
        val scaled = v / 1e4
        return (kotlin.math.floor(scaled * 10) / 10).toString() + "w"
    }

    fun setTwoDigits(x: Int): String = if (x < 10 && x >= 0) "0$x" else x.toString()
}

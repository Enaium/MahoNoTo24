package cn.enaium.mahonoto

import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLRenderer

/**
 * Bitmap font: glyphs rendered at dev time into a grid atlas (white on
 * transparent). Text is composed by blitting glyph cells with per-char
 * advances read from chars.txt.
 */
class TextRenderer(private val assets: Assets, private val renderer: SDLRenderer) {

    companion object {
        const val CELL = 32
        const val COLS = 16
    }

    private val glyphs = HashMap<Char, Int>() // char -> index
    private val advances = HashMap<Char, Int>()
    private var atlasTex: cn.enaium.sdl.SDLTexture? = null

    fun load(fontDir: String) {
        val chars = readFileText("$fontDir/chars.txt") ?: return
        var idx = 0
        for (line in chars.lines()) {
            if (line.isBlank()) continue
            val c = line[0]
            val adv = line.substringAfter(' ').trim().toIntOrNull() ?: CELL
            glyphs[c] = idx
            advances[c] = adv
            idx++
        }
        atlasTex = assets.textureFromFile("$fontDir/atlas.png").also {
            it.blendMode = cn.enaium.sdl.SDLBlendMode.BLEND
        }
    }

    fun close() {
        atlasTex?.close()
        atlasTex = null
    }

    fun hasGlyph(c: Char): Boolean = glyphs.containsKey(c)

    /** Total advance width of [text] (before scale). */
    fun measure(text: String): Int {
        var w = 0
        for (c in text) w += advances[c] ?: CELL
        return w
    }

    /** Draws [text] with its top-left at (x, y), scaled by [scale]. */
    fun draw(text: String, x: Int, y: Int, scale: Int = 1, color: SDLColor = SDLColor(255, 255, 255)) {
        val tex = atlasTex ?: return
        val old = currentColor
        tex.colorMod = color
        currentColor = color
        var cx = x
        for (c in text) {
            val idx = glyphs[c] ?: continue
            val sx = (idx % COLS) * CELL
            val sy = (idx / COLS) * CELL
            renderer.drawTextureRegion(tex, sx, sy, CELL, CELL, cx, y, CELL * scale, CELL * scale)
            cx += (advances[c] ?: CELL) * scale
        }
        tex.colorMod = old
        currentColor = old
    }

    private var currentColor = SDLColor(255, 255, 255)

    /** Draws [text] centered horizontally at (cx, y). */
    fun drawCentered(text: String, cx: Int, y: Int, scale: Int = 1, color: SDLColor = SDLColor(255, 255, 255)) {
        draw(text, cx - measure(text) * scale / 2, y, scale, color)
    }
}

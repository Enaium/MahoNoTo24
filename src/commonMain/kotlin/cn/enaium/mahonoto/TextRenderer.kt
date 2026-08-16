package cn.enaium.mahonoto

import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import kotlin.math.roundToInt

/**
 * Bitmap font: glyphs rendered at dev time into a grid atlas (white on
 * transparent). Text is composed by blitting glyph cells with per-char
 * advances read from chars.txt.
 *
 * The atlas is pre-scaled (nearest-neighbour) to each used pixel size so
 * glyphs are drawn at exact 1:1 scale — the software renderer dims
 * textures at fractional scales.
 */
class TextRenderer(private val renderer: SDLRenderer) {

    companion object {
        const val CELL = 32
        const val COLS = 16
    }

    private val glyphs = HashMap<Char, Int>() // char -> index
    private val advances = HashMap<Char, Int>()
    private var atlas: PngDecoder.PngImage? = null
    private val scaledAtlases = HashMap<Int, SDLTexture>()

    fun load(fontDir: String) {
        val chars = Fio.readText("$fontDir/chars.txt") ?: return
        var idx = 0
        for (line in chars.lines()) {
            if (line.isBlank()) continue
            val c = line[0]
            val adv = line.substringAfter(' ').trim().toIntOrNull() ?: CELL
            glyphs[c] = idx
            advances[c] = adv
            idx++
        }
        val data = Fio.readBytes("$fontDir/atlas.png") ?: return
        atlas = PngDecoder.decode(data)
    }

    fun close() {
        scaledAtlases.values.forEach { it.close() }
        scaledAtlases.clear()
        atlas = null
    }

    /** Returns a texture with glyphs at exactly [sizePx] pixels (scaled cache). */
    private fun textureFor(sizePx: Int): SDLTexture? {
        val base = atlas ?: return null
        return scaledAtlases.getOrPut(sizePx) {
            if (sizePx == CELL) {
                makeTexture(base)
            } else {
                makeTexture(scaleNearest(base, sizePx))
            }
        }
    }

    private fun makeTexture(img: PngDecoder.PngImage): SDLTexture {
        val tex = renderer.createTexture(
            format = SDLPixelFormat.RGBA32,
            access = SDLTextureAccess.STATIC,
            width = img.width,
            height = img.height,
        )
        tex.update(null, img.rgba, img.width * 4)
        tex.blendMode = cn.enaium.sdl.SDLBlendMode.BLEND
        tex.scaleMode = SDLScaleMode.NEAREST
        return tex
    }

    /** Nearest-neighbour scaling of the whole atlas so glyphs are [sizePx]. */
    private fun scaleNearest(img: PngDecoder.PngImage, sizePx: Int): PngDecoder.PngImage {
        val nw = img.width * sizePx / CELL
        val nh = img.height * sizePx / CELL
        val out = ByteArray(nw * nh * 4)
        for (y in 0 until nh) {
            val sy = y * CELL / sizePx
            for (x in 0 until nw) {
                val sx = x * CELL / sizePx
                val s = (sy * img.width + sx) * 4
                val d = (y * nw + x) * 4
                out[d] = img.rgba[s]
                out[d + 1] = img.rgba[s + 1]
                out[d + 2] = img.rgba[s + 2]
                out[d + 3] = img.rgba[s + 3]
            }
        }
        return PngDecoder.PngImage(nw, nh, out)
    }

    fun hasGlyph(c: Char): Boolean = glyphs.containsKey(c)

    /** Total advance width of [text] at the given pixel size. */
    fun measure(text: String, sizePx: Int): Int {
        var w = 0
        for (c in text) {
            val adv = advances[c] ?: CELL
            w += (adv * sizePx) / CELL
        }
        return w
    }

    /** Draws [text] with its top-left at (x, y), glyphs [sizePx] pixels tall. */
    fun draw(text: String, x: Int, y: Int, sizePx: Int, color: SDLColor = SDLColor(255, 255, 255)) {
        val tex = textureFor(sizePx) ?: return
        val old = currentColor
        tex.colorMod = color
        currentColor = color
        drawRaw(tex, text, x, y, sizePx)
        tex.colorMod = old
        currentColor = old
    }

    /** Draws [text] centered horizontally at (cx, y). */
    fun drawCentered(text: String, cx: Int, y: Int, sizePx: Int, color: SDLColor = SDLColor(255, 255, 255)) {
        draw(text, cx - measure(text, sizePx) / 2, y, sizePx, color)
    }

    /**
     * Draws [text] with a [stroke]-colored outline (like the engine's
     * fillBoldText): offset passes then the fill color on top.
     */
    fun drawStroked(
        text: String,
        x: Int,
        y: Int,
        sizePx: Int,
        color: SDLColor = SDLColor(255, 255, 255),
        stroke: SDLColor = SDLColor(0, 0, 0),
        alpha: Float = 1f,
    ) {
        if (text.isEmpty()) return
        val tex = textureFor(sizePx) ?: return
        val old = currentColor
        // outline passes
        val strokeColor = SDLColor(stroke.r, stroke.g, stroke.b, (stroke.a * alpha).toInt())
        tex.colorMod = strokeColor
        currentColor = strokeColor
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                drawRaw(tex, text, x + dx, y + dy, sizePx)
            }
        }
        // fill pass
        val fillColor = SDLColor(color.r, color.g, color.b, (color.a * alpha).toInt())
        tex.colorMod = fillColor
        currentColor = fillColor
        drawRaw(tex, text, x, y, sizePx)
        tex.colorMod = old
        currentColor = old
    }

    private fun drawRaw(tex: SDLTexture, text: String, x: Int, y: Int, sizePx: Int) {
        var cx = x
        // the glyph content sits in the middle of the atlas cell; shift up
        // so the given y is roughly the visual center of the text
        val cy = y - sizePx / 2 - 4
        for (c in text) {
            val idx = glyphs[c] ?: continue
            // the scaled atlas cells are sizePx wide/tall
            val sx = (idx % COLS) * sizePx
            val sy = (idx / COLS) * sizePx
            renderer.renderTexture(
                tex,
                src = SDLFRect(sx.toFloat(), sy.toFloat(), sizePx.toFloat(), sizePx.toFloat()),
                dst = SDLFRect(cx.toFloat(), cy.toFloat(), sizePx.toFloat(), sizePx.toFloat()),
            )
            cx += ((advances[c] ?: CELL) * sizePx) / CELL
        }
    }

    private var currentColor = SDLColor(255, 255, 255)
}

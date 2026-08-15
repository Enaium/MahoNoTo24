package cn.enaium.mahonoto

import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTextureAccess

/**
 * Loads PNG sprites from the assets directory into SDL textures.
 * Sprites are keyed by their symbol class name (e.g. "mt_00", "mt_other_03").
 */
class Assets(private val assetsDir: String) {

    fun assetsDir(): String = assetsDir

    private var renderer: SDLRenderer? = null
    internal val spriteDirs = HashMap<String, String>()
    private val textureCache = HashMap<String, SDLTexture>()

    /** Scans the sprites directory once to map class names to folders. */
    fun scan() {
        val entries = listDir("$assetsDir/sprites") ?: emptyList()
        for (name in entries) {
            val idx = name.indexOf("_mt_")
            if (idx > 0) {
                spriteDirs["mt_" + name.substring(idx + 4)] = "$assetsDir/sprites/$name"
            }
        }
    }

    fun hasSprite(name: String): Boolean = spriteDirs.containsKey(name)

    /** Loads a texture for sprite [name] at [frame] (1-based), or null. */
    fun texture(name: String, frame: Int = 1): SDLTexture? {
        val key = "$name#$frame"
        textureCache[key]?.let { return it }
        val dir = spriteDirs[name] ?: return null
        val data = readFileBytes("$dir/$frame.png") ?: return null
        val img = PngDecoder.decode(data)
        val r = renderer ?: return null
        val tex = r.createTexture(
            format = SDLPixelFormat.RGBA32,
            access = SDLTextureAccess.STATIC,
            width = img.width,
            height = img.height,
        )
        tex.update(null, img.rgba, img.width * 4)
        tex.scaleMode = SDLScaleMode.NEAREST
        textureCache[key] = tex
        return tex
    }

    /** Loads an arbitrary png file as a texture. */
    fun textureFromFile(path: String): SDLTexture {
        val r = renderer ?: error("renderer not set")
        val img = PngDecoder.decode(readFileBytes(path) ?: error("missing $path"))
        val tex = r.createTexture(
            format = SDLPixelFormat.RGBA32,
            access = SDLTextureAccess.STATIC,
            width = img.width,
            height = img.height,
        )
        tex.update(null, img.rgba, img.width * 4)
        tex.scaleMode = SDLScaleMode.NEAREST
        return tex
    }

    fun fileExists(path: String): Boolean = readFileBytes(path) != null

    fun setRenderer(r: SDLRenderer) {
        renderer = r
    }

    fun close() {
        textureCache.values.forEach { it.close() }
        textureCache.clear()
    }
}

/** A sprite texture plus its decoded size and a sampled background color. */
class Sprite(val texture: SDLTexture, val width: Int, val height: Int, val bg: SDLColor)

/** Loads a sprite keeping pixel data for background sampling. */
fun Assets.loadSprite(name: String, frame: Int = 1): Sprite? {
    val tex = texture(name, frame) ?: return null
    val img = decodePng(name, frame) ?: return null
    val c = img.sampleAverage(2, img.height - 3, img.width - 2, img.height - 1)
    return Sprite(tex, img.width, img.height, SDLColor(c[0], c[1], c[2]))
}

/** Decodes a sprite png (used for pixel sampling). */
fun Assets.decodePng(name: String, frame: Int = 1): PngDecoder.PngImage? {
    val dir = spriteDir(name) ?: return null
    val data = readFileBytes("$dir/$frame.png") ?: return null
    return PngDecoder.decode(data)
}

internal fun Assets.spriteDir(name: String): String? = spriteDirs[name]

fun PngDecoder.PngImage.sampleAverage(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val i = (y * width + x) * 4
            r += rgba[i].toInt() and 0xFF
            g += rgba[i + 1].toInt() and 0xFF
            b += rgba[i + 2].toInt() and 0xFF
            n++
        }
    }
    return if (n == 0L) intArrayOf(0, 0, 0) else intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

fun SDLRenderer.drawTexture(tex: SDLTexture, x: Int, y: Int, w: Int = 0, h: Int = 0) {
    val dw = if (w == 0) tex.size.x.toInt() else w
    val dh = if (h == 0) tex.size.y.toInt() else h
    renderTexture(tex, dst = SDLFRect(x.toFloat(), y.toFloat(), dw.toFloat(), dh.toFloat()))
}

fun SDLRenderer.drawTextureRegion(
    tex: SDLTexture,
    sx: Int, sy: Int, sw: Int, sh: Int,
    dx: Int, dy: Int, dw: Int, dh: Int,
) {
    renderTexture(
        tex,
        src = SDLFRect(sx.toFloat(), sy.toFloat(), sw.toFloat(), sh.toFloat()),
        dst = SDLFRect(dx.toFloat(), dy.toFloat(), dw.toFloat(), dh.toFloat()),
    )
}

fun SDLRenderer.fillRect(rect: SDLRect, color: SDLColor) {
    drawColor = color
    fillRect(rect)
}

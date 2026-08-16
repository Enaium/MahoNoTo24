package cn.enaium.mahonoto

import cn.enaium.mahonoto.Fio
import cn.enaium.mahonoto.PngDecoder
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess

/**
 * Loads h5mota material sheets / images and caches cropped SDL textures.
 * Sheet crop key: "imageName@sx,sy,sw,sh".
 */
class Assets(val root: String) {

    private var renderer: SDLRenderer? = null

    private val decoded = HashMap<String, PngDecoder.PngImage>()
    private val textures = HashMap<String, SDLTexture>()

    fun setRenderer(r: SDLRenderer) { renderer = r }

    fun assetsDir(): String = root

    /** Decodes (and caches) a png under project dirs: images/, materials/. */
    fun decode(dir: String, name: String): PngDecoder.PngImage? {
        val key = "$dir/$name"
        decoded[key]?.let { return it }
        val data = Fio.readBytes("$root/$dir/$name") ?: return null
        return try {
            val img = PngDecoder.decode(data)
            decoded[key] = img
            img
        } catch (t: Throwable) {
            null
        }
    }

    fun image(name: String): PngDecoder.PngImage? = decode("images", name)
    fun material(name: String): PngDecoder.PngImage? = decode("materials", name)

    /** Loads a sheet region into an SDL texture (cached). */
    fun region(dir: String, name: String, sx: Int, sy: Int, sw: Int, sh: Int): SDLTexture? {
        val img = decode(dir, name) ?: return null
        val key = "$dir/$name@$sx,$sy,$sw,$sh"
        textures[key]?.let { return it }
        val r = renderer ?: return null
        val rgba = ByteArray(sw * sh * 4)
        for (y in 0 until sh) {
            val src = ((sy + y).coerceIn(0, img.height - 1)) * img.width * 4 + sx.coerceIn(0, img.width - 1) * 4
            val dst = y * sw * 4
            for (x in 0 until sw * 4) {
                rgba[dst + x] = if (sx + (x / 4) < img.width) img.rgba[src + x] else 0
            }
        }
        val tex = r.createTexture(
            format = SDLPixelFormat.RGBA32,
            access = SDLTextureAccess.STATIC,
            width = sw,
            height = sh,
        )
        tex.update(null, rgba, sw * 4)
        tex.scaleMode = SDLScaleMode.NEAREST
        textures[key] = tex
        return tex
    }

    /** Whole image as texture (cached by name). */
    fun imageTexture(dir: String, name: String): SDLTexture? {
        val img = decode(dir, name) ?: return null
        return region(dir, name, 0, 0, img.width, img.height)
    }

    fun close() {
        textures.values.forEach { it.close() }
        textures.clear()
        decoded.clear()
    }
}

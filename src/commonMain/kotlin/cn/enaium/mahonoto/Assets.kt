package cn.enaium.mahonoto

import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLSurface
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.image.SDLImage

/**
 * Loads h5mota material sheets / images (via SDL_image) and caches cropped
 * SDL textures. Sheet crop key: "imageName@sx,sy,sw,sh".
 */
class Assets(val root: String) {

    private var renderer: SDLRenderer? = null

    private val surfaces = HashMap<String, SDLSurface>()
    private val textures = HashMap<String, SDLTexture>()

    fun setRenderer(r: SDLRenderer) { renderer = r }

    fun assetsDir(): String = root

    /** Loads (and caches) a surface under project dirs: images/, materials/. */
    private fun surfaceFor(dir: String, name: String): SDLSurface? {
        val key = "$dir/$name"
        surfaces[key]?.let { return it }
        val s = try {
            SDLImage.load("$root/$dir/$name")
        } catch (t: Throwable) {
            null
        } ?: return null
        surfaces[key] = s
        return s
    }

    /** Loads a sheet region into an SDL texture (cached). */
    fun region(dir: String, name: String, sx: Int, sy: Int, sw: Int, sh: Int): SDLTexture? {
        val s = surfaceFor(dir, name) ?: return null
        val key = "$dir/$name@$sx,$sy,$sw,$sh"
        textures[key]?.let { return it }
        val r = renderer ?: return null
        val bpp = 4
        val rgba = ByteArray(sw * sh * bpp)
        // read the surface pixels once (native get-pixels copies the whole
        // surface on every call, which is extremely slow in a loop)
        val px = s.pixels
        for (y in 0 until sh) {
            val srcRow = (sy + y).coerceIn(0, s.height - 1)
            val srcOff = srcRow * s.pitch + sx.coerceIn(0, s.width - 1) * bpp
            val dstOff = y * sw * bpp
            for (x in 0 until sw * bpp) {
                rgba[dstOff + x] = if (sx + (x / bpp) < s.width) px[srcOff + x] else 0
            }
        }
        val tex = r.createTexture(
            format = s.format,
            access = SDLTextureAccess.STATIC,
            width = sw,
            height = sh,
        )
        tex.update(null, rgba, sw * bpp)
        tex.scaleMode = SDLScaleMode.NEAREST
        textures[key] = tex
        return tex
    }

    /** Whole image as texture (cached by name). */
    fun imageTexture(dir: String, name: String): SDLTexture? {
        val s = surfaceFor(dir, name) ?: return null
        val key = "$dir/$name"
        textures[key]?.let { return it }
        val r = renderer ?: return null
        val tex = r.createTexture(
            format = s.format,
            access = SDLTextureAccess.STATIC,
            width = s.width,
            height = s.height,
        )
        tex.update(null, s.pixels, s.pitch)
        tex.scaleMode = SDLScaleMode.NEAREST
        textures[key] = tex
        return tex
    }

    fun close() {
        textures.values.forEach { it.close() }
        textures.clear()
        surfaces.values.forEach { it.close() }
        surfaces.clear()
    }
}

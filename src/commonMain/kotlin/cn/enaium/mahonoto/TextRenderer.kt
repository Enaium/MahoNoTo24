package cn.enaium.mahonoto

import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLFRect
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.ttf.SDLTTF
import cn.enaium.sdl.ttf.SDLTTFFont

/**
 * Text rendering via sdl-ttf-kmp (SDL_ttf 3.x bindings).
 *
 * Every draw call rasterizes the string with FreeType into an alpha
 * surface, uploads it into a texture and blits it at 1:1 scale. Textures
 * are cached per (text, size, color, alpha) so static strings cost one
 * rasterization each; the cache is bounded and drops the least recently
 * used entries.
 */
class TextRenderer(private val renderer: SDLRenderer) {

    private var baseFont: SDLTTFFont? = null
    private val fontCache = HashMap<Int, SDLTTFFont>() // sizePx -> font copy

    private val textureCache = HashMap<String, SDLTexture>()
    private val cacheOrder = ArrayDeque<String>()

    private fun putTexture(key: String, tex: SDLTexture) {
        if (textureCache.containsKey(key)) return
        textureCache[key] = tex
        cacheOrder.addLast(key)
        while (cacheOrder.isNotEmpty() && textureCache.size > MAX_CACHE) {
            val oldest = cacheOrder.removeFirst()
            textureCache.remove(oldest)?.close()
        }
    }

    fun load() {
        if (!SDLTTF.init()) error("TTF_Init failed: ${SDLTTF.error()}")
        val fontPath = resolveCjkFont() ?: error("no CJK-capable system font found")
        baseFont = SDLTTF.openFont(fontPath, 16f)
    }

    /** Probes well-known system fonts and returns the first CJK-capable one. */
    private fun resolveCjkFont(): String? {
        val candidates = listOf(
            // macOS
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/Hiragino Sans GB.ttc",
            "/System/Library/Fonts/STHeiti Light.ttc",
            "/System/Library/Fonts/Supplemental/Songti.ttc",
            // Linux: Noto CJK (various paths and naming conventions)
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/noto-cjk/NotoSansSC-Regular.otf",
            "/usr/share/fonts/opentype/noto/NotoSansSC-Regular.otf",
            "/usr/share/fonts/noto-cjk/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/noto/NotoSansSC-Regular.otf",
            // Linux: Adobe Source Han Sans (思源黑体)
            "/usr/share/fonts/adobe-source-han-sans/SourceHanSansCN-Regular.otf",
            "/usr/share/fonts/adobe-source-han-sans/SourceHanSans-Regular.otf",
            "/usr/share/fonts/adobe-source-han-sans/SourceHanSansCN-Bold.otf",
            "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/google-noto-cjk/NotoSansSC-Regular.otf",
            // Linux: WenQuanYi (文泉驿)
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/usr/share/fonts/wqy-zenhei/wqy-zenhei.ttc",
            "/usr/share/fonts/wqy-microhei/wqy-microhei.ttc",
            "/usr/share/fonts/wqy-zenhei/wqy-zenhei-bitmap.ttc",
            // Linux: Arphic (文鼎)
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/usr/share/fonts/arphic-uming/uming.ttc",
            "/usr/share/fonts/truetype/arphic/ukai.ttc",
            "/usr/share/fonts/arphic-ukai/ukai.ttc",
            // Linux: Droid (Android fonts)
            "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
            "/usr/share/fonts/droid-fonts/DroidSansFallbackFull.ttf",
            "/usr/share/fonts/truetype/droid/DroidSansJapanese.ttf",
            // Linux: Other common CJK fonts
            "/usr/share/fonts/truetype/cjk/uming.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto-cjk/NotoSansCJK-Regular.ttc",
            // Windows: 微软雅黑 / 黑体 / 宋体
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/msyhbd.ttc",
        )
        for (path in candidates) {
            val font = try {
                SDLTTF.openFont(path, 12f)
            } catch (t: Throwable) {
                continue // missing or unreadable
            }
            val hasCjk = font.hasGlyph('文'.code)
            font.close()
            if (hasCjk) return path
        }
        return null
    }

    fun close() {
        textureCache.values.forEach { it.close() }
        textureCache.clear()
        fontCache.values.forEach { it.close() }
        fontCache.clear()
        baseFont?.close()
        baseFont = null
        SDLTTF.quit()
    }

    /**
     * A font copy rasterized at [sizePx] * [SUPER_SAMPLE] so the text is
     * downscaled to its final size on screen (supersampling); the glyphs
     * keep far more detail than rasterizing at 1x.
     */
    private fun fontFor(sizePx: Int): SDLTTFFont? {
        val base = baseFont ?: return null
        return fontCache.getOrPut(sizePx) {
            SDLTTF.copyFont(base).also {
                it.size = sizePx * SUPER_SAMPLE.toFloat()
            }
        }
    }

    fun hasGlyph(c: Char): Boolean = baseFont?.hasGlyph(c.code) == true

    /** The rendered width of [text] at the given pixel size. */
    fun measure(text: String, sizePx: Int): Int {
        val f = fontFor(sizePx) ?: return 0
        return ((f.getStringSize(text)?.x ?: 0) + SUPER_SAMPLE / 2) / SUPER_SAMPLE
    }

    /** The rendered height of [text] at the given pixel size. */
    fun measureHeight(text: String, sizePx: Int): Int {
        val f = fontFor(sizePx) ?: return 0
        return ((f.getStringSize(text)?.y ?: 0) + SUPER_SAMPLE / 2) / SUPER_SAMPLE
    }

    /** Rasterizes [text] into a cached texture. */
    private fun textureFor(text: String, sizePx: Int, color: SDLColor, alpha: Float = 1f): SDLTexture? {
        val font = fontFor(sizePx) ?: return null
        val key = "$sizePx|${color.r},${color.g},${color.b}|$alpha|$text"
        textureCache[key]?.let { return it }
        val surface = SDLTTF.renderTextBlended(
            font,
            text,
            SDLColor(color.r, color.g, color.b, (color.a * alpha).toInt()),
        ) ?: return null
        val tex = renderer.createTexture(
            format = surface.format,
            access = SDLTextureAccess.STATIC,
            width = surface.width,
            height = surface.height,
        )
        tex.update(null, surface.pixels, surface.pitch)
        tex.blendMode = SDLBlendMode.BLEND
        tex.scaleMode = SDLScaleMode.LINEAR
        surface.close()
        putTexture(key, tex)
        return tex
    }

    private fun dstRect(tex: SDLTexture, x: Int, y: Int): SDLFRect {
        val size = tex.size
        return SDLFRect(x.toFloat(), y.toFloat(), size.x / SUPER_SAMPLE, size.y / SUPER_SAMPLE)
    }

    /** Draws [text] with its top-left at (x, y), glyphs [sizePx] pixels tall. */
    fun draw(text: String, x: Int, y: Int, sizePx: Int, color: SDLColor = SDLColor(255, 255, 255)) {
        if (text.isEmpty()) return
        val tex = textureFor(text, sizePx, color) ?: return
        renderer.renderTexture(tex, dst = dstRect(tex, x, y))
    }

    /** Draws [text] centered horizontally at (cx, y). */
    fun drawCentered(text: String, cx: Int, y: Int, sizePx: Int, color: SDLColor = SDLColor(255, 255, 255)) {
        draw(text, cx - measure(text, sizePx) / 2, y, sizePx, color)
    }

    /**
     * Draws [text] with a [stroke]-colored outline (like the engine's
     * fillBoldText): offset passes then the fill color on top. The given y
     * is the visual center of the text.
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
        val strokeTex = textureFor(text, sizePx, stroke, alpha) ?: return
        // center vertically on the actual rendered (logical) height
        val ty = (y - strokeTex.size.y / SUPER_SAMPLE / 2).toInt()
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                renderer.renderTexture(strokeTex, dst = dstRect(strokeTex, x + dx, ty + dy))
            }
        }
        val fillTex = textureFor(text, sizePx, color, alpha) ?: return
        renderer.renderTexture(fillTex, dst = dstRect(fillTex, x, ty))
    }

    companion object {
        /** Rasterization scale: text is rendered this many times larger and downscaled. */
        const val SUPER_SAMPLE = 4
        const val MAX_CACHE = 256
    }
}

package cn.enaium.mahonoto.tools

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * Dev-time tool (JVM only): renders every glyph used by the game (all text
 * from the game JSON data + UI strings) from a TTF font into a
 * white-on-transparent bitmap atlas + advance index.
 * Run via `./gradlew generateAtlas`.
 */
fun main(args: Array<String>) {
    AtlasGenerator.generate(args[0], args[1], args.getOrNull(2) ?: "assets")
}

object AtlasGenerator {

    /** Extra glyphs used by rendering code that don't appear in the data. */
    private val extra = listOf(
        "0123456789?-·，。：▶％%＋－×÷=（）[]",
        "生命值攻击力防御力金币经验等级暴击",
        "获得 个第层序章级",
        "风之罗盘道具栏存读档 选择要提交的榜单！",
        "你打不过此怪物！你的血量不够无暴击计算的预期伤害，无法开战！",
        "本层无怪物返回游戏",
        "战斗胜利怪物勇士",
        "按 ESC 返回标题画面",
        "移动音效 详细显伤 开 关",
        "当前无法使用 使用成功 操作失败",
        "得到金币数 经验值 ！",
        "你没有圣光徽！你没有风之罗盘！当前楼层无法使用风之罗盘！无法传送到该楼层！",
        "你的钥匙不足！",
        "存档成功",
        "（空）",
    )

    fun generate(fontPath: String, outputDir: String, h5Dir: String = "assets") {
        val chars = linkedSetOf<Char>()
        fun scan(s: String) {
            for (c in s) if (c != '\n' && c != '\r' && c != '\t') chars.add(c)
        }
        for (s in extra) scan(s)

        // scan all JSON data files
        val files = mutableListOf<File>()
        fun addDir(dir: File) {
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) addDir(f)
                else if (f.name.endsWith(".json")) files.add(f)
            }
        }
        val h5 = File(h5Dir)
        if (h5.exists()) addDir(h5)

        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            scan(text)
        }

        val list = chars.toList()
        println("glyphs: ${list.size}")

        val font = resolveFont(fontPath)

        val cell = 32
        val cols = 16
        val rows = (list.size + cols - 1) / cols
        val img = BufferedImage(cols * cell, rows * cell, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.font = font

        val advances = StringBuilder()
        for ((i, c) in list.withIndex()) {
            val cx = (i % cols) * cell
            val cy = (i / cols) * cell
            val fm = g.fontMetrics
            val w = fm.charWidth(c)
            val adv = if (w <= 0) cell / 2 else w + 2
            g.drawString(c.toString(), cx + 1, cy + cell - 5)
            advances.append(c).append(' ').append(adv).append('\n')
        }
        g.dispose()

        File(outputDir).mkdirs()
        val out = File(outputDir, "atlas.png")
        ImageIO.write(img, "png", out)
        File(outputDir, "chars.txt").writeText(advances.toString())
        println("wrote $out (${img.width}x${img.height})")
        println("wrote ${File(outputDir, "chars.txt")}")
    }

    /**
     * Resolves a font that can actually rasterize CJK glyphs. The game's
     * embedded TTFs (黑体 etc.) fail to render with java.awt on macOS
     * (canDisplay=false -> .notdef boxes), so fall back to system CJK fonts.
     */
    private fun resolveFont(fontPath: String): Font {
        val test = '开'
        try {
            val f = Font.createFont(Font.TRUETYPE_FONT, File(fontPath))
            if (f.canDisplay(test)) {
                println("using embedded font: ${f.family}")
                return f.deriveFont(Font.PLAIN, 26f)
            }
        } catch (t: Throwable) {
            println("embedded font load failed: ${t.message}")
        }
        for (name in listOf("Heiti SC", "PingFang SC", "Hiragino Sans GB", "Songti SC", "STHeiti")) {
            val f = Font(name, Font.PLAIN, 26)
            if (f.canDisplay(test) && f.family != "Dialog" && f.family != "SansSerif") {
                println("using system font: ${f.family}")
                return f
            }
        }
        val fallback = Font(Font.SANS_SERIF, Font.PLAIN, 26)
        println("using fallback font: ${fallback.family} (canDisplay=${fallback.canDisplay(test)})")
        return fallback
    }
}

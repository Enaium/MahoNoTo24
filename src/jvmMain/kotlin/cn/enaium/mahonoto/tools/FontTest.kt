package cn.enaium.mahonoto.tools

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage

object FontTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val candidates = mutableListOf<Font>()
        if (args.isNotEmpty()) {
            try {
                candidates.add(Font.createFont(Font.TRUETYPE_FONT, java.io.File(args[0])))
            } catch (t: Throwable) {
                println("createFont failed: $t")
            }
        }
        candidates.add(Font("PingFang SC", Font.PLAIN, 26))
        candidates.add(Font("Heiti SC", Font.PLAIN, 26))
        candidates.add(Font("Songti SC", Font.PLAIN, 26))
        candidates.add(Font("Hiragino Sans GB", Font.PLAIN, 26))
        candidates.add(Font(Font.SANS_SERIF, Font.PLAIN, 26))
        for (f in candidates) {
            val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.color = java.awt.Color.WHITE
            g.font = f
            g.drawString("开", 1, 27)
            g.dispose()
            var n = 0
            for (y in 0 until 32) for (x in 0 until 32) {
                if ((img.getRGB(x, y) ushr 24) and 0xFF > 100) n++
            }
            println("${f.family}: canDisplay=${f.canDisplay('开')} px=$n")
        }
    }
}

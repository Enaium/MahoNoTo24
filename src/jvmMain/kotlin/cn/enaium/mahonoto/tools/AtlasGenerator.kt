package cn.enaium.mahonoto.tools

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Dev-time tool (JVM only): renders every Chinese glyph used by the game UI
 * from a TTF font into a white-on-transparent bitmap atlas + advance index.
 * Run via `./gradlew generateAtlas`.
 */
fun main(args: Array<String>) {
    AtlasGenerator.generate(args[0], args[1])
}

object AtlasGenerator {

    private val strings = listOf(
        // panels
        "等级生命攻击防御金币经验黄钥匙蓝钥匙红钥匙个第层序章",
        // text popups
        "得到一个 黄钥匙 ！",
        "得到一个 蓝钥匙 ！",
        "得到一个 红钥匙 ！",
        "得到一个小血瓶 生命加 200 ！",
        "得到一个大血瓶 生命加 500 ！",
        "得到一个红宝石 攻击力加 3 ！",
        "得到一个蓝宝石 防御力加 3 ！",
        "得到 铁剑 攻击加 10 ！",
        "得到 钢剑 攻击加 40 ！",
        "得到 青锋剑 攻击加 70 ！",
        "得到 圣光剑 攻击加 110 ！",
        "得到 星光神剑 攻击加 150 ！",
        "得到 铁盾 防御加 10 ！",
        "得到 钢盾 防御加 30 ！",
        "得到 黄金盾 防御加 85 ！",
        "得到 星光盾 防御加 120 ！",
        "得到 光芒神盾 防御加 190 ！",
        "得到 钥匙盒 各种钥匙数加 1 ！",
        "得到 金块 金币数加 300 ！",
        "得到 小飞羽 等级提升一级 ！",
        "得到 大飞羽 等级提升三级 ！",
        "得到金币数 1000 经验值 100 ！",
        // monsters
        "绿头怪红头怪小蝙蝠骷髅人青头怪骷髅士兵初级法师大蝙蝠兽面人骷髅队长石头怪人麻衣法师初级卫兵红蝙蝠高级法师怪王白衣武士金甲卫士红衣法师冥卫兵高级卫兵双手剑士冥战士金甲队长灵法师冥队长灵武士红衣魔王影子战士冥灵魔王",
        // kill dialog
        "生命值攻击力防御力",
        // list dialog
        "名称生命攻击防御金经损失",
        // misc UI
        "开始游戏帮助退出音乐来源制作者胖老鼠工作室魔塔请输入第层请选择要进入的楼层",
        "请按空格键进入游戏游戏结束胜利通关",
        "序章",
        "0123456789?-·，。：▶",
    )

    fun generate(fontPath: String, outputDir: String) {
        val chars = linkedSetOf<Char>()
        for (s in strings) for (c in s) chars.add(c)
        val list = chars.toList()
        println("glyphs: ${list.size}")

        val font = Font.createFont(Font.TRUETYPE_FONT, File(fontPath))
            .deriveFont(Font.PLAIN, 26f)

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
}

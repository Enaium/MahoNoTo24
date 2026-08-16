package cn.enaium.mahonoto

import io.github.vinceglb.filekit.FileKit

fun main(args: Array<String>) {
    FileKit.init(appId = "MahoNoTo")
    val testMode = args.contains("--test")
    val fullTest = args.contains("--full")
    val dir = args.firstOrNull { !it.startsWith("--") } ?: "assets"
    runGame(dir, testMode, fullTest)
}

package cn.enaium.mahonoto

fun main(args: Array<String>) {
    val testMode = args.contains("--test")
    val dir = args.firstOrNull { !it.startsWith("--") } ?: "assets"
    runGame(dir, testMode)
}

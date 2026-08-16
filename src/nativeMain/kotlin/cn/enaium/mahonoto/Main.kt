package cn.enaium.mahonoto

fun main(args: Array<String>) {
    val testMode = args.contains("--test")
    val fullTest = args.contains("--full")
    val dir = args.firstOrNull { !it.startsWith("--") } ?: "assets"
    runGame(dir, testMode, fullTest)
}

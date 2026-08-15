package cn.enaium.mahonoto

import java.io.File

actual fun readFileBytes(path: String): ByteArray? {
    val f = File(path)
    return if (f.isFile) f.readBytes() else null
}

actual fun readFileText(path: String): String? {
    val f = File(path)
    return if (f.isFile) f.readText() else null
}

actual fun listDir(path: String): List<String>? {
    val f = File(path)
    return if (f.isDirectory) f.list()?.toList() ?: emptyList() else null
}

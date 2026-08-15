package cn.enaium.mahonoto

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.FILE
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rewind

@OptIn(ExperimentalForeignApi::class)
actual fun readFileBytes(path: String): ByteArray? = memScoped {
    val file = fopen(path, "rb") ?: return null
    try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        rewind(file)
        if (size <= 0) return@memScoped ByteArray(0)
        val buf = allocArray<ByteVar>(size)
        val n = fread(buf, 1.toULong(), size.toULong(), file)
        return@memScoped buf.readBytes(n.toInt())
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun readFileText(path: String): String? {
    val bytes = readFileBytes(path) ?: return null
    return bytes.decodeToString()
}

@OptIn(ExperimentalForeignApi::class)
actual fun listDir(path: String): List<String>? {
    val dir = opendir(path) ?: return null
    val out = ArrayList<String>()
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            out.add(name)
        }
    } finally {
        platform.posix.closedir(dir)
    }
    return out
}

private const val SEEK_END = 2

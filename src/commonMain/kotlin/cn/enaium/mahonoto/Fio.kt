package cn.enaium.mahonoto

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.parent
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.runBlocking

/**
 * Synchronous file IO backed by FileKit (https://github.com/vinceglb/FileKit).
 * FileKit's read/write APIs are suspend functions; the game loop is
 * synchronous, so every call is wrapped in runBlocking.
 */
object Fio {

    fun readBytes(path: String): ByteArray? = runBlocking {
        try {
            PlatformFile(path).readBytes()
        } catch (t: Throwable) {
            null
        }
    }

    fun readText(path: String): String? = runBlocking {
        try {
            PlatformFile(path).readString()
        } catch (t: Throwable) {
            null
        }
    }

    /** Lists directory entries (names), or null if the directory does not exist. */
    fun listDir(path: String): List<String>? = runBlocking {
        try {
            val dir = PlatformFile(path)
            if (!dir.isDirectory()) return@runBlocking null
            dir.list().map { it.name }
        } catch (t: Throwable) {
            null
        }
    }

    /** Writes text (overwrites), creating parent directories. */
    fun writeText(path: String, text: String): Boolean = runBlocking {
        try {
            val file = PlatformFile(path)
            file.parent()?.createDirectories()
            file.writeString(text)
            true
        } catch (t: Throwable) {
            false
        }
    }
}

package cn.enaium.mahonoto

/** Platform file IO abstraction (common code). */
expect fun readFileBytes(path: String): ByteArray?

expect fun readFileText(path: String): String?

/** Lists directory entries (names), or null if the directory does not exist. */
expect fun listDir(path: String): List<String>?

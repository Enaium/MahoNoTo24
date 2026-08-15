package cn.enaium.mahonoto

/**
 * Minimal pure-Kotlin PNG decoder (8-bit, non-interlaced, color types 0/2/4/6).
 * Implemented so common code can load sprite textures without SDL_image.
 */
object PngDecoder {

    class PngImage(val width: Int, val height: Int, val rgba: ByteArray)

    fun decode(data: ByteArray): PngImage {
        require(data.size >= 8) { "too small" }
        require(data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()) {
            "not a png"
        }
        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var idat = ByteArray(0)
        while (pos < data.size) {
            val len = readInt(data, pos)
            val t0 = data[pos + 4]
            val t1 = data[pos + 5]
            val t2 = data[pos + 6]
            val t3 = data[pos + 7]
            val chunk = data.copyOfRange(pos + 8, pos + 8 + len)
            if (t0 == 'I'.code.toByte() && t1 == 'H'.code.toByte() && t2 == 'D'.code.toByte() && t3 == 'R'.code.toByte()) {
                width = readInt(chunk, 0)
                height = readInt(chunk, 4)
                bitDepth = chunk[8].toInt() and 0xFF
                colorType = chunk[9].toInt() and 0xFF
                require(bitDepth == 8) { "unsupported bit depth $bitDepth" }
                require(chunk[12].toInt() == 0) { "interlaced png unsupported" }
            } else if (t0 == 'I'.code.toByte() && t1 == 'D'.code.toByte() && t2 == 'A'.code.toByte() && t3 == 'T'.code.toByte()) {
                idat += chunk
            } else if (t0 == 'I'.code.toByte() && t1 == 'E'.code.toByte() && t2 == 'N'.code.toByte() && t3 == 'D'.code.toByte()) {
                break
            }
            pos += 12 + len
        }
        require(width > 0 && height > 0) { "missing IHDR" }

        val channels = when (colorType) {
            0 -> 1
            2 -> 3
            4 -> 2
            6 -> 4
            else -> error("unsupported color type $colorType")
        }

        val raw = inflate(idat)
        val stride = width * channels
        val out = ByteArray(width * height * 4)
        val prev = ByteArray(stride)
        var rp = 0
        for (y in 0 until height) {
            val filter = raw[rp].toInt() and 0xFF
            rp++
            val line = raw.copyOfRange(rp, rp + stride)
            rp += stride
            val recon = ByteArray(stride)
            for (x in 0 until stride) {
                val a = if (x >= channels) recon[x - channels] else 0
                val b = if (y > 0) prev[x] else 0
                val c = if (x >= channels && y > 0) prev[x - channels] else 0
                val v = when (filter) {
                    0 -> line[x]
                    1 -> line[x] + a
                    2 -> line[x] + b
                    3 -> line[x] + ((a + b) / 2)
                    4 -> line[x] + paeth(a, b, c)
                    else -> error("bad filter $filter")
                }
                recon[x] = v.toByte()
            }
            for (x in 0 until width) {
                val o = (y * width + x) * 4
                when (colorType) {
                    0 -> {
                        out[o] = recon[x]
                        out[o + 1] = recon[x]
                        out[o + 2] = recon[x]
                        out[o + 3] = 255.toByte()
                    }
                    2 -> {
                        out[o] = recon[x * 3]
                        out[o + 1] = recon[x * 3 + 1]
                        out[o + 2] = recon[x * 3 + 2]
                        out[o + 3] = 255.toByte()
                    }
                    4 -> {
                        out[o] = recon[x * 2]
                        out[o + 1] = recon[x * 2]
                        out[o + 2] = recon[x * 2]
                        out[o + 3] = recon[x * 2 + 1]
                    }
                    6 -> {
                        out[o] = recon[x * 4]
                        out[o + 1] = recon[x * 4 + 1]
                        out[o + 2] = recon[x * 4 + 2]
                        out[o + 3] = recon[x * 4 + 3]
                    }
                }
            }
            prev.forEachIndexed { i, _ -> prev[i] = recon[i] }
        }
        return PngImage(width, height, out)
    }

    private fun paeth(a: Byte, b: Byte, c: Byte): Byte {
        val aa = a.toInt() and 0xFF
        val bb = b.toInt() and 0xFF
        val cc = c.toInt() and 0xFF
        val p = aa + bb - cc
        val pa = kotlin.math.abs(p - aa)
        val pb = kotlin.math.abs(p - bb)
        val pc = kotlin.math.abs(p - cc)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun readInt(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
                ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    // ---------------- DEFLATE ----------------

    private fun inflate(data: ByteArray): ByteArray {
        val bits = BitReader(data)
        val cmf = bits.readByte()
        val flg = bits.readByte()
        require(((cmf and 0xF) == 8) && ((cmf shl 8) + flg) % 31 == 0) { "bad zlib header" }
        val out = ByteArrayOutputStreamEx()
        while (true) {
            val bfinal = bits.readBit() == 1
            val btype = bits.readBits(2)
            when (btype) {
                0 -> {
                    bits.alignByte()
                    val len = bits.readLE16()
                    bits.readLE16()
                    for (i in 0 until len) out.write(bits.readByte())
                }
                1 -> inflateHuffman(bits, fixedLit(), fixedDist(), out)
                2 -> {
                    val (lit, dist) = dynamicHuffman(bits)
                    inflateHuffman(bits, lit, dist, out)
                }
                else -> error("bad block type")
            }
            if (bfinal) break
        }
        return out.toByteArray()
    }

    private fun inflateHuffman(bits: BitReader, lit: DecodeTable, dist: DecodeTable, out: ByteArrayOutputStreamEx) {
        while (true) {
            val sym = lit.decode(bits)
            if (sym < 256) {
                out.write(sym)
            } else if (sym == 256) {
                return
            } else {
                val li = sym - 257
                val length = LENGTH_BASE[li] + (if (LENGTH_EXTRA[li] > 0) bits.readBits(LENGTH_EXTRA[li]) else 0)
                val dsym = dist.decode(bits)
                val di = dsym
                val distance = DIST_BASE[di] + (if (DIST_EXTRA[di] > 0) bits.readBits(DIST_EXTRA[di]) else 0)
                out.backReference(distance, length)
            }
        }
    }

    private class ByteArrayOutputStreamEx {
        private var buf = ByteArray(65536)
        private var size = 0

        fun write(b: Int) {
            if (size == buf.size) buf = buf.copyOf(size * 2)
            buf[size++] = b.toByte()
        }

        fun backReference(distance: Int, length: Int) {
            while (size + length > buf.size) buf = buf.copyOf(buf.size * 2)
            var i = 0
            while (i < length) {
                buf[size] = buf[size - distance]
                size++
                i++
            }
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    private class DecodeTable(val counts: IntArray, val symbols: IntArray) {
        val maxLen: Int = (1..15).lastOrNull { counts[it] > 0 } ?: 1
        private val firstCode = IntArray(16)
        private val firstIndex = IntArray(16)
        private val table = Array(16) { IntArray(0) }

        init {
            var code = 0
            var index = 0
            for (len in 1..15) {
                code = (code + counts[len - 1]) shl 1
                firstCode[len] = code
                firstIndex[len] = index
                index += counts[len]
            }
            for (len in 1..15) {
                if (counts[len] == 0) continue
                val t = IntArray(counts[len])
                var pos = firstIndex[len]
                for (k in 0 until counts[len]) {
                    t[k] = symbols[pos]
                    pos++
                }
                table[len] = t
            }
        }

        fun decode(bits: BitReader): Int {
            var code = 0
            for (len in 1..15) {
                code = (code shl 1) or bits.readBit()
                if (code - firstCode[len] < counts[len]) {
                    return table[len][code - firstCode[len]]
                }
            }
            error("bad huffman code")
        }
    }

    private fun buildDecodeTable(lengths: IntArray): DecodeTable {
        val counts = IntArray(16)
        for (l in lengths) if (l > 0) counts[l]++
        val symbols = IntArray(lengths.size)
        var pos = 0
        for (len in 1..15) {
            for (s in lengths.indices) {
                if (lengths[s] == len) symbols[pos++] = s
            }
        }
        return DecodeTable(counts, symbols)
    }

    private fun fixedLit(): DecodeTable {
        val lengths = IntArray(288)
        for (s in 0..143) lengths[s] = 8
        for (s in 144..255) lengths[s] = 9
        for (s in 256..279) lengths[s] = 7
        for (s in 280..287) lengths[s] = 8
        return buildDecodeTable(lengths)
    }

    private fun fixedDist(): DecodeTable {
        val lengths = IntArray(32) { 5 }
        return buildDecodeTable(lengths)
    }

    private fun dynamicHuffman(bits: BitReader): Pair<DecodeTable, DecodeTable> {
        val hlit = bits.readBits(5) + 257
        val hdist = bits.readBits(5) + 1
        val hclen = bits.readBits(4) + 4
        val order = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        val clLengths = IntArray(19)
        for (i in 0 until hclen) clLengths[order[i]] = bits.readBits(3)
        val clTable = buildDecodeTable(clLengths)
        val litLengths = IntArray(hlit)
        var i = 0
        while (i < hlit) {
            val sym = clTable.decode(bits)
            when {
                sym < 16 -> litLengths[i++] = sym
                sym == 16 -> {
                    val rep = bits.readBits(2) + 3
                    require(i > 0)
                    val v = litLengths[i - 1]
                    repeat(rep) { litLengths[i++] = v }
                }
                sym == 17 -> {
                    val rep = bits.readBits(3) + 3
                    repeat(rep) { litLengths[i++] = 0 }
                }
                else -> {
                    val rep = bits.readBits(7) + 11
                    repeat(rep) { litLengths[i++] = 0 }
                }
            }
        }
        val distLengths = IntArray(hdist)
        i = 0
        while (i < hdist) {
            val sym = clTable.decode(bits)
            when {
                sym < 16 -> distLengths[i++] = sym
                sym == 16 -> {
                    val rep = bits.readBits(2) + 3
                    require(i > 0)
                    val v = distLengths[i - 1]
                    repeat(rep) { distLengths[i++] = v }
                }
                sym == 17 -> {
                    val rep = bits.readBits(3) + 3
                    repeat(rep) { distLengths[i++] = 0 }
                }
                else -> {
                    val rep = bits.readBits(7) + 11
                    repeat(rep) { distLengths[i++] = 0 }
                }
            }
        }
        return buildDecodeTable(litLengths) to buildDecodeTable(distLengths)
    }

    private class BitReader(val data: ByteArray) {
        var pos = 0
        var bitPos = 0

        fun readBit(): Int {
            val b = (data[pos].toInt() and 0xFF) shr bitPos and 1
            bitPos++
            if (bitPos == 8) {
                bitPos = 0
                pos++
            }
            return b
        }

        fun readBits(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = v or (readBit() shl i)
            return v
        }

        fun readByte(): Int {
            if (bitPos == 0) {
                val v = data[pos].toInt() and 0xFF
                pos++
                return v
            }
            return readBits(8)
        }

        fun readLE16(): Int {
            val a = readBits(8)
            val b = readBits(8)
            return a or (b shl 8)
        }

        fun alignByte() {
            if (bitPos != 0) {
                bitPos = 0
                pos++
            }
        }
    }

    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DIST_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )
}

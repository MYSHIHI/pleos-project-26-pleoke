package io.github.myshihi.pleosproject26.n02vehiclekaraoke

/**
 * Minimal QR Code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1–15.
 * Enough for the short local join URLs this app shows; no external library needed.
 */
class QrCode private constructor(val version: Int) {
    val size = version * 4 + 17
    private val modules = Array(size) { BooleanArray(size) }
    private val function = Array(size) { BooleanArray(size) }
    var mask = 0
        private set

    /** True = dark module. x is the column, y the row. */
    operator fun get(x: Int, y: Int) = modules[y][x]

    companion object {
        const val MAX_VERSION = 15
        // Index = version; level M only.
        private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24)
        private val BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10)
        private const val FORMAT_LEVEL_M = 0

        fun encode(text: String): QrCode = encode(text.toByteArray(Charsets.UTF_8))

        fun encode(data: ByteArray): QrCode {
            val version = (1..MAX_VERSION).firstOrNull { 4 + countBits(it) + data.size * 8 <= dataCodewords(it) * 8 }
                ?: throw IllegalArgumentException("Data too long for a version $MAX_VERSION-M QR code")
            val bits = ArrayList<Boolean>()
            fun put(value: Int, length: Int) { for (i in length - 1 downTo 0) bits.add((value ushr i) and 1 != 0) }
            put(0b0100, 4)
            put(data.size, countBits(version))
            data.forEach { put(it.toInt() and 0xFF, 8) }
            val capacity = dataCodewords(version) * 8
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val codewords = ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or if (bits[i * 8 + b]) 1 else 0 }.toByte() }
            return QrCode(version).apply { build(interleave(version, codewords)) }
        }

        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        internal fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        internal fun dataCodewords(version: Int) = rawDataModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version]

        internal fun interleave(version: Int, data: ByteArray): ByteArray {
            val blocks = BLOCKS[version]
            val eccLen = ECC_PER_BLOCK[version]
            val raw = rawDataModules(version) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val divisor = reedSolomonDivisor(eccLen)
            var k = 0
            val all = List(blocks) { i ->
                val length = shortLen - eccLen + if (i < shortBlocks) 0 else 1
                val dat = data.copyOfRange(k, k + length); k += length
                val block = dat + reedSolomonRemainder(dat, divisor)
                if (i < shortBlocks) block.copyOfRange(0, length) + byteArrayOf(0) + block.copyOfRange(length, block.size) else block
            }
            val out = ArrayList<Byte>(raw)
            for (i in all[0].indices) for (j in all.indices) {
                if (i != shortLen - eccLen || j >= shortBlocks) out.add(all[j][i])
            }
            return out.toByteArray()
        }

        internal fun reedSolomonDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = multiply(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        internal fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = (result[i].toInt() xor multiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return result
        }

        /** GF(2^8) multiplication modulo x^8 + x^4 + x^3 + x^2 + 1. */
        internal fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        internal fun formatBits(mask: Int): Int {
            val data = FORMAT_LEVEL_M shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return (data shl 10 or rem) xor 0x5412
        }

        internal fun versionBits(version: Int): Int {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            return version shl 12 or rem
        }

        internal fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
            val result = IntArray(count)
            result[0] = 6
            var pos = version * 4 + 17 - 7
            for (i in count - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        private fun bit(value: Int, i: Int) = (value ushr i) and 1 != 0
    }

    private fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; function[y][x] = true }

    private fun build(codewords: ByteArray) {
        drawFunctionPatterns()
        drawCodewords(codewords)
        var best = 0
        var bestPenalty = Int.MAX_VALUE
        for (m in 0 until 8) {
            applyMask(m); drawFormat(m)
            val p = penalty()
            if (p < bestPenalty) { bestPenalty = p; best = m }
            applyMask(m) // undo (XOR)
        }
        mask = best
        applyMask(best); drawFormat(best)
    }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
        listOf(3 to 3, size - 4 to 3, 3 to size - 4).forEach { (cx, cy) ->
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    set(x, y, dist != 2 && dist != 4)
                }
            }
        }
        val align = alignmentPositions(version)
        for (i in align.indices) for (j in align.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == align.size - 1) || (i == align.size - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
        drawFormat(0)
        if (version >= 7) {
            val bits = versionBits(version)
            for (i in 0 until 18) {
                val a = size - 11 + i % 3; val b = i / 3
                set(a, b, bit(bits, i)); set(b, a, bit(bits, i))
            }
        }
    }

    private fun drawFormat(mask: Int) {
        val bits = formatBits(mask)
        for (i in 0..5) set(8, i, bit(bits, i))
        set(8, 7, bit(bits, 6)); set(8, 8, bit(bits, 7)); set(7, 8, bit(bits, 8))
        for (i in 9 until 15) set(14 - i, 8, bit(bits, i))
        for (i in 0 until 8) set(size - 1 - i, 8, bit(bits, i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(bits, i))
        set(8, size - 8, true)
    }

    private fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = (right + 1) and 2 == 0
                val y = if (upward) size - 1 - vert else vert
                if (!function[y][x] && i < data.size * 8) {
                    modules[y][x] = bit(data[i ushr 3].toInt(), 7 - (i and 7)); i++
                }
            }
            right -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert && !function[y][x]) modules[y][x] = !modules[y][x]
        }
    }

    /** Standard penalty rules N1–N4; only used to choose a readable mask. */
    private fun penalty(): Int {
        var result = 0
        fun line(get: (Int) -> Boolean) {
            var run = 1
            for (i in 1 until size) {
                if (get(i) == get(i - 1)) { run++; if (run == 5) result += 3 else if (run > 5) result++ } else run = 1
            }
            val finder = listOf(true, false, true, true, true, false, true)
            for (i in 0..size - 7) {
                if ((0 until 7).all { get(i + it) == finder[it] }) {
                    val before = (i - 4 until i).all { it < 0 || !get(it) }
                    val after = (i + 7 until i + 11).all { it >= size || !get(it) }
                    if (before || after) result += 40
                }
            }
        }
        for (y in 0 until size) line { modules[y][it] }
        for (x in 0 until size) line { modules[it][x] }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = modules[y][x]
            if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
        }
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * 10
        return result
    }

    /** Reads the data/ECC bit stream back out of the symbol (unmasked). Used by tests. */
    internal fun readCodewords(): ByteArray {
        val out = ArrayList<Byte>()
        var acc = 0; var n = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = (right + 1) and 2 == 0
                val y = if (upward) size - 1 - vert else vert
                if (!function[y][x]) {
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0; 1 -> y % 2 == 0; 2 -> x % 3 == 0; 3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0; 5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0; else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    acc = (acc shl 1) or if (modules[y][x] != invert) 1 else 0
                    if (++n == 8) { out.add(acc.toByte()); acc = 0; n = 0 }
                }
            }
            right -= 2
        }
        return out.toByteArray()
    }
}

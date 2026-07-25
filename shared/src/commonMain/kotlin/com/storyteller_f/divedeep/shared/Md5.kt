@file:Suppress("MagicNumber")

package com.storyteller_f.divedeep.shared

private const val MD5_BLOCK_BYTES = 64
private const val MD5_DIGEST_WORDS = 4

fun md5Hex(input: String): String {
    val message = input.encodeToByteArray()
    val padded = md5PaddedMessage(message)
    var a0 = 0x67452301
    var b0 = 0xefcdab89.toInt()
    var c0 = 0x98badcfe.toInt()
    var d0 = 0x10325476
    val shifts = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )
    val constants = IntArray(MD5_BLOCK_BYTES) { index ->
        (kotlin.math.abs(kotlin.math.sin((index + 1).toDouble())) * 4294967296.0).toLong().toInt()
    }

    for (chunkStart in padded.indices step MD5_BLOCK_BYTES) {
        val words = IntArray(16) { index ->
            val offset = chunkStart + index * 4
            padded[offset].toIntUnsigned() or
                (padded[offset + 1].toIntUnsigned() shl 8) or
                (padded[offset + 2].toIntUnsigned() shl 16) or
                (padded[offset + 3].toIntUnsigned() shl 24)
        }
        var a = a0
        var b = b0
        var c = c0
        var d = d0

        for (index in 0 until MD5_BLOCK_BYTES) {
            val (f, g) = when (index) {
                in 0..15 -> ((b and c) or (b.inv() and d)) to index
                in 16..31 -> ((d and b) or (d.inv() and c)) to ((5 * index + 1) % 16)
                in 32..47 -> (b xor c xor d) to ((3 * index + 5) % 16)
                else -> (c xor (b or d.inv())) to ((7 * index) % 16)
            }
            val nextD = c
            c = b
            b += (a + f + constants[index] + words[g]).rotateLeft(shifts[index])
            a = d
            d = nextD
        }

        a0 += a
        b0 += b
        c0 += c
        d0 += d
    }

    return buildString(MD5_DIGEST_WORDS * 8) {
        appendLittleEndianHex(a0)
        appendLittleEndianHex(b0)
        appendLittleEndianHex(c0)
        appendLittleEndianHex(d0)
    }
}

private fun md5PaddedMessage(message: ByteArray): ByteArray {
    val bitLength = message.size.toLong() * 8L
    val paddingLength = ((56 - (message.size + 1) % MD5_BLOCK_BYTES) + MD5_BLOCK_BYTES) % MD5_BLOCK_BYTES
    val padded = ByteArray(message.size + 1 + paddingLength + Long.SIZE_BYTES)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (index in 0 until Long.SIZE_BYTES) {
        padded[padded.lastIndex - Long.SIZE_BYTES + 1 + index] = (bitLength ushr (8 * index)).toByte()
    }
    return padded
}

private fun Byte.toIntUnsigned(): Int = toInt() and 0xff

private fun StringBuilder.appendLittleEndianHex(word: Int) {
    repeat(Int.SIZE_BYTES) { index ->
        val byte = (word ushr (8 * index)) and 0xff
        append(byte.toString(16).padStart(2, '0'))
    }
}

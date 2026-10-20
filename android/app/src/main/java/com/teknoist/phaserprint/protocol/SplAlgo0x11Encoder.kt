package com.teknoist.phaserprint.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implements Xerox/Samsung Algo 0x11 (FBXOR) compression for SPL banded planes.
 */
object SplAlgo0x11Encoder {

    private const val COMPRESS_SAMPLE_RATE = 0x800
    private const val TABLE_PTR_SIZE = 64
    private const val MAX_UNCOMPRESSED_BYTES = 0x80
    private const val MAX_COMPRESSED_BYTES = 0x202
    private const val MIN_COMPRESSED_BYTES = 3
    private const val COMPRESSION_FLAG = 0x80

    /**
     * Compresses raw column-major band data using Algo 0x11.
     */
    fun compress(data: ByteArray): ByteArray {
        val size = data.size
        if (size == 0) return ByteArray(0)

        // 1. Build dictionary of 64 most frequent repeat offsets
        val occurs = HashMap<Int, Int>()
        var i = COMPRESS_SAMPLE_RATE
        while (i < size) {
            val b = data[i]
            val maxLookback = minOf(COMPRESS_SAMPLE_RATE, i)
            for (j in 1 until maxLookback) {
                if (data[i - j] == b) {
                    occurs[j] = (occurs[j] ?: 0) + 1
                }
            }
            i += COMPRESS_SAMPLE_RATE
        }

        val sortedOffsets = occurs.entries.sortedWith { a, b ->
            val cmp = b.value.compareTo(a.value)
            if (cmp != 0) cmp else a.key.compareTo(b.key)
        }

        val ptrArray = IntArray(TABLE_PTR_SIZE)
        var ptrCount = 0
        var hasOne = false
        for (entry in sortedOffsets) {
            if (ptrCount >= TABLE_PTR_SIZE) break
            val off = entry.key
            ptrArray[ptrCount++] = off
            if (off == 1) hasOne = true
        }
        while (ptrCount < TABLE_PTR_SIZE) {
            ptrArray[ptrCount] = ptrCount + 1
            if (ptrArray[ptrCount] == 1) hasOne = true
            ptrCount++
        }
        if (!hasOne) {
            ptrArray[TABLE_PTR_SIZE - 1] = 1
        }

        // 2. Determine uncompressed prefix
        var maxPtr = 1
        for (p in ptrArray) {
            if (p > maxPtr) maxPtr = p
        }
        val uncompSize = minOf(MAX_UNCOMPRESSED_BYTES, minOf(size, maxPtr))

        val output = ByteArrayOutputStream(size)
        val prefixHeader = ByteBuffer.allocate(4 + TABLE_PTR_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN)
        prefixHeader.putInt(uncompSize)
        for (p in ptrArray) {
            prefixHeader.putShort(p.toShort())
        }
        output.write(prefixHeader.array())
        output.write(data, 0, uncompSize)

        // 3. Compression loop
        var r = uncompSize
        val rawBuffer = ByteArray(128)
        var rawCount = 0

        fun flushRaw() {
            if (rawCount > 0) {
                output.write((rawCount - 1) and 0x7F)
                output.write(rawBuffer, 0, rawCount)
                rawCount = 0
            }
        }

        while (r < size) {
            val maxComp = minOf(MAX_COMPRESSED_BYTES, size - r)
            var bestLen = 0
            var bestPtr = 0

            if (maxComp >= MIN_COMPRESSED_BYTES) {
                for (idx in 0 until TABLE_PTR_SIZE) {
                    val ptr = ptrArray[idx]
                    if (ptr > r) continue
                    val rTmp = r - ptr
                    var len = 0
                    while (len < maxComp && data[r + len] == data[rTmp + len]) {
                        len++
                    }
                    if (len > bestLen) {
                        bestLen = len
                        bestPtr = idx
                        if (bestLen == maxComp) break
                    }
                }
            }

            if (bestLen >= MIN_COMPRESSED_BYTES) {
                flushRaw()
                val count = bestLen - 3
                val b0 = COMPRESSION_FLAG or (count and 0x7F)
                val b1 = ((count ushr 1) and 0xC0) or (bestPtr and 0x3F)
                output.write(b0)
                output.write(b1)
                r += bestLen
            } else {
                rawBuffer[rawCount++] = data[r]
                r++
                if (rawCount == 128) {
                    flushRaw()
                }
            }
        }
        flushRaw()

        return output.toByteArray()
    }

    /**
     * Calculates the SPL 32-bit arithmetic sum checksum of a byte buffer.
     */
    fun calculateChecksum(data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        var sum: Long = 0
        val end = offset + length
        for (i in offset until end) {
            sum += (data[i].toInt() and 0xFF)
        }
        return sum and 0xFFFFFFFFL
    }
}

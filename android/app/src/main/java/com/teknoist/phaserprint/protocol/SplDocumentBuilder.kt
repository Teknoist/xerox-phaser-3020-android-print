package com.teknoist.phaserprint.protocol

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds valid SPL / QPDL v3 byte streams for Xerox Phaser 3020 / Samsung M2020.
 */
class SplDocumentBuilder(
    val paperType: Byte = SplConstants.PAPER_TYPE_A4,
    val resolutionDpi: Int = 600,
    val copies: Int = 1,
    val jobName: String = "AndroidDocument",
    val userName: String = "AndroidUser"
) {

    private val bandWidthBytes: Int
    private val mediaWidth300: Int
    private val mediaHeight300: Int
    private val totalPageWidthPx: Int
    private val totalPageHeightPx: Int
    private val lineWidthBytes: Int

    init {
        if (paperType == SplConstants.PAPER_TYPE_A4) {
            bandWidthBytes = SplConstants.BAND_WIDTH_BYTES_A4
            mediaWidth300 = SplConstants.DIM_300_WIDTH_A4
            mediaHeight300 = SplConstants.DIM_300_HEIGHT_A4
            totalPageWidthPx = SplConstants.PAGE_PIXEL_WIDTH_A4
            totalPageHeightPx = SplConstants.PAGE_PIXEL_HEIGHT_A4
        } else {
            bandWidthBytes = SplConstants.BAND_WIDTH_BYTES_LETTER
            mediaWidth300 = SplConstants.DIM_300_WIDTH_LETTER
            mediaHeight300 = SplConstants.DIM_300_HEIGHT_LETTER
            totalPageWidthPx = SplConstants.PAGE_PIXEL_WIDTH_LETTER
            totalPageHeightPx = SplConstants.PAGE_PIXEL_HEIGHT_LETTER
        }
        lineWidthBytes = (totalPageWidthPx + 7) / 8
    }

    fun writePjlHeader(out: OutputStream) {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dateStr = dateFormat.format(Date())

        val pjl = StringBuilder()
            .append("\u001B%-12345X@PJL\r\n")
            .append("@PJL DEFAULT SERVICEDATE=").append(dateStr).append("\r\n")
            .append("@PJL SET USERNAME=\"").append(userName).append("\"\r\n")
            .append("@PJL SET JOBNAME=\"").append(jobName).append("\"\r\n")
            .append("@PJL SET PAPERTYPE=NORMAL\r\n")
            .append("@PJL SET ALTITUDE=LOW\r\n")
            .append("@PJL SET DENSITY=3\r\n")
            .append("@PJL SET RET=NORMAL\r\n")
            .append("@PJL SET DUPLEX=OFF\r\n")
            .append("@PJL ENTER LANGUAGE = QPDL\r\n")

        out.write(pjl.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun writePjlFooter(out: OutputStream) {
        out.write("\u001B%-12345X".toByteArray(Charsets.ISO_8859_1))
    }

    fun writePageHeader(out: OutputStream) {
        val header = ByteArray(17)
        val resUnit = (resolutionDpi / 100).toByte()

        header[0] = SplConstants.SIG_PAGE_HEADER
        header[1] = resUnit
        header[2] = ((copies ushr 8) and 0xFF).toByte()
        header[3] = (copies and 0xFF).toByte()
        header[4] = paperType
        header[5] = ((mediaWidth300 ushr 8) and 0xFF).toByte()
        header[6] = (mediaWidth300 and 0xFF).toByte()
        header[7] = ((mediaHeight300 ushr 8) and 0xFF).toByte()
        header[8] = (mediaHeight300 and 0xFF).toByte()
        header[9] = 1 // Paper source: Auto
        header[10] = 0 // Unknown / reserved
        header[11] = 1 // Duplex: Simplex in QPDL v3 mono
        header[12] = 0 // Tumble
        header[13] = 0 // Unknown / reserved
        header[14] = SplConstants.QPDL_VERSION_3
        header[15] = 1 // 1 Color Plane (Monochrome)
        header[16] = resUnit

        out.write(header)
    }

    fun writePageTrailer(out: OutputStream) {
        val trailer = byteArrayOf(
            SplConstants.SIG_PAGE_TRAILER,
            ((copies ushr 8) and 0xFF).toByte(),
            (copies and 0xFF).toByte()
        )
        out.write(trailer)
    }

    /**
     * Transposes and checks if a horizontal band contains black pixels.
     * In input bitmap: 0 = white, 1 = black.
     * Returns inverted column-major byte buffer, or null if completely white.
     */
    fun extractBand(
        pageBitmap: ByteArray,
        bandIndex: Int
    ): ByteArray? {
        val bandHeight = SplConstants.BAND_HEIGHT
        val bandSize = bandWidthBytes * bandHeight
        val band = ByteArray(bandSize)

        val startY = SplConstants.HARD_MARGIN_Y_LINES + bandIndex * bandHeight
        val totalLines = pageBitmap.size / lineWidthBytes

        var hasBlackPixels = false

        for (y in 0 until bandHeight) {
            val pageY = startY + y
            if (pageY >= totalLines) break
            val rowOffset = pageY * lineWidthBytes
            for (x in 0 until bandWidthBytes) {
                val pageX = SplConstants.HARD_MARGIN_X_BYTES + x
                val v = if (pageX < lineWidthBytes) pageBitmap[rowOffset + pageX] else 0
                if (v.toInt() != 0) {
                    hasBlackPixels = true
                }
                band[x * bandHeight + y] = v
            }
        }

        if (!hasBlackPixels) {
            return null // Skip completely blank bands
        }

        // Invert bytes for printer engine
        val inverted = ByteArray(bandSize)
        for (i in 0 until bandSize) {
            inverted[i] = (band[i].toInt().inv() and 0xFF).toByte()
        }
        return inverted
    }

    fun writeBandRecord(out: OutputStream, bandIndex: Int, rawBand: ByteArray) {
        val bandPixelWidth = bandWidthBytes * 8
        val compressed = SplAlgo0x11Encoder.compress(rawBand)

        val subheader = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(SplConstants.SUBHEADER_SIGNATURE)
            .array()

        // Checksum covers subheader + compressed payload
        val chkStream = ByteArray(4 + compressed.size)
        System.arraycopy(subheader, 0, chkStream, 0, 4)
        System.arraycopy(compressed, 0, chkStream, 4, compressed.size)
        val checksum = SplAlgo0x11Encoder.calculateChecksum(chkStream)

        // dataSize = subheader (4) + payload + checksum (4)
        val dataSize = 4 + compressed.size + 4

        // 1. Band Record Header (6 bytes)
        val bHdr = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        bHdr.put(SplConstants.SIG_BAND_RECORD)
        bHdr.put((bandIndex and 0xFF).toByte())
        bHdr.putShort(bandPixelWidth.toShort())
        bHdr.putShort(SplConstants.BAND_HEIGHT.toShort())
        out.write(bHdr.array())

        // 2. Plane Header (5 bytes)
        val pHdr = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
        pHdr.put(SplConstants.ALGO_0X11)
        pHdr.putInt(dataSize)
        out.write(pHdr.array())

        // 3. Subheader (4 bytes)
        out.write(subheader)

        // 4. Compressed Payload
        out.write(compressed)

        // 5. Checksum (4 bytes)
        val chkBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        chkBuf.putInt(checksum.toInt())
        out.write(chkBuf.array())
    }

    fun writePage(out: OutputStream, pageBitmap: ByteArray) {
        writePageHeader(out)

        val totalLines = pageBitmap.size / lineWidthBytes
        val printableLines = totalLines - SplConstants.HARD_MARGIN_Y_LINES
        val numBands = (printableLines + SplConstants.BAND_HEIGHT - 1) / SplConstants.BAND_HEIGHT

        for (bandIdx in 0 until numBands) {
            val bandData = extractBand(pageBitmap, bandIdx)
            if (bandData != null) {
                writeBandRecord(out, bandIdx, bandData)
            }
        }

        writePageTrailer(out)
    }
}

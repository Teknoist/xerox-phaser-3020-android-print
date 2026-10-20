package com.teknoist.phaserprint

import com.teknoist.phaserprint.protocol.SplAlgo0x11Encoder
import com.teknoist.phaserprint.protocol.SplConstants
import com.teknoist.phaserprint.protocol.SplDocumentBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SplProtocolUnitTest {

    @Test
    fun testAlgo0x11Compression() {
        val testData = ByteArray(2048) { (it % 17).toByte() }
        val compressed = SplAlgo0x11Encoder.compress(testData)

        assertTrue("Compressed size should be smaller than raw", compressed.size < testData.size)
        assertTrue("Compressed data should not be empty", compressed.isNotEmpty())

        val checksum = SplAlgo0x11Encoder.calculateChecksum(compressed)
        assertTrue("Checksum must be non-zero", checksum > 0)
    }

    @Test
    fun testSplDocumentBuilderHeaders() {
        val builder = SplDocumentBuilder(
            paperType = SplConstants.PAPER_TYPE_A4,
            resolutionDpi = 600,
            copies = 1,
            jobName = "TestDoc",
            userName = "TestUser"
        )

        // Test PJL Header
        val pjlOut = ByteArrayOutputStream()
        builder.writePjlHeader(pjlOut)
        val pjlStr = pjlOut.toString("ISO-8859-1")

        assertTrue(pjlStr.contains("\u001B%-12345X@PJL"))
        assertTrue(pjlStr.contains("@PJL SET JOBNAME=\"TestDoc\""))
        assertTrue(pjlStr.contains("@PJL ENTER LANGUAGE = QPDL"))

        // Test Page Header (17 bytes)
        val pageHdrOut = ByteArrayOutputStream()
        builder.writePageHeader(pageHdrOut)
        val pageHdr = pageHdrOut.toByteArray()

        assertEquals("Page header must be exactly 17 bytes", 17, pageHdr.size)
        assertEquals("Magic byte 0 must be 0x00", 0x00.toByte(), pageHdr[0])
        assertEquals("Y resolution must be 6 (600 DPI)", 6.toByte(), pageHdr[1])
        assertEquals("Paper type must be A4 (2)", SplConstants.PAPER_TYPE_A4, pageHdr[4])
        assertEquals("QPDL Version must be 3", SplConstants.QPDL_VERSION_3, pageHdr[14])
        assertEquals("Planes must be 1 (Monochrome)", 1.toByte(), pageHdr[15])
        assertEquals("X resolution must be 6 (600 DPI)", 6.toByte(), pageHdr[16])

        // Test Page Trailer (3 bytes)
        val trailerOut = ByteArrayOutputStream()
        builder.writePageTrailer(trailerOut)
        val trailer = trailerOut.toByteArray()

        assertEquals("Page trailer must be exactly 3 bytes", 3, trailer.size)
        assertEquals("Trailer magic must be 0x01", 0x01.toByte(), trailer[0])
        assertEquals("Copies must match", 1.toByte(), trailer[2])
    }

    @Test
    fun testEmptyBandSkipping() {
        val builder = SplDocumentBuilder(paperType = SplConstants.PAPER_TYPE_A4)
        val lineWidthBytes = (SplConstants.PAGE_PIXEL_WIDTH_A4 + 7) / 8
        // All white bitmap (all zeros)
        val emptyBitmap = ByteArray(lineWidthBytes * 500)

        val extracted = builder.extractBand(emptyBitmap, 0)
        assertNull("Empty band should return null to allow skipping", extracted)

        // Now add a single black pixel
        emptyBitmap[SplConstants.HARD_MARGIN_Y_LINES * lineWidthBytes + SplConstants.HARD_MARGIN_X_BYTES + 5] = 0x80.toByte()
        val extractedWithPixel = builder.extractBand(emptyBitmap, 0)
        assertNotNull("Non-empty band should not be null", extractedWithPixel)
    }
}

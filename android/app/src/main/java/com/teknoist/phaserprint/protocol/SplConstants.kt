package com.teknoist.phaserprint.protocol

/**
 * Protocol constants for Xerox Phaser 3020 / Samsung M2020 SPL (QPDL Version 3).
 */
object SplConstants {
    const val RAW_PRINT_PORT = 9100

    // Signatures
    const val SIG_PAGE_HEADER: Byte = 0x00
    const val SIG_BAND_RECORD: Byte = 0x0C
    const val SIG_PAGE_TRAILER: Byte = 0x01

    const val QPDL_VERSION_3: Byte = 0x03
    const val ALGO_0X11: Byte = 0x11
    const val SUBHEADER_SIGNATURE: Int = 0x09ABCDEF

    // Paper Types
    const val PAPER_TYPE_LETTER: Byte = 0
    const val PAPER_TYPE_LEGAL: Byte = 1
    const val PAPER_TYPE_A4: Byte = 2
    const val PAPER_TYPE_EXECUTIVE: Byte = 3
    const val PAPER_TYPE_A5: Byte = 16

    // Band size
    const val BAND_HEIGHT = 128 // scanlines per band

    // Band widths in bytes at 600 DPI (from Xerox/Samsung ULD ASIC lookup table)
    const val BAND_WIDTH_BYTES_A4 = 608       // 4,864 pixels
    const val BAND_WIDTH_BYTES_LETTER = 640   // 5,120 pixels

    // Total page pixel dimensions at 600 DPI
    const val PAGE_PIXEL_WIDTH_A4 = 4960
    const val PAGE_PIXEL_HEIGHT_A4 = 7016

    const val PAGE_PIXEL_WIDTH_LETTER = 5100
    const val PAGE_PIXEL_HEIGHT_LETTER = 6600

    // Dimensions in 300 DPI units for SPL Page Header
    const val DIM_300_WIDTH_A4 = 2480
    const val DIM_300_HEIGHT_A4 = 3508

    const val DIM_300_WIDTH_LETTER = 2550
    const val DIM_300_HEIGHT_LETTER = 3300

    // Hardware margins in 600 DPI pixels (12.5 pt margins = ~104 pixels)
    const val HARD_MARGIN_X_BYTES = 13 // 104 pixels / 8
    const val HARD_MARGIN_Y_LINES = 104

    // Wi-Fi Direct Default Gateways
    val WIFI_DIRECT_DEFAULT_IPS = listOf("192.168.32.1", "192.168.49.1")
}

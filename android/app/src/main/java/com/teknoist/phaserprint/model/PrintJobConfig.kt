package com.teknoist.phaserprint.model

import android.net.Uri

/**
 * Configuration for a zero-click background print job.
 */
data class PrintJobConfig(
    val uris: List<Uri>,
    val mimeType: String,
    val copies: Int = 1,
    val paperType: Byte = com.teknoist.phaserprint.protocol.SplConstants.PAPER_TYPE_A4,
    val resolutionDpi: Int = 600,
    val jobName: String = "AndroidPrintDocument",
    val userName: String = "AndroidUser"
)

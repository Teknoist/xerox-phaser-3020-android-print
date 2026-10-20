package com.teknoist.phaserprint.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import com.teknoist.phaserprint.discovery.PrinterDiscoveryAggregator
import com.teknoist.phaserprint.model.PrintJobConfig
import com.teknoist.phaserprint.protocol.SplConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream

/**
 * Native Android PrintService integration for Xerox Phaser 3020 / Samsung M2020.
 * Allows the printer to appear directly in the Android System Print Dialog.
 */
class PhaserPrintService : PrintService() {

    companion object {
        private const val TAG = "PhaserPrintService"
        private const val PRINTER_ID_NAME = "xerox_phaser_3020_primary"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                Log.d(TAG, "Starting system printer discovery...")

                // Immediately announce default Xerox Phaser 3020 profile
                val printerId = generatePrinterId(PRINTER_ID_NAME)
                val printer = buildPrinterInfo(printerId, "Xerox Phaser 3020 (Wi-Fi/Direct)", PrinterInfo.STATUS_IDLE)
                addPrinters(listOf(printer))

                // Perform active network probe in background
                sessionScope.launch {
                    val aggregator = PrinterDiscoveryAggregator(applicationContext)
                    val discovered = aggregator.discoverPrinter { _ -> }
                    if (discovered != null) {
                        val activeInfo = buildPrinterInfo(
                            printerId,
                            "${discovered.name} [${discovered.hostAddress}]",
                            PrinterInfo.STATUS_IDLE
                        )
                        addPrinters(listOf(activeInfo))
                    }
                }
            }

            override fun onStopPrinterDiscovery() {
                sessionScope.cancel()
            }

            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
            override fun onStartPrinterStateTracking(printerId: PrinterId) {}
            override fun onStopPrinterStateTracking(printerId: PrinterId) {}
            override fun onDestroy() {
                sessionScope.cancel()
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        Log.i(TAG, "Received system print job: ${printJob.id}")
        printJob.start()

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Extract document from PrintJob
                val doc = printJob.document
                val data: ParcelFileDescriptor = doc.data ?: run {
                    printJob.fail("No document data available")
                    return@launch
                }

                // Copy to temporary cache file
                val tempPdf = File(cacheDir, "job_${System.currentTimeMillis()}.pdf")
                FileInputStream(data.fileDescriptor).use { input ->
                    FileOutputStream(tempPdf).use { output ->
                        input.copyTo(output)
                    }
                }
                data.close()

                // Launch foreground service to handle rasterization, SPL encoding, and transmission
                val intent = Intent(this@PhaserPrintService, ZeroClickPrintJobService::class.java).apply {
                    action = ZeroClickPrintJobService.ACTION_EXECUTE_PRINT
                    putExtra(ZeroClickPrintJobService.EXTRA_FILE_PATH, tempPdf.absolutePath)
                    putExtra(ZeroClickPrintJobService.EXTRA_MIME_TYPE, "application/pdf")
                    putExtra(ZeroClickPrintJobService.EXTRA_JOB_NAME, printJob.document.info.name ?: "AndroidPrintJob")
                    putExtra(ZeroClickPrintJobService.EXTRA_COPIES, printJob.info.copies)
                }
                startForegroundService(intent)

                printJob.complete()
            } catch (e: Exception) {
                Log.e(TAG, "System print job failed", e)
                printJob.fail("Printing failed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun buildPrinterInfo(id: PrinterId, name: String, status: Int): PrinterInfo {
        val caps = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
            .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
            .addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
            .addResolution(PrintAttributes.Resolution("600dpi", "600 x 600 DPI", 600, 600), true)
            .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            .setMinMargins(PrintAttributes.Margins(174, 174, 174, 174)) // ~12.5 pt hardware margins
            .build()

        return PrinterInfo.Builder(id, name, status)
            .setCapabilities(caps)
            .setDescription("Xerox Phaser 3020 / Samsung M2020 Raw Port 9100 Print Engine")
            .build()
    }
}

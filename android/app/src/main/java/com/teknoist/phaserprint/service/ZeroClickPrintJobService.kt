package com.teknoist.phaserprint.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.teknoist.phaserprint.R
import com.teknoist.phaserprint.discovery.PrinterDiscoveryAggregator
import com.teknoist.phaserprint.network.RawPort9100Client
import com.teknoist.phaserprint.protocol.SplConstants
import com.teknoist.phaserprint.protocol.SplDocumentBuilder
import com.teknoist.phaserprint.raster.ImageRasterizer
import com.teknoist.phaserprint.raster.PdfRasterizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground Service that executes the zero-click print pipeline and notifies the user via notifications only.
 */
class ZeroClickPrintJobService : Service() {

    companion object {
        const val ACTION_EXECUTE_PRINT = "com.teknoist.phaserprint.EXECUTE_PRINT"
        const val ACTION_RETRY_PRINT = "com.teknoist.phaserprint.RETRY_PRINT"

        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_JOB_NAME = "extra_job_name"
        const val EXTRA_COPIES = "extra_copies"

        private const val CHANNEL_ID = "phaser_print_channel"
        private const val NOTIFICATION_ID = 3020
        private const val TAG = "ZeroClickPrintJob"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notificationManager: NotificationManager
    private val rawClient = RawPort9100Client()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val uris = intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "application/pdf"
        val jobName = intent.getStringExtra(EXTRA_JOB_NAME) ?: "ZeroClickPrint"
        val copies = intent.getIntExtra(EXTRA_COPIES, 1)

        startForeground(NOTIFICATION_ID, buildProgressNotification("Preparing print job...", 0, 100))

        serviceScope.launch {
            executePrintPipeline(filePath, uris, mimeType, jobName, copies, intent)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private suspend fun executePrintPipeline(
        filePath: String?,
        uris: List<Uri>?,
        mimeType: String,
        jobName: String,
        copies: Int,
        originalIntent: Intent
    ) {
        try {
            // 1. Auto-Discovery & Connection
            updateNotification("Discovering Xerox Phaser 3020 on Wi-Fi...", 10, 100)
            val aggregator = PrinterDiscoveryAggregator(applicationContext)
            val printer = aggregator.discoverPrinter { status ->
                updateNotification(status, 15, 100)
            }

            if (printer == null) {
                showErrorNotification(
                    "Printer Not Found",
                    "Could not find Phaser 3020 on LAN or Wi-Fi Direct. Ensure printer Wi-Fi is enabled.",
                    originalIntent
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return
            }

            Log.i(TAG, "Printer found: ${printer.name} at ${printer.hostAddress}:${printer.port}")
            updateNotification("Connected to ${printer.name}. Rasterizing document...", 30, 100)

            // 2. Rasterization (PDF or Image)
            val pageBitmaps = mutableListOf<ByteArray>()

            if (filePath != null) {
                val file = File(filePath)
                val pdfRasterizer = PdfRasterizer(paperType = SplConstants.PAPER_TYPE_A4)
                val bitmaps = pdfRasterizer.rasterizePdf(file) { current, total ->
                    val pct = 30 + (current * 30 / total)
                    updateNotification("Rasterizing page $current of $total (600 DPI)...", pct, 100)
                }
                pageBitmaps.addAll(bitmaps)
            } else if (!uris.isNullOrEmpty()) {
                if (mimeType == "application/pdf") {
                    val uri = uris.first()
                    val tempFile = File(cacheDir, "shared_temp_${System.currentTimeMillis()}.pdf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    val pdfRasterizer = PdfRasterizer(paperType = SplConstants.PAPER_TYPE_A4)
                    val bitmaps = pdfRasterizer.rasterizePdf(tempFile) { current, total ->
                        val pct = 30 + (current * 30 / total)
                        updateNotification("Rasterizing page $current of $total (600 DPI)...", pct, 100)
                    }
                    pageBitmaps.addAll(bitmaps)
                    tempFile.delete()
                } else {
                    // Images
                    val imageRasterizer = ImageRasterizer(applicationContext, paperType = SplConstants.PAPER_TYPE_A4)
                    for ((idx, uri) in uris.withIndex()) {
                        updateNotification("Processing image ${idx + 1} of ${uris.size}...", 30 + (idx * 30 / uris.size), 100)
                        pageBitmaps.add(imageRasterizer.rasterizeImage(uri))
                    }
                }
            }

            if (pageBitmaps.isEmpty()) {
                showErrorNotification("Print Failed", "No printable pages found.", originalIntent)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return
            }

            // 3. SPL Document Encoding and Port 9100 Streaming
            updateNotification("Encoding SPL packets and transmitting to printer...", 65, 100)
            val docBuilder = SplDocumentBuilder(
                paperType = SplConstants.PAPER_TYPE_A4,
                resolutionDpi = 600,
                copies = copies,
                jobName = jobName,
                userName = "ZeroClickUser"
            )

            rawClient.sendStream(
                host = printer.hostAddress,
                port = printer.port,
                streamSupplier = { outStream ->
                    docBuilder.writePjlHeader(outStream)
                    for ((pageIdx, bitmap) in pageBitmaps.withIndex()) {
                        docBuilder.writePage(outStream, bitmap)
                        val pct = 65 + ((pageIdx + 1) * 30 / pageBitmaps.size)
                        updateNotification("Printing page ${pageIdx + 1} of ${pageBitmaps.size}...", pct, 100)
                    }
                    docBuilder.writePjlFooter(outStream)
                },
                onProgress = { bytesSent ->
                    Log.d(TAG, "Transmitted $bytesSent bytes to printer.")
                }
            )

            // 4. Success Completion Notification
            showSuccessNotification("Print Completed Successfully", "${pageBitmaps.size} page(s) printed on ${printer.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Print job failed", e)
            showErrorNotification("Printing Error", e.message ?: "Communication failure over Port 9100", originalIntent)
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(text: String, progress: Int, max: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Xerox Phaser 3020 Printing")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_print_notification)
            .setProgress(max, progress, progress == 0)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String, progress: Int, max: Int) {
        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(text, progress, max))
    }

    private fun showSuccessNotification(title: String, text: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_print_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notif)
    }

    private fun showErrorNotification(title: String, text: String, retryIntent: Intent) {
        val pendingRetry = PendingIntent.getService(
            this,
            0,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_print_notification)
            .addAction(R.drawable.ic_printer, getString(R.string.action_retry), pendingRetry)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notif)
    }
}

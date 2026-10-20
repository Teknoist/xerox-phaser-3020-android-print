package com.teknoist.phaserprint.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.teknoist.phaserprint.R
import com.teknoist.phaserprint.discovery.PrinterDiscoveryAggregator
import com.teknoist.phaserprint.protocol.SplConstants
import com.teknoist.phaserprint.service.ZeroClickPrintJobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Samsung One UI Companion Dashboard for Xerox Phaser 3020 / Samsung M2020.
 */
class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvPrinterDetails: TextView
    private lateinit var tvScanResults: TextView
    private lateinit var btnTestPrint: Button
    private lateinit var btnScanPrinters: Button
    private lateinit var etManualIp: EditText
    private lateinit var btnSaveIp: Button

    private val prefs by lazy { getSharedPreferences("phaser_printer_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusBadge = findViewById(R.id.tv_status_badge)
        tvPrinterDetails = findViewById(R.id.tv_printer_details)
        tvScanResults = findViewById(R.id.tv_scan_results)
        btnTestPrint = findViewById(R.id.btn_test_print)
        btnScanPrinters = findViewById(R.id.btn_scan_printers)
        etManualIp = findViewById(R.id.et_manual_ip)
        btnSaveIp = findViewById(R.id.btn_save_ip)

        loadCachedConfig()
        setupListeners()
    }

    private fun loadCachedConfig() {
        val cachedIp = prefs.getString("last_known_ip", null)
        if (cachedIp != null) {
            etManualIp.setText(cachedIp)
            tvStatusBadge.text = "Kayıtlı IP"
            tvPrinterDetails.text = "Tanımlı Yazıcı IP: $cachedIp:9100\n600 DPI Lazer • QPDL v3 (Port 9100)"
        }
    }

    private fun setupListeners() {
        btnTestPrint.setOnClickListener {
            triggerTestPrint()
        }

        btnScanPrinters.setOnClickListener {
            performNetworkScan()
        }

        btnSaveIp.setOnClickListener {
            val ip = etManualIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                prefs.edit().putString("last_known_ip", ip).apply()
                tvStatusBadge.text = "Kayıtlı IP"
                tvPrinterDetails.text = "Tanımlı Yazıcı IP: $ip:9100\n600 DPI Lazer • QPDL v3 (Port 9100)"
                Toast.makeText(this, "Yazıcı IP kaydedildi: $ip", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performNetworkScan() {
        tvScanResults.text = "Ağ taranıyor (mDNS, Subnet ve Wi-Fi Direct)..."
        btnScanPrinters.isEnabled = false

        launch {
            val aggregator = PrinterDiscoveryAggregator(applicationContext)
            val printer = withContext(Dispatchers.IO) {
                aggregator.discoverPrinter { status ->
                    launch(Dispatchers.Main) {
                        tvScanResults.text = status
                    }
                }
            }

            btnScanPrinters.isEnabled = true
            if (printer != null) {
                tvScanResults.text = "Yazıcı bulundu: ${printer.name} (${printer.hostAddress}:${printer.port})"
                tvStatusBadge.text = "Bağlandı"
                etManualIp.setText(printer.hostAddress)
                tvPrinterDetails.text = "Aktif Yazıcı: ${printer.name}\nIP: ${printer.hostAddress}:${printer.port}\nProtokol: QPDL v3 / 600 DPI"
            } else {
                tvScanResults.text = "Yazıcı bulunamadı. Lütfen yazıcının açık ve Wi-Fi ağına bağlı olduğundan emin olun."
            }
        }
    }

    private fun triggerTestPrint() {
        launch(Dispatchers.IO) {
            // Generate a sample test image bitmap at 600 DPI
            val width = SplConstants.PAGE_PIXEL_WIDTH_A4
            val height = SplConstants.PAGE_PIXEL_HEIGHT_A4
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 90f
                isFakeBoldText = true
            }

            // Draw header banner
            val bannerPaint = Paint().apply { color = Color.BLACK }
            canvas.drawRect(200f, 200f, (width - 200).toFloat(), 480f, bannerPaint)

            paint.color = Color.WHITE
            canvas.drawText("XEROX PHASER 3020 / SAMSUNG M2020", 280f, 380f, paint)

            paint.color = Color.BLACK
            paint.textSize = 55f
            paint.isFakeBoldText = false

            var y = 650f
            val lines = listOf(
                "Sıfır Tıklama (Zero-Click) Android Yazıcı Servisi",
                "Samsung One UI Arayüz Entegrasyonu Test Sayfası",
                "Çözünürlük: 600 x 600 DPI Monokrom Lazer",
                "Sıkıştırma: Algo 0x11 (FBXOR) Banded Compression",
                "Protokol: SPL / QPDL Sürüm 3 (Port 9100 Raw Stream)",
                "Tarih: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
                "Durum: Test Başarılı! Donanımınız yazdırmaya hazırdır."
            )

            for (line in lines) {
                canvas.drawText(line, 280f, y, paint)
                y += 100f
            }

            // Draw border
            val strokePaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 12f
            }
            canvas.drawRect(120f, 120f, (width - 120).toFloat(), (height - 120).toFloat(), strokePaint)

            // Save to temp file
            val tempImg = File(cacheDir, "test_print_oneui.png")
            FileOutputStream(tempImg).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()

            // Launch ZeroClickPrintJobService
            val intent = Intent(this@MainActivity, ZeroClickPrintJobService::class.java).apply {
                action = ZeroClickPrintJobService.ACTION_EXECUTE_PRINT
                putExtra(ZeroClickPrintJobService.EXTRA_FILE_PATH, tempImg.absolutePath)
                putExtra(ZeroClickPrintJobService.EXTRA_MIME_TYPE, "image/png")
                putExtra(ZeroClickPrintJobService.EXTRA_JOB_NAME, "OneUI_Test_Page")
                putExtra(ZeroClickPrintJobService.EXTRA_COPIES, 1)
            }
            startForegroundService(intent)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Test sayfası basılıyor. Bildirimleri kontrol edin.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

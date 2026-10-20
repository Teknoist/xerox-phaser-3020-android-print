package com.teknoist.phaserprint.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.teknoist.phaserprint.service.ZeroClickPrintJobService

/**
 * Invisible (Theme.NoDisplay) entry point Activity for the Android Share sheet and View intents.
 * Extracts document URIs, starts ZeroClickPrintJobService in foreground, and finishes instantly.
 * Zero user interaction required.
 */
class QuickPrintActivity : Activity() {

    companion object {
        private const val TAG = "QuickPrintActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            handleIncomingIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle zero-click print intent", e)
        } finally {
            // Dismiss instantly: 0 taps
            finish()
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type ?: "application/pdf"
        val uris = ArrayList<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (streamUri != null) {
                    uris.add(streamUri)
                } else if (intent.data != null) {
                    intent.data?.let { uris.add(it) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (streamUris != null) {
                    uris.addAll(streamUris)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uris.add(it) }
            }
        }

        if (uris.isNotEmpty()) {
            Log.i(TAG, "Launching Zero-Click background print service for ${uris.size} document(s)...")
            val serviceIntent = Intent(this, ZeroClickPrintJobService::class.java).apply {
                this.action = ZeroClickPrintJobService.ACTION_EXECUTE_PRINT
                putParcelableArrayListExtra(ZeroClickPrintJobService.EXTRA_URIS, uris)
                putExtra(ZeroClickPrintJobService.EXTRA_MIME_TYPE, type)
                putExtra(ZeroClickPrintJobService.EXTRA_JOB_NAME, "ZeroClick_${System.currentTimeMillis()}")
                putExtra(ZeroClickPrintJobService.EXTRA_COPIES, 1)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Log.w(TAG, "Received print intent without valid file URIs.")
        }
    }
}

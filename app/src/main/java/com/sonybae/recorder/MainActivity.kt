package com.sonybae.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_CAPTURE = 1001
    private val REQUEST_CODE_PERMS = 1003

    private var bitrate = 20_000_000
    private var frameRate = 30
    private var scale = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupSpinners()

        findViewById<Button>(R.id.btnStartRecord).setOnClickListener {
            readSettings()
            if (checkPermissions()) {
                askProjection()
            }
        }
    }

    private fun setupSpinners() {
        findViewById<Spinner>(R.id.spinnerResolution).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Full Screen (Native)", "Setengah Resolusi (Hemat)")
            )
            setSelection(0)
        }

        findViewById<Spinner>(R.id.spinnerBitrate).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "24 Mbps (Ultra)",
                    "20 Mbps (Sangat Jernih)",
                    "15 Mbps (HD)",
                    "10 Mbps",
                    "5 Mbps"
                )
            )
            setSelection(1)
        }

        findViewById<Spinner>(R.id.spinnerFps).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("60 FPS (Mulus)", "30 FPS (Hemat)")
            )
            setSelection(1)
        }
    }

    private fun readSettings() {
        val bitText = findViewById<Spinner>(R.id.spinnerBitrate).selectedItem.toString()
        bitrate = when {
            bitText.startsWith("24") -> 24_000_000
            bitText.startsWith("20") -> 20_000_000
            bitText.startsWith("15") -> 15_000_000
            bitText.startsWith("10") -> 10_000_000
            else -> 5_000_000
        }

        val fpsText = findViewById<Spinner>(R.id.spinnerFps).selectedItem.toString()
        frameRate = if (fpsText.startsWith("60")) 60 else 30

        // Panel Itel P55 5G = 720 x 1612. "Native" memakai ukuran asli penuh,
        // "Setengah" menurunkannya agar hemat panas/baterai.
        val resText = findViewById<Spinner>(R.id.spinnerResolution).selectedItem.toString()
        scale = if (resText.startsWith("Setengah")) 0.5f else 1f
    }

    /**
     * Ukuran layar fisik. maximumWindowMetrics mengikuti orientasi saat ini,
     * jadi dipakai apa adanya (portrait 720x1612, landscape 1612x720).
     */
    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WindowManager::class.java)
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun checkPermissions(): Boolean {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMS)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMS) {
            val audioOk = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (audioOk) {
                askProjection()
            } else {
                Toast.makeText(
                    this,
                    "Izin mikrofon wajib untuk menangkap audio internal.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun askProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Izin rekam layar ditolak.", Toast.LENGTH_SHORT).show()
            return
        }

        val (w, h) = screenSize()
        var outWidth = (w * scale).toInt()
        var outHeight = (h * scale).toInt()
        if (outWidth % 2 != 0) outWidth--
        if (outHeight % 2 != 0) outHeight--

        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordService.EXTRA_WIDTH, outWidth)
            putExtra(ScreenRecordService.EXTRA_HEIGHT, outHeight)
            putExtra(ScreenRecordService.EXTRA_BITRATE, bitrate)
            putExtra(ScreenRecordService.EXTRA_FPS, frameRate)
        }

        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Mulai merekam. Stop lewat panel notifikasi.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }
}
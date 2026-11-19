package com.sonybae.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_SCREEN_CAPTURE = 1001
    private val REQUEST_CODE_OVERLAY = 1002
    
    private var selectedRes = 720
    private var selectedBitrate = 15000000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        setupSpinners()

        findViewById<Button>(R.id.btnStartRecord).setOnClickListener { 
            saveSettings()
            checkPermissionsAndStart() 
        }
    }

    private fun setupSpinners() {
        val resSpinner = findViewById<Spinner>(R.id.spinnerResolution)
        val bitSpinner = findViewById<Spinner>(R.id.spinnerBitrate)

        // Opsi Resolusi
        val resOptions = arrayOf("1080p (FHD)", "720p (HD)", "480p (SD)")
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        resSpinner.adapter = resAdapter
        resSpinner.setSelection(1) // Default ke 720p

        // Opsi Bitrate
        val bitOptions = arrayOf("24 Mbps (Ultra HD)", "20 Mbps (Sangat Jernih)", "15 Mbps (Standar)", "10 Mbps (Sedang)", "5 Mbps (Rendah)")
        val bitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bitOptions)
        bitSpinner.adapter = bitAdapter
        bitSpinner.setSelection(1) // Default ke 20 Mbps
    }

    private fun saveSettings() {
        val resText = findViewById<Spinner>(R.id.spinnerResolution).selectedItem.toString()
        selectedRes = when {
            resText.contains("1080") -> 1080
            resText.contains("720") -> 720
            else -> 480
        }

        val bitText = findViewById<Spinner>(R.id.spinnerBitrate).selectedItem.toString()
        selectedBitrate = when {
            bitText.contains("24") -> 24000000
            bitText.contains("20") -> 20000000
            bitText.contains("15") -> 15000000
            bitText.contains("10") -> 10000000
            else -> 5000000
        }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            ActivityCompat.requestPermissions(this, permissions, 1003)
            return
        }

        startProjection()
    }

    private fun startProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == RESULT_OK) {
            val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
                putExtra("RESOLUTION", selectedRes)
                putExtra("BITRATE", selectedBitrate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            moveTaskToBack(true) 
        }
    }
}
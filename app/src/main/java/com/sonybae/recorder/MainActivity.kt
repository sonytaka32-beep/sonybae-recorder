package com.sonybae.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_SCREEN_CAPTURE = 1001
    private var selectedBitrate = 20000000

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

        // Resolusi dipaksa mengikuti layar (Full Screen)
        val resOptions = arrayOf("Full Screen (Otomatis)")
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        resSpinner.adapter = resAdapter

        val bitOptions = arrayOf("24 Mbps (Ultra HD)", "20 Mbps (Sangat Jernih)", "15 Mbps (Standar)")
        val bitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bitOptions)
        bitSpinner.adapter = bitAdapter
        bitSpinner.setSelection(1) // 20 Mbps default
    }

    private fun saveSettings() {
        val bitText = findViewById<Spinner>(R.id.spinnerBitrate).selectedItem.toString()
        selectedBitrate = when {
            bitText.contains("24") -> 24000000
            bitText.contains("20") -> 20000000
            else -> 15000000
        }
    }

    private fun checkPermissionsAndStart() {
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
                putExtra("BITRATE", selectedBitrate)
                action = "START" // Penanda untuk memulai Service
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
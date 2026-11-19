package com.sonybae.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hbisoft.hbrecorder.HBRecorder
import com.hbisoft.hbrecorder.HBRecorderListener
import java.io.File

class MainActivity : AppCompatActivity(), HBRecorderListener {

    private val REQUEST_CODE_SCREEN_CAPTURE = 1001
    private var selectedBitrate = 20000000
    private lateinit var hbRecorder: HBRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inisialisasi Mesin HBRecorder
        hbRecorder = HBRecorder(this, this)

        setupSpinners()

        findViewById<Button>(R.id.btnStartRecord).setOnClickListener { 
            saveSettings()
            checkPermissionsAndStart() 
        }
    }

    private fun setupSpinners() {
        val resSpinner = findViewById<Spinner>(R.id.spinnerResolution)
        val bitSpinner = findViewById<Spinner>(R.id.spinnerBitrate)

        val resOptions = arrayOf("Full Screen (Otomatis)")
        resSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)

        val bitOptions = arrayOf("24 Mbps (Ultra HD)", "20 Mbps (Sangat Jernih)", "15 Mbps (Standar)")
        val bitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bitOptions)
        bitSpinner.adapter = bitAdapter
        bitSpinner.setSelection(1) // Default ke 20 Mbps
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
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        var allGranted = true
        for (p in permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false
                break
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1003)
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
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            
            // PENGATURAN KUALITAS VIDEO
            hbRecorder.setVideoBitrate(selectedBitrate)
            hbRecorder.setVideoEncoder("H264")
            hbRecorder.recordHDVideo(true) // Paksa otomatis ikut resolusi hardware Itel P55 5G
            
            // PENGATURAN INTERNAL AUDIO (KHUSUS ANDROID 10+)
            hbRecorder.isAudioEnabled(true)
            hbRecorder.recordInternalAudio(true) 
            
            // LOKASI PENYIMPANAN KE GALERI
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SONYBAE")
            if (!folder.exists()) folder.mkdirs()
            hbRecorder.setOutputPath(folder.absolutePath)
            
            // KONTROL VIA NOTIFIKASI
            hbRecorder.setNotificationTitle("SONYBAE Merekam")
            hbRecorder.setNotificationDescription("Layar dan Audio Internal sedang direkam")

            // EKSEKUSI PEREKAMAN
            hbRecorder.startScreenRecording(data, resultCode)
            
            Toast.makeText(this, "Perekaman Dimulai! Tarik panel atas untuk Pause/Stop.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true) // Tutup activity agar layar bersih
        }
    }

    // --- LISTENER WAJIB DARI HBRECORDER ---
    override fun HBRecorderOnStart() {}
    
    override fun HBRecorderOnComplete() {
        Toast.makeText(this, "Video Tersimpan di Galeri (Movies/SONYBAE)", Toast.LENGTH_LONG).show()
    }
    
    override fun HBRecorderOnError(errorCode: Int, reason: String?) {
        Toast.makeText(this, "Gagal merekam: $reason", Toast.LENGTH_LONG).show()
    }
    
    override fun HBRecorderOnPause() {}
    override fun HBRecorderOnResume() {}
}
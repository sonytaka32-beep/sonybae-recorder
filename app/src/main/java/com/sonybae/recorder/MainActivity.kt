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
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_SCREEN_CAPTURE = 1001
    private val REQUEST_CODE_OVERLAY = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val btnStart = Button(this).apply {
            text = "MULAI REKAM (SONYBAE HD)"
            setOnClickListener { checkPermissionsAndStart() }
        }
        setContentView(btnStart)
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1003)
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
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish() // Tutup activity agar layar bersih sebelum merekam
        }
    }
}
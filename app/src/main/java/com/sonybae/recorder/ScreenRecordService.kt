package com.sonybae.recorder

import android.annotation.SuppressLint
import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoUri: Uri? = null

    private var targetBitrate = 20000000
    private var isPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            "START" -> {
                targetBitrate = intent.getIntExtra("BITRATE", 20000000)
                val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>("DATA")

                if (resultCode == Activity.RESULT_OK && data != null) {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                    
                    startForeground(1, createNotification())
                    startRecording()
                }
            }
            "PAUSE_RESUME" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (isPaused) {
                        mediaRecorder?.resume()
                        Toast.makeText(this, "Dilanjutkan...", Toast.LENGTH_SHORT).show()
                    } else {
                        mediaRecorder?.pause()
                        Toast.makeText(this, "Jeda.", Toast.LENGTH_SHORT).show()
                    }
                    isPaused = !isPaused
                    // Update notifikasi agar teks tombol berubah
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(1, createNotification())
                }
            }
            "STOP" -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val metrics = resources.displayMetrics
        // MENGAMBIL RESOLUSI MENTAH DARI HARDWARE (MENCEGAH BORDER HITAM)
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        val density = metrics.densityDpi

        // Pastikan genap agar codec H264 tidak crash
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--

        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "SONYBAE_FULL_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SONYBAE")
            }
        }

        videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        val fileDescriptor = resolver.openFileDescriptor(videoUri!!, "w")?.fileDescriptor

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

        mediaRecorder?.apply {
            // Menggunakan MIC agar lolos kompilasi.
            // Sistem HP (FuntouchOS/ItelOS) biasanya akan mengawinkan suara internal ke saluran ini.
            setAudioSource(MediaRecorder.AudioSource.MIC)
            
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (fileDescriptor != null) {
                setOutputFile(fileDescriptor)
            }
            setVideoSize(width, height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(targetBitrate) 
            setVideoFrameRate(60) 
            prepare()
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SONYBAE_REC",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface, null, null
        )

        mediaRecorder?.start()
        Toast.makeText(this, "Merekam Full Screen", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            Toast.makeText(this, "Video HD Tersimpan!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { e.printStackTrace() }
        
        mediaProjection?.stop()
        stopSelf()
    }

    // KONTROL VIA NOTIFIKASI (MENCEGAH TOMBOL MUNCUL DI VIDEO)
    private fun createNotification(): Notification {
        val channelId = "record_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Perekaman SONYBAE", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        // Action Intent untuk Pause/Resume
        val pauseIntent = Intent(this, ScreenRecordService::class.java).apply { action = "PAUSE_RESUME" }
        val pausePendingIntent = PendingIntent.getService(this, 0, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Action Intent untuk Stop
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val pauseText = if (isPaused) "▶️ RESUME" else "⏸️ PAUSE"

        return Notification.Builder(this, channelId)
            .setContentTitle("🔴 SONYBAE Sedang Merekam")
            .setContentText("Kontrol perekaman dari sini (tidak akan terekam)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, pauseText, pausePendingIntent)
            .addAction(android.R.drawable.ic_delete, "⏹️ STOP", stopPendingIntent)
            .setOngoing(true) // Notifikasi tidak bisa di-swipe
            .build()
    }
}
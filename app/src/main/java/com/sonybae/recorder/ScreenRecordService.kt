package com.sonybae.recorder

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var isPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopRecording()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")

        if (resultCode == Activity.RESULT_OK && data != null) {
            startForeground(1, createNotification())
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            setupFloatingWindow()
            startRecording()
        }
        return START_NOT_STICKY
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_control, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager.addView(floatingView, params)

        val btnPause = floatingView.findViewById<Button>(R.id.btnPauseResume)
        val btnStop = floatingView.findViewById<Button>(R.id.btnStop)

        btnPause.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (isPaused) {
                    mediaRecorder?.resume()
                    btnPause.text = "PAUSE"
                } else {
                    mediaRecorder?.pause()
                    btnPause.text = "RESUME"
                }
                isPaused = !isPaused
            }
        }

        btnStop.setOnClickListener {
            stopRecording()
        }
    }

    private fun startRecording() {
        val metrics = resources.displayMetrics
        // Pembulatan ke genap agar codec tidak error
        val width = metrics.widthPixels.let { if (it % 2 != 0) it - 1 else it }
        val height = metrics.heightPixels.let { if (it % 2 != 0) it - 1 else it }
        val density = metrics.densityDpi

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        
        val videoFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "SONYBAE_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        )

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoFile.absolutePath)
            setVideoSize(width, height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // BITRATE TINGGI: 18 Mbps untuk HD bebas pecah
            setVideoEncodingBitRate(18000000) 
            setVideoFrameRate(60) // 60 FPS untuk pergerakan halus
            prepare()
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SONYBAE_REC",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface, null, null
        )

        mediaRecorder?.start()
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
        } catch (e: Exception) { e.printStackTrace() }
        
        mediaProjection?.stop()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val channelId = "record_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Perekaman SONYBAE", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Perekaman Aktif")
            .setContentText("Merekam layar dengan Bitrate HD...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}
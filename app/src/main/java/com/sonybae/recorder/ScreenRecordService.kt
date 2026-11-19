package com.sonybae.recorder

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoUri: Uri? = null

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var isPaused = false
    
    private var targetResolution = 720
    private var targetBitrate = 15000000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")
        
        // Ambil pengaturan manual dari MainActivity
        targetResolution = intent?.getIntExtra("RESOLUTION", 720) ?: 720
        targetBitrate = intent?.getIntExtra("BITRATE", 15000000) ?: 15000000

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
        params.x = 20
        params.y = 150

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
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        // Kalkulasi resolusi proporsional sesuai rasio layar HP
        val shortSide = Math.min(screenWidth, screenHeight)
        val longSide = Math.max(screenWidth, screenHeight)
        val scale = targetResolution.toFloat() / shortSide.toFloat()
        
        var videoShort = targetResolution
        var videoLong = (longSide * scale).toInt()

        // WAJIB: Resolusi video Android harus genap, jika ganjil codec akan error
        if (videoShort % 2 != 0) videoShort--
        if (videoLong % 2 != 0) videoLong--

        val width = if (screenWidth < screenHeight) videoShort else videoLong
        val height = if (screenWidth < screenHeight) videoLong else videoShort

        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "SONYBAE_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SONYBAE")
            }
        }

        videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        val fileDescriptor = resolver.openFileDescriptor(videoUri!!, "w")?.fileDescriptor

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (fileDescriptor != null) {
                setOutputFile(fileDescriptor)
            }
            setVideoSize(width, height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(targetBitrate) // Menggunakan bitrate pilihan Anda
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
        Toast.makeText(this, "Merekam di ${targetResolution}p...", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            Toast.makeText(this, "Video Tersimpan!", Toast.LENGTH_LONG).show()
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
            .setContentTitle("SONYBAE Recording")
            .setContentText("Merekam di ${targetResolution}p / ${targetBitrate / 1000000} Mbps")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}
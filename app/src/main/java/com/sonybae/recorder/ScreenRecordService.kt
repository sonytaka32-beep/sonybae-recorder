package com.sonybae.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rekaman layar full-screen + AUDIO INTERNAL (suara game/sistem) memakai
 * MediaProjection + AudioPlaybackCapture + MediaCodec + MediaMuxer.
 *
 * Tidak memakai mikrofon. Tidak ada tombol melayang, kontrol via notifikasi.
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecordService"

        const val ACTION_START = "com.sonybae.recorder.action.START"
        const val ACTION_STOP = "com.sonybae.recorder.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_FPS = "extra_fps"

        private const val CHANNEL_ID = "sonybae_recording"
        private const val NOTIFICATION_ID = 1001

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2
        private const val AUDIO_BITRATE = 192_000
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var audioEnabled = true

    // MediaCodec surface timestamps may be based on the device monotonic clock.
    // Rebase each muxer track to zero to avoid huge/overflowed MP4 durations.
    private var videoPtsBaseUs: Long? = null
    private var audioPtsBaseUs: Long? = null
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var videoFrameDurationUs = 33_333L
    private val audioFrameDurationUs = 1_024_000_000L / SAMPLE_RATE

    private val muxerLock = Object()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var recording = false

    @Volatile
    private var stopping = false

    @Volatile
    private var videoDrainFailed = false

    @Volatile
    private var audioDrainFailed = false

    private val stopLock = Object()

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Wajib: service foreground bertipe mediaProjection harus sudah jalan
                // SEBELUM getMediaProjection() dipanggil (syarat Android 10+/14).
                startForegroundInternal()
                startRecording(intent)
            }
            ACTION_STOP -> stopRecording()
            else -> {
                // Intent di-restart sistem tanpa action: hentikan supaya tidak menggantung.
                if (!recording) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- START

    private fun startForegroundInternal() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(intent: Intent) {
        if (recording || stopping) return

        // Reset per-recording state in case this Service instance is reused.
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false
        audioEnabled = true
        videoPtsBaseUs = null
        audioPtsBaseUs = null
        lastVideoPtsUs = -1L
        lastAudioPtsUs = -1L
        videoFrameDurationUs = 1_000_000L / 30L
        videoDrainFailed = false
        audioDrainFailed = false

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null) {
            toast("Gagal: izin rekam layar tidak valid.")
            stopSelf()
            return
        }

        var width = intent.getIntExtra(EXTRA_WIDTH, 0)
        var height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val requestedBitrate = intent.getIntExtra(EXTRA_BITRATE, 20_000_000)
        val requestedFps = intent.getIntExtra(EXTRA_FPS, 30)

        if (width <= 0 || height <= 0) {
            val dm = resources.displayMetrics
            width = dm.widthPixels
            height = dm.heightPixels
        }
        // H264 butuh dimensi genap. 720 & 1612 sudah genap, jadi resolusi native utuh.
        width = alignEven(width)
        height = alignEven(height)

        val densityDpi = resources.displayMetrics.densityDpi

        try {
            // MediaProjection
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(resultCode, resultData)
            if (mp == null) {
                toast("Gagal memulai MediaProjection.")
                stopSelf()
                return
            }
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Pengguna mencabut izin dari sistem.
                    mainHandler.post { stopRecording() }
                }
            }, mainHandler)

            // Video encoder dengan fallback bertingkat (encoder MediaTek sering
            // menolak kombinasi bitrate/fps tinggi, yang sebelumnya bikin crash).
            val bundle = createVideoEncoder(width, height, requestedBitrate, requestedFps)
            videoFrameDurationUs = 1_000_000L / maxOf(1, bundle.fps)
            videoEncoder = bundle.encoder

            // Output file (langsung ke MediaStore, IS_PENDING sampai selesai)
            val uri = createOutputUri()
                ?: throw IllegalStateException("Gagal membuat file output MediaStore")
            outputUri = uri
            val pfd = contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Gagal membuka file output MediaStore")
            outputPfd = pfd
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Audio internal (Android 10+)
            audioEnabled = setupInternalAudio()

            // VirtualDisplay: ukuran HARUS sama dengan surface encoder hasil fallback,
            // kalau tidak video jadi bergeser/terpotong.
            virtualDisplay = mp.createVirtualDisplay(
                "SONYBAE_Recorder",
                bundle.width, bundle.height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                bundle.surface, null, mainHandler
            )

            recording = true

            videoThread = Thread({ drainVideo() }, "sonybae-video").apply { start() }
            if (audioEnabled) {
                audioThread = Thread({ drainAudio() }, "sonybae-audio").apply { start() }
                scheduleAudioFallback()
            }

            toast(
                if (audioEnabled) {
                    "Merekam ${bundle.width}x${bundle.height} @${bundle.fps} — audio internal, bukan mic."
                } else {
                    "Merekam layar saja (audio internal tidak tersedia)."
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            toast("Gagal mulai merekam: ${t.message}")
            recording = false
            releaseAll()
            stopForeground(true)
            stopSelf()
        }
    }

    private data class EncoderBundle(
        val encoder: MediaCodec,
        val surface: android.view.Surface,
        val width: Int,
        val height: Int,
        val fps: Int
    )

    private data class EncoderAttempt(
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val fps: Int
    )

    /**
     * Coba beberapa konfigurasi sampai encoder menerima. Encoder MediaTek
     * (Dimensity 6080 di Itel P55) sering menolak bitrate/fps tinggi; tanpa
     * fallback ini, configure() melempar exception dan app force close.
     */
    private fun createVideoEncoder(
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int
    ): EncoderBundle {
        val attempts = mutableListOf<EncoderAttempt>()
        attempts.add(EncoderAttempt(width, height, bitrate, fps))
        if (fps > 30) attempts.add(EncoderAttempt(width, height, bitrate, 30))
        for (br in intArrayOf(bitrate, 12_000_000, 10_000_000, 8_000_000, 5_000_000)) {
            attempts.add(EncoderAttempt(width, height, br, 30))
        }
        // Fallback resolusi berdasarkan sisi panjang, berlaku portrait maupun landscape.
        // Itel P55 portrait 720x1612 tetap mencoba native dulu; bila hardware menolak,
        // turunkan bertahap dengan rasio layar tetap sama.
        val longSide = maxOf(width, height)
        for (targetLongSide in intArrayOf(1080, 960, 720)) {
            if (longSide > targetLongSide) {
                val scale = targetLongSide.toFloat() / longSide
                val scaledWidth = alignEven((width * scale).toInt())
                val scaledHeight = alignEven((height * scale).toInt())
                attempts.add(EncoderAttempt(scaledWidth, scaledHeight, 8_000_000, 30))
                attempts.add(EncoderAttempt(scaledWidth, scaledHeight, 5_000_000, 30))
            }
        }
        // Buang percobaan duplikat supaya tidak mencoba konfigurasi yang sama 2x.
        val uniqueAttempts = attempts.distinct()

        var lastError: Throwable? = null
        for (attempt in uniqueAttempts) {
            var codec: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, attempt.width, attempt.height
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, attempt.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, attempt.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                Log.i(
                    TAG,
                    "Encoder OK: ${attempt.width}x${attempt.height} " +
                        "bitrate=${attempt.bitrate} fps=${attempt.fps}"
                )
                return EncoderBundle(
                    codec, surface, attempt.width, attempt.height, attempt.fps
                )
            } catch (t: Throwable) {
                lastError = t
                // Jangan tinggalkan codec yang gagal configure/start.
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                Log.w(
                    TAG,
                    "Encoder gagal ${attempt.width}x${attempt.height} " +
                        "bitrate=${attempt.bitrate} fps=${attempt.fps}: ${t.message}"
                )
            }
        }
        throw IllegalStateException(
            "Encoder H264 tidak menerima konfigurasi apa pun: ${lastError?.message}",
            lastError
        )
    }

    private fun alignEven(value: Int): Int {
        val even = (value / 2) * 2
        return if (even >= 2) even else 2
    }

    private fun createOutputUri(): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "SONYBAE_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/SONYBAE"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return runCatching {
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
    }

    /** @return true bila audio internal berhasil disiapkan. */
    private fun setupInternalAudio(): Boolean {
        // AudioPlaybackCapture hanya ada sejak Android 10 (API 29).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val mp = projection ?: return false
        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder = aEnc
            aEnc.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aEnc.start()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer * 4, 16384)

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                runCatching { aEnc.stop() }
                runCatching { aEnc.release() }
                audioEncoder = null
                Log.w(TAG, "AudioRecord tidak terinisialisasi")
                return false
            }

            audioRecord = record
            record.startRecording()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Audio internal gagal disiapkan", t)
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            audioEncoder = null
            false
        }
    }

    // ---------------------------------------------------------------- DRAIN

    private fun drainVideo() {
        val codec = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var stopDeadline = 0L
        try {
            while (true) {
                if (!recording && stopDeadline == 0L) {
                    stopDeadline = System.nanoTime() + 10_000_000_000L
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!recording && System.nanoTime() > stopDeadline) break
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        onFormatChanged(isVideo = true, codec.outputFormat)
                    }
                    index >= 0 -> {
                        val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && !isConfig) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writeSample(isVideo = true, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (endOfStream) break
                    }
                    else -> if (!recording && System.nanoTime() > stopDeadline) break
                }
            }
        } catch (t: Throwable) {
            videoDrainFailed = true
            Log.e(TAG, "drainVideo berhenti", t)
        }
    }

    /**
     * Kalau audio internal tidak pernah mengirim format (misal tidak ada suara
     * sama sekali di perangkat), jangan biarkan muxer menggantung selamanya:
     * setelah tenggang waktu, mulai muxer dengan track video saja.
     */
    private fun scheduleAudioFallback() {
        mainHandler.postDelayed({
            synchronized(muxerLock) {
                val m = muxer ?: return@postDelayed
                if (stopping || !recording) return@postDelayed
                if (muxerStarted) return@postDelayed
                if (videoTrackIndex < 0) return@postDelayed
                if (audioTrackIndex >= 0) return@postDelayed
                Log.w(TAG, "Format audio tidak muncul, lanjut video saja.")
                audioEnabled = false
                try {
                    m.start()
                    muxerStarted = true
                } catch (t: Throwable) {
                    Log.e(TAG, "Gagal memulai muxer video-saja", t)
                    mainHandler.post { stopRecording() }
                }
            }
        }, 2000)
    }

    private fun drainAudio() {
        val codec = audioEncoder ?: return
        val record = audioRecord ?: return
        val info = MediaCodec.BufferInfo()
        val bytesPerSecond = SAMPLE_RATE.toLong() * CHANNEL_COUNT * 2L
        var totalBytes = 0L
        var inputEosQueued = false
        var sawEos = false
        var stopDeadline = 0L
        val scratch = java.nio.ByteBuffer.allocateDirect(16384)

        try {
            while (true) {
                if (!recording && stopDeadline == 0L) {
                    stopDeadline = System.nanoTime() + 10_000_000_000L
                }
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuffer = codec.getInputBuffer(inIndex)
                    if (inBuffer != null) {
                        if (!recording && !inputEosQueued) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0,
                                totalBytes * 1_000_000L / bytesPerSecond,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEosQueued = true
                        } else if (recording) {
                            scratch.clear()
                            val read = record.read(scratch, scratch.capacity())
                            if (read > 0) {
                                val ptsUs = totalBytes * 1_000_000L / bytesPerSecond
                                totalBytes += read
                                scratch.position(0)
                                scratch.limit(read)
                                inBuffer.clear()
                                inBuffer.put(scratch)
                                codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                            } else {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                            }
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        }
                    }
                }

                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        onFormatChanged(isVideo = false, codec.outputFormat)
                        continue
                    }
                    if (outIndex < 0) break
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val buffer = codec.getOutputBuffer(outIndex)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        writeSample(isVideo = false, buffer, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (endOfStream) sawEos = true
                }

                if (sawEos || (!recording && System.nanoTime() > stopDeadline)) break
            }
        } catch (t: Throwable) {
            audioDrainFailed = true
            Log.e(TAG, "drainAudio berhenti", t)
        }
    }

    private fun onFormatChanged(isVideo: Boolean, format: MediaFormat) {
        synchronized(muxerLock) {
            val m = muxer ?: return
            // addTrack hanya boleh sebelum Muxer.start(); kalau sudah mulai, abaikan.
            if (muxerStarted) return
            if (isVideo) {
                if (videoTrackIndex < 0) videoTrackIndex = m.addTrack(format)
            } else {
                if (audioTrackIndex < 0) audioTrackIndex = m.addTrack(format)
            }
            maybeStartMuxer()
        }
    }

    private fun maybeStartMuxer() {
        if (muxerStarted) return
        val m = muxer ?: return
        if (videoTrackIndex < 0) return
        if (audioEnabled && audioTrackIndex < 0) return
        m.start()
        muxerStarted = true
    }

    private fun writeSample(
        isVideo: Boolean,
        buffer: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        synchronized(muxerLock) {
            if (!muxerStarted) return
            val m = muxer ?: return
            val track = if (isVideo) videoTrackIndex else audioTrackIndex
            if (track < 0) return

            // Normalize timestamps independently per track to start at 0.
            // Ensure strict monotonicity because some vendor codecs repeat PTS.
            val pts = info.presentationTimeUs
            val base = if (isVideo) videoPtsBaseUs else audioPtsBaseUs
            val normalizedBase = base ?: pts.also {
                if (isVideo) videoPtsBaseUs = it else audioPtsBaseUs = it
            }
            var normalizedPts = (pts - normalizedBase).coerceAtLeast(0L)
            if (isVideo) {
                // Preserve the frame cadence when MediaTek occasionally repeats PTS.
                if (normalizedPts <= lastVideoPtsUs) {
                    normalizedPts = lastVideoPtsUs + videoFrameDurationUs
                }
                lastVideoPtsUs = normalizedPts
            } else {
                // AAC output carries 1024-sample access units; correct any repeated
                // or regressing codec timestamp by one AAC frame duration.
                if (normalizedPts <= lastAudioPtsUs) {
                    normalizedPts = lastAudioPtsUs + audioFrameDurationUs
                }
                lastAudioPtsUs = normalizedPts
            }

            val normalizedInfo = MediaCodec.BufferInfo().apply {
                set(info.offset, info.size, normalizedPts, info.flags)
            }
            try {
                m.writeSampleData(track, buffer, normalizedInfo)
            } catch (t: Throwable) {
                Log.w(TAG, "writeSample gagal: ${t.message}")
            }
        }
    }

    // ---------------------------------------------------------------- STOP

    private fun stopRecording() {
        synchronized(stopLock) {
            if (stopping) return
            if (!recording && muxer == null) {
                stopSelf()
                return
            }
            stopping = true
        }

        try {
            // Hentikan sumber frame lebih dulu, lalu minta encoder mengeluarkan EOS.
            recording = false
            runCatching { audioRecord?.stop() }
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { videoEncoder?.signalEndOfInputStream() }

            // Beri encoder waktu mengeluarkan semua sample akhir sebelum muxer ditutup.
            // Satu deadline bersama mencegah dua thread memblokir service hingga 24 detik.
            val drainDeadline = System.nanoTime() + 12_000_000_000L
            val videoDrained = joinQuietly(videoThread, drainDeadline)
            val audioDrained = joinQuietly(audioThread, drainDeadline)
            videoThread = null
            audioThread = null
            val drainsFinished =
                videoDrained && audioDrained && !videoDrainFailed && !audioDrainFailed
            if (!drainsFinished) {
                Log.e(TAG, "Timeout menunggu drain encoder; batalkan file agar tidak menyimpan MP4 rusak.")
            }

            var finalizeOk = false
            synchronized(muxerLock) {
                val m = muxer
                if (m != null) {
                    if (muxerStarted && drainsFinished) {
                        finalizeOk = runCatching { m.stop() }.isSuccess
                    }
                    runCatching { m.release() }
                }
                muxer = null
                muxerStarted = false
            }
            // A file is complete only if muxer.stop succeeded and every worker
            // drained normally; don't publish partially written MP4 data.
            finalizeOk = finalizeOk && drainsFinished

            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            videoEncoder = null

            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            audioEncoder = null

            runCatching { audioRecord?.release() }
            audioRecord = null

            runCatching { projection?.stop() }
            projection = null

            runCatching { outputPfd?.close() }
            outputPfd = null

            finishOutput(finalizeOk)
            toast(
                if (finalizeOk) "Video tersimpan di Galeri > Movies > SONYBAE"
                else "Rekaman gagal disimpan."
            )
        } catch (t: Throwable) {
            Log.e(TAG, "stopRecording gagal", t)
            releaseAll()
        } finally {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun finishOutput(success: Boolean) {
        val uri = outputUri ?: return
        outputUri = null
        try {
            if (success) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            } else {
                contentResolver.delete(uri, null, null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "finishOutput: ${t.message}")
        }
    }

    private fun joinQuietly(thread: Thread?, deadlineNs: Long): Boolean {
        if (thread == null || thread === Thread.currentThread()) return true
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs > 0L) {
            val remainingMs = (remainingNs + 999_999L) / 1_000_000L
            runCatching { thread.join(remainingMs) }
        }
        return !thread.isAlive
    }

    private fun releaseAll() {
        recording = false
        runCatching { audioRecord?.stop() }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { videoEncoder?.signalEndOfInputStream() }
        val drainDeadline = System.nanoTime() + 12_000_000_000L
        joinQuietly(videoThread, drainDeadline)
        joinQuietly(audioThread, drainDeadline)
        videoThread = null
        audioThread = null
        synchronized(muxerLock) {
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
        }
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        audioEncoder = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        finishOutput(false)
        runCatching { outputPfd?.close() }
        outputPfd = null
    }

    override fun onDestroy() {
        if (recording) stopRecording()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- NOTIFICATION

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Perekaman Layar",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Kontrol perekaman SONYBAE"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SONYBAE RECORDER · MEREKAM")
            .setContentText("Audio internal saja. Ketuk STOP untuk menyimpan.")
            .setSmallIcon(R.drawable.ic_stat_record)
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "STOP", stopPending)
            .build()
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
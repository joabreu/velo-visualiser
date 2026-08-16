package com.lowlatency.visualizer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.PixelFormat
import android.view.Display
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.IntentCompat
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Foreground service that owns a MediaProjection session and captures *system
 * audio* via AudioPlaybackCapture (API 29+).
 *
 * Android 14+ requires:
 *   - a foreground service of type `mediaProjection`,
 *   - the service started *before* MediaProjection is used,
 *   - FOREGROUND_SERVICE_MEDIA_PROJECTION permission.
 *
 * Captured 16-bit PCM is pushed straight into the native ring buffer through
 * [NativeBridge.nativePushPcm], so the renderer consumes mic and system audio
 * through the exact same path.
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "audio_capture"
        private const val NOTIFICATION_ID = 0x5C09

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val ACTION_STOP = "com.lowlatency.visualizer.ACTION_STOP"
        const val ACTION_STOPPED = "com.lowlatency.visualizer.ACTION_STOPPED"

        /** Set on [ACTION_STOPPED] when capture never started (vs a normal stop). */
        const val EXTRA_FAILED = "capture_failed"

        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2 // playback capture is stereo

        // Read granularity, NOT buffer capacity: a blocking read() only returns
        // once this many frames have accumulated, so reading minBuf-sized
        // chunks (~40 ms on some devices) added that much latency on top of
        // the OS capture path. 256 frames ≈ 5.3 ms keeps delivery tracking the
        // mixer's own burst cadence instead — matching the local-playback
        // mirror's chunk convention (AudioEngine::pushPlaybackAudio).
        private const val CHUNK_FRAMES = 256

        // System audio (AudioPlaybackCapture) arrives near full-scale, whereas
        // the UNPROCESSED mic the visuals were tuned for is much quieter — so
        // internal audio saturates every scene. Attenuate it here, at the shared
        // ring-buffer source, so the waveform, the native FFT bands, and the
        // Kotlin spectrum analyzer all scale down together. ~ -10 dBFS.
        // MUST match AudioEngine::kDigitalMonoGain (local playback's mirror)
        // so every digital source drives the visuals identically.
        private const val SYSTEM_AUDIO_GAIN = 0.30f

        fun newIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }

    @Volatile private var capturing = false
    @Volatile private var captureFailed = false
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var readerThread: Thread? = null

    private var screenReader: ImageReader? = null
    private var screenDisplay: VirtualDisplay? = null
    private var screenThread: HandlerThread? = null
    private var screenHandler: Handler? = null
    private val screenAnalyzer = ScreenColorAnalyzer()

    // Background audio analysis state. This deliberately lives in the foreground
    // service rather than VisualizerRenderer so system-audio/Hue sync survives
    // Home/task removal.
    private val backgroundBeatDetector = BeatDetector()
    private var bassLp = 0f
    private var midLp = 0f
    private var levelFollow = 0f
    private var bassRatioSmooth = 0.5f
    private val beatWindow = FloatArray(1024)
    private var beatWindowCount = 0

    // Service-owned FFT data used by Hue while the Activity is backgrounded.
    private val fftBands = FloatArray(3)
    private val fftMagnitudes = FloatArray(128)
    private val fftPeaks = FloatArray(128)
    private val previousMagnitudes = FloatArray(128)
    private var havePreviousSpectrum = false

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "Missing MediaProjection token; stopping.")
            captureFailed = true
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // Tapping the notification body brings the visualizer forward
        // (singleTop launch mode resumes the existing instance).
        val openAppPendingIntent = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.capture_notification_stop), stopPendingIntent
                ).build()
            )
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startCapture(resultCode: Int, data: Intent) {

        val mpm = getSystemService(MediaProjectionManager::class.java)
        // getMediaProjection() is @Nullable on API 36+ — bail out cleanly if the
        // token can't be turned into a session.
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            Log.e(TAG, "Could not obtain MediaProjection; stopping.")
            captureFailed = true
            stopSelf()
            return
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system/user.")
                stopSelf()
            }
        }, null)
        projection = mp

        // Reuse the same MediaProjection grant for video. No second user
        // permission dialog is required.
        startScreenCapture(mp)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val rec = buildAndStartPlaybackCapture(mp, minBuf)
        if (rec == null) {
            // stopSelf() → onDestroy() broadcasts ACTION_STOPPED (+ EXTRA_FAILED),
            // which flips the UI back to the microphone with an explanation
            // instead of leaving a dead SYSTEM segment.
            captureFailed = true
            stopSelf()
            return
        }
        record = rec
        capturing = true

        // Read on a dedicated high-priority thread; forward to native engine.
        readerThread = thread(name = "PlaybackCaptureReader") {
            // Real audio scheduling class — Thread.MAX_PRIORITY barely moves
            // the Linux nice value, and 5 ms reads are jitter-sensitive.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(CHUNK_FRAMES * CHANNELS)
            while (capturing) {
                val read = record?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    CaptureHealth.markRawAudio()
                    analysePlaybackAudio(buf, read)
                    NativeBridge.nativePushPcm(buf, read / CHANNELS, CHANNELS, SYSTEM_AUDIO_GAIN)
                    updateSystemSpectrum()
                } else if (read < 0) {
                    // Mid-session death (route lost, permission revoked, …):
                    // stop the whole service so the notification clears and the
                    // UI falls back to the mic with a toast, instead of leaving
                    // a frozen SYSTEM screen under a live notification.
                    Log.e(TAG, "AudioRecord.read error: $read")
                    if (capturing) {
                        captureFailed = true
                        stopSelf()
                    }
                    break
                }
            }
        }
        Log.i(TAG, "System-audio capture started.")
    }

    /** Pull the same native 128-bin FFT used by the renderer and publish a
     * six-band envelope plus spectral flux for the background Hue path. */
    private fun updateSystemSpectrum() {
        val n = NativeBridge.fillLatestAll(fftBands, fftMagnitudes, fftPeaks, 0.02f)
        if (n <= 0) return

        val ranges = intArrayOf(1, 4, 8, 16, 32, 64, 128)
        val bands = FloatArray(6)
        for (b in 0 until 6) {
            var sum = 0f
            var count = 0
            for (i in ranges[b] until ranges[b + 1]) {
                if (i < fftMagnitudes.size) {
                    val v = fftMagnitudes[i].coerceAtLeast(0f)
                    sum += v * v
                    count++
                }
            }
            bands[b] = if (count > 0) sqrt(sum / count).coerceIn(0f, 1f) else 0f
        }

        var flux = 0f
        if (havePreviousSpectrum) {
            var positive = 0f
            var total = 0f
            for (i in fftMagnitudes.indices) {
                val now = fftMagnitudes[i].coerceAtLeast(0f)
                val prev = previousMagnitudes[i].coerceAtLeast(0f)
                positive += (now - prev).coerceAtLeast(0f)
                total += now
                previousMagnitudes[i] = now
            }
            flux = (positive / (total + 1e-4f) * 3.0f).coerceIn(0f, 1f)
        } else {
            for (i in fftMagnitudes.indices) previousMagnitudes[i] = fftMagnitudes[i]
            havePreviousSpectrum = true
        }

        AudioSyncBus.band0 = bands[0]
        AudioSyncBus.band1 = bands[1]
        AudioSyncBus.band2 = bands[2]
        AudioSyncBus.band3 = bands[3]
        AudioSyncBus.band4 = bands[4]
        AudioSyncBus.band5 = bands[5]
        AudioSyncBus.spectralFlux = flux
    }

    /**
     * Analyse the actual AudioPlaybackCapture PCM in the service. This is the
     * source that contains the Android playback mix before it is routed to
     * Bluetooth, so it continues working after the Activity/GL thread stops.
     */
    private fun analysePlaybackAudio(buf: ShortArray, samples: Int) {
        if (samples <= 0) return

        var peak = 0f
        var lowAcc = 0f
        var midAcc = 0f
        var highAcc = 0f
        var bassAcc = 0f
        var trebleAcc = 0f

        val invMax = 1f / 32768f
        var i = 0
        while (i + 1 < samples) {
            // Stereo -> mono. Apply the same digital gain used by the native
            // playback path so the service-side analysis has sensible levels.
            val s = (((buf[i].toInt() + buf[i + 1].toInt()) * 0.5f) * invMax) * SYSTEM_AUDIO_GAIN
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a

            bassLp += 0.025f * (s - bassLp)
            midLp += 0.20f * (s - midLp)

            val low = bassLp
            val mid = midLp - bassLp
            val high = s - midLp
            lowAcc += low * low
            midAcc += mid * mid
            highAcc += high * high
            bassAcc += low * low
            trebleAcc += (s - bassLp) * (s - bassLp)

            if (beatWindowCount < beatWindow.size) {
                beatWindow[beatWindowCount++] = s
            }
            i += CHANNELS
        }

        val frames = samples / CHANNELS
        if (frames <= 0) return
        val inv = 1f / frames
        val lowRms = sqrt(lowAcc * inv)
        val midRms = sqrt(midAcc * inv)
        val highRms = sqrt(highAcc * inv)
        val bassRms = sqrt(bassAcc * inv)
        val trebleRms = sqrt(trebleAcc * inv)

        levelFollow = if (peak > levelFollow) peak else levelFollow * 0.985f
        val rawRatio = if (peak < 0.002f) 0f else
            bassRms / (bassRms + trebleRms + 1e-6f)
        bassRatioSmooth += 0.25f * (rawRatio - bassRatioSmooth)

        val base = BeatSettings.levelBase
        val full = BeatSettings.levelFull
        val gate = ((levelFollow - base) / (full - base + 1e-6f)).coerceIn(0f, 1f)
        val loudness = gate * gate * (3f - 2f * gate)

        AudioSyncBus.low = lowRms
        AudioSyncBus.mid = midRms
        AudioSyncBus.high = highRms
        AudioSyncBus.level = levelFollow
        AudioSyncBus.bassRatio = bassRatioSmooth
        AudioSyncBus.loudness = loudness

        if (beatWindowCount == beatWindow.size) {
            if (backgroundBeatDetector.update(beatWindow) && loudness > 0f) {
                AudioSyncBus.beatCount++
            }
            beatWindowCount = 0
        }

        AudioSyncBus.lastAnalysisNs = System.nanoTime()
        CaptureHealth.markAudioAnalysis()
    }

    /**
     * Reuses the MediaProjection already granted for system audio to capture
     * the display into a small RGBA frame for perimeter colour extraction.
     */
    private fun startScreenCapture(mp: MediaProjection) {
        val metrics = android.util.DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(resources.displayMetrics)
        }
        val sourceWidth = metrics.widthPixels.coerceAtLeast(320)
        val sourceHeight = metrics.heightPixels.coerceAtLeast(180)

        // Capture the complete physical display at its native dimensions.
        // Do not downscale the VirtualDisplay: on Android TV the logical
        // metrics may otherwise result in only part of the framebuffer being
        // mirrored into the ImageReader.
        val width = sourceWidth
        val height = sourceHeight

        screenThread = HandlerThread("VeloScreenCapture").also { it.start() }
        screenHandler = Handler(screenThread!!.looper)

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            3
        )
        screenReader = reader

        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage()
                ?: return@setOnImageAvailableListener

            try {
                val crop = image.cropRect
                if (crop.width() != image.width || crop.height() != image.height) {
                    Log.w(
                        TAG,
                        "MediaProjection delivered cropped frame: " +
                            "image=${image.width}x${image.height} " +
                            "crop=${crop.left},${crop.top}-${crop.right},${crop.bottom}"
                    )
                }
                ScreenSyncBus.publish(screenAnalyzer.analyse(image))
            } catch (t: Throwable) {
                Log.w(TAG, "Screen colour analysis failed", t)
            } finally {
                image.close()
            }
        }, screenHandler)

        screenDisplay = mp.createVirtualDisplay(
            "VeloScreenSync",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            screenHandler
        )

        Log.i(
            TAG,
            "Screen capture started: real=${sourceWidth}x${sourceHeight} " +
                "virtual=${width}x${height} densityDpi=${metrics.densityDpi}"
        )
    }

    /**
     * Builds and starts the playback-capture AudioRecord, or returns null when
     * the device rejects it. Both build() and startRecording() throw on some
     * devices (rejected config, missing RECORD_AUDIO grant), and an uncaught
     * exception in onStartCommand would take down the whole app.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildAndStartPlaybackCapture(mp: MediaProjection, minBuf: Int): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        return try {
            val rec = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord did not enter RECORDING state; giving up.")
                rec.release()
                null
            } else {
                rec
            }
        } catch (e: RuntimeException) {
            // UnsupportedOperationException / IllegalStateException / SecurityException
            Log.e(TAG, "Playback-capture AudioRecord failed", e)
            null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The capture service is intentionally independent from the Activity
        // task. Leaving/swiping the app must not terminate screen/audio capture:
        // Hue synchronization has to continue while the video player is
        // running in the foreground.
        //
        // MediaProjection is owned by this foreground service, so keep the
        // service alive until the explicit Stop action or projection shutdown.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Stop the loop, then unblock + drain the reader BEFORE releasing the
        // AudioRecord — releasing it while the reader is parked in read() is
        // undefined and can crash the native layer on some devices.
        capturing = false
        record?.runCatching { stop() }          // unblocks a pending blocking read()
        readerThread?.runCatching { join(300) } // wait for the reader to exit its loop
        readerThread = null
        record?.runCatching { release() }
        record = null

        screenReader?.setOnImageAvailableListener(null, null)
        screenDisplay?.runCatching { release() }
        screenDisplay = null
        screenReader?.runCatching { close() }
        screenReader = null

        screenThread?.runCatching {
            quitSafely()
            join(300)
        }
        screenThread = null
        screenHandler = null

        ScreenSyncBus.clear()
        AudioSyncBus.reset()
        CaptureHealth.reset()

        projection?.stop()
        projection = null
        sendBroadcast(
            Intent(ACTION_STOPPED)
                .setPackage(packageName)
                .putExtra(EXTRA_FAILED, captureFailed)
        )
        super.onDestroy()
    }
}

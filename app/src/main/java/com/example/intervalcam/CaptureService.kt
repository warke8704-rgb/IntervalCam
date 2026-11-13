package com.example.intervalcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * خدمة أمامية (Foreground Service) تعمل بإحدى طريقتين:
 *  - صور: التقاط صورة بأعلى دقة كل فترة محددة.
 *  - فيديو: تسجيل فيديو متواصل بالجودة المختارة على ملفات مدة كل منها 10 دقائق.
 * وتحفظ في المجلد الذي اختاره المستخدم (خارج المعرض).
 */
class CaptureService : LifecycleService() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var folder: DocumentFile? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var mode = MODE_PHOTO
    private var videoQuality = 1
    private var withAudio = false
    private var intervalMs = 2000L

    private var running = false
    private var stopping = false
    private var busy = false
    private var count = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_PHOTO
        videoQuality = intent?.getIntExtra(EXTRA_VIDEO_QUALITY, 1) ?: 1
        intervalMs = intent?.getLongExtra(EXTRA_INTERVAL_MS, 2000L)?.coerceAtLeast(500L) ?: 2000L
        withAudio = mode == MODE_VIDEO &&
            (intent?.getBooleanExtra(EXTRA_AUDIO, false) ?: false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        val uriStr = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_FOLDER, null)
        folder = uriStr?.let { DocumentFile.fromTreeUri(this, Uri.parse(it)) }
        if (folder == null || folder?.canWrite() != true) {
            Toast.makeText(this, R.string.err_folder_access, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (withAudio) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.err_service, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalCam:capture").apply {
            acquire()
        }

        bindCamera()
        return START_NOT_STICKY
    }

    /** إيقاف هادئ: في وضع الفيديو ننتظر إنهاء الملف الجاري قبل إغلاق الخدمة. */
    private fun requestStop() {
        val rec = recording
        if (mode == MODE_VIDEO && rec != null && !stopping) {
            stopping = true
            rec.stop() // عند وصول حدث Finalize نغلق الخدمة
            // احتياط: إن لم يصل الحدث نغلق بعد 5 ثوانٍ
            handler.postDelayed({ stopSelf() }, 5000)
        } else {
            stopSelf()
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            provider.unbindAll()

            // معاينة وهمية غير مرئية: تُبقي التركيز التلقائي والتعريض يعملان
            // فتخرج الصور والفيديو أوضح وبإضاءة أدق.
            val mainExecutor = ContextCompat.getMainExecutor(this)
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(mainExecutor) { request ->
                val tex = SurfaceTexture(0)
                tex.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(tex)
                request.provideSurface(surface, mainExecutor) {
                    surface.release()
                    tex.release()
                }
            }

            try {
                if (mode == MODE_VIDEO) {
                    val quality = when (videoQuality) {
                        0 -> Quality.UHD
                        2 -> Quality.HD
                        else -> Quality.FHD
                    }
                    val selector = QualitySelector.from(
                        quality,
                        FallbackStrategy.lowerQualityOrHigherThan(quality)
                    )
                    val recorder = Recorder.Builder().setQualitySelector(selector).build()
                    val vc = VideoCapture.withOutput(recorder)
                    videoCapture = vc
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc)
                    running = true
                    startSegment()
                } else {
                    val resolution = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(resolution)
                        .build()
                    imageCapture = capture
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                    )
                    running = true
                    // مهلة قصيرة ليستقر التركيز والتعريض قبل أول صورة
                    handler.postDelayed(tick, 800)
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.err_service, Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------------------------------------------------------- الصور

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!busy) captureOne()
            handler.postDelayed(this, intervalMs)
        }
    }

    private fun captureOne() {
        val dir = folder ?: return
        val capture = imageCapture ?: return
        busy = true

        // إنشاء الملف (عملية SAF) خارج الخيط الرئيسي ثم طلب الالتقاط على الخيط الرئيسي
        executor.execute {
            val name = "IMG_${dateFormat.format(Date())}.jpg"
            val file = dir.createFile("image/jpeg", name)
            val out = file?.uri?.let { contentResolver.openOutputStream(it) }
            if (file == null || out == null) {
                busy = false
                return@execute
            }
            handler.post {
                if (!running) {
                    runCatching { out.close() }
                    file.delete()
                    busy = false
                    return@post
                }
                val options = ImageCapture.OutputFileOptions.Builder(out).build()
                capture.takePicture(
                    options,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            runCatching { out.close() }
                            count++
                            busy = false
                            updateNotification()
                        }

                        override fun onError(exception: ImageCaptureException) {
                            runCatching { out.close() }
                            file.delete()
                            busy = false
                        }
                    }
                )
            }
        }
    }

    // --------------------------------------------------------------- الفيديو

    /** يبدأ تسجيل ملف جديد؛ عند انتهاء مدته (10 دقائق) يبدأ التالي تلقائياً. */
    private fun startSegment() {
        val vc = videoCapture ?: return
        val dir = folder ?: return
        if (!running || stopping) return

        executor.execute {
            val name = "VID_${dateFormat.format(Date())}.mp4"
            val file = dir.createFile("video/mp4", name)
            val pfd: ParcelFileDescriptor? =
                file?.uri?.let { contentResolver.openFileDescriptor(it, "rw") }
            if (file == null || pfd == null) {
                handler.post { failVideo() }
                return@execute
            }
            handler.post {
                if (!running || stopping) {
                    runCatching { pfd.close() }
                    file.delete()
                    return@post
                }
                val options = FileDescriptorOutputOptions.Builder(pfd)
                    .setDurationLimitMillis(SEGMENT_MS)
                    .build()
                var pending = vc.output.prepareRecording(this, options)
                if (withAudio) pending = pending.withAudioEnabled()
                recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        runCatching { pfd.close() }
                        recording = null
                        val err = event.error
                        val ok = err == VideoRecordEvent.Finalize.ERROR_NONE ||
                            err == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                        if (ok) {
                            count++
                            updateNotification()
                        } else {
                            // ملف ناقص أو فارغ
                            executor.execute { runCatching { file.delete() } }
                        }
                        when {
                            stopping -> stopSelf()
                            ok && running -> startSegment()
                            else -> failVideo()
                        }
                    }
                }
            }
        }
    }

    private fun failVideo() {
        Toast.makeText(this, R.string.err_video, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    // --------------------------------------------------------------- الإشعار

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (mode == MODE_VIDEO) {
            getString(R.string.notif_text_video, count)
        } else {
            val seconds = (intervalMs / 1000f).toString().removeSuffix(".0")
            getString(R.string.notif_text, count, seconds)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        // احتياط: إن بقي تسجيل جارياً نوقفه قبل فك الكاميرا
        val rec = recording
        val provider = cameraProvider
        if (rec != null) {
            runCatching { rec.stop() }
            handler.postDelayed({ provider?.unbindAll() }, 1500)
        } else {
            provider?.unbindAll()
        }
        executor.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_MODE = "mode"
        const val EXTRA_VIDEO_QUALITY = "video_quality"
        const val EXTRA_AUDIO = "audio"
        const val MODE_PHOTO = "photo"
        const val MODE_VIDEO = "video"
        const val ACTION_STOP = "com.example.intervalcam.STOP"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1
        private const val SEGMENT_MS = 10L * 60 * 1000
    }
}

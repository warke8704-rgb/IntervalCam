package com.example.intervalcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
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
 * خدمة أمامية (Foreground Service) تلتقط صورة بالكاميرا الخلفية كل فترة محددة
 * وتحفظها في المجلد الذي اختاره المستخدم (خارج المعرض).
 */
class CaptureService : LifecycleService() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var folder: DocumentFile? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var intervalMs = 2000L
    private var running = false
    private var busy = false
    private var count = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        intervalMs = intent?.getLongExtra(EXTRA_INTERVAL_MS, 2000L)?.coerceAtLeast(500L) ?: 2000L

        val uriStr = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_FOLDER, null)
        folder = uriStr?.let { DocumentFile.fromTreeUri(this, Uri.parse(it)) }
        if (folder == null || folder?.canWrite() != true) {
            Toast.makeText(this, R.string.err_folder_access, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalCam:capture").apply {
            acquire()
        }

        bindCamera()
        return START_NOT_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            running = true
            handler.post(tick)
        }, ContextCompat.getMainExecutor(this))
    }

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
        val seconds = (intervalMs / 1000f).toString().removeSuffix(".0")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, count, seconds))
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
        cameraProvider?.unbindAll()
        executor.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val ACTION_STOP = "com.example.intervalcam.STOP"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1
    }
}

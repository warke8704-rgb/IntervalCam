package com.example.intervalcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etInterval: EditText
    private lateinit var tvFolder: TextView
    private lateinit var rbPhoto: RadioButton
    private lateinit var rbVideo: RadioButton
    private lateinit var llPhoto: View
    private lateinit var llVideo: View
    private lateinit var spQuality: Spinner
    private lateinit var cbAudio: CheckBox

    private val isVideo get() = rbVideo.isChecked

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
                showFolder()
            }
        }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                startCapture()
            } else {
                Toast.makeText(this, R.string.err_camera, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setTitle(R.string.app_name)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        etInterval = findViewById(R.id.etInterval)
        tvFolder = findViewById(R.id.tvFolder)
        rbPhoto = findViewById(R.id.rbPhoto)
        rbVideo = findViewById(R.id.rbVideo)
        llPhoto = findViewById(R.id.llPhoto)
        llVideo = findViewById(R.id.llVideo)
        spQuality = findViewById(R.id.spQuality)
        cbAudio = findViewById(R.id.cbAudio)

        etInterval.setText(prefs.getFloat(KEY_INTERVAL, 2f).toString().removeSuffix(".0"))

        val adapter = ArrayAdapter.createFromResource(
            this, R.array.video_qualities, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spQuality.adapter = adapter
        // الافتراضي: Full HD
        spQuality.setSelection(prefs.getInt(KEY_VQ, 1).coerceIn(0, 2))
        cbAudio.isChecked = prefs.getBoolean(KEY_AUDIO, true)

        if (prefs.getString(KEY_MODE, CaptureService.MODE_PHOTO) == CaptureService.MODE_VIDEO) {
            rbVideo.isChecked = true
        } else {
            rbPhoto.isChecked = true
        }
        updateModeViews()
        rbPhoto.setOnClickListener { updateModeViews() }
        rbVideo.setOnClickListener { updateModeViews() }

        showFolder()

        findViewById<Button>(R.id.btnFolder).setOnClickListener { pickFolder.launch(null) }
        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            // إيقاف هادئ: تُنهي الخدمة تسجيل الفيديو الجاري قبل أن تتوقف
            startService(
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
            )
            Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateModeViews() {
        llPhoto.visibility = if (isVideo) View.GONE else View.VISIBLE
        llVideo.visibility = if (isVideo) View.VISIBLE else View.GONE
    }

    private fun showFolder() {
        val uriStr = prefs.getString(KEY_FOLDER, null)
        if (uriStr == null) {
            tvFolder.setText(R.string.no_folder)
        } else {
            val name = DocumentFile.fromTreeUri(this, Uri.parse(uriStr))?.name ?: uriStr
            tvFolder.text = getString(R.string.folder_prefix, name)
        }
    }

    private fun onStartClicked() {
        if (!isVideo) {
            val seconds = etInterval.text.toString().trim().toFloatOrNull()
            if (seconds == null || seconds < 0.5f) {
                Toast.makeText(this, R.string.err_interval, Toast.LENGTH_LONG).show()
                return
            }
            prefs.edit().putFloat(KEY_INTERVAL, seconds).apply()
        }
        if (prefs.getString(KEY_FOLDER, null) == null) {
            Toast.makeText(this, R.string.err_folder, Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit()
            .putString(
                KEY_MODE,
                if (isVideo) CaptureService.MODE_VIDEO else CaptureService.MODE_PHOTO
            )
            .putInt(KEY_VQ, spQuality.selectedItemPosition)
            .putBoolean(KEY_AUDIO, cbAudio.isChecked)
            .apply()

        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) {
            needed += Manifest.permission.CAMERA
        }
        if (isVideo && cbAudio.isChecked && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) startCapture() else askPermissions.launch(needed.toTypedArray())
    }

    private fun startCapture() {
        val seconds = prefs.getFloat(KEY_INTERVAL, 2f)
        val wantAudio = isVideo && cbAudio.isChecked
        if (wantAudio && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, R.string.warn_no_audio, Toast.LENGTH_LONG).show()
        }
        val intent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_INTERVAL_MS, (seconds * 1000).toLong())
            .putExtra(
                CaptureService.EXTRA_MODE,
                if (isVideo) CaptureService.MODE_VIDEO else CaptureService.MODE_PHOTO
            )
            .putExtra(CaptureService.EXTRA_VIDEO_QUALITY, spQuality.selectedItemPosition)
            .putExtra(CaptureService.EXTRA_AUDIO, wantAudio)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.started, Toast.LENGTH_SHORT).show()
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val PREFS = "prefs"
        const val KEY_FOLDER = "folder"
        const val KEY_INTERVAL = "interval"
        const val KEY_MODE = "mode"
        const val KEY_VQ = "video_quality"
        const val KEY_AUDIO = "audio"
    }
}

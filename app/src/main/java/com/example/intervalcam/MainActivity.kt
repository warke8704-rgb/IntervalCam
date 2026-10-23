package com.example.intervalcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true ||
                hasPermission(Manifest.permission.CAMERA)
            ) {
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

        etInterval.setText(prefs.getFloat(KEY_INTERVAL, 2f).toString().removeSuffix(".0"))
        showFolder()

        findViewById<Button>(R.id.btnFolder).setOnClickListener { pickFolder.launch(null) }
        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
        }
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
        val seconds = etInterval.text.toString().trim().toFloatOrNull()
        if (seconds == null || seconds < 0.5f) {
            Toast.makeText(this, R.string.err_interval, Toast.LENGTH_LONG).show()
            return
        }
        if (prefs.getString(KEY_FOLDER, null) == null) {
            Toast.makeText(this, R.string.err_folder, Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putFloat(KEY_INTERVAL, seconds).apply()

        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) {
            needed += Manifest.permission.CAMERA
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
        val intent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_INTERVAL_MS, (seconds * 1000).toLong())
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.started, Toast.LENGTH_SHORT).show()
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val PREFS = "prefs"
        const val KEY_FOLDER = "folder"
        const val KEY_INTERVAL = "interval"
    }
}

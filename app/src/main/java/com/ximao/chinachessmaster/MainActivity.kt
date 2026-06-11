package com.ximao.chinachessmaster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ximao.chinachessmaster.service.OverlayService
import com.ximao.chinachessmaster.service.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能运行", Toast.LENGTH_SHORT).show()
            }
        }

        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startServices(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "需要屏幕录制权限才能运行", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.btn_start).setOnClickListener {
            checkPermissionsAndStart()
        }

        updatePermissionHint()
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (ScreenCaptureService.getInstance() != null) {
            startOverlayServiceOnly()
            return
        }

        requestScreenCapture()
    }

    private fun startOverlayServiceOnly() {
        val overlayIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent)
        } else {
            startService(overlayIntent)
        }
        Toast.makeText(this, "象棋大师已启动，请切换到象棋应用", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestScreenCapture() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startServices(resultCode: Int, data: Intent) {
        val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(captureIntent)
        } else {
            startService(captureIntent)
        }

        val overlayIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent)
        } else {
            startService(overlayIntent)
        }

        Toast.makeText(this, "象棋大师已启动，请切换到象棋应用", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun updatePermissionHint() {
        val hintView = findViewById<TextView>(R.id.text_permission_hint)
        val hints = mutableListOf<String>()
        if (!Settings.canDrawOverlays(this)) {
            hints.add("悬浮窗")
        }
        if (ScreenCaptureService.getInstance() == null) {
            hints.add("屏幕录制")
        }

        if (hints.isNotEmpty()) {
            hintView.text = "需要 ${hints.joinToString("、")} 权限"
        } else {
            hintView.text = "权限已就绪，点击开始"
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionHint()
    }
}
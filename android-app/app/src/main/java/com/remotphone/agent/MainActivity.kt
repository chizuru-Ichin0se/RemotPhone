package com.remotphone.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.remotphone.agent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaProjectionIntent: Intent? = null

    private val screenCaptureRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            mediaProjectionIntent = result.data
            startRemoteService(result.data!!)
            log("✅ Трансляция экрана запущена")
            binding.screenCaptureBtn.text = "⏹ Остановить трансляцию"
        } else {
            log("❌ Разрешение на захват экрана отклонено")
        }
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.filter { it.value }.map { it.key }
        val denied = permissions.entries.filter { !it.value }.map { it.key }
        if (granted.isNotEmpty()) log("✅ Разрешения получены: ${granted.size}")
        if (denied.isNotEmpty()) log("⚠️ Отклонено: ${denied.size}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved server URL
        val prefs = getSharedPreferences("remotphone", MODE_PRIVATE)
        prefs.getString("serverUrl", null)?.let {
            binding.serverUrlInput.setText(it)
        }

        setupButtons()
        requestPermissions()
    }

    private fun setupButtons() {
        // Connect button
        binding.connectBtn.setOnClickListener {
            val url = binding.serverUrlInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Введите адрес сервера", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save URL
            getSharedPreferences("remotphone", MODE_PRIVATE)
                .edit().putString("serverUrl", url).apply()

            if (RemoteService.isRunning) {
                // Disconnect
                stopService(Intent(this, RemoteService::class.java))
                updateUI(false)
                log("🔌 Отключено")
            } else {
                // Connect
                val intent = Intent(this, RemoteService::class.java).apply {
                    putExtra("serverUrl", url)
                    putExtra("action", "connect")
                }
                ContextCompat.startForegroundService(this, intent)
                log("🔗 Подключение к $url ...")
                binding.connectBtn.text = "Отключиться"

                // Wait for connection result
                binding.root.postDelayed({
                    if (RemoteService.isRunning && RemoteService.pairingCode != null) {
                        updateUI(true)
                        binding.codeText.text = RemoteService.pairingCode
                    }
                }, 2000)

                // Poll for code
                pollForCode()
            }
        }

        // Screen capture button
        binding.screenCaptureBtn.setOnClickListener {
            if (RemoteService.isStreaming) {
                RemoteService.instance?.stopScreenCapture()
                binding.screenCaptureBtn.text = "▶ Начать трансляцию экрана"
                log("⏹ Трансляция остановлена")
            } else {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureRequest.launch(mpManager.createScreenCaptureIntent())
            }
        }

        // Accessibility settings
        binding.accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            log("⚙️ Откройте RemotPhone в списке")
        }

        // Notification listener settings
        binding.notificationBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            log("🔔 Включите RemotPhone в списке")
        }

        // Battery optimization
        binding.batteryBtn.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun pollForCode() {
        val handler = binding.root.handler ?: return
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (RemoteService.isRunning) {
                    val code = RemoteService.pairingCode
                    if (code != null) {
                        updateUI(true)
                        binding.codeText.text = code
                        log("🔑 Код: $code")
                    } else {
                        handler.postDelayed(this, 500)
                    }
                }
            }
        }, 500)
    }

    private fun startRemoteService(projectionData: Intent) {
        val intent = Intent(this, RemoteService::class.java).apply {
            putExtra("action", "start_capture")
            putExtra("projectionData", projectionData)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updateUI(connected: Boolean) {
        if (connected) {
            binding.statusDot.setBackgroundResource(R.drawable.dot_online)
            binding.statusText.text = "Подключено к серверу"
            binding.statusText.setTextColor(0xFF00D2A0.toInt())
            binding.connectBtn.text = "Отключиться"
            binding.codeContainer.visibility = View.VISIBLE
            binding.screenCaptureBtn.visibility = View.VISIBLE
        } else {
            binding.statusDot.setBackgroundResource(R.drawable.dot_offline)
            binding.statusText.text = "Не подключено"
            binding.statusText.setTextColor(0xFF888899.toInt())
            binding.connectBtn.text = "Подключиться к серверу"
            binding.codeContainer.visibility = View.GONE
            binding.screenCaptureBtn.visibility = View.GONE
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionRequest.launch(needed.toTypedArray())
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            val current = binding.logText.text.toString()
            val lines = current.split("\n").takeLast(8)
            binding.logText.text = (lines + msg).joinToString("\n")
        }
    }
}

package com.remotphone.agent

import android.app.*
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer

class RemoteService : Service() {

    companion object {
        var instance: RemoteService? = null
        var isRunning = false
        var isStreaming = false
        var pairingCode: String? = null

        const val NOTIF_CHANNEL = "remotphone_channel"
        const val NOTIF_ID = 1001
    }

    private var wsClient: WebSocketClient? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val gson = Gson()
    private var screenWidth = 1080
    private var screenHeight = 2340
    private var screenDpi = 320
    private var frameQuality = 40 // JPEG quality (lower = faster)
    private var frameInterval = 100L // ms between frames
    private val frameHandler = Handler(Looper.getMainLooper())
    private var isPcConnected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        getScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Запуск..."))

        when (intent?.getStringExtra("action")) {
            "connect" -> {
                val url = intent.getStringExtra("serverUrl") ?: return START_NOT_STICKY
                connectToServer(url)
            }
            "start_capture" -> {
                val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("projectionData", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("projectionData")
                }
                if (projectionData != null) {
                    startScreenCapture(projectionData)
                }
            }
        }

        return START_STICKY
    }

    private fun getScreenMetrics() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDpi = metrics.densityDpi
    }

    // ─── WebSocket ─────────────────────────────────────
    private fun connectToServer(url: String) {
        wsClient?.close()

        wsClient = object : WebSocketClient(URI(url)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                isRunning = true
                updateNotification("Подключено к серверу")

                // Register as phone
                val info = JsonObject().apply {
                    addProperty("type", "phone_register")
                    add("deviceInfo", gson.toJsonTree(mapOf(
                        "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "device" to Build.DEVICE,
                        "androidVersion" to Build.VERSION.RELEASE,
                        "sdkVersion" to Build.VERSION.SDK_INT,
                        "screenWidth" to screenWidth,
                        "screenHeight" to screenHeight
                    )))
                }
                send(info.toString())
            }

            override fun onMessage(message: String) {
                handleMessage(message)
            }

            override fun onMessage(bytes: ByteBuffer) {
                // Binary messages from PC (e.g., file uploads)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                isRunning = false
                isPcConnected = false
                pairingCode = null
                updateNotification("Отключено")

                // Auto-reconnect after 5 seconds
                Handler(Looper.getMainLooper()).postDelayed({
                    if (instance != null && !isRunning) {
                        // Try reconnecting
                    }
                }, 5000)
            }

            override fun onError(ex: Exception?) {
                updateNotification("Ошибка: ${ex?.message}")
            }
        }

        wsClient?.connect()
    }

    private fun handleMessage(raw: String) {
        try {
            val msg = gson.fromJson(raw, JsonObject::class.java)
            val type = msg.get("type")?.asString ?: return

            when (type) {
                "registered" -> {
                    pairingCode = msg.get("code")?.asString
                    updateNotification("Код: $pairingCode")
                }

                "pc_connected" -> {
                    isPcConnected = true
                    updateNotification("ПК подключён")
                    // Send device info
                    sendDeviceInfo()
                    sendBatteryInfo()
                }

                "peer_disconnected" -> {
                    isPcConnected = false
                    updateNotification("ПК отключился. Код: $pairingCode")
                }

                // ── Screen commands ──
                "request_screen" -> {
                    if (isStreaming) captureAndSendFrame()
                }

                "screen_config" -> {
                    msg.get("quality")?.asInt?.let { frameQuality = it.coerceIn(10, 90) }
                    msg.get("interval")?.asLong?.let { frameInterval = it.coerceIn(50, 2000) }
                }

                // ── Touch / Input ──
                "touch" -> {
                    val x = msg.get("x")?.asInt ?: return
                    val y = msg.get("y")?.asInt ?: return
                    RemoteAccessibilityService.instance?.performTap(x.toFloat(), y.toFloat())
                }

                "swipe" -> {
                    val sx = msg.get("startX")?.asInt ?: return
                    val sy = msg.get("startY")?.asInt ?: return
                    val ex = msg.get("endX")?.asInt ?: return
                    val ey = msg.get("endY")?.asInt ?: return
                    val dur = msg.get("duration")?.asLong ?: 300
                    RemoteAccessibilityService.instance?.performSwipe(
                        sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), dur
                    )
                }

                "text_input" -> {
                    val text = msg.get("text")?.asString ?: return
                    RemoteAccessibilityService.instance?.inputText(text)
                }

                "key" -> {
                    val keyCode = msg.get("keyCode")?.asInt ?: return
                    RemoteAccessibilityService.instance?.pressKey(keyCode)
                }

                "back" -> {
                    RemoteAccessibilityService.instance?.performBack()
                }

                "home" -> {
                    RemoteAccessibilityService.instance?.performHome()
                }

                "recents" -> {
                    RemoteAccessibilityService.instance?.performRecents()
                }

                // ── Data requests ──
                "request_info" -> sendDeviceInfo()
                "request_battery" -> sendBatteryInfo()
                "request_notifications" -> { /* Handled by NotificationListener */ }

                "request_sms" -> sendSMSList()
                "send_sms" -> {
                    val to = msg.get("to")?.asString ?: return
                    val body = msg.get("body")?.asString ?: return
                    sendSMS(to, body)
                }

                "request_files" -> {
                    val path = msg.get("path")?.asString
                    sendFileList(path)
                }

                "download_file" -> {
                    val path = msg.get("path")?.asString ?: return
                    sendFileData(path)
                }

                "request_apps" -> sendAppList()
                "launch_app" -> {
                    val pkg = msg.get("packageName")?.asString ?: return
                    launchApp(pkg)
                }

                "request_clipboard" -> sendClipboard()
                "set_clipboard" -> {
                    val text = msg.get("text")?.asString ?: ""
                    setClipboard(text)
                }

                "volume_up" -> {
                    val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    audio.adjustVolume(android.media.AudioManager.ADJUST_RAISE, 0)
                }

                "volume_down" -> {
                    val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    audio.adjustVolume(android.media.AudioManager.ADJUST_LOWER, 0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── Screen Capture ────────────────────────────────
    fun startScreenCapture(projectionData: Intent) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(Activity.RESULT_OK, projectionData)

        val captureWidth = screenWidth / 2  // Scale down for performance
        val captureHeight = screenHeight / 2

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RemotPhone",
            captureWidth, captureHeight, screenDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )

        isStreaming = true
        startFrameLoop()
        updateNotification("Трансляция экрана...")
    }

    fun stopScreenCapture() {
        isStreaming = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        updateNotification("Подключено (экран выкл)")
    }

    private fun startFrameLoop() {
        frameHandler.post(object : Runnable {
            override fun run() {
                if (isStreaming && isPcConnected) {
                    captureAndSendFrame()
                    frameHandler.postDelayed(this, frameInterval)
                }
            }
        })
    }

    private fun captureAndSendFrame() {
        try {
            val image = imageReader?.acquireLatestImage() ?: return
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Crop to actual size
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            if (cropped != bitmap) bitmap.recycle()

            // Compress and send as binary
            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, frameQuality, stream)
            cropped.recycle()

            val bytes = stream.toByteArray()
            wsClient?.send(bytes)
        } catch (e: Exception) {
            // Frame capture can fail intermittently, ignore
        }
    }

    // ─── Device Info ───────────────────────────────────
    private fun sendDeviceInfo() {
        val info = JsonObject().apply {
            addProperty("type", "device_info")
            addProperty("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            addProperty("androidVersion", Build.VERSION.RELEASE)
            addProperty("sdkVersion", Build.VERSION.SDK_INT)
            addProperty("screenWidth", screenWidth)
            addProperty("screenHeight", screenHeight)
        }
        wsClient?.send(info.toString())
    }

    private fun sendBatteryInfo() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging

        val info = JsonObject().apply {
            addProperty("type", "battery_info")
            addProperty("level", level)
            addProperty("charging", charging)
        }
        wsClient?.send(info.toString())
    }

    // ─── SMS ───────────────────────────────────────────
    private fun sendSMSList() {
        try {
            val messages = mutableListOf<Map<String, Any?>>()
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null,
                "${Telephony.Sms.DATE} DESC LIMIT 50"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    messages.add(mapOf(
                        "address" to it.getString(0),
                        "body" to it.getString(1),
                        "date" to it.getLong(2),
                        "type" to it.getInt(3)
                    ))
                }
            }

            val msg = JsonObject().apply {
                addProperty("type", "sms_list")
                add("messages", gson.toJsonTree(messages))
            }
            wsClient?.send(msg.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendSMS(to: String, body: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(to, null, body, null, null)

            val result = JsonObject().apply {
                addProperty("type", "sms_sent")
                addProperty("success", true)
                addProperty("to", to)
            }
            wsClient?.send(result.toString())
        } catch (e: Exception) {
            val result = JsonObject().apply {
                addProperty("type", "sms_sent")
                addProperty("success", false)
                addProperty("error", e.message)
            }
            wsClient?.send(result.toString())
        }
    }

    // ─── Files ─────────────────────────────────────────
    private fun sendFileList(path: String?) {
        try {
            val dir = if (path != null) File(path) else Environment.getExternalStorageDirectory()
            val files = dir.listFiles()?.map { f ->
                mapOf(
                    "name" to f.name,
                    "path" to f.absolutePath,
                    "isDir" to f.isDirectory,
                    "size" to f.length(),
                    "modified" to f.lastModified()
                )
            }?.sortedWith(compareByDescending<Map<String, Any?>> { it["isDir"] as Boolean }.thenBy { (it["name"] as String).lowercase() })
                ?: emptyList()

            val msg = JsonObject().apply {
                addProperty("type", "file_list")
                addProperty("path", dir.absolutePath)
                add("files", gson.toJsonTree(files))
            }
            wsClient?.send(msg.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendFileData(path: String) {
        try {
            val file = File(path)
            if (!file.exists() || file.length() > 10 * 1024 * 1024) return // Max 10MB

            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val msg = JsonObject().apply {
                addProperty("type", "file_data")
                addProperty("name", file.name)
                addProperty("path", path)
                addProperty("data", base64)
                addProperty("size", file.length())
            }
            wsClient?.send(msg.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── Apps ──────────────────────────────────────────
    private fun sendAppList() {
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map {
                    mapOf(
                        "name" to pm.getApplicationLabel(it).toString(),
                        "packageName" to it.packageName
                    )
                }
                .sortedBy { (it["name"] as String).lowercase() }

            val msg = JsonObject().apply {
                addProperty("type", "app_list")
                add("apps", gson.toJsonTree(apps))
            }
            wsClient?.send(msg.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── Clipboard ─────────────────────────────────────
    private fun sendClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString() ?: ""

        val msg = JsonObject().apply {
            addProperty("type", "clipboard_data")
            addProperty("text", text)
        }
        wsClient?.send(msg.toString())
    }

    private fun setClipboard(text: String) {
        Handler(Looper.getMainLooper()).post {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("RemotPhone", text))
        }
    }

    // ─── Send to PC helper ─────────────────────────────
    fun sendToPC(json: String) {
        if (isPcConnected) {
            wsClient?.send(json)
        }
    }

    // ─── Notification ──────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("RemotPhone")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopScreenCapture()
        wsClient?.close()
        isRunning = false
        isPcConnected = false
        pairingCode = null
        instance = null
        super.onDestroy()
    }
}

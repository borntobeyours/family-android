package com.apps.myfamily

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.apps.myfamily.network.ApiConfig
import com.apps.myfamily.MyDeviceAdminReceiver
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CommandService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val interval = 30000L // 30 detik

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        handler.post(object : Runnable {
            override fun run() {
                pollCommand()
                handler.postDelayed(this, interval)
            }
        })
    }

    private fun pollCommand() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val url = "${ApiConfig.BASE_URL}/api/device/command?device_id=$deviceId"

        val request = Request.Builder().url(url).get().build()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("CmdService", "Poll failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                if (response.code == 204) return

                try {
                    val json = JSONObject(body)
                    val command = json.getString("command")
                    val params = json.getJSONObject("params")
                    
                    // Log received command
                    Log.d("CmdService", "Received command: $command with params: $params")

                    when (command) {
                        "lock_screen" -> {
                            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            val compName = ComponentName(applicationContext, MyDeviceAdminReceiver::class.java)
                            if (dpm.isAdminActive(compName)) dpm.lockNow()
                        }

                        "take_photo" -> {
                            val camera = params.optString("camera", "front")
                            val uploadUrl = params.optString("upload_url")
                            Log.d("CmdService", "Initiating photo capture and upload with camera: $camera, uploadUrl: $uploadUrl")
                            takePhoto(camera, uploadUrl)
                            Log.d("CmdService", "Photo capture and upload initiated")
                        }
                        
                        "show_notification" -> {
                            val title = params.optString("title", "Family Control")
                            val message = params.optString("message", "New notification")
                            showCustomNotification(title, message)
                            Log.d("CmdService", "Showing notification with title: $title, message: $message")
                        }

                        "lock_with_message" -> {
                            val message = params.optString("message", "Layar akan dikunci")
                            showWarningUI(applicationContext, message)
                        }
                    }

                } catch (e: Exception) {
                    Log.e("CmdService", "Parse error: ${e.message}")
                }
            }
        })
    }

    private fun startForegroundNotification() {
        val channelId = "command_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "Family Control Service",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Family Control")
            .setContentText("Service aktif")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Shows a custom notification with the specified title and message
     */
    private fun showCustomNotification(title: String, message: String) {
        val channelId = "custom_notifications"
        
        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Custom Notifications",
                NotificationManager.IMPORTANCE_HIGH // Use HIGH to ensure notification shows even when app is closed
            ).apply {
                description = "Notifications sent through command API"
                enableVibration(true)
                enableLights(true)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        // Build the notification
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        
        // Show notification
        with(NotificationManagerCompat.from(this)) {
            // Use a unique ID based on current time to avoid overwriting previous notifications
            val notificationId = System.currentTimeMillis().toInt()
            try {
                notify(notificationId, notificationBuilder.build())
                Log.d("CmdService", "Notification displayed with ID: $notificationId")
            } catch (e: SecurityException) {
                Log.e("CmdService", "Permission denied for showing notification: ${e.message}")
            }
        }
    }

    /**
     * Captures a photo using the specified camera and uploads it to the given URL
     * This method ensures proper handling of camera resources in a background context
     *
     * @param cameraFacing "front" or "back" camera to use
     * @param uploadUrl URL where the photo should be uploaded
     */
    private fun takePhoto(cameraFacing: String, uploadUrl: String) {
        Log.d("CmdService", "Taking photo with camera: $cameraFacing, uploadUrl: $uploadUrl")
        
        // Update notification to indicate camera use (required for Android 9+)
        Log.d("CmdService", "Updating foreground notification to indicate camera use")
        updateForegroundNotification("Taking photo...")
        
        try {
            // Use PhotoUtils for better error handling and compression
            PhotoUtils.takePhotoAndUpload(applicationContext, cameraFacing, uploadUrl)
        } catch (e: Exception) {
            Log.e("CmdService", "Error in takePhoto: ${e.message}", e)
        } finally {
            // Reset notification after photo capture attempt
            Handler(Looper.getMainLooper()).postDelayed({
                updateForegroundNotification("Service aktif")
            }, 3000) // Delay to ensure photo capture has time to complete
        }
    }
    
    /**
     * Updates the foreground notification with a new message
     *
     * @param message The message to display in the notification
     */
    private fun updateForegroundNotification(message: String) {
        val channelId = "command_service"
        
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Family Control")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build()
        } else {
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Family Control")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }

    fun showWarningUI(context: Context, message: String) {
        val intent = Intent(context, WarningActivity::class.java).apply {
            putExtra("message", message)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        context.startActivity(intent)
    }    
    
}

package com.apps.myfamily

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.location.Location
import android.view.Surface
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.apps.myfamily.network.ApiConfig
import com.apps.myfamily.ui.theme.FamilyControlAppTheme
import com.google.android.gms.location.LocationServices
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {
    // Store pending notification to show after permission is granted
    private var pendingNotification: Pair<String, String>? = null
    
    // Track if we're returning from settings
    private var returningFromSettings = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FamilyControlAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Registering...",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        // Check location permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1002)
        }
        

        // Panggil fungsi untuk kirim info device
        requestDeviceAdmin()
        startCommandService()
        registerDevice()
        sendAppUsageToBackend()
        sendDeviceLocation()
        startPollingCommands()
        
        // Register activity lifecycle callbacks to detect return from settings
        registerActivityLifecycleCallbacks()
    }

    private fun startCommandService() {
        val intent = Intent(this, CommandService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
    

    private fun registerDevice() {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val model = Build.MODEL
        val androidVersion = Build.VERSION.RELEASE

        val json = JSONObject()
        json.put("device_id", androidId)
        json.put("model", model)
        json.put("android_version", androidVersion)

        val client = OkHttpClient()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = RequestBody.create(mediaType, json.toString())

        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/device/register")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("RegisterDevice", "Failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string()
                if (response.isSuccessful) {
                    Log.i("RegisterDevice", "Success: $responseText")
                } else {
                    Log.e("RegisterDevice", "Error ${response.code}: $responseText")
                }
            }
        })
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun sendAppUsageToBackend() {
        if (!hasUsageStatsPermission()) {
            requestUsageStatsPermission()
            return
        }

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (1000 * 60 * 60 * 24) // 24 jam terakhir

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        val usageArray = mutableListOf<JSONObject>()

        for (usage in stats) {
            if (usage.totalTimeInForeground > 0) {
                val app = JSONObject()
                app.put("package_name", usage.packageName)
                app.put("duration_seconds", usage.totalTimeInForeground / 1000)
                usageArray.add(app)
            }
        }

        val jsonBody = JSONArray(usageArray).toString()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = RequestBody.create(mediaType, jsonBody)

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/device/usage?device_id=$androidId")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        val client = OkHttpClient()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("AppUsageSend", "Failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful) {
                    Log.i("AppUsageSend", "Success: $body")
                } else {
                    Log.e("AppUsageSend", "Error ${response.code}: $body")
                }
            }
        })
    }

    private fun sendDeviceLocation() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("LocationSend", "Permission not granted")
            return
        }
    
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    val latitude = location.latitude
                    val longitude = location.longitude
    
                    val json = JSONObject()
                    json.put("latitude", latitude as Double)
                    json.put("longitude", longitude as Double)
    
                    val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val requestBody = RequestBody.create(mediaType, json.toString())
    
                    val request = Request.Builder()
                        .url("${ApiConfig.BASE_URL}/api/device/location?device_id=$androidId")
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .build()
    
                    val client = OkHttpClient()
                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            Log.e("LocationSend", "Failed: ${e.message}")
                        }
    
                        override fun onResponse(call: Call, response: Response) {
                            val body = response.body?.string()
                            if (response.isSuccessful) {
                                Log.i("LocationSend", "Success: $body")
                            } else {
                                Log.e("LocationSend", "Error ${response.code}: $body")
                            }
                        }
                    })
                } else {
                    Log.e("LocationSend", "Location is null")
                }
            }
    }
    
    private fun startPollingCommands() {
        val handler = Handler(Looper.getMainLooper())
        val interval = 10_000L // 10 detik

        handler.post(object : Runnable {
            override fun run() {
                pollCommandFromBackend()
                handler.postDelayed(this, interval)
            }
        })
    }

    private fun pollCommandFromBackend() {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val url = "${ApiConfig.BASE_URL}/api/device/command?device_id=$androidId"
    
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
    
        val client = OkHttpClient()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, t: IOException) {
                Log.e("PollCommand", "Network request failed", t)
            }
    
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                Log.d("PollCommand", "Response Code: ${response.code}")
                Log.d("PollCommand", "Response Body: $body")
                if (response.code == 204) return // no command
                if (!response.isSuccessful || body == null) return
    
                try {
                    val json = JSONObject(body)
                    val command = json.getString("command")
                    val params = json.getJSONObject("params")
    
                    when (command) {
                        "show_notification" -> {
                            val title = params.optString("title", "Family Control")
                            val message = params.optString("message", "No message")
                            showNotification(title, message)
                        }
                        "take_photo" -> {
                            val camera = params.optString("camera", "front")
                            val uploadUrl = params.optString("upload_url")
                            if (uploadUrl.isNotEmpty()) {
                                takePhotoAndUpload(camera, uploadUrl)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PollCommand", "Parse error: ${e.message}")
                }
            }
        })
    }

    // Permission launcher for POST_NOTIFICATIONS
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("NotificationDebug", "POST_NOTIFICATIONS permission granted")
            
            // Show pending notification if exists
            pendingNotification?.let { (title, message) ->
                Log.d("NotificationDebug", "Permission granted, showing pending notification: $title")
                // Show the notification with a slight delay to ensure permission is fully processed
                Handler(Looper.getMainLooper()).postDelayed({
                    showNotificationAfterPermissionCheck(title, message)
                }, 300)
                pendingNotification = null
            }
        } else {
            Log.d("NotificationDebug", "POST_NOTIFICATIONS permission denied")
            Toast.makeText(
                this,
                "Notification permission denied. Some features will not work properly.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Checks if the POST_NOTIFICATIONS permission is granted for Android 13+
     */
    private fun checkNotificationPermission(): Boolean {
        Log.d("NotificationDebug", "Checking notification permission")
        val isAndroid13OrAbove = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        Log.d("NotificationDebug", "Is Android 13 or above: $isAndroid13OrAbove")
        
        if (isAndroid13OrAbove) {
            val permissionGranted = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            
            Log.d("NotificationDebug", "POST_NOTIFICATIONS permission granted: $permissionGranted")
            return permissionGranted
        } else {
            // For devices below Android 13, permission is granted at install time
            Log.d("NotificationDebug", "Below Android 13, permission assumed granted")
            return true
        }
    }

    /**
     * Requests the POST_NOTIFICATIONS permission for Android 13+
     */
    private fun requestNotificationPermission(title: String = "", message: String = "") {
        Log.d("NotificationDebug", "Request notification permission called with title: $title, message: $message")
        
        // Store notification details if provided
        if (title.isNotEmpty() && message.isNotEmpty()) {
            pendingNotification = Pair(title, message)
            Log.d("NotificationDebug", "Stored pending notification: $title, $message")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d("NotificationDebug", "Android 13+ - proceeding with permission request")
            
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            Log.d("NotificationDebug", "Should show rationale: $shouldShowRationale")
            
            if (shouldShowRationale) {
                // Explain why the permission is needed
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Notification permission is required to send you important alerts.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            
            Log.d("NotificationDebug", "Launching permission request")
            // Make sure this runs on the UI thread
            runOnUiThread {
                Log.d("NotificationDebug", "Running permission launcher on UI thread")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            Log.d("NotificationDebug", "Below Android 13 - no need to request permission")
            
            // If we have pending notification and don't need permission on this Android version,
            // show it immediately
            if (pendingNotification != null) {
                val (storedTitle, storedMessage) = pendingNotification!!
                pendingNotification = null
                showNotificationAfterPermissionCheck(storedTitle, storedMessage)
            }
        }
    }

    /**
     * Checks if notifications are enabled at the system level
     */
    private fun areNotificationsEnabled(): Boolean {
        Log.d("NotificationDebug", "Checking if notifications are enabled at system level")
        val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        Log.d("NotificationDebug", "Notifications enabled at system level: $enabled")
        return enabled
    }

    /**
     * Opens notification settings for the app
     */
    private fun openNotificationSettings() {
        returningFromSettings = true
        Log.d("NotificationDebug", "Setting returningFromSettings flag to true")
        
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }
        startActivity(intent)
    }

    /**
     * Shows a dialog to guide the user to notification settings
     */
    private fun showNotificationSettingsDialog() {
        Log.d("NotificationDebug", "Showing notification settings dialog")
        
        // Using a simple Toast for now, but could be replaced with a proper dialog or Snackbar
        runOnUiThread {
            Log.d("NotificationDebug", "Displaying toast on UI thread")
            Toast.makeText(
                this,
                "Notifications are disabled for this app. Please enable them in Settings to receive alerts.",
                Toast.LENGTH_LONG
            ).show()
            
            // Open notification settings after a short delay
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("NotificationDebug", "Opening notification settings")
                openNotificationSettings()
            }, 1000)
        }
    }

    // Function to show notification without permission checks (to be called after checks are done)
    private fun showNotificationAfterPermissionCheck(title: String, message: String) {
        Log.d("NotificationDebug", "Showing notification after permission check: Title=$title, Message=$message")
        
        val channelId = "family_control_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("NotificationDebug", "Creating notification channel: $channelId")
            val channel = NotificationChannel(
                channelId,
                "Family Control Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for Family Control alerts and notifications"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    
        Log.d("NotificationDebug", "Building notification with title: $title and message: $message")
        
        try {
            val notificationId = System.currentTimeMillis().toInt()
            notificationManager.notify(notificationId, builder.build())
            Log.d("NotificationDebug", "NotificationManager.notify() called with ID: $notificationId")
        } catch (e: Exception) {
            Log.e("NotificationDebug", "Error showing notification: ${e.message}", e)
        }
    }
    
    private fun showNotification(title: String, message: String) {
        Log.d("NotificationDebug", "========================================")
        Log.d("NotificationDebug", "showNotification called with Title: $title, Message: $message")
        
        // Check notification permission for Android 13+
        Log.d("NotificationDebug", "About to check notification permission")
        val permissionGranted = checkNotificationPermission()
        Log.d("NotificationDebug", "Permission check result: $permissionGranted")
        
        if (!permissionGranted) {
            Log.d("NotificationDebug", "Notification permission not granted, requesting with notification data")
            requestNotificationPermission(title, message)
            return
        }
        
        // Check if notifications are enabled at the system level
        Log.d("NotificationDebug", "About to check if notifications are enabled at system level")
        val notificationsEnabled = areNotificationsEnabled()
        Log.d("NotificationDebug", "Notifications enabled check result: $notificationsEnabled")
        
        if (!notificationsEnabled) {
            Log.d("NotificationDebug", "Notifications disabled at system level, showing settings dialog")
            // Store notification data for potential retry
            pendingNotification = Pair(title, message)
            showNotificationSettingsDialog()
            return
        }
        
        Log.d("NotificationDebug", "All checks passed, proceeding with notification creation")
        showNotificationAfterPermissionCheck(title, message)
    }
    
    private fun registerActivityLifecycleCallbacks() {
        Log.d("NotificationDebug", "Registering activity lifecycle callbacks")
        
        application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            
            override fun onActivityResumed(activity: android.app.Activity) {
                // Check if we're returning from settings
                if (activity == this@MainActivity && returningFromSettings) {
                    Log.d("NotificationDebug", "Activity resumed after returning from settings")
                    returningFromSettings = false
                    
                    // Check if notifications are now enabled
                    if (areNotificationsEnabled()) {
                        Log.d("NotificationDebug", "Notifications are now enabled, showing pending notification")
                        pendingNotification?.let { (title, message) ->
                            Log.d("NotificationDebug", "Showing pending notification after returning from settings: $title")
                            Handler(Looper.getMainLooper()).postDelayed({
                                showNotificationAfterPermissionCheck(title, message)
                                pendingNotification = null
                            }, 500)
                        }
                    } else {
                        Log.d("NotificationDebug", "Returned from settings but notifications still disabled")
                    }
                }
            }
            
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    private fun takePhotoAndUpload(cameraFacing: String, uploadUrl: String) {
        val cameraSelector = if (cameraFacing == "front") {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    
        val imageCapture = ImageCapture.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .build()
    
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(null) // no preview
            }
    
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                File.createTempFile("photo_", ".jpg", cacheDir)
            ).build()
    
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)
    
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e("TakePhoto", "Capture failed: ${exc.message}")
                    }
    
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val file = output.savedUri?.let { uri ->
                            File(uri.path ?: return)
                        } ?: return
                        uploadFileToBackend(file, uploadUrl)
                    }
                }
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun uploadFileToBackend(file: File, uploadUrl: String) {
        try {
            // Log original file size
            val originalFileSize = file.length()
            Log.i("UploadPhoto", "Original image size: $originalFileSize bytes")

            // Create compressed image file
            val compressedFile = compressImage(file)
            
            // Log compressed file size
            val compressedFileSize = compressedFile.length()
            Log.i("UploadPhoto", "Compressed image size: $compressedFileSize bytes (${(compressedFileSize * 100 / originalFileSize)}%)")

            // Get device ID
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            
            // Create the JSON payload with the required structure
            val payloadJson = """
                {
                    "device_id": "$androidId",
                    "command": "take_photo",
                    "params": {
                        "camera": "front",
                        "upload_url": "http://20.20.20.55:8080/api/device/upload_photo"
                    }
                }
            """.trimIndent()

            // Log the payload for debugging
            Log.i("UploadPhoto", "Payload JSON: $payloadJson")
            
            // Create RequestBody for the JSON part with explicit Content-Type
            val jsonRequestBody = payloadJson.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // Add the JSON RequestBody as a part with null filename for non-file parts
                .addFormDataPart("payload_json", null, jsonRequestBody)
                .addFormDataPart("photo", compressedFile.name, compressedFile.asRequestBody("image/jpeg".toMediaType()))
                .build()
        
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()
        
            OkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("UploadPhoto", "Failed: ${e.message}")
                }
        
                override fun onResponse(call: Call, response: Response) {
                    Log.i("UploadPhoto", "Success: ${response.body?.string()}")
                }
            })
        } catch (e: Exception) {
            Log.e("UploadPhoto", "Error compressing/uploading image: ${e.message}", e)
        }
    }

    /**
     * Compresses an image file and returns the compressed file
     */
    private fun compressImage(imageFile: File): File {
        // Decode the image file to a Bitmap
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.RGB_565 // Use RGB_565 for smaller size
        
        var bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options)
        
        // Resize the bitmap if it's too large
        val maxWidth = 1920
        val maxHeight = 1080
        val width = bitmap.width
        val height = bitmap.height
        
        if (width > maxWidth || height > maxHeight) {
            val scaleWidth = maxWidth.toFloat() / width
            val scaleHeight = maxHeight.toFloat() / height
            val scale = scaleWidth.coerceAtMost(scaleHeight)
            
            val matrix = android.graphics.Matrix()
            matrix.postScale(scale, scale)
            
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        }
        
        // Create a compressed output file
        val outputFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
        val outputStream = FileOutputStream(outputFile)
        
        // Compress the bitmap to JPEG with 80% quality
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        
        outputStream.flush()
        outputStream.close()
        
        // Recycle bitmap to free memory
        bitmap.recycle()
        
        return outputFile
    }

    private fun requestDeviceAdmin() {
        val componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This app needs device admin permission to lock screen, monitor usage, etc.")
        }
        startActivityForResult(intent, 1003)
    }
    
    
    
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@ComposePreview(showBackground = true)
@Composable
fun GreetingPreview() {
    FamilyControlAppTheme {
        Greeting("Android")
    }
}

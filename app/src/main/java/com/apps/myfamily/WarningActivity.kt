package com.apps.myfamily

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView

class WarningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = intent.getStringExtra("message") ?: "Layar akan dikunci dalam 10 detik"

        // Set flag agar muncul di lockscreen dan mematikan keyguard
        window.addFlags(
            LayoutParams.FLAG_KEEP_SCREEN_ON or
            LayoutParams.FLAG_DISMISS_KEYGUARD or
            LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val textView = TextView(this).apply {
            text = message
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }

        setContentView(textView)

        // Lock setelah 10 detik
        Handler(Looper.getMainLooper()).postDelayed({
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                Log.d("WarningActivity", "Attempting to lock device. IsAdminActive: ${dpm.isAdminActive(admin)}")
                dpm.lockNow()
            }
            finish()
        }, 10_000)
    }
}

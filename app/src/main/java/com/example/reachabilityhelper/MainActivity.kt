package com.example.reachabilityhelper

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_accessibility_settings).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_overlay_settings).setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        tvServiceStatus = findViewById(R.id.tv_service_status)

        findViewById<Button>(R.id.btn_toggle_service).setOnClickListener {
            // Send a broadcast to toggle the service
            sendBroadcast(Intent("com.example.reachabilityhelper.TOGGLE_TOUCHPAD"))
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isRunning = ReachabilityService.isServiceRunning
        if (isRunning) {
            tvServiceStatus.text = "Service: ACTIVE"
            tvServiceStatus.setTextColor(Color.GREEN)
        } else {
            tvServiceStatus.text = "Service: DISABLED (Enable in Settings)"
            tvServiceStatus.setTextColor(Color.RED)
        }
    }
}

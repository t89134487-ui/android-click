package com.example.reachabilityhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvLogs: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: return
            tvLogs.append("\n$message")
        }
    }

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
        tvLogs = findViewById(R.id.tv_logs)

        findViewById<Button>(R.id.btn_toggle_service).setOnClickListener {
            sendBroadcast(Intent("com.example.reachabilityhelper.TOGGLE_TOUCHPAD"))
        }

        findViewById<Button>(R.id.btn_test_tap).setOnClickListener {
            sendBroadcast(Intent("com.example.reachabilityhelper.TEST_TAP"))
        }

        val filter = IntentFilter("com.example.reachabilityhelper.LOG")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
    }

    private fun updateServiceStatus() {
        val isRunning = ReachabilityService.isServiceRunning
        if (isRunning) {
            tvServiceStatus.text = "Service: ACTIVE"
            tvServiceStatus.setTextColor(Color.GREEN)
        } else {
            tvServiceStatus.text = "Service: DISABLED"
            tvServiceStatus.setTextColor(Color.RED)
        }
    }
}

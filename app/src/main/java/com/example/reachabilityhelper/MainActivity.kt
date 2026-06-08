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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var sbHeight: SeekBar
    private lateinit var sbOffset: SeekBar
    private lateinit var sbWidth: SeekBar
    private lateinit var sbLeftOffset: SeekBar

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: return
            tvLogs.append("\n$message")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

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
        sbHeight = findViewById(R.id.sb_height)
        sbOffset = findViewById(R.id.sb_offset)
        sbWidth = findViewById(R.id.sb_width)
        sbLeftOffset = findViewById(R.id.sb_left_offset)

        sbHeight.progress = prefs.getInt("touchpad_height_pct", 33)
        sbOffset.progress = prefs.getInt("touchpad_bottom_offset", 0)
        sbWidth.progress = prefs.getInt("touchpad_width_pct", 100)
        sbLeftOffset.progress = prefs.getInt("touchpad_left_offset", 0)

        val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val editor = prefs.edit()
                    editor.putInt("touchpad_height_pct", sbHeight.progress)
                    editor.putInt("touchpad_bottom_offset", sbOffset.progress)
                    editor.putInt("touchpad_width_pct", sbWidth.progress)
                    editor.putInt("touchpad_left_offset", sbLeftOffset.progress)
                    editor.apply()
                    sendBroadcast(Intent("com.example.reachabilityhelper.SETTINGS_CHANGED"))
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        sbHeight.setOnSeekBarChangeListener(seekBarListener)
        sbOffset.setOnSeekBarChangeListener(seekBarListener)
        sbWidth.setOnSeekBarChangeListener(seekBarListener)
        sbLeftOffset.setOnSeekBarChangeListener(seekBarListener)

        findViewById<Button>(R.id.btn_toggle_service).setOnClickListener {
            sendBroadcast(Intent("com.example.reachabilityhelper.TOGGLE_TOUCHPAD"))
        }

        findViewById<Button>(R.id.btn_test_tap).setOnClickListener {
            sendBroadcast(Intent("com.example.reachabilityhelper.TEST_TAP"))
        }

        findViewById<Button>(R.id.btn_clear_logs).setOnClickListener {
            tvLogs.text = "Logs cleared."
        }

        findViewById<Button>(R.id.btn_copy_logs).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Reachability Logs", tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        val filter = IntentFilter("com.example.reachabilityhelper.LOG")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_EXPORTED)
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

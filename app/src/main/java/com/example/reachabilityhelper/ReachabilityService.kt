package com.example.reachabilityhelper

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast

class ReachabilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReachabilityService"
        var isServiceRunning = false
    }

    private var windowManager: WindowManager? = null
    private var touchpadOverlay: FrameLayout? = null
    private var isTouchpadEnabled = false
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private val autoOffRunnable = Runnable {
        Log.d(TAG, "Auto-off timer triggered")
        if (isTouchpadEnabled) {
            disableTouchpad()
        }
    }

    private val toggleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.example.reachabilityhelper.TOGGLE_TOUCHPAD") {
                Log.d(TAG, "Toggle broadcast received")
                if (isTouchpadEnabled) disableTouchpad() else enableTouchpad()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        isServiceRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        Toast.makeText(this, "Reachability Helper Activated", Toast.LENGTH_SHORT).show()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
                    Log.d(TAG, "Accessibility button clicked")
                    if (isTouchpadEnabled) disableTouchpad() else enableTouchpad()
                }
            }
            accessibilityButtonCallback?.let {
                controller.registerAccessibilityButtonCallback(it)
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, android.content.IntentFilter("com.example.reachabilityhelper.TOGGLE_TOUCHPAD"), RECEIVER_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, android.content.IntentFilter("com.example.reachabilityhelper.TOGGLE_TOUCHPAD"))
        }

        // Auto-enable when service starts (user just toggled it in settings)
        enableTouchpad()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun enableTouchpad() {
        if (isTouchpadEnabled) return
        Log.d(TAG, "Enabling touchpad")
        addTouchpadOverlay()
        resetAutoOffTimer(3000) // 3 seconds initial buffer
        isTouchpadEnabled = true
        Toast.makeText(this, "Touchpad On", Toast.LENGTH_SHORT).show()
    }

    private fun disableTouchpad() {
        if (!isTouchpadEnabled) return
        Log.d(TAG, "Disabling touchpad")
        removeTouchpadOverlay()
        handler.removeCallbacks(autoOffRunnable)
        isTouchpadEnabled = false
        Toast.makeText(this, "Touchpad Off", Toast.LENGTH_SHORT).show()
    }

    private fun resetAutoOffTimer(delayMillis: Long = 1000) {
        handler.removeCallbacks(autoOffRunnable)
        handler.postDelayed(autoOffRunnable, delayMillis)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchpadOverlay() {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        val overlayHeight = screenHeight / 2

        touchpadOverlay = FrameLayout(this).apply {
            val borderView = View(context).apply {
                val background = GradientDrawable().apply {
                    setStroke(15, Color.RED) // Thicker border
                    setColor(Color.argb(50, 255, 0, 0)) // More visible background
                }
                setBackground(background)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(borderView)

            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    resetAutoOffTimer(1000)
                    dispatchClickToTop(event.x, event.y)
                }
                true
            }
        }

        val params = WindowManager.LayoutParams(
            screenWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        try {
            windowManager?.addView(touchpadOverlay, params)
            Log.d(TAG, "Touchpad overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay", e)
        }
    }

    private fun removeTouchpadOverlay() {
        touchpadOverlay?.let {
            try {
                windowManager?.removeView(it)
                Log.d(TAG, "Touchpad overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
            touchpadOverlay = null
        }
    }

    private fun dispatchClickToTop(x: Float, y: Float) {
        val targetX = x
        val targetY = y

        Log.d(TAG, "Clicking at ($targetX, $targetY)")
        showVisualIndicator(targetX, targetY)

        val path = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX, targetY + 1)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture success")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "Gesture cancelled")
            }
        }, null)
    }

    private fun showVisualIndicator(x: Float, y: Float) {
        val size = 80
        val indicator = View(this).apply {
            val shape = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(Color.argb(200, 255, 0, 0))
            }
            background = shape
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - size / 2).toInt()
            this.y = (y - size / 2).toInt()
        }

        try {
            windowManager?.addView(indicator, params)

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    windowManager?.removeView(indicator)
                } catch (e: Exception) {}
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding indicator", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        disableTouchpad()
        try {
            unregisterReceiver(toggleReceiver)
        } catch (e: Exception) {}

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            accessibilityButtonCallback?.let {
                try {
                    accessibilityButtonController.unregisterAccessibilityButtonCallback(it)
                } catch (e: Exception) {}
            }
        }
    }
}

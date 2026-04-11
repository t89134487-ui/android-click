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
    private var isTouchpadEnabled = true
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val toggleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.example.reachabilityhelper.TOGGLE_TOUCHPAD") {
                Log.d(TAG, "Toggle broadcast received")
                toggleTouchpad()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        isServiceRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Toast.makeText(this, "Reachability Helper Connected", Toast.LENGTH_SHORT).show()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
                    Log.d(TAG, "Accessibility button clicked")
                    toggleTouchpad()
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

        addTouchpadOverlay()
        Log.d(TAG, "Touchpad auto-activated on service connection")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun toggleTouchpad() {
        if (isTouchpadEnabled) {
            Log.d(TAG, "Disabling touchpad")
            removeTouchpadOverlay()
            Toast.makeText(this, "Touchpad Disabled", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "Enabling touchpad")
            addTouchpadOverlay()
            Toast.makeText(this, "Touchpad Enabled", Toast.LENGTH_SHORT).show()
        }
        isTouchpadEnabled = !isTouchpadEnabled
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchpadOverlay() {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        val overlayHeight = screenHeight / 2

        touchpadOverlay = FrameLayout(this).apply {
            // Create a red border and semi-transparent background for visibility
            val borderView = View(context).apply {
                val background = GradientDrawable().apply {
                    setStroke(10, Color.RED)
                    setColor(Color.argb(30, 255, 0, 0)) // 30/255 alpha for visibility
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
            Log.d(TAG, "Touchpad overlay added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding touchpad overlay", e)
        }
    }

    private fun removeTouchpadOverlay() {
        touchpadOverlay?.let {
            try {
                windowManager?.removeView(it)
                Log.d(TAG, "Touchpad overlay removed from WindowManager")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing touchpad overlay", e)
            }
            touchpadOverlay = null
        }
    }

    private fun dispatchClickToTop(x: Float, y: Float) {
        // targetY = y maps top of touchpad to top of screen
        val targetX = x
        val targetY = y

        Log.d(TAG, "Dispatching click to ($targetX, $targetY)")
        showVisualIndicator(targetX, targetY)

        val path = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX, targetY + 1)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Gesture completed successfully")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "Gesture cancelled")
            }
        }, null)
    }

    private fun showVisualIndicator(x: Float, y: Float) {
        val indicator = View(this).apply {
            val size = 60
            layoutParams = FrameLayout.LayoutParams(size, size)
            val shape = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(Color.argb(180, 255, 0, 0))
            }
            background = shape
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - 30).toInt()
            this.y = (y - 30).toInt()
        }

        try {
            windowManager?.addView(indicator, params)

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    windowManager?.removeView(indicator)
                } catch (e: Exception) {
                }
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding visual indicator", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        isServiceRunning = false
        removeTouchpadOverlay()
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

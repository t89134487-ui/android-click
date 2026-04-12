package com.example.reachabilityhelper

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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

        enableTouchpad()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun enableTouchpad() {
        if (isTouchpadEnabled) return
        Log.d(TAG, "Enabling touchpad")
        addTouchpadOverlay()
        resetAutoOffTimer(3000)
        isTouchpadEnabled = true
    }

    private fun disableTouchpad() {
        if (!isTouchpadEnabled) return
        Log.d(TAG, "Disabling touchpad")
        removeTouchpadOverlay()
        handler.removeCallbacks(autoOffRunnable)
        isTouchpadEnabled = false
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
                    setStroke(15, Color.RED)
                    setColor(Color.argb(30, 255, 0, 0))
                }
                setBackground(background)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(borderView)

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        resetAutoOffTimer(1000)
                    }
                    MotionEvent.ACTION_UP -> {
                        resetAutoOffTimer(1000)
                        dispatchClickToTop(event.rawX, event.rawY)
                    }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay", e)
        }
    }

    private fun removeTouchpadOverlay() {
        touchpadOverlay?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
            touchpadOverlay = null
        }
    }

    private fun dispatchClickToTop(rawX: Float, rawY: Float) {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels

        // rawY is 0 at the top of the screen.
        // touchpad is at bottom half, so rawY is between screenHeight/2 and screenHeight.
        // We want targetY to be between 0 and screenHeight/2.
        // So targetY = rawY - (screenHeight / 2)
        val targetX = rawX
        val targetY = rawY - (screenHeight / 2f)

        Log.d(TAG, "Raw input: ($rawX, $rawY), Dispatching click to ($targetX, $targetY)")
        showVisualIndicator(targetX, targetY)
        vibrate()

        val path = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX, targetY + 1)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture success")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "Gesture cancelled")
            }
        }, null)
        Log.d(TAG, "dispatchGesture result: $result")
    }

    private fun vibrate() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
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

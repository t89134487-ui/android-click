package com.example.reachabilityhelper

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Display
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
        sendLog("Auto-off timer expired. Disabling service.")
        disableSelf()
    }

    private val toggleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.reachabilityhelper.TOGGLE_TOUCHPAD") {
                if (isTouchpadEnabled) disableTouchpad() else enableTouchpad()
            } else if (intent?.action == "com.example.reachabilityhelper.TEST_TAP") {
                testInjectedTap()
            }
        }
    }

    private fun sendLog(message: String) {
        Log.d(TAG, message)
        val intent = Intent("com.example.reachabilityhelper.LOG")
        intent.putExtra("message", message)
        sendBroadcast(intent)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sendLog("Service connected")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
                    if (isTouchpadEnabled) disableTouchpad() else enableTouchpad()
                }
            }
            accessibilityButtonCallback?.let {
                controller.registerAccessibilityButtonCallback(it)
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction("com.example.reachabilityhelper.TOGGLE_TOUCHPAD")
            addAction("com.example.reachabilityhelper.TEST_TAP")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, filter)
        }

        enableTouchpad()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun enableTouchpad() {
        if (isTouchpadEnabled) return
        sendLog("Enabling touchpad")
        addTouchpadOverlay()
        resetAutoOffTimer(3000)
        isTouchpadEnabled = true
    }

    private fun disableTouchpad() {
        if (!isTouchpadEnabled) return
        sendLog("Disabling touchpad")
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
        val display = windowManager?.defaultDisplay
        val size = Point()
        display?.getRealSize(size)
        val screenHeight = size.y
        val screenWidth = size.x

        val overlayHeight = screenHeight / 2

        touchpadOverlay = FrameLayout(this).apply {
            val borderView = View(context).apply {
                val background = GradientDrawable().apply {
                    setStroke(15, Color.RED)
                    setColor(Color.argb(50, 255, 0, 0))
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
                        resetAutoOffTimer(2000)
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
            sendLog("Overlay error: ${e.message}")
        }
    }

    private fun removeTouchpadOverlay() {
        touchpadOverlay?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {}
            touchpadOverlay = null
        }
    }

    private fun dispatchClickToTop(rawX: Float, rawY: Float) {
        val display = windowManager?.defaultDisplay
        val screenSize = Point()
        display?.getRealSize(screenSize)
        val screenHeight = screenSize.y

        val targetX = rawX
        val targetY = rawY - (screenHeight / 2f)

        // Clamping to ensure it's in the top half
        val clampedX = targetX.coerceIn(0f, screenSize.x.toFloat())
        val clampedY = targetY.coerceIn(0f, (screenHeight / 2f) - 1f)

        sendLog("Touch (${rawX.toInt()}, ${rawY.toInt()}) -> Target (${clampedX.toInt()}, ${clampedY.toInt()})")

        showVisualIndicator(clampedX, clampedY)
        vibrate()

        val path = Path().apply {
            moveTo(clampedX, clampedY)
            lineTo(clampedX, clampedY + 1)
        }

        // Wait 500ms for user release, then perform tap
        handler.postDelayed({
            val gestureBuilder = GestureDescription.Builder()
            val strokeDescription = GestureDescription.StrokeDescription(path, 0, 100)
            gestureBuilder.addStroke(strokeDescription)

            // For Android 11+, explicitly set the display ID
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                gestureBuilder.setDisplayId(Display.DEFAULT_DISPLAY)
            }

            val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    sendLog("Gesture Success at (${clampedX.toInt()}, ${clampedY.toInt()})")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    sendLog("Gesture CANCELLED by system")
                }
            }, null)
            if (!result) sendLog("dispatchGesture returned FALSE")
        }, 500)
    }

    private fun testInjectedTap() {
        val display = windowManager?.defaultDisplay
        val screenSize = Point()
        display?.getRealSize(screenSize)

        val targetX = screenSize.x / 2f
        val targetY = screenSize.y / 4f // Center of the top half

        sendLog("Starting 3s test countdown to click top center...")

        handler.postDelayed({
            sendLog("TEST TAP NOW")
            showVisualIndicator(targetX, targetY)
            vibrate()
            val path = Path().apply {
                moveTo(targetX, targetY)
                lineTo(targetX, targetY + 1)
            }
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                gestureBuilder.setDisplayId(Display.DEFAULT_DISPLAY)
            }
            dispatchGesture(gestureBuilder.build(), null, null)
        }, 3000)
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {}
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
            handler.postDelayed({
                try {
                    windowManager?.removeView(indicator)
                } catch (e: Exception) {}
            }, 300)
        } catch (e: Exception) {}
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        disableTouchpad()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        disableTouchpad()
        try {
            unregisterReceiver(toggleReceiver)
        } catch (e: Exception) {}
    }
}

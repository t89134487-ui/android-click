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
import kotlin.math.roundToInt

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
            when (intent?.action) {
                "com.example.reachabilityhelper.TOGGLE_TOUCHPAD" -> {
                    if (isTouchpadEnabled) disableTouchpad() else enableTouchpad()
                }
                "com.example.reachabilityhelper.TEST_TAP" -> {
                    testInjectedTap()
                }
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
        if (touchpadOverlay != null) return

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
                        hideTouchpadAndClick(event.rawX, event.rawY)
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

    private fun hideTouchpadAndClick(rawX: Float, rawY: Float) {
        val display = windowManager?.defaultDisplay
        val screenSize = Point()
        display?.getRealSize(screenSize)
        val screenHeight = screenSize.y

        val targetX = rawX
        val targetY = rawY - (screenHeight / 2f)

        // Clamping
        val clampedX = targetX.coerceIn(0f, screenSize.x.toFloat())
        val clampedY = targetY.coerceIn(0f, (screenHeight / 2f) - 1f)

        sendLog("Hide-to-Click at (${clampedX.toInt()}, ${clampedY.toInt()})")

        // 1. Hide the overlay immediately
        removeTouchpadOverlay()

        showVisualIndicator(clampedX, clampedY)
        vibrate()

        val path = Path().apply {
            moveTo(clampedX, clampedY)
        }

        // 2. Wait 100ms for overlay removal to be processed by system
        handler.postDelayed({
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 10))

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                gestureBuilder.setDisplayId(Display.DEFAULT_DISPLAY)
            }

            // 3. Dispatch the gesture
            dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    sendLog("Click SUCCESS")
                    // 4. Re-add overlay after gesture completes
                    if (isTouchpadEnabled) addTouchpadOverlay()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    sendLog("Click CANCELLED")
                    if (isTouchpadEnabled) addTouchpadOverlay()
                }
            }, null)
        }, 100)
    }

    private fun testInjectedTap() {
        val display = windowManager?.defaultDisplay
        val screenSize = Point()
        display?.getRealSize(screenSize)

        val targetX = screenSize.x / 2f
        val targetY = screenSize.y / 4f

        sendLog("Test Tap (3s)...")
        handler.postDelayed({
            sendLog("TESTING TAP NOW")
            showVisualIndicator(targetX, targetY)
            vibrate()
            val path = Path().apply { moveTo(targetX, targetY) }
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 10))
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

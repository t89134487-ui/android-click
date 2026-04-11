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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

class ReachabilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var touchpadOverlay: FrameLayout? = null
    private var isTouchpadEnabled = false
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val toggleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.example.reachabilityhelper.TOGGLE_TOUCHPAD") {
                toggleTouchpad()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun toggleTouchpad() {
        if (isTouchpadEnabled) {
            removeTouchpadOverlay()
        } else {
            addTouchpadOverlay()
        }
        isTouchpadEnabled = !isTouchpadEnabled
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchpadOverlay() {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        // Use slightly less than half height to avoid covering system navigation bar if possible
        val overlayHeight = (screenHeight * 0.45).toInt()

        touchpadOverlay = FrameLayout(this).apply {
            // Create a red border and very faint background
            val borderView = View(context).apply {
                val background = GradientDrawable().apply {
                    setStroke(10, Color.RED)
                    setColor(Color.argb(5, 255, 0, 0)) // Extremely faint red to confirm active area
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        try {
            windowManager?.addView(touchpadOverlay, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeTouchpadOverlay() {
        touchpadOverlay?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            touchpadOverlay = null
        }
    }

    private fun dispatchClickToTop(x: Float, y: Float) {
        // User requested targetY = y
        // top of red square (y=0) -> top of screen (targetY=0)
        val targetX = x
        val targetY = y

        showVisualIndicator(targetX, targetY)

        val path = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX, targetY + 1)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50)) // 50ms is more reliable
        dispatchGesture(gestureBuilder.build(), null, null)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeTouchpadOverlay()
        unregisterReceiver(toggleReceiver)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            accessibilityButtonCallback?.let {
                accessibilityButtonController.unregisterAccessibilityButtonCallback(it)
            }
        }
    }
}

package com.example.reachabilityhelper

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

class ReachabilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReachabilityService"
        var isServiceRunning = false
    }

    private var windowManager: WindowManager? = null
    private var touchpadOverlay: FrameLayout? = null
    private var seekIndicator: View? = null
    private var isTouchpadActive = false
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private var lastSeekX = 0f
    private var lastSeekY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val autoOffRunnable = Runnable {
        disableTouchpadUI()
    }

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.reachabilityhelper.TOGGLE_TOUCHPAD" -> {
                    if (isTouchpadActive) disableTouchpadUI() else enableTouchpadUI()
                }
                "com.example.reachabilityhelper.SETTINGS_CHANGED" -> {
                    if (isTouchpadActive) {
                        removeOverlays()
                        addOverlays()
                    }
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

        val info = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
            notificationTimeout = 100
        }
        serviceInfo = info

        sendLog("Service connected")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
                    if (isTouchpadActive) disableTouchpadUI() else enableTouchpadUI()
                }
            }
            accessibilityButtonCallback?.let {
                controller.registerAccessibilityButtonCallback(it)
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.example.reachabilityhelper.TOGGLE_TOUCHPAD")
            addAction("com.example.reachabilityhelper.SETTINGS_CHANGED")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        enableTouchpadUI()
    }

    private fun enableTouchpadUI() {
        if (isTouchpadActive) return
        addOverlays()
        isTouchpadActive = true
        resetAutoOffTimer(12000)
    }

    private fun disableTouchpadUI() {
        if (!isTouchpadActive) return
        removeOverlays()
        handler.removeCallbacks(autoOffRunnable)
        isTouchpadActive = false
    }

    private fun resetAutoOffTimer(delayMillis: Long = 12000) {
        handler.removeCallbacks(autoOffRunnable)
        handler.postDelayed(autoOffRunnable, delayMillis)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlays() {
        val display = windowManager?.defaultDisplay
        val screenSize = Point()
        display?.getRealSize(screenSize)
        val screenHeight = screenSize.y
        val screenWidth = screenSize.x

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val heightPct = prefs.getInt("touchpad_height_pct", 33) / 100f
        val bottomOffset = prefs.getInt("touchpad_bottom_offset", 0)
        val widthPct = prefs.getInt("touchpad_width_pct", 100) / 100f
        val leftOffset = prefs.getInt("touchpad_left_offset", 0)

        val touchpadHeight = (screenHeight * heightPct).toInt()
        val touchpadWidth = (screenWidth * widthPct).toInt()
        val targetAreaHeight = screenHeight / 2f
        val targetAreaWidth = screenWidth.toFloat()

        // 1. Touchpad Overlay
        touchpadOverlay = FrameLayout(this).apply {
            // Thin red border
            val background = GradientDrawable().apply {
                setStroke(4, Color.RED)
                setColor(Color.argb(10, 255, 255, 255))
            }
            setBackground(background)

            setOnTouchListener { _, event ->
                val localX = event.x // 0 at left of touchpad
                val localY = event.y // 0 at top of touchpad

                // Scaling: map localY [0, touchpadHeight] to targetY [0, targetAreaHeight]
                val scaledY = (localY / touchpadHeight) * targetAreaHeight
                // Scaling: map localX [0, touchpadWidth] to targetX [0, targetAreaWidth]
                val scaledX = (localX / touchpadWidth) * targetAreaWidth

                val targetX = scaledX.coerceIn(0f, targetAreaWidth - 1f)
                val targetY = scaledY.coerceIn(0f, targetAreaHeight - 1f)

                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        updateSeekIndicator(targetX, targetY)
                        resetAutoOffTimer(5000) // Keep alive while seeking
                    }
                    MotionEvent.ACTION_UP -> {
                        lastSeekX = targetX
                        lastSeekY = targetY
                        performSeekClick()
                    }
                }
                true
            }
        }

        val touchpadParams = WindowManager.LayoutParams(
            touchpadWidth, touchpadHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = leftOffset
            y = bottomOffset
        }

        // 2. Seek Indicator (Mirrored Side)
        seekIndicator = View(this).apply {
            val shape = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(Color.argb(150, 255, 0, 0)) // Red shadow
            }
            background = shape
            visibility = View.GONE
        }
        val indicatorParams = WindowManager.LayoutParams(
            40, 40,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager?.addView(touchpadOverlay, touchpadParams)
            windowManager?.addView(seekIndicator, indicatorParams)
        } catch (e: Exception) {
            sendLog("Overlay error: ${e.message}")
        }
    }

    private fun updateSeekIndicator(x: Float, y: Float) {
        seekIndicator?.let {
            it.visibility = View.VISIBLE
            val params = it.layoutParams as WindowManager.LayoutParams
            params.x = (x - 20).toInt()
            params.y = (y - 20).toInt()
            windowManager?.updateViewLayout(it, params)

            // Change color if over a button
            val isOverClickable = isOverClickable(x, y)
            val color = if (isOverClickable) {
                Color.argb(180, 0, 255, 0) // Green shadow for buttons
            } else {
                Color.argb(150, 255, 0, 0) // Red shadow otherwise
            }
            (it.background as? GradientDrawable)?.setColor(color)
        }
    }

    private fun isOverClickable(x: Float, y: Float): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val point = Point(x.toInt(), y.toInt())
        val clickableNode = findClickableNodeAt(rootNode, point)
        rootNode.recycle()
        return clickableNode != null
    }

    private fun findClickableNodeAt(node: android.view.accessibility.AccessibilityNodeInfo, point: Point): android.view.accessibility.AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        if (!bounds.contains(point.x, point.y)) {
            return null
        }

        // Check children first (deepest node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableNodeAt(child, point)
            if (result != null) {
                return result
            }
            child.recycle()
        }

        if (node.isClickable) {
            return node
        }

        return null
    }

    private fun performSeekClick() {
        val x = lastSeekX
        val y = lastSeekY

        sendLog("Seek Click at (${x.toInt()}, ${y.toInt()})")
        vibrate()

        // Hide UI immediately for injection
        disableTouchpadUI()

        handler.postDelayed({
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y + 1)
            }
            val gesture = GestureDescription.Builder().apply {
                addStroke(GestureDescription.StrokeDescription(path, 10, 100))
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setDisplayId(Display.DEFAULT_DISPLAY)
                }
            }.build()

            dispatchGesture(gesture, null, null)
        }, 150)
    }

    private fun removeOverlays() {
        touchpadOverlay?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        seekIndicator?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        touchpadOverlay = null
        seekIndicator = null
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        disableTouchpadUI()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        disableTouchpadUI()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }
}

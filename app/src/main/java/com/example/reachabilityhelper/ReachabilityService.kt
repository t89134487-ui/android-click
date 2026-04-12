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
import android.graphics.Rect
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
import android.view.accessibility.AccessibilityNodeInfo
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
                        handleMirrorTouch(event.rawX, event.rawY)
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

    private fun handleMirrorTouch(rawX: Float, rawY: Float) {
        val display = windowManager?.defaultDisplay
        val screenSize = Point()
        display?.getRealSize(screenSize)
        val screenHeight = screenSize.y

        val targetX = rawX
        val targetY = rawY - (screenHeight / 2f)

        // Clamping
        val clampedX = targetX.coerceIn(0f, screenSize.x.toFloat())
        val clampedY = targetY.coerceIn(0f, (screenHeight / 2f) - 1f)

        sendLog("Target: (${clampedX.toInt()}, ${clampedY.toInt()})")

        showVisualIndicator(clampedX, clampedY)
        vibrate()

        // Hide overlay to ensure it doesn't block node discovery or gesture
        removeTouchpadOverlay()

        // 1. Try Smart Click (ACTION_CLICK on AccessibilityNodeInfo)
        val clicked = trySmartClick(clampedX.toInt(), clampedY.toInt())

        // 2. Fallback to dispatchGesture with a delay
        if (!clicked) {
            handler.postDelayed({
                dispatchSyntheticClick(clampedX, clampedY)
            }, 150)
        } else {
            // Re-add overlay if smart clicked
            if (isTouchpadEnabled) addTouchpadOverlay()
        }
    }

    private fun trySmartClick(x: Int, y: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val clickableNode = findClickableNodeAt(root, x, y)
        if (clickableNode != null) {
            val text = clickableNode.text ?: clickableNode.contentDescription ?: clickableNode.className
            sendLog("Smart Click on: $text")
            val result = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // Note: Don't recycle clickableNode if it's the result of findClickableNodeAt as we recycled others there
            // Actually, findClickableNodeAt returns a NEW reference via obtain() or similar
            clickableNode.recycle()
            root.recycle()
            return result
        }
        root.recycle()
        return false
    }

    private fun findClickableNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        // Search children first for the deepest node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableNodeAt(child, x, y)
            if (result != null) {
                // We found it deeper down
                return result
            }
            child.recycle()
        }

        // If no clickable children, check if this node is clickable
        if (node.isClickable) {
            return AccessibilityNodeInfo.obtain(node)
        }

        return null
    }

    private fun dispatchSyntheticClick(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            gestureBuilder.setDisplayId(Display.DEFAULT_DISPLAY)
        }

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                sendLog("Gesture success")
                if (isTouchpadEnabled) addTouchpadOverlay()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                sendLog("Gesture cancelled")
                if (isTouchpadEnabled) addTouchpadOverlay()
            }
        }, null)
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
            dispatchSyntheticClick(targetX, targetY)
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

package com.example.reachabilityhelper

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    private var mirrorGridOverlay: FrameLayout? = null
    private var isTouchpadActive = false
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private val autoOffRunnable = Runnable {
        disableTouchpadUI()
    }

    private val toggleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.reachabilityhelper.TOGGLE_TOUCHPAD" -> {
                    if (isTouchpadActive) disableTouchpadUI() else enableTouchpadUI()
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

        val filter = IntentFilter("com.example.reachabilityhelper.TOGGLE_TOUCHPAD")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, filter)
        }

        enableTouchpadUI()
    }

    private fun enableTouchpadUI() {
        if (isTouchpadActive) return
        sendLog("Showing UI")
        addOverlays()
        isTouchpadActive = true
        resetAutoOffTimer(3000)
    }

    private fun disableTouchpadUI() {
        if (!isTouchpadActive) return
        sendLog("Hiding UI")
        removeOverlays()
        handler.removeCallbacks(autoOffRunnable)
        isTouchpadActive = false
    }

    private fun resetAutoOffTimer(delayMillis: Long = 1000) {
        handler.removeCallbacks(autoOffRunnable)
        handler.postDelayed(autoOffRunnable, delayMillis)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlays() {
        val display = windowManager?.defaultDisplay
        val screenSize = Point()
        display?.getRealSize(screenSize)
        val halfHeight = screenSize.y / 2

        mirrorGridOverlay = FrameLayout(this).apply {
            addView(GridView(context, screenSize.x, halfHeight, false))
        }
        val topParams = WindowManager.LayoutParams(
            screenSize.x, halfHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        touchpadOverlay = FrameLayout(this).apply {
            addView(GridView(context, screenSize.x, halfHeight, true))
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    handleTouch(event.rawX, event.rawY)
                }
                true
            }
        }
        val bottomParams = WindowManager.LayoutParams(
            screenSize.x, halfHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        try {
            windowManager?.addView(mirrorGridOverlay, topParams)
            windowManager?.addView(touchpadOverlay, bottomParams)
        } catch (e: Exception) {
            sendLog("Overlay error: ${e.message}")
        }
    }

    private fun removeOverlays() {
        touchpadOverlay?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        mirrorGridOverlay?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        touchpadOverlay = null
        mirrorGridOverlay = null
    }

    private fun handleTouch(rawX: Float, rawY: Float) {
        val display = windowManager?.defaultDisplay
        val screenSize = Point()
        display?.getRealSize(screenSize)

        val targetX = rawX
        val targetY = rawY - (screenSize.y / 2f)
        val clampedY = targetY.coerceIn(0f, (screenSize.y / 2f) - 1f)

        sendLog("Action: Click at (${targetX.toInt()}, ${clampedY.toInt()})")

        vibrate()

        // Final action: remove overlays and set inactive immediately
        disableTouchpadUI()

        handler.postDelayed({
            dispatchSyntheticClick(targetX, clampedY)
        }, 150)
    }

    private fun dispatchSyntheticClick(x: Float, y: Float) {
        showVisualIndicator(x, y)
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

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                sendLog("Gesture Success")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                sendLog("Gesture Cancelled")
            }
        }, null)
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

    private fun showVisualIndicator(x: Float, y: Float) {
        val indicator = View(this).apply {
            val shape = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(Color.argb(200, 255, 0, 0))
            }
            background = shape
        }
        val params = WindowManager.LayoutParams(
            80, 80,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - 40).toInt()
            this.y = (y - 40).toInt()
        }
        try {
            windowManager?.addView(indicator, params)
            handler.postDelayed({ try { windowManager?.removeView(indicator) } catch (e: Exception) {} }, 300)
        } catch (e: Exception) {}
    }

    private class GridView(context: Context, val w: Int, val h: Int, val isTouchpad: Boolean) : View(context) {
        private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)
        private val paint = Paint().apply {
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = Color.RED
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            for (i in 1..3) {
                paint.color = colors[i % colors.size]
                val vx = (w / 4f) * i
                canvas.drawLine(vx, 0f, vx, h.toFloat(), paint)
                paint.color = colors[(i + 3) % colors.size]
                val hy = (h / 4f) * i
                canvas.drawLine(0f, hy, w.toFloat(), hy, paint)
            }
            if (isTouchpad) {
                canvas.drawColor(Color.argb(10, 255, 0, 0))
            }
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
        try { unregisterReceiver(toggleReceiver) } catch (e: Exception) {}
    }
}

package com.example.reachabilityhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout

class ReachabilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var touchpadOverlay: FrameLayout? = null
    private var toggleButton: Button? = null
    private var isTouchpadEnabled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createToggleButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    @SuppressLint("ClickableViewAccessibility")
    private fun createToggleButton() {
        toggleButton = Button(this).apply {
            text = "R"
            setBackgroundColor(Color.BLUE)
            setTextColor(Color.WHITE)
            setOnClickListener {
                toggleTouchpad()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        windowManager?.addView(toggleButton, params)
    }

    private fun toggleTouchpad() {
        if (isTouchpadEnabled) {
            removeTouchpadOverlay()
            toggleButton?.setBackgroundColor(Color.BLUE)
        } else {
            addTouchpadOverlay()
            toggleButton?.setBackgroundColor(Color.RED)
        }
        isTouchpadEnabled = !isTouchpadEnabled
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchpadOverlay() {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        touchpadOverlay = FrameLayout(this).apply {
            // Create a red border
            val borderView = View(context).apply {
                val background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(5, Color.RED)
                }
                setBackground(background)
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
            screenHeight / 2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager?.addView(touchpadOverlay, params)
    }

    private fun removeTouchpadOverlay() {
        touchpadOverlay?.let {
            windowManager?.removeView(it)
            touchpadOverlay = null
        }
    }

    private fun dispatchClickToTop(x: Float, y: Float) {
        // Map y from bottom half (0 to screenHeight/2) to top half (0 to screenHeight/2)
        // Since the overlay is in the bottom half, event.y 0 is at screenHeight/2
        // We want to click at exactly the same relative position in the top half.
        val targetX = x
        val targetY = y // Relative y in the bottom half is the same as relative y in the top half

        val path = Path().apply {
            moveTo(targetX, targetY)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 10))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        removeTouchpadOverlay()
        toggleButton?.let {
            windowManager?.removeView(it)
        }
    }
}

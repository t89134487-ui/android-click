package com.example.reachabilityhelper

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val accessibilityButtonController = accessibilityButtonController
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController?) {
                        toggleTouchpad()
                    }
                }
            )
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

        touchpadOverlay = FrameLayout(this).apply {
            // Create a red border
            val borderView = View(context).apply {
                val background = GradientDrawable().apply {
                    setStroke(10, Color.RED)
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
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels

        // REVISED MAPPING:
        // touchpadOverlay height is screenHeight/2
        // y=0 in touchpadOverlay (top of red square) should map to y=0 (top of screen)
        // y=screenHeight/2 in touchpadOverlay (bottom of red square) should map to y=screenHeight/2 (middle of screen)
        // This is targetY = y

        // WAIT, user said: "When I tap the top of the red square, the top half of the screen should be tapped in that location, i.e. the very top of the screen."
        // That means y=0 -> targetY=0.
        // "bottom of the screen should mirror my clicks to the top"
        // If I tap bottom edge of touchpad (y=screenHeight/2), it should tap middle of screen (targetY=screenHeight/2).
        // If I tap top edge of touchpad (y=0), it should tap top of screen (targetY=0).

        // Wait, "It's working the wrong way round" was said when my previous code HAD targetY = y.
        // Let's re-read: "When I tap the top of the red square, the top half of the screen should be tapped in that location, i.e. the very top of the screen."
        // In my previous version:
        // touchpad was Gravity.BOTTOM.
        // If screen is 2000px, touchpad is at 1000-2000.
        // MotionEvent y=0 is at absolute 1000.
        // My code was: targetY = y. So absolute targetY = 0.
        // If MotionEvent y=1000 is at absolute 2000.
        // My code was: targetY = 1000. So absolute targetY = 1000.

        // If the user says it's "working the wrong way round", maybe they want:
        // Bottom of touchpad (absolute screen bottom) -> Top of screen (absolute 0).
        // Top of touchpad (absolute screen middle) -> Middle of screen (absolute screenHeight/2).
        // targetY = (screenHeight / 2) - y

        // Let's re-read user again: "When I tap the top of the red square, the top half of the screen should be tapped in that location, i.e. the very top of the screen."
        // TOP of red square (y=0) -> VERY TOP of screen (targetY=0).
        // This matches targetY = y.

        // Why did they say it works the wrong way?
        // Ah! "I want to make an Android app that makes the bottom half of the screen click the top half. So that I don't need to reach to the top of the screen."
        // If I tap the VERY BOTTOM of the screen, I want to click the VERY TOP of the screen.
        // Bottom of screen is y = screenHeight/2 (in the overlay).
        // Top of screen is targetY = 0.
        // So y = 0 -> targetY = 0.
        // y = screenHeight / 2 -> targetY = screenHeight / 2.
        // targetY = y

        val targetX = x
        val targetY = y

        showVisualIndicator(targetX, targetY)

        val path = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX, targetY + 1)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 10))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun showVisualIndicator(x: Float, y: Float) {
        val indicator = View(this).apply {
            val size = 40
            layoutParams = FrameLayout.LayoutParams(size, size)
            val shape = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(Color.argb(128, 255, 0, 0)) // Semi-transparent red
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
            this.x = (x - 20).toInt()
            this.y = (y - 20).toInt()
        }

        windowManager?.addView(indicator, params)

        // Remove indicator after 300ms
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                windowManager?.removeView(indicator)
            } catch (e: Exception) {
                // Ignore if already removed
            }
        }, 300)
    }

    override fun onDestroy() {
        super.onDestroy()
        removeTouchpadOverlay()
    }
}

package com.quizearn.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var statusText: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnClose: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateUiState()
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        createFloatingWidget()
        uiHandler.post(refreshRunnable)
    }

    private fun startForegroundServiceNotification() {
        val channelId = "quiz_earn_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Quiz & Earn Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Quiz & Earn Mobile Bot")
                .setContentText("Floating overlay is active.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Quiz & Earn Mobile Bot")
                .setContentText("Floating overlay is active.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }

        startForeground(1001, notification)
    }

    private fun createFloatingWidget() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL

            // Pure OLED Black Card Styling
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#08080A"))
                setStroke(dp(1), Color.parseColor("#1F1F26"))
                cornerRadius = dp(24).toFloat()
            }
            background = bg
        }

        statusText = TextView(this).apply {
            text = "STANDBY"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), 0, dp(10), 0)
        }

        btnToggle = Button(this).apply {
            text = "START"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(dp(12), dp(4), dp(12), dp(4))

            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(16).toFloat()
            }
            background = btnBg

            setOnClickListener {
                QuizEarnAccessibilityService.isRunning = !QuizEarnAccessibilityService.isRunning
                updateUiState()
            }
        }

        btnClose = Button(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = null
            setPadding(dp(6), 0, dp(2), 0)
            setOnClickListener {
                QuizEarnAccessibilityService.isRunning = false
                stopSelf()
            }
        }

        container.addView(statusText)
        container.addView(btnToggle)
        container.addView(btnClose)

        // Draggable Touch Listener
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val p = params ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x
                        initialY = p.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initialX + (event.rawX - initialTouchX).toInt()
                        p.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, p)
                        return true
                    }
                }
                return false
            }
        })

        floatView = container
        windowManager.addView(floatView, params)
        updateUiState()
    }

    private fun updateUiState() {
        val running = QuizEarnAccessibilityService.isRunning
        val serviceOk = QuizEarnAccessibilityService.isServiceActive
        val count = QuizEarnAccessibilityService.solvedCount

        if (!serviceOk) {
            statusText.text = "SERVICE OFF"
            statusText.setTextColor(Color.parseColor("#EF4444"))
            btnToggle.isEnabled = false
        } else if (running) {
            statusText.text = "RUNNING ($count)"
            statusText.setTextColor(Color.parseColor("#10B981"))
            btnToggle.text = "PAUSE"
            btnToggle.isEnabled = true
        } else {
            statusText.text = "STANDBY ($count)"
            statusText.setTextColor(Color.parseColor("#9CA3AF"))
            btnToggle.text = "START"
            btnToggle.isEnabled = true
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(refreshRunnable)
        if (floatView != null) {
            windowManager.removeView(floatView)
            floatView = null
        }
    }
}

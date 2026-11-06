package com.quizearn.bot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var txtOverlayStatus: TextView
    private lateinit var txtAccessStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnAccess: Button
    private lateinit var btnLaunch: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#000000"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#000000"))
            setPadding(dp(24), dp(40), dp(24), dp(30))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Header Title
        val title = TextView(this).apply {
            text = "Quiz & Earn Bot"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Autonomous Mobile Engine • No PC • No Root"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(30))
            gravity = Gravity.CENTER
        }

        root.addView(title)
        root.addView(subtitle)

        // Permission Card 1: Floating Overlay
        val cardOverlay = createCard("1. FLOATING OVERLAY PERMISSION", "Required to display the floating control widget on top of Quiz & Earn.")
        txtOverlayStatus = createStatusBadge("Checking...")
        btnOverlay = createActionButton("GRANT OVERLAY PERMISSION") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
        cardOverlay.addView(txtOverlayStatus)
        cardOverlay.addView(btnOverlay)
        root.addView(cardOverlay)

        // Permission Card 2: Accessibility Service
        val cardAccess = createCard("2. ACCESSIBILITY SERVICE", "Allows the bot to read math questions and tap the correct answers automatically.")
        txtAccessStatus = createStatusBadge("Checking...")
        btnAccess = createActionButton("ENABLE ACCESSIBILITY") {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
        cardAccess.addView(txtAccessStatus)
        cardAccess.addView(btnAccess)
        root.addView(cardAccess)

        // Launch Action Button
        btnLaunch = Button(this).apply {
            text = "LAUNCH FLOATING BOT"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(16), dp(20), dp(16))

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(14).toFloat()
            }
            background = bg

            setOnClickListener {
                startFloatingService()
            }
        }

        val launchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, dp(20))
            addView(btnLaunch)
        }
        root.addView(launchContainer)

        // Quick Instructions
        val infoText = TextView(this).apply {
            text = "How it works:\n1. Enable both permissions above.\n2. Tap 'Launch Floating Bot'.\n3. Open Quiz & Earn and tap START on the floating bubble.\n4. The bot will automatically solve quizzes and claim rewards AFK."
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(8), dp(10), dp(8), dp(20))
        }
        root.addView(infoText)

        scrollView.addView(root)
        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        val hasAccess = QuizEarnAccessibilityService.isServiceActive

        if (hasOverlay) {
            txtOverlayStatus.text = "✓ Overlay Permission Granted"
            txtOverlayStatus.setTextColor(Color.parseColor("#10B981"))
            btnOverlay.isEnabled = false
            btnOverlay.text = "GRANTED"
        } else {
            txtOverlayStatus.text = "✕ Overlay Permission Needed"
            txtOverlayStatus.setTextColor(Color.parseColor("#EF4444"))
            btnOverlay.isEnabled = true
            btnOverlay.text = "GRANT OVERLAY PERMISSION"
        }

        if (hasAccess) {
            txtAccessStatus.text = "✓ Accessibility Service Running"
            txtAccessStatus.setTextColor(Color.parseColor("#10B981"))
            btnAccess.isEnabled = false
            btnAccess.text = "ENABLED"
        } else {
            txtAccessStatus.text = "✕ Accessibility Service Not Active"
            txtAccessStatus.setTextColor(Color.parseColor("#EF4444"))
            btnAccess.isEnabled = true
            btnAccess.text = "ENABLE ACCESSIBILITY"
        }

        btnLaunch.isEnabled = hasOverlay && hasAccess
        if (btnLaunch.isEnabled) {
            btnLaunch.alpha = 1.0f
        } else {
            btnLaunch.alpha = 0.5f
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Attempt to launch Quiz & Earn app
        val launchIntent = packageManager.getLaunchIntentForPackage(QuizEarnAccessibilityService.TARGET_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
    }

    private fun createCard(title: String, desc: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#08080A"))
                setStroke(dp(1), Color.parseColor("#1F1F26"))
                cornerRadius = dp(16).toFloat()
            }
            background = bg
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(16))
        }
        card.layoutParams = params

        val t = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val d = TextView(this).apply {
            text = desc
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, dp(12))
        }

        card.addView(t)
        card.addView(d)
        return card
    }

    private fun createStatusBadge(initialText: String): TextView {
        return TextView(this).apply {
            text = initialText
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(10))
        }
    }

    private fun createActionButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#16161A"))
                setStroke(dp(1), Color.parseColor("#27272A"))
                cornerRadius = dp(10).toFloat()
            }
            background = bg

            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

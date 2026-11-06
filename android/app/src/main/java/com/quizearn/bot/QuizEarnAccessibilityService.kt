package com.quizearn.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

class QuizEarnAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "QuizEarnService"
        const val TARGET_PACKAGE = "com.quiz.earn.money.game"

        @Volatile
        var isRunning: Boolean = false
        @Volatile
        var isServiceActive: Boolean = false
        @Volatile
        var solvedCount: Int = 0
        @Volatile
        var currentStatus: String = "Standby"

        var instance: QuizEarnAccessibilityService? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastActionTime: Long = 0
    private val mathPattern = Pattern.compile("(\\d+)\\s*([\\+\\-\\×\\*\\÷\\/])\\s*(\\d+)")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceActive = true
        Log.i(TAG, "QuizEarnAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return

        val pkg = event.packageName?.toString() ?: ""
        // Process when inside Quiz & Earn or handling return navigation
        if (pkg == TARGET_PACKAGE) {
            val now = System.currentTimeMillis()
            if (now - lastActionTime > 400) {
                lastActionTime = now
                processQuizEarnScreen()
            }
        }
    }

    private fun processQuizEarnScreen() {
        val root = rootInActiveWindow ?: return

        try {
            // 1. Check for Math Quiz
            if (detectAndSolveMathQuiz(root)) {
                return
            }

            // 2. Check for Claim / Bonus Buttons
            if (detectAndClaimRewards(root)) {
                return
            }

            // 3. Check for Super Offers progression
            detectAndHandleSuperOffers(root)
        } finally {
            root.recycle()
        }
    }

    private fun detectAndSolveMathQuiz(root: AccessibilityNodeInfo): Boolean {
        val textNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllTextNodes(root, textNodes)

        var questionNode: AccessibilityNodeInfo? = null
        var solvedAnswer: Int? = null

        for (node in textNodes) {
            val text = node.text?.toString() ?: continue
            val matcher = mathPattern.matcher(text)
            if (matcher.find()) {
                val n1 = matcher.group(1)?.toIntOrNull() ?: continue
                val op = matcher.group(2) ?: ""
                val n2 = matcher.group(3)?.toIntOrNull() ?: continue

                val ans = when (op) {
                    "+", "plus" -> n1 + n2
                    "-", "minus" -> n1 - n2
                    "*", "×", "x" -> n1 * n2
                    "/", "÷" -> if (n2 != 0) n1 / n2 else null
                    else -> null
                }

                if (ans != null) {
                    questionNode = node
                    solvedAnswer = ans
                    break
                }
            }
        }

        if (questionNode != null && solvedAnswer != null) {
            val ansStr = solvedAnswer.toString()
            currentStatus = "Solved: $ansStr"

            // Find matching answer button
            for (node in textNodes) {
                val text = node.text?.toString()?.trim() ?: continue
                if (text == ansStr) {
                    if (clickNodeOrParent(node)) {
                        solvedCount++
                        currentStatus = "Answered #$solvedCount ($ansStr)"
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun detectAndClaimRewards(root: AccessibilityNodeInfo): Boolean {
        val claimNodes = mutableListOf<AccessibilityNodeInfo>()
        searchNodesByKeywords(root, listOf("CLAIM", "Claim", "Collect", "COLLECT", "Double Reward"), claimNodes)

        for (node in claimNodes) {
            if (clickNodeOrParent(node)) {
                currentStatus = "Reward Claimed!"
                return true
            }
        }

        return false
    }

    private fun detectAndHandleSuperOffers(root: AccessibilityNodeInfo): Boolean {
        // Look for completed offers with bonus rewards
        val bonusNodes = mutableListOf<AccessibilityNodeInfo>()
        searchNodesByKeywords(root, listOf("All milestones completed!", "BONUS REWARDS", "Claim Bonus"), bonusNodes)

        for (node in bonusNodes) {
            if (clickNodeOrParent(node)) {
                currentStatus = "Inspecting Bonus..."
                return true
            }
        }

        return false
    }

    private fun collectAllTextNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (!node.text.isNullOrEmpty()) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTextNodes(child, list)
        }
    }

    private fun searchNodesByKeywords(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        matches: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        for (kw in keywords) {
            if ((text != null && text.contains(kw, ignoreCase = true)) ||
                (desc != null && desc.contains(kw, ignoreCase = true))) {
                matches.add(node)
                break
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchNodesByKeywords(child, keywords, matches)
        }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var curr: AccessibilityNodeInfo? = node
        while (curr != null) {
            if (curr.isClickable) {
                return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            curr = curr.parent
        }

        // Fallback: Dispatch gesture click directly on center coordinates
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }

        return false
    }

    fun dispatchTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        Log.w(TAG, "QuizEarnAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceActive = false
        isRunning = false
    }
}

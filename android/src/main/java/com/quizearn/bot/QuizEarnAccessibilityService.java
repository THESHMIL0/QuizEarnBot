package com.quizearn.bot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QuizEarnAccessibilityService extends AccessibilityService {

    public static final String TAG = "QuizEarnService";
    public static final String TARGET_PACKAGE = "com.quiz.earn.money.game";

    public static volatile boolean isRunning = false;
    public static volatile boolean isServiceActive = false;
    public static volatile int solvedCount = 0;
    public static volatile String currentStatus = "Standby";

    public static QuizEarnAccessibilityService instance = null;

    private long lastActionTime = 0;
    private final Pattern mathPattern = Pattern.compile("(\\d+)\\s*([\\+\\-\\×\\*\\÷\\/])\\s*(\\d+)");

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        isServiceActive = true;
        Log.i(TAG, "QuizEarn Math Solver connected.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning || event == null) return;

        CharSequence pkgSeq = event.getPackageName();
        String pkg = (pkgSeq != null) ? pkgSeq.toString() : "";

        if (TARGET_PACKAGE.equals(pkg)) {
            long now = System.currentTimeMillis();
            if (now - lastActionTime > 350) {
                lastActionTime = now;
                solveScreenQuiz();
            }
        }
    }

    private void solveScreenQuiz() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            List<AccessibilityNodeInfo> textNodes = new ArrayList<>();
            collectTextNodes(root, textNodes);

            AccessibilityNodeInfo questionNode = null;
            Integer solvedAnswer = null;

            for (AccessibilityNodeInfo node : textNodes) {
                CharSequence textSeq = node.getText();
                if (textSeq == null) continue;
                String text = textSeq.toString();

                Matcher matcher = mathPattern.matcher(text);
                if (matcher.find()) {
                    try {
                        int n1 = Integer.parseInt(matcher.group(1));
                        String op = matcher.group(2);
                        int n2 = Integer.parseInt(matcher.group(3));

                        int ans;
                        if ("+".equals(op) || "plus".equalsIgnoreCase(op)) {
                            ans = n1 + n2;
                        } else if ("-".equals(op) || "minus".equalsIgnoreCase(op)) {
                            ans = n1 - n2;
                        } else if ("*".equals(op) || "×".equals(op) || "x".equalsIgnoreCase(op)) {
                            ans = n1 * n2;
                        } else if ("/".equals(op) || "÷".equals(op)) {
                            if (n2 == 0) continue;
                            ans = n1 / n2;
                        } else {
                            continue;
                        }

                        questionNode = node;
                        solvedAnswer = ans;
                        break;
                    } catch (Exception ignored) {}
                }
            }

            if (questionNode != null && solvedAnswer != null) {
                String ansStr = String.valueOf(solvedAnswer);
                currentStatus = "Solved: " + ansStr;

                for (AccessibilityNodeInfo node : textNodes) {
                    CharSequence textSeq = node.getText();
                    if (textSeq == null) continue;
                    String text = textSeq.toString().trim();

                    if (ansStr.equals(text)) {
                        if (clickNodeOrParent(node)) {
                            solvedCount++;
                            currentStatus = "Solved #" + solvedCount + " (" + ansStr + ")";
                            return;
                        }
                    }
                }
            }
        } finally {
            root.recycle();
        }
    }

    private void collectTextNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> list) {
        if (node.getText() != null && node.getText().length() > 0) {
            list.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTextNodes(child, list);
            }
        }
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo curr = node;
        while (curr != null) {
            if (curr.isClickable()) {
                return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            curr = curr.getParent();
        }

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (!rect.isEmpty()) {
            Path path = new Path();
            path.moveTo(rect.centerX(), rect.centerY());
            GestureDescription stroke = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 50L))
                .build();
            dispatchGesture(stroke, null, null);
            return true;
        }

        return false;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        isServiceActive = false;
        isRunning = false;
    }
}

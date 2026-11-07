package com.quizearn.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QuizEarnAccessibilityService extends AccessibilityService {

    public static final String TAG = "QuizEarnAssistant";

    public static volatile boolean isRunning = false;
    public static volatile boolean isServiceActive = false;
    public static volatile int solvedCount = 0;
    public static volatile String currentStatus = "Standby";

    public static QuizEarnAccessibilityService instance = null;

    private final Handler loopHandler = new Handler(Looper.getMainLooper());
    private long lastTapTime = 0;

    private final Pattern mathPattern = Pattern.compile("(\\d+)\\s*([\\+\\-\\×\\*\\÷\\/])\\s*(\\d+)");
    private final Pattern numPattern = Pattern.compile("^\\d{3,6}$");

    private final Runnable solveLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                try {
                    solveScreen();
                } catch (Exception e) {
                    Log.e(TAG, "Error in solveScreen", e);
                }
                loopHandler.postDelayed(this, 300);
            }
        }
    };

    public static void startSolving() {
        isRunning = true;
        currentStatus = "Scanning...";
        Log.i(TAG, "startSolving called! instance = " + instance);
        if (instance != null) {
            instance.loopHandler.removeCallbacks(instance.solveLoopRunnable);
            instance.loopHandler.post(instance.solveLoopRunnable);
        }
    }

    public static void stopSolving() {
        isRunning = false;
        currentStatus = "Standby";
        Log.i(TAG, "stopSolving called.");
        if (instance != null) {
            instance.loopHandler.removeCallbacks(instance.solveLoopRunnable);
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        isServiceActive = true;
        Log.i(TAG, "Quiz Math Assistant connected successfully.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning || event == null) return;
        long now = System.currentTimeMillis();
        if (now - lastTapTime > 600) {
            solveScreen();
        }
    }

    public synchronized void solveScreen() {
        if (!isRunning) return;

        long now = System.currentTimeMillis();
        if (now - lastTapTime < 700) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            List<NodeItem> allNodes = new ArrayList<>();
            collectAllNodes(root, allNodes);

            if (allNodes.isEmpty()) return;

            // 1. Math Quiz solver (e.g. 12 + 5 = ?)
            for (NodeItem item : allNodes) {
                Matcher m = mathPattern.matcher(item.text);
                if (m.find()) {
                    try {
                        int n1 = Integer.parseInt(m.group(1));
                        String op = m.group(2);
                        int n2 = Integer.parseInt(m.group(3));
                        int ans;
                        if ("+".equals(op)) ans = n1 + n2;
                        else if ("-".equals(op)) ans = n1 - n2;
                        else if ("*".equals(op) || "×".equals(op)) ans = n1 * n2;
                        else if ("/".equals(op) || "÷".equals(op)) {
                            if (n2 == 0) continue;
                            ans = n1 / n2;
                        } else continue;

                        String ansStr = String.valueOf(ans);
                        Log.i(TAG, "Math Quiz Detected: " + n1 + " " + op + " " + n2 + " = " + ansStr);
                        for (NodeItem opt : allNodes) {
                            if (opt.node != item.node && opt.text.trim().equals(ansStr)) {
                                Log.i(TAG, "Tapping answer button: " + ansStr + " at " + opt.rect);
                                tapNode(opt);
                                solvedCount++;
                                lastTapTime = System.currentTimeMillis();
                                currentStatus = "Solved #" + solvedCount + " (" + ansStr + ")";
                                return;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            // 2. Number Matching / Captcha Quiz (e.g. target 1035 & 4 option buttons below)
            NodeItem targetNode = null;
            for (NodeItem item : allNodes) {
                if (numPattern.matcher(item.text).matches()) {
                    if (item.rect.centerY() > 100 && item.rect.centerY() < 550) {
                        targetNode = item;
                        break;
                    }
                }
            }

            if (targetNode != null) {
                String targetStr = targetNode.text.trim();
                Log.i(TAG, "Target Number Detected: " + targetStr + " at " + targetNode.rect);

                for (NodeItem opt : allNodes) {
                    if (opt.node != targetNode.node && opt.rect.centerY() >= 450 && opt.text.trim().equals(targetStr)) {
                        Log.i(TAG, "Tapping matching button: " + targetStr + " at " + opt.rect);
                        tapNode(opt);
                        solvedCount++;
                        lastTapTime = System.currentTimeMillis();
                        currentStatus = "Solved #" + solvedCount + " (" + targetStr + ")";
                        return;
                    }
                }
            } else {
                currentStatus = "Scanning...";
            }

        } finally {
            root.recycle();
        }
    }

    private void collectAllNodes(AccessibilityNodeInfo node, List<NodeItem> list) {
        if (node == null) return;

        CharSequence textSeq = node.getText();
        CharSequence descSeq = node.getContentDescription();
        String text = (textSeq != null && textSeq.length() > 0) ? textSeq.toString()
                    : ((descSeq != null && descSeq.length() > 0) ? descSeq.toString() : "");

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);

        if (!text.isEmpty() && !rect.isEmpty()) {
            list.add(new NodeItem(node, text.trim(), rect));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectAllNodes(child, list);
            }
        }
    }

    private void tapNode(NodeItem item) {
        // A. Native node or parent click action
        AccessibilityNodeInfo curr = item.node;
        while (curr != null) {
            if (curr.isClickable()) {
                if (curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "Native accessibility click performed successfully.");
                    return;
                }
            }
            curr = curr.getParent();
        }

        // B. Universal Gesture Tap at center coordinates
        int cx = item.rect.centerX();
        int cy = item.rect.centerY();
        if (cx > 0 && cy > 0) {
            Log.i(TAG, "Dispatching gesture tap at (" + cx + ", " + cy + ")...");
            Path path = new Path();
            path.moveTo(cx, cy);
            GestureDescription stroke = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 50L))
                .build();
            dispatchGesture(stroke, null, null);
        }
    }

    private static class NodeItem {
        final AccessibilityNodeInfo node;
        final String text;
        final Rect rect;

        NodeItem(AccessibilityNodeInfo node, String text, Rect rect) {
            this.node = node;
            this.text = text;
            this.rect = rect;
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopSolving();
        instance = null;
        isServiceActive = false;
    }
}

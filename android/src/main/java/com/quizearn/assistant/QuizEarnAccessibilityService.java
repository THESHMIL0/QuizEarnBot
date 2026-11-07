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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private long adStartTime = 0;

    // Real math operations (excluding simple '/' which represents progress fractions like 0/25)
    private final Pattern mathPattern = Pattern.compile("(\\d+)\\s*([\\+\\-\\×\\*]|÷)\\s*(\\d+)");
    private final Pattern mathSlashPattern = Pattern.compile("(\\d+)\\s*\\/\\s*(\\d+)\\s*(?:=|\\?)");
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
        if (isRunning) {
            startSolving();
        }
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
        if (now - lastTapTime < 800) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            List<NodeItem> allNodes = new ArrayList<>();
            collectAllNodes(root, allNodes);

            if (allNodes.isEmpty()) return;

            // 1. Check for "Collect Gem", "Claim", or Reward collection buttons
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                if (t.equals("collect gem") || t.equals("collect reward") || t.equals("collect coins") || 
                    t.equals("collect") || t.equals("claim") || t.equals("claim now")) {
                    Log.i(TAG, "Found Collect/Claim button: " + item.text + " at " + item.rect);
                    tapNode(item);
                    lastTapTime = System.currentTimeMillis();
                    currentStatus = "Collecting Gem...";
                    adStartTime = 0;
                    return;
                }
            }

            // 2. Check for Ad Close / Skip / Dismiss buttons
            for (NodeItem item : allNodes) {
                String t = item.text.trim();
                if (t.equalsIgnoreCase("Close") || t.equalsIgnoreCase("Close sheet") || 
                    t.equalsIgnoreCase("Skip") || t.equalsIgnoreCase("Skip Ad") || 
                    t.equalsIgnoreCase("✕") || t.equalsIgnoreCase("✖") || 
                    t.equalsIgnoreCase("X") || t.equalsIgnoreCase("No thanks") || 
                    t.equalsIgnoreCase("Not now") || t.toLowerCase().contains("reward granted")) {
                    if (!numPattern.matcher(t).matches()) {
                        Log.i(TAG, "Found Ad Close/Skip button: " + t + " at " + item.rect);
                        tapNode(item);
                        lastTapTime = System.currentTimeMillis();
                        currentStatus = "Closing Ad...";
                        adStartTime = 0;
                        return;
                    }
                }
            }

            // 3. Ad Detection & Automated Back Key Dismissal
            boolean isAd = false;
            CharSequence pkgSeq = root.getPackageName();
            String pkg = (pkgSeq != null) ? pkgSeq.toString() : "";
            if (pkg.contains("vending") || pkg.contains("gms.ads") || pkg.contains("admob")) {
                isAd = true;
            } else {
                for (NodeItem item : allNodes) {
                    if (item.text.startsWith("Ad ") || item.text.contains("seconds after") || 
                        item.text.contains("will be taken to the website")) {
                        isAd = true;
                        break;
                    }
                }
            }

            if (isAd) {
                if (adStartTime == 0) adStartTime = now;
                long adElapsed = (now - adStartTime) / 1000;
                currentStatus = "Ad Playing (" + adElapsed + "s)...";

                // After ad has run for 5+ seconds, send Back key to close it
                if (adElapsed >= 5) {
                    Log.i(TAG, "Ad active for " + adElapsed + "s. Pressing Back to return to Quiz...");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastTapTime = System.currentTimeMillis();
                    adStartTime = 0;
                    return;
                }
                return;
            } else {
                adStartTime = 0;
            }

            // 4. Number Matching / Captcha Quiz (Duplicate 3-6 digit detection)
            Map<String, List<NodeItem>> numGroups = new HashMap<>();
            for (NodeItem item : allNodes) {
                if (numPattern.matcher(item.text).matches()) {
                    List<NodeItem> list = numGroups.get(item.text);
                    if (list == null) {
                        list = new ArrayList<>();
                        numGroups.put(item.text, list);
                    }
                    list.add(item);
                }
            }

            for (Map.Entry<String, List<NodeItem>> entry : numGroups.entrySet()) {
                if (entry.getValue().size() >= 2) {
                    List<NodeItem> group = entry.getValue();
                    Collections.sort(group, new Comparator<NodeItem>() {
                        @Override
                        public int compare(NodeItem a, NodeItem b) {
                            return Integer.compare(a.rect.centerY(), b.rect.centerY());
                        }
                    });

                    NodeItem question = group.get(0);
                    NodeItem answerButton = group.get(group.size() - 1);

                    Log.i(TAG, "TARGET NUMBER DETECTED: " + entry.getKey());
                    Log.i(TAG, "Question at Y=" + question.rect.centerY() + ", Option button at (" + answerButton.rect.centerX() + ", " + answerButton.rect.centerY() + ")");

                    currentStatus = "Target: " + entry.getKey();
                    tapNode(answerButton);

                    solvedCount++;
                    lastTapTime = System.currentTimeMillis();
                    currentStatus = "Solved #" + solvedCount + " (" + entry.getKey() + ")";
                    return;
                }
            }

            // 5. Math Quiz (e.g. 12 + 5 = ?)
            for (NodeItem item : allNodes) {
                if (item.text.contains("/25") || item.text.contains("/ 25") || item.text.contains("/50") || item.text.contains("/2")) {
                    continue;
                }

                Matcher m = mathPattern.matcher(item.text);
                boolean isSlashMath = false;
                if (!m.find()) {
                    m = mathSlashPattern.matcher(item.text);
                    isSlashMath = m.find();
                }

                if (m.matches() || isSlashMath || m.find()) {
                    try {
                        int n1 = Integer.parseInt(m.group(1));
                        String op = isSlashMath ? "/" : m.group(2);
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

            currentStatus = "Scanning...";

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

    private void tapNode(final NodeItem item) {
        final int cx = item.rect.centerX();
        final int cy = item.rect.centerY();

        if (cx > 0 && cy > 0) {
            Log.i(TAG, "Dispatching clean gesture tap at (" + cx + ", " + cy + ")...");
            Path path = new Path();
            path.moveTo(cx, cy);
            GestureDescription stroke = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 80L))
                .build();
            dispatchGesture(stroke, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    Log.i(TAG, "Gesture tap completed at (" + cx + ", " + cy + ")");
                }
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    Log.w(TAG, "Gesture tap cancelled at (" + cx + ", " + cy + ")");
                }
            }, null);
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

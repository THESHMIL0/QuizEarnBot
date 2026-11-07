package com.quizearn.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
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
    private long lastProgressTime = 0;

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
                loopHandler.postDelayed(this, 200);
            }
        }
    };

    public static void startSolving() {
        isRunning = true;
        currentStatus = "Scanning...";
        Log.i(TAG, "startSolving called! instance = " + instance);
        if (instance != null) {
            instance.lastProgressTime = System.currentTimeMillis();
            instance.adStartTime = 0;
            instance.loopHandler.removeCallbacks(instance.solveLoopRunnable);
            instance.loopHandler.post(instance.solveLoopRunnable);
        }
    }

    public static void restartQuizApp() {
        Log.i(TAG, "restartQuizApp called.");
        Context ctx = (instance != null) ? instance : null;
        if (ctx != null) {
            try {
                Intent intent = ctx.getPackageManager().getLaunchIntentForPackage("com.quiz.earn.money.game");
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    ctx.startActivity(intent);
                    Log.i(TAG, "Quiz & Earn relaunched cleanly.");
                    if (instance != null) {
                        instance.lastProgressTime = System.currentTimeMillis();
                        instance.adStartTime = 0;
                    }
                    currentStatus = "App Restarted";
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart Quiz & Earn", e);
            }
        }
    }

    public static void stopSolving() {
        isRunning = false;
        Log.i(TAG, "stopSolving called.");
        if (instance != null) {
            instance.loopHandler.removeCallbacks(instance.solveLoopRunnable);
        }
        if (!currentStatus.contains("25 GEMS")) {
            currentStatus = "Standby";
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
        if (now - lastTapTime > 250) {
            solveScreen();
        }
    }

    public synchronized void solveScreen() {
        if (!isRunning) return;

        long now = System.currentTimeMillis();
        if (now - lastTapTime < 350) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            CharSequence pkgSeq = root.getPackageName();
            String pkg = (pkgSeq != null) ? pkgSeq.toString() : "";

            // 0. Auto-Dismiss Google Play Store redirect
            if (pkg.contains("vending")) {
                Log.i(TAG, "Play Store opened by ad! Pressing Back immediately to return...");
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastTapTime = now;
                lastProgressTime = now;
                currentStatus = "Returning from Store...";
                adStartTime = 0;
                return;
            }

            List<NodeItem> allNodes = new ArrayList<>();
            collectAllNodes(root, allNodes);

            if (allNodes.isEmpty()) return;

            // 1. Detect Ad State & Countdowns (Ad 1 of 2, Ad 2 of 2, etc.)
            boolean hasAdCountdown = false;
            boolean isAd = false;
            if (pkg.contains("gms.ads") || pkg.contains("admob")) {
                isAd = true;
            }

            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase();
                String d = item.contentDesc.toLowerCase();
                String id = item.resId.toLowerCase();

                if (t.contains("ad 1 of") || t.contains("ad 2 of") || t.contains("ad 1 of 3") || 
                    t.contains("ad 2 of 3") || t.contains("ad 3 of 3") ||
                    t.contains("reward in") || t.contains("video ends in") || 
                    t.contains("video will end in") || t.contains("seconds after") || 
                    d.contains("ad 1 of") || d.contains("ad 2 of") || d.contains("reward in")) {
                    hasAdCountdown = true;
                    isAd = true;
                }

                if (!isAd && (t.startsWith("ad ") || t.contains("seconds after") || 
                    t.contains("will be taken to the website") || t.contains("install now") ||
                    t.contains("play store") || t.contains("advertisement") ||
                    t.contains("reward will be") || t.contains("you can skip in") ||
                    d.contains("liftoff") || id.contains("adjs") || id.contains("moloco") || 
                    id.contains("endcard") || id.contains("watermark") || id.contains("advert") || 
                    id.contains("unity3d") || id.contains("applovin") || id.contains("mbridge") || 
                    id.contains("tt_") || id.contains("close-or-skip-button") ||
                    t.contains("moloco") || t.contains("liftoff"))) {
                    isAd = true;
                }
            }

            // 2. OFFICIAL SKIP ARROW CHECK (Tapped as soon as network provides Skip)
            for (NodeItem item : allNodes) {
                if (isSkipElement(item)) {
                    Log.i(TAG, "⚡ OFFICIAL SKIP ARROW TAP: text='" + item.text + "', desc='" + item.contentDesc + "', id='" + item.resId + "' at " + item.rect);
                    tapNode(item);
                    lastTapTime = now;
                    lastProgressTime = now;
                    currentStatus = "Skipping Ad...";
                    return;
                }
            }

            // 3. OFFICIAL CLOSE BUTTON CHECK (Tapped only when video is completely finished!)
            for (NodeItem item : allNodes) {
                if (isCloseElement(item, hasAdCountdown)) {
                    Log.i(TAG, "⚡ OFFICIAL CLOSE BUTTON TAP: text='" + item.text + "', desc='" + item.contentDesc + "', id='" + item.resId + "' at " + item.rect);
                    tapNode(item);
                    lastTapTime = now;
                    lastProgressTime = now;
                    currentStatus = "Closing Ad...";
                    adStartTime = 0;
                    return;
                }
            }

            // 4. Safe Ad Watching: Never forcefully remove ads or auto-restart during ads!
            if (isAd) {
                lastProgressTime = now; // Actively watching ad - NOT a stall!
                if (adStartTime == 0) adStartTime = now;
                long adElapsed = (now - adStartTime) / 1000;
                currentStatus = "Ad Playing (" + adElapsed + "s)...";
                return;
            } else {
                adStartTime = 0;
            }

            // 5. Game Stall Auto-Restart (Only when in game and no ads load after rounds for > 50s)
            if (pkg.equals("com.quiz.earn.money.game") && lastProgressTime > 0 && now - lastProgressTime > 50000) {
                Log.w(TAG, "Quiz & Earn stuck without ads (>50s). Auto-restarting...");
                currentStatus = "Stall: Restarting...";
                restartQuizApp();
                lastProgressTime = now;
                return;
            }

            // 3. Check for 25 Gems Milestone (Stop bot for 1,000 Coins Super Offer)
            boolean hasCollectGem = false;
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                String combined = (t + " " + d).trim();
                if (combined.contains("collect gem") || combined.contains("collect reward") || combined.contains("collect coins")) {
                    hasCollectGem = true;
                    break;
                }
            }

            boolean is25Gems = false;
            for (int i = 0; i < allNodes.size(); i++) {
                String t = allNodes.get(i).text.toLowerCase().trim();
                if (t.contains("25/25") || t.contains("25 / 25")) {
                    is25Gems = true;
                    break;
                }
                if (t.contains("super offer unlocked") || t.contains("super task unlocked")) {
                    is25Gems = true;
                    break;
                }
                // Check split nodes: "25" then "/" then "25" on the same row
                if (t.equals("25")) {
                    for (int j = i + 1; j < Math.min(i + 5, allNodes.size()); j++) {
                        String tj = allNodes.get(j).text.trim();
                        if (tj.equals("/")) {
                            for (int k = j + 1; k < Math.min(j + 5, allNodes.size()); k++) {
                                String tk = allNodes.get(k).text.trim();
                                if (tk.equals("25")) {
                                    int diffY = Math.abs(allNodes.get(i).rect.centerY() - allNodes.get(k).rect.centerY());
                                    if (diffY < 80) {
                                        is25Gems = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                if (is25Gems) break;
            }

            if (solvedCount >= 25) {
                is25Gems = true;
            }

            // If 25 gems reached and no pending Collect Gem popup: PAUSE BOT!
            if (is25Gems && !hasCollectGem) {
                Log.i(TAG, "25 GEMS TARGET REACHED! Milestone achieved. Pausing bot for Super Offer claim.");
                currentStatus = "25 GEMS HIT! (1000 Coins)";
                stopSolving();
                return;
            }

            // 4. Check for "Collect Gem", "Claim", or Reward collection buttons
            for (NodeItem item : allNodes) {
                if (isForbiddenElement(item)) continue;
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                String combined = (t + " " + d).trim();
                if (combined.contains("collect gem") || combined.contains("collect reward") || combined.contains("collect coins") || 
                    t.equals("collect") || t.equals("claim") || t.equals("claim now")) {
                    Log.i(TAG, "Found Collect/Claim button: " + item.text + " at " + item.rect);
                    tapNode(item);
                    lastTapTime = now;
                    lastProgressTime = now;
                    currentStatus = "Collecting Gem...";
                    adStartTime = 0;
                    return;
                }
            }

            // 5. Number Matching / Captcha Quiz (Duplicate 3-6 digit detection)
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
                    lastTapTime = now;
                    lastProgressTime = now;
                    currentStatus = "Solved #" + solvedCount + " (" + entry.getKey() + ")";
                    return;
                }
            }

            // 6. Math Quiz (e.g. 12 + 5 = ?)
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
                                lastTapTime = now;
                                lastProgressTime = now;
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

        CharSequence pkgSeq = node.getPackageName();
        String nodePkg = (pkgSeq != null) ? pkgSeq.toString() : "";

        // STRICT NOTIFICATION & FOREIGN APP FILTER
        if (nodePkg.equals("com.android.systemui") || 
            nodePkg.equals("com.android.launcher") || 
            nodePkg.contains("launcher") || 
            (!nodePkg.isEmpty() && !nodePkg.equals("com.quiz.earn.money.game") && 
             !nodePkg.contains("gms.ads") && !nodePkg.contains("admob") && 
             !nodePkg.contains("vending") && !nodePkg.contains("webview") && 
             !nodePkg.contains("quizearn.assistant"))) {
            return;
        }

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);

        CharSequence textSeq = node.getText();
        CharSequence descSeq = node.getContentDescription();
        CharSequence idSeq = node.getViewIdResourceName();
        String text = (textSeq != null) ? textSeq.toString().trim() : "";
        String desc = (descSeq != null) ? descSeq.toString().trim() : "";
        String resId = (idSeq != null) ? idSeq.toString().trim() : "";

        // Effective text falls back to content description
        String effectiveText = !text.isEmpty() ? text : desc;

        // Filter out status bar area (top 110px clock/icons), but NEVER filter out close/skip buttons
        if (rect.bottom <= 110) {
            String lower = (text + " " + desc + " " + resId).toLowerCase();
            if (!lower.contains("close") && !lower.contains("skip") && 
                !lower.contains("\u2715") && !lower.contains("\u2716") && 
                !lower.equals("x") && !lower.contains("dismiss")) {
                return;
            }
        }

        if ((!effectiveText.isEmpty() || !resId.isEmpty()) && !rect.isEmpty()) {
            list.add(new NodeItem(node, effectiveText, desc, resId, rect));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectAllNodes(child, list);
            }
        }
    }

    private void tapCoordinates(final int cx, final int cy) {
        if (cx > 0 && cy > 0) {
            Log.i(TAG, "Dispatching gesture tap at (" + cx + ", " + cy + ")...");
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

    private void tapNode(final NodeItem item) {
        int cx = item.rect.centerX();
        int cy = item.rect.centerY();
        // If close/skip button overlaps status bar (Y < 120), clamp Y so OS doesn't swallow gesture
        if (cy < 120) {
            if (item.rect.bottom > 120) {
                cy = item.rect.bottom - 10;
            } else {
                cy = 125;
            }
        }
        tapCoordinates(cx, cy);
    }

    // Global filter to NEVER touch sound/mute, install, privacy, or info buttons
    private boolean isForbiddenElement(NodeItem item) {
        String t = item.text.toLowerCase().trim();
        String d = item.contentDesc.toLowerCase().trim();
        String id = item.resId.toLowerCase().trim();
        String combined = (t + " " + d + " " + id);

        // Sound / Audio / Mute / Volume / Music
        if (combined.contains("sound") || combined.contains("mute") || 
            combined.contains("unmute") || combined.contains("volume") || 
            combined.contains("audio") || combined.contains("speaker") || 
            combined.contains("music") || id.contains("sound") || 
            id.contains("mute") || id.contains("volume") || id.contains("audio")) {
            return true;
        }

        // Install / Download / CTA
        if (combined.contains("install") || combined.contains("download") || 
            combined.contains("get app") || combined.contains("play store") || 
            id.contains("install") || id.contains("download") || id.contains("cta")) {
            return true;
        }

        // Info / AdChoices / Privacy / Report
        if (combined.contains("adchoice") || combined.contains("privacy") || 
            combined.contains("about this ad") || combined.contains("why this ad") ||
            id.contains("adchoice") || id.contains("privacy") || id.contains("info")) {
            return true;
        }

        return false;
    }

    private boolean isSkipElement(NodeItem item) {
        if (isForbiddenElement(item)) {
            return false;
        }

        String t = item.text.toLowerCase().trim();
        String d = item.contentDesc.toLowerCase().trim();
        String id = item.resId.toLowerCase().trim();

        if (numPattern.matcher(item.text).matches()) {
            return false;
        }

        // Explicit skip text or description
        if (t.contains("skip") || d.contains("skip")) {
            return true;
        }

        // Visible skip arrows: >>, ››, », >|, ▶▶
        if (t.contains(">>") || t.contains("\u203A\u203A") || t.contains("\u00BB") || 
            t.contains(">|") || t.contains("\u25B6\u25B6") || 
            d.contains(">>") || d.contains("\u203A\u203A") || d.contains("\u00BB") || 
            d.contains("fast forward")) {
            return true;
        }

        // Only exact skip resource IDs
        if (id.contains("skip_button") || id.contains("btn_skip") || 
            id.contains("close-or-skip-button") || id.contains("skip_ad")) {
            return true;
        }

        return false;
    }

    private boolean isCloseElement(NodeItem item, boolean hasAdCountdown) {
        if (isForbiddenElement(item)) {
            return false;
        }

        String t = item.text.toLowerCase().trim();
        String d = item.contentDesc.toLowerCase().trim();
        String id = item.resId.toLowerCase().trim();

        if (numPattern.matcher(item.text).matches()) {
            return false;
        }

        // Multi-ad countdown indicator ("Ad 1 of 2", "Ad 2 of 2", etc.)
        // NEVER treat as close button!
        if (t.contains("ad 1 of") || t.contains("ad 2 of") || t.contains("ad 1 of 3") || 
            t.contains("ad 2 of 3") || t.contains("ad 3 of 3") ||
            d.contains("ad 1 of") || d.contains("ad 2 of") || d.contains("ad 1 of 3") || 
            d.contains("ad 2 of 3") || d.contains("ad 3 of 3")) {
            return false;
        }
        if ((t.contains("close ad") || d.contains("close ad")) && item.rect.centerX() < 350) {
            return false;
        }

        // While an ad countdown is actively showing, DO NOT tap any generic "Close Ad"
        // so we don't prematurely kill the ad before all videos in the sequence finish.
        if (hasAdCountdown) {
            if (t.contains("close ad") || d.contains("close ad")) {
                return false;
            }
        }

        // Explicit Close resource IDs
        if (id.contains("close_button") || id.contains("btn_close") || id.contains("iv_close") || 
            id.contains("tt_video_ad_close") || id.contains("close_ad_btn")) {
            return true;
        }

        // Explicit Close text symbols (✕, ✖, ⨯, ×, X)
        if (t.equals("\u2715") || t.equals("\u2716") || t.equals("\u2A2F") || 
            t.equals("\u00D7") || t.equalsIgnoreCase("x")) {
            return true;
        }

        // Content description strictly "close" or "dismiss" or "close button"
        if (d.equals("close") || d.equals("dismiss") || d.equals("close button") || d.equals("close video")) {
            return true;
        }

        // Post-reward claims
        if (t.contains("reward granted") || t.contains("reward credited") || 
            t.contains("no thanks") || t.contains("not now")) {
            return true;
        }

        if ((t.equals("close") || t.equals("dismiss")) && item.rect.centerX() > 300) {
            return true;
        }

        return false;
    }

    private static class NodeItem {
        final AccessibilityNodeInfo node;
        final String text;
        final String contentDesc;
        final String resId;
        final Rect rect;

        NodeItem(AccessibilityNodeInfo node, String text, String contentDesc, String resId, Rect rect) {
            this.node = node;
            this.text = text;
            this.contentDesc = contentDesc;
            this.resId = resId;
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

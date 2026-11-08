package com.quizearn.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
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
    public static volatile int liveGems = -1;
    public static volatile String currentStatus = "Standby";
    private static boolean hasAlerted25Gems = false;

    public static QuizEarnAccessibilityService instance = null;

    private final Handler loopHandler = new Handler(Looper.getMainLooper());
    private long lastTapTime = 0;
    private long adStartTime = 0;
    private long lastProgressTime = 0;
    private String lastSolvedTarget = "";
    private long lastSolveTime = 0;

    // Real math operations (including unicode multiplication and division)
    private final Pattern mathPattern = Pattern.compile("(\\d+)\\s*([\\+\\-\\–\\—\\*×xX]|÷|\\/)\\s*(\\d+)");
    private final Pattern mathSlashPattern = Pattern.compile("(\\d+)\\s*\\/\\s*(\\d+)\\s*(?:=|\\?)");
    private final Pattern numPattern = Pattern.compile("^\\d{3,6}$");

    private String cleanNumText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[\\s\\u00A0\\u200B\\r\\n]+", "").trim();
    }

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
        hasAlerted25Gems = false;
        currentStatus = "Scanning...";
        Log.i(TAG, "startSolving called! instance = " + instance);
        if (instance != null) {
            instance.lastSolvedTarget = "";
            instance.lastSolveTime = 0;
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
                        instance.lastSolvedTarget = "";
                        instance.lastSolveTime = 0;
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
            instance.lastSolvedTarget = "";
            instance.lastSolveTime = 0;
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
        if (now - lastTapTime > 350) {
            solveScreen();
        }
    }

    public synchronized void solveScreen() {
        if (!isRunning) return;

        long now = System.currentTimeMillis();
        if (now - lastTapTime < 450) {
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

                if (t.contains("reward in") || t.contains("video ends in") || 
                    t.contains("video will end in") || t.contains("seconds after") || 
                    t.contains("skip in ") || d.contains("reward in") || 
                    d.contains("ends in") || d.contains("skip in ")) {
                    hasAdCountdown = true;
                    isAd = true;
                }

                if (t.contains("ad 1 of") || t.contains("ad 2 of") || t.contains("ad 1 of 3") || 
                    t.contains("ad 2 of 3") || t.contains("ad 3 of 3") ||
                    d.contains("ad 1 of") || d.contains("ad 2 of")) {
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
                    lastSolvedTarget = "";
                    lastSolveTime = 0;
                    currentStatus = "Skipping Ad...";
                    return;
                }
            }

            // 2.5 REWARD GRANTED CHECK (Rewarded ad finished! Tap the top-right Close X icon)
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase().trim();
                if (t.contains("reward granted") || t.contains("reward credited")) {
                    Log.i(TAG, "Reward granted banner detected! Tapping top-right Close (X)...");
                    int screenW = getResources().getDisplayMetrics().widthPixels;
                    int targetX = Math.min(screenW - 75, item.rect.right + 60);
                    int targetY = item.rect.centerY();
                    tapCoordinates(targetX, targetY);
                    lastTapTime = now;
                    lastProgressTime = now;
                    lastSolvedTarget = "";
                    lastSolveTime = 0;
                    adStartTime = 0;
                    currentStatus = "Reward Claimed! Closing...";
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
                    lastSolvedTarget = "";
                    lastSolveTime = 0;
                    currentStatus = "Closing Ad...";
                    adStartTime = 0;
                    return;
                }
            }

            // 4. Safe Ad Watching & Smart Progressive Ad Dismissal
            if (isAd) {
                lastProgressTime = now; // Actively watching ad - NOT a stall!
                if (adStartTime == 0) adStartTime = now;
                long adElapsed = (now - adStartTime) / 1000;
                currentStatus = "Ad Playing (" + adElapsed + "s)...";

                int screenW = getResources().getDisplayMetrics().widthPixels;
                int screenH = getResources().getDisplayMetrics().heightPixels;

                // After 22s: Search for small corner icons / back buttons in custom webview/canvas
                if (adElapsed >= 22 && (now - lastTapTime > 2500)) {
                    for (NodeItem item : allNodes) {
                        if (isForbiddenElement(item)) continue;
                        int cx = item.rect.centerX();
                        int cy = item.rect.centerY();
                        int w = item.rect.width();
                        int h = item.rect.height();

                        // Top-right close icon in webview/playable
                        if (cx > (int)(screenW * 0.80) && cy < 350 && w < 250 && h < 250) {
                            Log.i(TAG, "Ad timeout (" + adElapsed + "s): Tapping top-right corner node at " + item.rect);
                            tapNode(item);
                            lastTapTime = now;
                            return;
                        }

                        // Top-left back / skip arrow in webview/playable
                        if (cx < (int)(screenW * 0.20) && cy < 350 && w < 250 && h < 250) {
                            Log.i(TAG, "Ad timeout (" + adElapsed + "s): Tapping top-left corner node at " + item.rect);
                            tapNode(item);
                            lastTapTime = now;
                            return;
                        }
                    }
                }

                // After 28s: Universal Close coordinates tap (covers Google, Unity, AppLovin, Liftoff)
                if (adElapsed >= 28 && adElapsed < 36 && (now - lastTapTime > 2500)) {
                    Log.i(TAG, "Ad running long (" + adElapsed + "s): Tapping top-right universal close coordinates...");
                    tapCoordinates(screenW - 75, 180);
                    lastTapTime = now;
                    return;
                }

                // After 36s+: Press native Android Back key to return to Quiz
                if (adElapsed >= 36 && (now - lastTapTime > 3000)) {
                    Log.i(TAG, "Ad timeout (" + adElapsed + "s): Pressing Back key to return to Quiz...");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastTapTime = now;
                    return;
                }

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

            // 3. Detect Real Gem Count & 25 Gems Milestone
            int detectedGems = -1;

            // Pattern A: Match "X/25" in a single node (e.g. "5/25 gems to unlock", "20/25")
            Pattern slash25Pattern = Pattern.compile("(\\d+)\\s*/\\s*25");
            for (NodeItem item : allNodes) {
                Matcher m = slash25Pattern.matcher(item.text);
                if (m.find()) {
                    try {
                        detectedGems = Integer.parseInt(m.group(1));
                        break;
                    } catch (Exception ignored) {}
                }
            }

            // Pattern B: Match split nodes in Super Offer row: [X] [/] [25]
            if (detectedGems < 0) {
                for (int i = 0; i < allNodes.size(); i++) {
                    if (allNodes.get(i).text.trim().equals("/")) {
                        if (i > 0 && (i + 1) < allNodes.size()) {
                            NodeItem prev = allNodes.get(i - 1);
                            NodeItem next = allNodes.get(i + 1);
                            if (next.text.trim().equals("25")) {
                                try {
                                    int g = Integer.parseInt(prev.text.trim());
                                    int diffY = Math.abs(prev.rect.centerY() - next.rect.centerY());
                                    if (diffY < 60) {
                                        detectedGems = g;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }

            // Pattern C: Top-right header gem badge ([💎] [X] at X > 850, Y < 300)
            if (detectedGems < 0) {
                for (NodeItem item : allNodes) {
                    if (item.rect.centerX() > 850 && item.rect.centerY() > 140 && item.rect.centerY() < 300) {
                        if (item.text.matches("^\\d{1,3}$")) {
                            try {
                                detectedGems = Integer.parseInt(item.text.trim());
                                break;
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (detectedGems >= 0) {
                liveGems = detectedGems;
            }

            boolean hasCollectGem = false;
            for (NodeItem item : allNodes) {
                if (isForbiddenElement(item)) continue;
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                String combined = (t + " " + d).trim();
                if (combined.contains("collect gem") || combined.contains("collect reward") || combined.contains("collect coins")) {
                    hasCollectGem = true;
                    break;
                }
            }

            boolean is25Gems = false;
            // Stop ONLY when verified real gems reached >= 25
            if (liveGems >= 25) {
                is25Gems = true;
            }

            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase().trim();
                if (t.contains("super offer unlocked") || t.contains("super task unlocked")) {
                    is25Gems = true;
                    break;
                }
            }

            // If 25 gems reached and no pending Collect Gem popup: PAUSE BOT!
            if (is25Gems && !hasCollectGem) {
                Log.i(TAG, "25 GEMS TARGET REACHED! Milestone achieved (" + liveGems + "/25). Pausing bot for Super Offer claim.");
                currentStatus = "25 GEMS HIT! (1000 Coins)";
                if (!hasAlerted25Gems) {
                    hasAlerted25Gems = true;
                    play25GemsMilestoneAlert();
                }
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
                    lastSolvedTarget = "";
                    lastSolveTime = 0;
                    currentStatus = "Collecting Gem...";
                    adStartTime = 0;
                    return;
                }
            }

            // 5. ZONE-BASED 100% ACCURATE QUIZ SOLVER
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;

            // Strict spatial zones to prevent ANY header/footer badge misidentification:
            // - Header Zone: Y < screenH * 0.12 (Gems counter, Gift badges, Quiz title) -> EXCLUDED
            // - Question Card Zone: screenH * 0.12 <= Y <= screenH * 0.36 (Math equation / Captcha number)
            // - Options Grid Zone: screenH * 0.28 <= Y <= screenH * 0.60 (2x2 grid of 4 answer buttons)
            // - Footer Zone: Y > screenH * 0.60 (Super offer milestones, 1000 coins text, My Offers) -> EXCLUDED
            int qMinY = (int) (screenH * 0.12);
            int qMaxY = (int) (screenH * 0.36);
            int optMinY = (int) (screenH * 0.28);
            int optMaxY = (int) (screenH * 0.60);

            String detectedTarget = null;
            String targetAnsStr = null;
            int questionCenterY = -1;

            // --- 5A. MATH QUIZ DETECTION ---
            for (NodeItem item : allNodes) {
                int cy = item.rect.centerY();
                if (cy < qMinY || cy > qMaxY) continue;

                String rawText = item.text.trim();
                if (rawText.contains("/25") || rawText.contains("/ 25") || rawText.contains("/50") || rawText.contains("/2")) {
                    continue;
                }
                String lower = rawText.toLowerCase();
                if (lower.contains("attempt") || lower.contains("select") || lower.contains("correct")) {
                    continue;
                }

                Matcher m = mathPattern.matcher(rawText);
                boolean isSlashMath = false;
                if (!m.find()) {
                    m = mathSlashPattern.matcher(rawText);
                    isSlashMath = m.find();
                }

                if (m.matches() || isSlashMath || m.find()) {
                    try {
                        int n1 = Integer.parseInt(m.group(1));
                        String op = isSlashMath ? "/" : m.group(2);
                        int n2 = Integer.parseInt(m.group(3));

                        int ans = -1;
                        if ("+".equals(op)) ans = n1 + n2;
                        else if ("-".equals(op) || "–".equals(op) || "—".equals(op)) ans = n1 - n2;
                        else if ("*".equals(op) || "×".equals(op) || "x".equalsIgnoreCase(op)) ans = n1 * n2;
                        else if ("/".equals(op) || "÷".equals(op)) {
                            if (n2 != 0 && (n1 % n2 == 0)) {
                                ans = n1 / n2;
                            }
                        }

                        if (ans >= 0) {
                            detectedTarget = "MATH:" + n1 + op + n2 + "=" + ans;
                            targetAnsStr = String.valueOf(ans);
                            questionCenterY = cy;
                            Log.i(TAG, "Math Quiz Detected in Question Zone: " + n1 + " " + op + " " + n2 + " = " + ans);
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }

            // --- 5B. NUMBER CAPTCHA QUIZ DETECTION ---
            if (detectedTarget == null) {
                // Find target number in Question Zone (e.g. 3983)
                NodeItem bestQNode = null;
                int maxArea = -1;
                for (NodeItem item : allNodes) {
                    int cy = item.rect.centerY();
                    if (cy < qMinY || cy > qMaxY) continue;

                    String cleaned = cleanNumText(item.text);
                    if (numPattern.matcher(cleaned).matches()) {
                        int area = item.rect.width() * item.rect.height();
                        if (area > maxArea) {
                            maxArea = area;
                            bestQNode = item;
                        }
                    }
                }

                if (bestQNode != null) {
                    String targetNum = cleanNumText(bestQNode.text);
                    detectedTarget = "CAPTCHA:" + targetNum;
                    targetAnsStr = targetNum;
                    questionCenterY = bestQNode.rect.centerY();
                    Log.i(TAG, "Number Captcha Detected in Question Zone: " + targetNum + " at " + bestQNode.rect);
                } else {
                    // Fallback: check if single-digit nodes form the target in Question Zone
                    List<NodeItem> digitNodes = new ArrayList<>();
                    for (NodeItem item : allNodes) {
                        int cy = item.rect.centerY();
                        if (cy >= qMinY && cy <= qMaxY) {
                            String cleaned = cleanNumText(item.text);
                            if (cleaned.length() == 1 && Character.isDigit(cleaned.charAt(0))) {
                                digitNodes.add(item);
                            }
                        }
                    }
                    if (digitNodes.size() >= 3 && digitNodes.size() <= 6) {
                        Collections.sort(digitNodes, new Comparator<NodeItem>() {
                            @Override
                            public int compare(NodeItem a, NodeItem b) {
                                return Integer.compare(a.rect.left, b.rect.left);
                            }
                        });
                        StringBuilder sb = new StringBuilder();
                        for (NodeItem d : digitNodes) {
                            sb.append(cleanNumText(d.text));
                        }
                        String reconstructed = sb.toString();
                        detectedTarget = "CAPTCHA:" + reconstructed;
                        targetAnsStr = reconstructed;
                        Log.i(TAG, "Reconstructed Captcha Target from single-digit nodes: " + reconstructed);
                    }
                }
            }

            // --- 5C. GENERAL DUPLICATE FALLBACK (If layout slightly shifted) ---
            if (detectedTarget == null) {
                Map<String, List<NodeItem>> numGroups = new HashMap<>();
                for (NodeItem item : allNodes) {
                    int cy = item.rect.centerY();
                    // Exclude Header Zone (< qMinY) and Footer Zone (> optMaxY + 80)
                    if (cy < qMinY || cy > (optMaxY + 80)) continue;

                    String cleaned = cleanNumText(item.text);
                    if (numPattern.matcher(cleaned).matches()) {
                        List<NodeItem> list = numGroups.get(cleaned);
                        if (list == null) {
                            list = new ArrayList<>();
                            numGroups.put(cleaned, list);
                        }
                        list.add(item);
                    }
                }

                for (Map.Entry<String, List<NodeItem>> entry : numGroups.entrySet()) {
                    if (entry.getValue().size() >= 2) {
                        detectedTarget = "CAPTCHA:" + entry.getKey();
                        targetAnsStr = entry.getKey();
                        Log.i(TAG, "Fallback duplicate target detected: " + entry.getKey());
                        break;
                    }
                }
            }

            // --- 5D. SOLVE TARGET IF DETECTED ---
            if (detectedTarget != null && targetAnsStr != null) {
                // Anti-Ghosting / Transition Protection:
                // If this is the exact same target we just solved within the last 2.5 seconds,
                // do NOT tap again! The UI is still transitioning to the next question.
                if (detectedTarget.equals(lastSolvedTarget) && (now - lastSolveTime < 2500)) {
                    // Waiting for screen to update to next question
                    return;
                }

                // Find the answer button strictly in the Options Zone below the question
                NodeItem answerButton = null;
                for (NodeItem opt : allNodes) {
                    if (isForbiddenElement(opt)) continue;
                    int cy = opt.rect.centerY();
                    if (cy < (optMinY - 40) || cy > (optMaxY + 60)) continue;
                    if (questionCenterY > 0 && cy <= questionCenterY) continue;

                    String optText = cleanNumText(opt.text);
                    String optDesc = cleanNumText(opt.contentDesc);
                    if (optText.equals(targetAnsStr) || optDesc.equals(targetAnsStr)) {
                        answerButton = opt;
                        break;
                    }
                }

                if (answerButton != null) {
                    Log.i(TAG, "⚡ SOLVING ANSWER: " + detectedTarget + " -> Tapping option button '" + targetAnsStr + "' at " + answerButton.rect);
                    tapNode(answerButton);

                    lastSolvedTarget = detectedTarget;
                    lastSolveTime = now;
                    lastTapTime = now;
                    lastProgressTime = now;
                    solvedCount++;
                    currentStatus = "Solved #" + solvedCount + " (" + targetAnsStr + ")";
                    return;
                } else {
                    Log.d(TAG, "Target detected (" + detectedTarget + "), but option button not yet visible in Options Zone.");
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

        if (item.rect.width() > 6) {
            cx = Math.max(item.rect.left + 3, Math.min(item.rect.right - 3, cx));
        }
        if (item.rect.height() > 6) {
            cy = Math.max(item.rect.top + 3, Math.min(item.rect.bottom - 3, cy));
        }

        // Always dispatch real hardware touch gesture (essential for custom UI, React Native & games)
        tapCoordinates(cx, cy);

        // Also trigger native accessibility action in parallel (ensures ad webview close handles it)
        try {
            if (item.node != null) {
                if (item.node.isClickable()) {
                    item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                AccessibilityNodeInfo parent = item.node.getParent();
                if (parent != null && parent.isClickable()) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
        } catch (Exception ignored) {}
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

        // Do not tap countdown timers (e.g. "Skip in 5s", "You can skip in 3 seconds")
        if (t.contains(" in ") || d.contains(" in ") || t.contains("seconds") || d.contains("seconds")) {
            return false;
        }

        // Explicit skip text or description
        if (t.equals("skip") || t.equals("skip ad") || t.startsWith("skip ") ||
            d.equals("skip") || d.equals("skip ad") || d.startsWith("skip ")) {
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

        // Countdown indicators ("Ad 1 of 2", "Video ends in 5s", "Reward in 10s")
        if (t.contains("ad 1 of") || t.contains("ad 2 of") || t.contains("ad 3 of") ||
            d.contains("ad 1 of") || d.contains("ad 2 of") || d.contains("ad 3 of") ||
            t.contains("ends in") || d.contains("ends in") ||
            t.contains("reward in") || d.contains("reward in") ||
            t.contains("seconds after") || d.contains("seconds after")) {
            return false;
        }

        // Do not close prematurely while countdown is active
        if (hasAdCountdown) {
            return false;
        }

        // Explicit Close / Close Ad content descriptions (e.g. Google Ads, Liftoff, AppLovin)
        if (d.equals("close ad") || d.equals("close") || d.equals("close button") || 
            d.equals("close video") || d.equals("dismiss") || d.contains("close ad")) {
            return true;
        }

        // Explicit Close resource IDs
        if (id.contains("close_button") || id.contains("btn_close") || id.contains("iv_close") || 
            id.contains("ia_clickable_close_button") || id.contains("tt_video_ad_close") || 
            id.contains("close_ad") || id.contains("ad_close") || id.contains("btn_back") || 
            id.contains("iv_back") || id.contains("button_back")) {
            return true;
        }

        // Explicit Close text symbols (✕, ✖, ⨯, ×, X, <, ‹, back, exit)
        if (t.equals("\u2715") || t.equals("\u2716") || t.equals("\u2A2F") || 
            t.equals("\u00D7") || t.equalsIgnoreCase("x") ||
            t.equals("<") || t.equals("\u2039") || t.equals("‹") ||
            t.equals("back") || t.equals("exit") ||
            d.equals("back") || d.equals("navigate up") || d.equals("exit")) {
            return true;
        }

        // Exact text match for close
        if (t.equals("close ad") || t.equals("close") || t.equals("dismiss") || 
            t.equals("no thanks") || t.equals("not now")) {
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

    private void play25GemsMilestoneAlert() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. Triumphant vibration pattern
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null && v.hasVibrator()) {
                        long[] pattern = {0, 350, 150, 350, 150, 600};
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                        } else {
                            v.vibrate(pattern, -1);
                        }
                    }
                } catch (Exception ignored) {}

                try {
                    // 2. Play default notification / alarm ringtone loud
                    Uri alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                    if (alertUri == null) {
                        alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                    }
                    if (alertUri != null) {
                        Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), alertUri);
                        if (r != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                AudioAttributes aa = new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build();
                                r.setAudioAttributes(aa);
                            }
                            r.play();
                        }
                    }
                } catch (Exception ignored) {}

                try {
                    // 3. Play loud ascending tone chime via ToneGenerator as foolproof audible fallback
                    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 300);
                    try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                    tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
                } catch (Exception ignored) {}
            }
        }).start();
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

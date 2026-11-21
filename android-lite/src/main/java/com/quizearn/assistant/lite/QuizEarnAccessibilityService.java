package com.quizearn.assistant.lite;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quiz & Earn Lite Accessibility Service
 * Specialized lightweight automation:
 * - Math Quiz Solver (+, -, *, /)
 * - Number Captcha Solver
 * - Gem Collection & Rewarded Ad Watching / Safe Ad Closing
 * - STRICT 25-GEM STOP & ALERT (Zero Super Offer automation, Zero Play Store installs)
 */
public class QuizEarnAccessibilityService extends AccessibilityService {

    public static final String TAG = "QuizEarnLiteAssistant";

    public static volatile boolean isRunning = false;
    public static volatile boolean isUserRunning = false;
    public static volatile boolean isServiceActive = false;
    public static volatile int solvedCount = 0;
    public static volatile int liveGems = -1;
    public static volatile boolean is25GemsOnScreen = false;
    public static volatile String currentStatus = "Standby";
    private static boolean hasAlerted25Gems = false;
    public static volatile boolean isCollecting25thGem = false;
    public static volatile boolean finalAdStarted = false;

    public static volatile String preferredQuizPackage = "com.quiz.earn.money.game";
    public static volatile String lastActiveQuizPackage = "";

    private static final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    public static QuizEarnAccessibilityService instance = null;

    private final Handler loopHandler = new Handler(Looper.getMainLooper());
    private long lastTapTime = 0;
    private long lastProgressTime = 0;
    private long lastSolveTime = 0;
    private String lastSolvedTarget = "";
    private long adStartTime = 0;
    private long collectGemScreenStartTime = 0;
    private int collectGemTapCount = 0;
    private int collectGemRestartCount = 0;
    private long lastSeenCollectionScreenTime = 0;
    private long questionWaitingStartTime = 0;
    private String lastWaitingQuestion = "";
    private long emptyNodesStartTime = 0;
    private int externalAppBackCount = 0;
    private String currentActivityName = "";

    private final Pattern mathPattern = Pattern.compile("(\\d{1,4})\\s*([+\\-\\–\\—*×x÷])\\s*(\\d{1,4})\\s*=\\s*\\?");
    private final Pattern mathSlashPattern = Pattern.compile("(\\d{1,4})\\s*/\\s*(\\d{1,4})\\s*=\\s*\\?");
    private final Pattern numPattern = Pattern.compile("^\\d{1,6}$");

    public static void setPreferredQuizPackage(Context context, String pkg) {
        if (pkg != null && isQuizAppPackage(pkg)) {
            preferredQuizPackage = pkg;
            if (context != null) {
                try {
                    context.getSharedPreferences("QuizEarnLitePrefs", Context.MODE_PRIVATE)
                        .edit().putString("preferred_quiz_pkg", pkg).apply();
                } catch (Exception ignored) {}
            }
        }
    }

    public static boolean isQuizAppPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return pkg.equals("com.quiz.earn.money.game") ||
               pkg.equals("com.rayole.cashmafia2") ||
               pkg.contains("cashmafia") ||
               pkg.contains("quiz.earn");
    }

    public static String getQuizAppPackage(Context ctx) {
        if (preferredQuizPackage != null && !preferredQuizPackage.isEmpty()) {
            return preferredQuizPackage;
        }
        if (ctx != null) {
            try {
                String saved = ctx.getSharedPreferences("QuizEarnLitePrefs", Context.MODE_PRIVATE)
                    .getString("preferred_quiz_pkg", "");
                if (!saved.isEmpty()) return saved;
            } catch (Exception ignored) {}
        }
        return "com.quiz.earn.money.game";
    }

    private String cleanNumText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^0-9]", "").trim();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * Math.max(0.75f, density));
    }

    private final Runnable solveLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                try {
                    solveScreen();
                } catch (Throwable t) {
                    Log.e(TAG, "Recovered in solveScreen loop", t);
                } finally {
                    if (isRunning) {
                        loopHandler.postDelayed(this, 140);
                    }
                }
            }
        }
    };

    public static void startSolving() {
        isRunning = true;
        isUserRunning = true;
        liveGems = -1;
        hasAlerted25Gems = false;
        isCollecting25thGem = false;
        finalAdStarted = false;
        currentStatus = "Scanning...";

        final QuizEarnAccessibilityService svc = instance;
        if (svc != null) {
            svc.lastSolvedTarget = "";
            svc.lastSolveTime = 0;
            svc.lastProgressTime = System.currentTimeMillis();
            svc.adStartTime = 0;
            svc.collectGemScreenStartTime = 0;
            svc.collectGemTapCount = 0;
            svc.collectGemRestartCount = 0;
            svc.lastSeenCollectionScreenTime = 0;
            svc.questionWaitingStartTime = 0;
            svc.lastWaitingQuestion = "";
            svc.emptyNodesStartTime = 0;
            svc.loopHandler.removeCallbacks(svc.solveLoopRunnable);
            svc.loopHandler.post(svc.solveLoopRunnable);
        }
    }

    public static void stopSolving() {
        isRunning = false;
        isUserRunning = false;
        currentStatus = "Paused";
        final QuizEarnAccessibilityService svc = instance;
        if (svc != null) {
            svc.loopHandler.removeCallbacks(svc.solveLoopRunnable);
        }
    }

    public static void restartCurrentQuiz(Context callerCtx) {
        restartQuizApp(callerCtx);
    }

    public static void restartQuizApp() {
        restartQuizApp(null);
    }

    private static volatile long lastRestartCallTime = 0;

    public static void restartQuizApp(Context callerCtx) {
        long nowMs = SystemClock.uptimeMillis();
        if (nowMs - lastRestartCallTime < 2500) {
            return;
        }
        lastRestartCallTime = nowMs;
        Log.i(TAG, "restartQuizApp called.");
        isCollecting25thGem = false;
        finalAdStarted = false;
        hasAlerted25Gems = false;
        is25GemsOnScreen = false;
        final Context ctx = (callerCtx != null) ? callerCtx : instance;
        if (ctx != null) {
            try {
                final Context appCtx = ctx.getApplicationContext();
                final String targetPkg = getQuizAppPackage(appCtx);

                if (instance != null) {
                    try {
                        instance.performGlobalAction(GLOBAL_ACTION_HOME);
                    } catch (Throwable ignored) {}
                }

                if (instance != null) {
                    instance.lastSolvedTarget = "";
                    instance.lastSolveTime = 0;
                    instance.lastProgressTime = System.currentTimeMillis();
                    instance.adStartTime = 0;
                    instance.collectGemScreenStartTime = 0;
                    instance.collectGemTapCount = 0;
                    instance.lastSeenCollectionScreenTime = 0;
                    instance.questionWaitingStartTime = 0;
                    instance.lastWaitingQuestion = "";
                }
                currentStatus = "Restarting App...";

                bgExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ActivityManager am = (ActivityManager) appCtx.getSystemService(Context.ACTIVITY_SERVICE);
                            if (am != null) {
                                am.killBackgroundProcesses(targetPkg);
                            }
                        } catch (Throwable ignored) {}

                        try { Thread.sleep(700); } catch (InterruptedException ignored) {}

                        try {
                            PackageManager pm = appCtx.getPackageManager();
                            Intent launch = pm.getLaunchIntentForPackage(targetPkg);
                            if (launch != null) {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                appCtx.startActivity(launch);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "Failed to launch target pkg: " + targetPkg, t);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "Failed in restartQuizApp", t);
            }
        }
    }

    public static void bringQuizAppToFront() {
        final QuizEarnAccessibilityService svc = instance;
        if (svc == null) return;
        try {
            String targetPkg = getQuizAppPackage(svc);
            PackageManager pm = svc.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(targetPkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                svc.startActivity(launch);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to bring quiz to front", t);
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        isServiceActive = true;
        Log.i(TAG, "QuizEarnLiteAccessibilityService connected!");

        try {
            SharedPreferences prefs = getSharedPreferences("QuizEarnLitePrefs", Context.MODE_PRIVATE);
            preferredQuizPackage = prefs.getString("preferred_quiz_pkg", "com.quiz.earn.money.game");
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                try {
                    Intent overlayIntent = new Intent(this, FloatingOverlayService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(overlayIntent);
                    } else {
                        startService(overlayIntent);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start FloatingOverlayService", e);
                }
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cn = event.getClassName();
            if (cn != null) currentActivityName = cn.toString();
            CharSequence pn = event.getPackageName();
            if (pn != null) {
                String pkg = pn.toString();
                if (isQuizAppPackage(pkg)) {
                    lastActiveQuizPackage = pkg;
                }
            }
        }
        if (isRunning && (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                          eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            loopHandler.removeCallbacks(solveLoopRunnable);
            loopHandler.post(solveLoopRunnable);
        }
    }

    public void restorePortraitOrientation() {
        try {
            Intent fixIntent = new Intent(this, PortraitFixActivity.class);
            fixIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(fixIntent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch PortraitFixActivity", t);
        }
    }

    public synchronized void solveScreen() {
        if (!isRunning) return;

        long now = System.currentTimeMillis();
        if (now - lastTapTime < 260) return;

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {}

        List<NodeItem> allNodes = new ArrayList<>();
        List<AccessibilityNodeInfo> nodesToRecycle = new ArrayList<>();
        String pkg = "";

        if (root != null) {
            nodesToRecycle.add(root);
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg != null) {
                pkg = rootPkg.toString();
                if (isQuizAppPackage(pkg)) {
                    lastActiveQuizPackage = pkg;
                }
            }
            collectAllNodes(root, allNodes, nodesToRecycle, 0);
        }

        boolean foundQuizInWindows = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo win : windows) {
                        if (win == null) continue;
                        AccessibilityNodeInfo winRoot = win.getRoot();
                        if (winRoot != null) {
                            nodesToRecycle.add(winRoot);
                            CharSequence wpSeq = winRoot.getPackageName();
                            if (wpSeq != null) {
                                String wp = wpSeq.toString();
                                if (wp.equals("com.quizearn.assistant.lite") || wp.equals("com.android.systemui")) {
                                    continue;
                                }
                                if (isQuizAppPackage(wp)) {
                                    foundQuizInWindows = true;
                                    lastActiveQuizPackage = wp;
                                }
                                if (pkg.isEmpty() && (win.isActive() || win.isFocused() || win.getType() == AccessibilityWindowInfo.TYPE_APPLICATION)) {
                                    pkg = wp;
                                }
                            }
                            if (root == null || !winRoot.equals(root)) {
                                collectAllNodes(winRoot, allNodes, nodesToRecycle, 0);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error reading windows", e);
            }
        }

        if (foundQuizInWindows || isQuizAppPackage(pkg)) {
            pkg = getQuizAppPackage(this);
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        try {
            if (allNodes.isEmpty()) {
                if (emptyNodesStartTime == 0) emptyNodesStartTime = now;
                long emptyElapsed = (now - emptyNodesStartTime) / 1000;
                if (emptyElapsed >= 15 && (now - lastTapTime > 2200)) {
                    if (emptyElapsed >= 60) {
                        bringQuizAppToFront();
                        lastTapTime = now + 3000;
                        emptyNodesStartTime = 0;
                    } else if (emptyElapsed % 6 == 0) {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        lastTapTime = now;
                    }
                }
                return;
            } else {
                emptyNodesStartTime = 0;
            }

            // Only enforce portrait inside target quiz app
            if (isQuizAppPackage(pkg) && screenW > screenH && Math.min(screenW, screenH) < dp(600)) {
                restorePortraitOrientation();
            }

            // Crash / ANR dialog dismissal
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.equals("close app") || d.equals("close app") || t.contains("close app") ||
                    t.equals("wait") || d.equals("wait")) {
                    tapNode(item);
                    lastTapTime = now + 800;
                    return;
                }
            }

            // Network Error Recovery
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.equals("try again") || d.equals("try again") || t.equals("retry") || d.equals("retry")) {
                    tapNode(item);
                    lastTapTime = now + 1200;
                    lastProgressTime = now;
                    currentStatus = "Retrying Connection...";
                    return;
                }
            }

            boolean isQuizApp = isQuizAppPackage(pkg);
            boolean isAssistantApp = pkg.equals("com.quizearn.assistant.lite");
            boolean isSystemOrSettings = pkg.equals("com.android.systemui") ||
                pkg.equals("com.android.settings") ||
                pkg.contains("permissioncontroller") ||
                pkg.contains("packageinstaller");
            boolean isAdActivity = pkg.contains("gms.ads") || pkg.contains("admob") ||
                pkg.contains("unity3d") || pkg.contains("applovin") ||
                pkg.contains("ironsource") || pkg.contains("vungle") ||
                pkg.contains("mintegral") || pkg.contains("inmobi") ||
                pkg.contains("fyber");
            boolean isPlayStore = pkg.equals("com.android.vending") || pkg.contains("vending");
            boolean isLauncher = pkg.contains("launcher") || pkg.contains("home") || pkg.equals("android");

            // LITE RULE: Never interact with Play Store - immediately press back
            if (isPlayStore) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastTapTime = now + 1500;
                lastProgressTime = now;
                currentStatus = "Closed Play Store (Lite Mode)";
                return;
            }

            // Foreign app escape
            if (!isQuizApp && !isAssistantApp && !isSystemOrSettings && !isAdActivity && !isLauncher && !pkg.isEmpty()) {
                externalAppBackCount++;
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastTapTime = now + 2000;
                lastProgressTime = now;
                currentStatus = "Returning to Quiz...";
                adStartTime = 0;
                if (externalAppBackCount >= 3) {
                    bringQuizAppToFront();
                    externalAppBackCount = 0;
                }
                return;
            } else {
                externalAppBackCount = 0;
            }

            // 1. Detect Live Gems Count & 25-Gem Milestone
            int detectedGems = detectLiveGemsCount(allNodes, screenW, screenH);
            if (detectedGems >= 0) liveGems = detectedGems;

            // Check if 25/25 or milestone text is present on screen
            is25GemsOnScreen = false;
            if (detectedGems >= 25) {
                is25GemsOnScreen = true;
            } else {
                for (NodeItem item : allNodes) {
                    String t = item.text.toLowerCase().trim();
                    String d = item.contentDesc.toLowerCase().trim();
                    if (t.contains("25/25") || d.contains("25/25") || t.contains("25 / 25") ||
                        t.contains("gems collected") || t.contains("super offer unlocked") ||
                        t.contains("super task unlocked")) {
                        is25GemsOnScreen = true;
                        break;
                    }
                }
            }

            // ==========================================
            // STRICT 25-GEM STOP & ALERT MILESTONE GUARD
            // ==========================================
            if (liveGems >= 25 || is25GemsOnScreen) {
                isRunning = false;
                isUserRunning = false;
                currentStatus = "25 GEMS REACHED! Auto-paused.";
                Log.i(TAG, ">>> [25 GEMS TARGET REACHED] Stopping bot and triggering sound + vibration alert!");

                if (!hasAlerted25Gems) {
                    hasAlerted25Gems = true;
                    play25GemsMilestoneAlert();
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "🎉 25 GEMS REACHED! Bot auto-paused.", Toast.LENGTH_LONG).show();
                        }
                    });
                }
                return; // Cease execution immediately
            }

            // 2. Check active quiz elements
            boolean hasActiveQuiz = false;
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.contains("select the correct number") || d.contains("select the correct number") ||
                    t.contains("attempts left") || d.contains("attempts left")) {
                    hasActiveQuiz = true;
                    break;
                }
                int cy = item.rect.centerY();
                if (cy >= (int)(screenH * 0.10f) && cy <= (int)(screenH * 0.42f)) {
                    String raw = item.text.trim();
                    String rawLower = raw.toLowerCase();
                    boolean isGemOrProgressText = raw.contains("/25") || raw.contains("/ 25") ||
                        raw.contains("/50") || rawLower.contains("gem") ||
                        rawLower.contains("unlock") || rawLower.contains("super offer") ||
                        rawLower.contains("attempt") || rawLower.contains("left");
                    if (!isGemOrProgressText &&
                        (mathPattern.matcher(raw).find() || numPattern.matcher(cleanNumText(raw)).matches())) {
                        hasActiveQuiz = true;
                        break;
                    }
                }
                if (cy >= (int)(screenH * 0.26f) && cy <= (int)(screenH * 0.68f)) {
                    String raw = cleanNumText(item.text.trim());
                    if (numPattern.matcher(raw).matches()) {
                        hasActiveQuiz = true;
                        break;
                    }
                }
            }

            // 3. Pre-scan Gem Collection Screen
            boolean hasCollectGem = false;
            for (NodeItem item : allNodes) {
                String combined = (item.text.trim().toLowerCase() + " " + item.contentDesc.trim().toLowerCase()).trim();
                if (combined.contains("collect gem") || combined.contains("collect reward") ||
                    combined.contains("claim gem") || combined.contains("watch an ad") ||
                    combined.contains("congratulations") || combined.contains("+1 gem") ||
                    combined.contains("gems to unlock")) {
                    hasCollectGem = true;
                    break;
                }
            }
            if (hasCollectGem) {
                hasActiveQuiz = false;
            }

            // 4. Detect Ad State & Countdowns
            boolean hasAdCountdown = false;
            boolean isAd = isAdActivity;
            String actName = currentActivityName.toLowerCase();
            if (actName.contains("inmobi") || actName.contains("vungle") || actName.contains("adactivity") ||
                actName.contains("unity3d") || actName.contains("ironsource") || actName.contains("applovin") ||
                actName.contains("mintegral") || actName.contains("adview") || actName.contains("criteo")) {
                isAd = true;
            }

            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase().trim();
                String d = item.contentDesc.toLowerCase().trim();
                String id = item.resId.toLowerCase();
                boolean isWebView = item.className.toLowerCase().contains("webview");

                if (t.contains("reward in") || t.contains("video ends in") ||
                    t.contains("video will end in") || t.contains("seconds after") ||
                    t.contains("skip in ") || d.contains("reward in") ||
                    d.contains("ends in") || d.contains("skip in ") ||
                    id.contains("countdown") || id.contains("count_down") || id.contains("timer")) {
                    hasAdCountdown = true;
                    isAd = true;
                }
                if (t.contains("ad 1 of") || t.contains("ad 2 of") || t.contains("ad 3 of") ||
                    d.contains("ad 1 of") || d.contains("ad 2 of")) {
                    isAd = true;
                }

                boolean isAdCta = (t.equals("install") || t.equals("install now") || (t.startsWith("install") && t.length() <= 15) ||
                    t.equals("download") || t.equals("download now") || (t.startsWith("download") && t.length() <= 15) ||
                    (t.contains("play store") && !t.contains("rate") && !t.contains("stars")) || t.contains("advertisement") ||
                    t.contains("reward will be") || t.contains("you can skip in") ||
                    t.contains("shop now") || t.contains("claim bonus") || t.contains("play now") ||
                    t.contains("learn more") || t.contains("skip to google play") || d.contains("skip to google play") ||
                    t.contains("get it on") || t.equals("get") || t.equals("open"));

                if (!isAd && (t.startsWith("ad ") || t.contains("seconds after") ||
                    t.contains("will be taken to the website") || isAdCta ||
                    d.contains("liftoff") || id.contains("adjs") || id.contains("moloco") ||
                    id.contains("endcard") || id.contains("watermark") || id.contains("advert") ||
                    id.contains("unity3d") || id.contains("applovin") || id.contains("mbridge") ||
                    id.contains("inmobi") || id.startsWith("im_") || id.contains("miniplayer") ||
                    id.contains("main-video") || id.contains("skip-close") ||
                    id.contains("tt_") || id.contains("ia_") || id.contains("close-or-skip-button") ||
                    id.contains("app-interstitial") || id.contains("ad-interstitial") ||
                    isWebView || t.contains("moloco") || t.contains("liftoff"))) {
                    isAd = true;
                }
            }

            if (hasActiveQuiz || hasCollectGem) {
                isAd = false;
                hasAdCountdown = false;
                adStartTime = 0;
            }

            // Dismiss In-App Store Bottom Sheet Overlay
            for (NodeItem item : allNodes) {
                String id = item.resId.toLowerCase().trim();
                String d = item.contentDesc.toLowerCase().trim();
                if (id.contains("store-view-close") || (d.equals("back") && item.rect.top < (int)(screenH * 0.28f) && item.rect.left < (int)(screenW * 0.14f))) {
                    tapNode(item);
                    lastTapTime = now + 1200;
                    lastProgressTime = now;
                    currentStatus = "Dismissing Store Overlay...";
                    return;
                }
            }

            // Reward Safety Check: "Close Ad? You will lose your reward" -> Tap RESUME
            boolean hasLoseRewardWarning = false;
            NodeItem resumeAdBtn = null;
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase().trim();
                String d = item.contentDesc.toLowerCase().trim();
                if (t.contains("lose your reward") || d.contains("lose your reward") ||
                    t.contains("lose reward") || d.contains("lose reward") ||
                    t.contains("won't get your reward") || t.contains("without reward") ||
                    t.contains("reward will be lost") || t.contains("close ad?") ||
                    t.contains("leave ad?") || t.contains("close video?")) {
                    hasLoseRewardWarning = true;
                }
                if (t.equals("resume") || d.equals("resume") ||
                    t.equals("resume video") || d.equals("resume video") ||
                    t.equals("keep watching") || d.equals("keep watching") ||
                    t.equals("continue") || t.equals("stay")) {
                    resumeAdBtn = item;
                }
            }
            if (hasLoseRewardWarning) {
                if (resumeAdBtn != null) {
                    tapNode(resumeAdBtn);
                } else {
                    tapCoordinates((int)(screenW * 0.68f), (int)(screenH * 0.60f));
                }
                lastTapTime = now + 1500;
                lastProgressTime = now;
                adStartTime = now;
                currentStatus = "Resumed Ad (Protecting Coins)";
                return;
            }

            // Skip / Reward Granted / Close Ad
            for (NodeItem item : allNodes) {
                if (isSkipElement(item, screenW, screenH)) {
                    tapNode(item);
                    lastTapTime = now;
                    lastProgressTime = now;
                    lastSolvedTarget = "";
                    lastSolveTime = 0;
                    currentStatus = "Skipping Ad...";
                    return;
                }
            }

            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase().trim();
                if (t.contains("reward granted") || t.contains("reward credited")) {
                    int targetX = Math.min(screenW - dp(36), item.rect.right + dp(30));
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

            for (NodeItem item : allNodes) {
                if (isCloseElement(item, hasAdCountdown, screenW, screenH)) {
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

            // Ad Watching & Progressive Ad Dismissal
            if (isAd) {
                lastProgressTime = now;
                collectGemScreenStartTime = 0;
                collectGemTapCount = 0;
                collectGemRestartCount = 0;
                questionWaitingStartTime = 0;
                lastWaitingQuestion = "";
                if (adStartTime == 0) adStartTime = now;
                long adElapsed = (now - adStartTime) / 1000;
                currentStatus = "Ad Playing (" + adElapsed + "s)...";

                int maxCloseIconPx = dp(68);
                if (!hasAdCountdown && adElapsed >= 26 && (now - lastTapTime > 1400)) {
                    for (NodeItem item : allNodes) {
                        if (isForbiddenElement(item, screenW, screenH)) continue;
                        int cx = item.rect.centerX();
                        int cy = item.rect.centerY();
                        if (item.rect.width() > maxCloseIconPx || item.rect.height() > maxCloseIconPx) continue;
                        boolean hasCloseIndicator = isCloseOrSkipText(item.text) ||
                            isCloseOrSkipDesc(item.contentDesc) ||
                            isCloseOrSkipId(item.resId);
                        boolean isCorner = (cx <= dp(50) && cy <= dp(65)) ||
                            (cx >= screenW - dp(50) && cy <= dp(65)) ||
                            (cx >= screenW - dp(50) && cy >= screenH - dp(65));
                        if (hasCloseIndicator || (isCorner && item.clickable)) {
                            tapNode(item);
                            lastTapTime = now;
                            lastProgressTime = now;
                            currentStatus = "Closing Ad (" + adElapsed + "s)...";
                            adStartTime = 0;
                            return;
                        }
                    }
                }

                if (!hasAdCountdown && adElapsed >= 32 && (now - lastTapTime > 2200)) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastTapTime = now;
                    lastProgressTime = now;
                    currentStatus = "Dismissing Ad (Back Key)...";
                    return;
                }

                if (!hasAdCountdown && adElapsed >= 40 && (now - lastTapTime > 2500)) {
                    tapCoordinates(screenW - dp(24), dp(32));
                    lastTapTime = now;
                    lastProgressTime = now;
                    return;
                }

                if (adElapsed >= 65) {
                    currentStatus = "Ad timed out -> Restarting App";
                    adStartTime = 0;
                    restartQuizApp();
                    lastTapTime = now + 3500;
                    return;
                }
                return;
            }

            // Home Screen Handling
            boolean isHomeScreen = false;
            NodeItem startQuizBtn = null;
            NodeItem rewardPopupBtn = null;

            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.contains("hi, ") || d.contains("hi, ") ||
                    t.contains("daily win") || t.contains("task partners") ||
                    t.contains("weekly leaderboard") || t.contains("refer & earn") ||
                    t.contains("go to wallet")) {
                    isHomeScreen = true;
                }
                if (t.equals("awesome!") || t.equals("awesome") || t.equals("great!") || t.equals("great") ||
                    (t.equals("ok") && !t.contains("stopping")) || t.equals("collect") || t.equals("collect reward")) {
                    if (item.rect.centerY() > screenH * 0.30f && item.rect.centerY() < screenH * 0.90f) {
                        rewardPopupBtn = item;
                    }
                }
                if (t.equals("start quiz") || t.equals("start math quiz") || t.contains("start quiz") ||
                    t.equals("play quiz") || d.contains("start quiz") || d.contains("start math")) {
                    startQuizBtn = item;
                }
            }

            if (rewardPopupBtn != null) {
                tapNode(rewardPopupBtn);
                lastTapTime = now + 1000;
                lastProgressTime = now;
                currentStatus = "Reward Collected!";
                liveGems = 0;
                is25GemsOnScreen = false;
                hasAlerted25Gems = false;
                return;
            }

            if (isHomeScreen) {
                if (startQuizBtn != null) {
                    tapNode(startQuizBtn);
                    lastTapTime = now + 1200;
                    lastProgressTime = now;
                    currentStatus = "Starting Quiz...";
                    return;
                } else {
                    // Try tapping quiz card on home
                    for (NodeItem item : allNodes) {
                        String t = item.text.trim().toLowerCase();
                        if ((t.equals("1,000") || t.equals("1000") || t.equals("super offer") || t.startsWith("claim")) &&
                            item.rect.centerX() < screenW / 2 && item.rect.centerY() < screenH * 0.70f) {
                            tapNode(item);
                            lastTapTime = now + 1500;
                            lastProgressTime = now;
                            currentStatus = "Entering Quiz...";
                            return;
                        }
                    }
                }
                return;
            }

            // Collect Gem Screen
            if (hasCollectGem) {
                boolean isGemCollectionScreen = false;
                boolean hasCollectGemButton = false;
                boolean isButtonLoading = false;
                NodeItem collectGemNode = null;
                NodeItem collectGemBackBtn = null;

                for (NodeItem item : allNodes) {
                    String t = item.text.trim().toLowerCase();
                    String d = item.contentDesc.trim().toLowerCase();
                    String combined = (t + " " + d).trim();

                    if ((d.contains("back") || d.contains("navigate up")) &&
                        item.rect.top < (int)(screenH * 0.14f) && item.rect.left < (int)(screenW * 0.25f)) {
                        collectGemBackBtn = item;
                    }

                    if (combined.contains("congratulations") || combined.contains("+1 gem") ||
                        combined.contains("watch an ad to collect") || combined.contains("gems to unlock super offer")) {
                        isGemCollectionScreen = true;
                    }

                    boolean isExplicitCollectBtn = !t.contains("watch an ad") && !d.contains("watch an ad") &&
                        (t.contains("collect gem") || t.contains("collect gems") || t.contains("collect reward") ||
                         t.contains("claim gem") || d.contains("collect gem") || d.contains("collect reward"));
                    if (isExplicitCollectBtn && item.rect.width() > dp(24) && item.rect.height() > dp(18)) {
                        if (!item.isEnabled) {
                            isButtonLoading = true;
                            isGemCollectionScreen = true;
                        } else {
                            hasCollectGemButton = true;
                            isGemCollectionScreen = true;
                            if (collectGemNode == null ||
                                (item.clickable && !collectGemNode.clickable) ||
                                (item.clickable == collectGemNode.clickable && item.rect.width() > collectGemNode.rect.width())) {
                                collectGemNode = item;
                            }
                        }
                    } else if (!isForbiddenElement(item, screenW, screenH) &&
                               (t.equals("loading...") || t.equals("loading") || d.equals("loading..."))) {
                        isButtonLoading = true;
                    }
                }

                if (isGemCollectionScreen) {
                    lastSeenCollectionScreenTime = now;
                    if (collectGemScreenStartTime == 0) collectGemScreenStartTime = now;
                    long elapsedOnCollect = now - collectGemScreenStartTime;

                    long stallTimeoutMs = isButtonLoading ? 20000L : 15000L;
                    if (collectGemTapCount >= 3 || elapsedOnCollect > stallTimeoutMs) {
                        collectGemScreenStartTime = 0;
                        collectGemTapCount = 0;
                        adStartTime = 0;
                        lastProgressTime = now;
                        if (collectGemRestartCount >= 1) {
                            currentStatus = "Ad stalled -> Backing to Home";
                            collectGemRestartCount = 0;
                            if (collectGemBackBtn != null) tapNode(collectGemBackBtn);
                            else performGlobalAction(GLOBAL_ACTION_BACK);
                            lastTapTime = now + 2000;
                        } else {
                            currentStatus = "Ad not loaded -> Restarting App";
                            collectGemRestartCount++;
                            restartQuizApp();
                            lastTapTime = now + 3500;
                        }
                        return;
                    }
                    if (hasCollectGemButton && collectGemNode != null) {
                        if (collectGemTapCount > 0 && (now - lastTapTime < 1800)) return;
                        collectGemTapCount++;
                        lastProgressTime = now;
                        if (liveGems >= 24) {
                            isCollecting25thGem = true;
                            finalAdStarted = false;
                        }
                        tapNode(collectGemNode);
                        lastTapTime = now + 1500;
                        lastSolvedTarget = "";
                        lastSolveTime = 0;
                        currentStatus = "Collecting Gem (" + collectGemTapCount + "/3)...";
                        adStartTime = 0;
                        return;
                    } else if (isButtonLoading) {
                        currentStatus = "Waiting for ad (" + (elapsedOnCollect / 1000) + "s)...";
                        return;
                    }
                }
            }

            // 5. RESPONSIVE MATH & NUMBER CAPTCHA SOLVER
            int qMinY = (int) (screenH * 0.10f);
            int qMaxY = (int) (screenH * 0.40f);
            int optMinY = (int) (screenH * 0.25f);
            int optMaxY = (int) (screenH * 0.68f);

            String detectedTarget = null;
            String targetAnsStr = null;
            int questionCenterY = -1;

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
                boolean foundMath = m.find();
                boolean isSlashMath = false;
                if (!foundMath) {
                    m = mathSlashPattern.matcher(rawText);
                    isSlashMath = m.find();
                    foundMath = isSlashMath;
                }

                if (foundMath) {
                    try {
                        int n1 = Integer.parseInt(m.group(1));
                        String op = isSlashMath ? "/" : m.group(2);
                        int n2 = Integer.parseInt(m.group(3));

                        int ans = Integer.MIN_VALUE;
                        if ("+".equals(op)) ans = n1 + n2;
                        else if ("-".equals(op) || "–".equals(op) || "—".equals(op)) ans = n1 - n2;
                        else if ("*".equals(op) || "×".equals(op) || "x".equalsIgnoreCase(op)) ans = n1 * n2;
                        else if ("/".equals(op) || "÷".equals(op)) {
                            if (n2 != 0 && (n1 % n2 == 0)) ans = n1 / n2;
                        }

                        if (ans != Integer.MIN_VALUE && ans >= 0) {
                            detectedTarget = "MATH:" + n1 + op + n2 + "=" + ans;
                            targetAnsStr = String.valueOf(ans);
                            questionCenterY = cy;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }

            boolean hasCaptchaContext = false;
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase();
                String d = item.contentDesc.toLowerCase();
                if (t.contains("select the correct") || d.contains("select the correct") ||
                    t.contains("attempt") || d.contains("attempt") ||
                    t.contains("/2")) {
                    hasCaptchaContext = true;
                    break;
                }
            }

            if (detectedTarget == null && hasCaptchaContext) {
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
                } else {
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
                        for (NodeItem dNode : digitNodes) sb.append(cleanNumText(dNode.text));
                        detectedTarget = "CAPTCHA:" + sb.toString();
                        targetAnsStr = sb.toString();
                    }
                }
            }

            if (detectedTarget == null) {
                Map<String, List<NodeItem>> numGroups = new HashMap<>();
                for (NodeItem item : allNodes) {
                    int cy = item.rect.centerY();
                    if (cy < qMinY || cy > (optMaxY + dp(40))) continue;
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
                        break;
                    }
                }
            }

            if (detectedTarget != null && targetAnsStr != null) {
                if (detectedTarget.equals(lastSolvedTarget)) {
                    if (now - lastSolveTime > 15000) {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        lastTapTime = now + 1500;
                        lastSolveTime = now;
                        lastSolvedTarget = "";
                        lastProgressTime = now;
                        currentStatus = "Refreshing Quiz...";
                        return;
                    }
                    if (now - lastSolveTime < 1100) return;
                }

                NodeItem answerButton = null;
                for (NodeItem opt : allNodes) {
                    if (isForbiddenElement(opt, screenW, screenH)) continue;
                    int cy = opt.rect.centerY();
                    if (cy < (optMinY - dp(20)) || cy > (optMaxY + dp(30))) continue;
                    if (questionCenterY > 0 && cy <= questionCenterY) continue;

                    String optText = cleanNumText(opt.text);
                    String optDesc = cleanNumText(opt.contentDesc);
                    if (optText.equals(targetAnsStr) || optDesc.equals(targetAnsStr)) {
                        answerButton = opt;
                        break;
                    }
                }

                if (answerButton != null) {
                    if (!answerButton.isEnabled) {
                        if (detectedTarget.equals(lastSolvedTarget) && (now - lastSolveTime > 12000)) {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            lastTapTime = now + 1500;
                            lastSolveTime = now;
                            lastSolvedTarget = "";
                            lastProgressTime = now;
                            currentStatus = "Refreshing Quiz...";
                            return;
                        }
                        return;
                    }
                    questionWaitingStartTime = 0;
                    lastWaitingQuestion = "";
                    tapNode(answerButton);

                    lastSolvedTarget = detectedTarget;
                    lastSolveTime = now;
                    lastTapTime = now;
                    lastProgressTime = now;
                    solvedCount++;
                    currentStatus = "Solved #" + solvedCount + " (" + targetAnsStr + ")";
                    return;
                } else {
                    if (questionWaitingStartTime == 0 || !detectedTarget.equals(lastWaitingQuestion)) {
                        questionWaitingStartTime = now;
                        lastWaitingQuestion = detectedTarget;
                    } else if (now - questionWaitingStartTime > 12000) {
                        questionWaitingStartTime = 0;
                        lastWaitingQuestion = "";
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        lastTapTime = now + 1200;
                        lastProgressTime = now;
                        currentStatus = "Refreshing Options...";
                        return;
                    }
                }
            } else {
                questionWaitingStartTime = 0;
                lastWaitingQuestion = "";
            }

            currentStatus = "Scanning...";
        } finally {
            for (AccessibilityNodeInfo n : nodesToRecycle) {
                try {
                    if (n != null) n.recycle();
                } catch (Throwable ignored) {}
            }
        }
    }

    private int detectLiveGemsCount(List<NodeItem> allNodes, int screenW, int screenH) {
        Pattern slash25Pattern = Pattern.compile("(\\d+)\\s*/\\s*25");
        for (NodeItem item : allNodes) {
            Matcher m = slash25Pattern.matcher(item.text);
            if (!m.find() && !item.contentDesc.isEmpty()) m = slash25Pattern.matcher(item.contentDesc);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
        }
        for (int i = 0; i < allNodes.size() - 2; i++) {
            if (allNodes.get(i + 1).text.trim().equals("/") && allNodes.get(i + 2).text.trim().equals("25")) {
                try {
                    int g = Integer.parseInt(allNodes.get(i).text.trim());
                    if (g >= 0 && g <= 25) return g;
                } catch (Exception ignored) {}
            }
        }
        for (int i = 0; i < allNodes.size() - 1; i++) {
            if (allNodes.get(i + 1).text.replaceAll("\\s+", "").equals("/25")) {
                try {
                    int g = Integer.parseInt(allNodes.get(i).text.trim());
                    if (g >= 0 && g <= 25) return g;
                } catch (Exception ignored) {}
            }
        }
        int maxCx = -1;
        int headerVal = -1;
        for (NodeItem item : allNodes) {
            int cx = item.rect.centerX();
            int cy = item.rect.centerY();
            if (cx > (int)(screenW * 0.65f) && cy < (int)(screenH * 0.12f)) {
                String digits = item.text.replaceAll("[^0-9]", "").trim();
                if (digits.length() >= 1 && digits.length() <= 2) {
                    try {
                        int val = Integer.parseInt(digits);
                        if (cx > maxCx && val >= 0 && val <= 99) {
                            maxCx = cx;
                            headerVal = val;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        if (headerVal >= 0) return headerVal;

        Pattern gemsCollectedPattern = Pattern.compile("(\\d+)\\s*gems?\\s*collected", Pattern.CASE_INSENSITIVE);
        for (int i = 0; i < allNodes.size(); i++) {
            NodeItem item = allNodes.get(i);
            Matcher m = gemsCollectedPattern.matcher(item.text);
            if (!m.find() && !item.contentDesc.isEmpty()) m = gemsCollectedPattern.matcher(item.contentDesc);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
        }
        return -1;
    }

    private void collectAllNodes(AccessibilityNodeInfo node, List<NodeItem> list, List<AccessibilityNodeInfo> recycleBag, int depth) {
        if (node == null || depth > 25 || list.size() > 450) return;

        CharSequence pkgSeq = node.getPackageName();
        String nodePkg = (pkgSeq != null) ? pkgSeq.toString() : "";
        if (nodePkg.equals("com.quizearn.assistant.lite") ||
            nodePkg.equals("com.android.systemui") ||
            nodePkg.contains("launcher") ||
            nodePkg.contains("systemui")) {
            return;
        }

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);

        CharSequence textSeq = node.getText();
        CharSequence descSeq = node.getContentDescription();
        CharSequence idSeq = node.getViewIdResourceName();
        CharSequence clsSeq = node.getClassName();

        String text = (textSeq != null) ? textSeq.toString().trim() : "";
        String desc = (descSeq != null) ? descSeq.toString().trim() : "";
        String resId = (idSeq != null) ? idSeq.toString().trim() : "";
        String cls = (clsSeq != null) ? clsSeq.toString() : "";
        String effectiveText = !text.isEmpty() ? text : desc;
        boolean isWebView = cls.toLowerCase().contains("webview");

        boolean clickable = false;
        boolean enabled = true;
        try {
            clickable = node.isClickable();
            enabled = node.isEnabled();
        } catch (IllegalStateException ignored) {}

        if ((!effectiveText.isEmpty() || !desc.isEmpty() || !resId.isEmpty() || clickable || isWebView) && !rect.isEmpty()) {
            list.add(new NodeItem(node, effectiveText, desc, resId, cls, rect, clickable, enabled));
        }

        int childCount = 0;
        try {
            childCount = node.getChildCount();
        } catch (IllegalStateException ignored) {}

        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (IllegalStateException ignored) {}
            if (child != null) {
                recycleBag.add(child);
                collectAllNodes(child, list, recycleBag, depth + 1);
            }
        }
    }

    private boolean tapCoordinates(final int cx, final int cy) {
        if (cx <= 0 || cy <= 0) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(cx, cy);
            GestureDescription stroke = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 85L))
                .build();
            return dispatchGesture(stroke, null, null);
        }
        return false;
    }

    private void tapNode(final NodeItem item) {
        if (item == null || item.rect == null) return;
        int cx = item.rect.centerX();
        int cy = item.rect.centerY();
        if (item.rect.width() > 6) cx = Math.max(item.rect.left + 3, Math.min(item.rect.right - 3, cx));
        if (item.rect.height() > 6) cy = Math.max(item.rect.top + 3, Math.min(item.rect.bottom - 3, cy));

        boolean gestureDispatched = tapCoordinates(cx, cy);

        if (!gestureDispatched) {
            try {
                if (item.node != null) {
                    if (item.clickable) {
                        item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    } else {
                        AccessibilityNodeInfo p = item.node.getParent();
                        int hops = 0;
                        while (p != null && hops < 3) {
                            if (p.isClickable()) {
                                p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                p.recycle();
                                break;
                            }
                            AccessibilityNodeInfo nextP = p.getParent();
                            p.recycle();
                            p = nextP;
                            hops++;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private boolean isCloseOrSkipText(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase();
        if (t.isEmpty()) return false;
        if ((t.contains(" in ") || t.contains(" in:") || t.contains("seconds") || t.contains("sec")) && !t.contains("in 0")) return false;
        return t.equals("close") || t.equals("skip") || t.contains("skip") || t.equals("dismiss") ||
               t.equals("x") || t.equals("✕") || t.equals("✖") || t.equals("×") ||
               t.equals("<") || t.equals("‹") || t.equals("«") || t.equals("back") ||
               t.equals("done") || t.equals("exit") || t.equals("no thanks") ||
               t.equals("not now") || t.contains(">>") || t.contains("\u203A\u203A") || t.contains("\u00BB") ||
               t.contains("\u276F\u276F") || t.contains("▶▶") || t.contains("⏭") || t.contains("⏩") || t.contains(">|");
    }

    private boolean isCloseOrSkipDesc(String desc) {
        if (desc == null) return false;
        String d = desc.trim().toLowerCase();
        return !d.isEmpty() && (d.contains("close") || d.contains("skip") || d.contains("dismiss") ||
               d.equals("x") || d.equals("✕") || d.equals("✖") || d.equals("back") ||
               d.equals("exit") || d.equals("navigate up"));
    }

    private boolean isCloseOrSkipId(String resId) {
        if (resId == null) return false;
        String id = resId.trim().toLowerCase();
        return !id.isEmpty() && (id.contains("close") || id.contains("skip") || id.contains("dismiss") ||
               id.contains("btn_back") || id.contains("iv_back") || id.contains("al_close"));
    }

    private boolean isForbiddenElement(NodeItem item, int screenW, int screenH) {
        if (item == null || item.rect == null) return true;
        String t = item.text.toLowerCase().trim();
        String d = item.contentDesc.toLowerCase().trim();
        String id = item.resId.toLowerCase().trim();

        if (t.contains("skip") || d.contains("skip") || id.contains("skip") ||
            t.contains(">>") || d.contains(">>")) {
            if (!t.contains(" in ") && !d.contains(" in ") && !t.contains("seconds") && !t.contains(" in:")) {
                return false;
            }
        }

        if (item.rect.width() >= (int)(screenW * 0.55f) && item.rect.height() >= (int)(screenH * 0.30f)) {
            return true;
        }

        String combined = t + " " + d + " " + id;
        if (combined.contains("fullscreen") || combined.contains("overlay") ||
            combined.contains("interceptor") || combined.contains("touch_layer") ||
            combined.contains("call to action") || id.contains("overlay") ||
            id.contains("fullscreen") || id.contains("cta")) {
            return true;
        }
        if (combined.contains("sound") || combined.contains("mute") || combined.contains("unmute") ||
            combined.contains("volume") || combined.contains("audio") || combined.contains("speaker")) {
            return true;
        }
        if (t.equals("open") || t.startsWith("open ") || t.contains("open in") || t.contains("open app") ||
            d.equals("open") || id.contains("open_button") || id.contains("btn_open")) {
            return true;
        }
        if (t.equals("play") || t.equals("play now") || t.startsWith("play ") || id.contains("btn_play")) {
            return true;
        }
        if (t.equals("get") || t.startsWith("get ") || combined.contains("install") || combined.contains("download") ||
            combined.contains("play store") || id.contains("install") || id.contains("download")) {
            return true;
        }
        if (t.equals("shop") || t.contains("shop now") || t.equals("try") || t.contains("try now") ||
            t.equals("visit") || combined.contains("learn more") || combined.contains("view details") ||
            combined.contains("see more") || combined.contains("sign up") || combined.contains("buy now") ||
            id.contains("action_button") || id.contains("btn_action")) {
            return true;
        }
        if (combined.contains("adchoice") || combined.contains("privacy") || combined.contains("about this ad")) {
            return true;
        }
        if (t.contains("ad 1 of") || t.contains("ad 2 of") || t.contains("ad 3 of") ||
            t.equals("ad") || t.equals("sponsored") || t.equals("advertisement")) {
            return true;
        }
        return false;
    }

    private boolean isSkipElement(NodeItem item, int screenW, int screenH) {
        if (isForbiddenElement(item, screenW, screenH)) return false;
        int w = item.rect.width();
        int h = item.rect.height();
        if (w < dp(6) || h < dp(6) || w > (int)(screenW * 0.94f) || h > (int)(screenH * 0.20f)) return false;
        if (numPattern.matcher(item.text).matches()) return false;

        String t = item.text.toLowerCase().trim();
        String d = item.contentDesc.toLowerCase().trim();
        String id = item.resId.toLowerCase().trim();
        if ((t.contains(" in ") || d.contains(" in ") || t.contains(" in:") || t.contains("seconds") || t.matches(".*skip.*\\d+\\s*s.*")) &&
            !t.contains("in 0") && !d.contains("in 0")) {
            return false;
        }
        if (t.contains("skip") || d.contains("skip")) return true;
        if (t.contains(">>") || t.contains("\u203A\u203A") || t.contains("\u00BB") ||
            t.contains("\u276F\u276F") || t.contains(">|") || t.contains("\u25B6\u25B6") ||
            d.contains(">>") || d.contains("fast forward")) {
            return true;
        }
        return id.contains("skip_button") || id.contains("btn_skip") || id.contains("close-or-skip-button") ||
               id.contains("skip_ad") || id.contains("skipbutton") || id.contains("skip-btn") ||
               id.contains("skip_btn") || id.contains("mbridge_skip") || id.contains("skip");
    }

    private boolean isCloseElement(NodeItem item, boolean hasAdCountdown, int screenW, int screenH) {
        if (hasAdCountdown) return false;
        if (isForbiddenElement(item, screenW, screenH)) return false;

        int w = item.rect.width();
        int h = item.rect.height();
        if (w < dp(6) || h < dp(6) || w > (int)(screenW * 0.50f) || h > (int)(screenH * 0.16f)) return false;
        if (numPattern.matcher(item.text).matches()) return false;

        String t = item.text.toLowerCase().trim();
        String d = item.contentDesc.toLowerCase().trim();
        String id = item.resId.toLowerCase().trim();

        if (t.contains("ad 1 of") || t.contains("ad 2 of") || t.contains("ends in") ||
            t.contains("reward in") || t.contains("seconds after") || id.contains("countdown")) {
            return false;
        }
        if ((t.equals("close") || t.equals("yes") || t.equals("leave")) &&
            item.rect.centerY() > (int)(screenH * 0.30f) && item.rect.centerY() < (int)(screenH * 0.75f)) {
            return false;
        }
        if (t.equals("close video") || t.equals("skip video") || t.equals("dismiss") ||
            t.equals("no thanks") || t.equals("not now") || t.equals("leave") ||
            t.equals("yes") || t.equals("close") || t.equals("close ad")) {
            return true;
        }

        boolean isTopOrCorner = (item.rect.top <= (int)(screenH * 0.28f)) ||
            (item.rect.centerX() >= (int)(screenW * 0.70f)) ||
            (item.rect.centerX() <= (int)(screenW * 0.30f));
        if (!isTopOrCorner) return false;

        if (d.equals("close ad") || d.equals("close") || d.equals("close button") ||
            d.equals("close video") || d.equals("dismiss") || d.startsWith("close") || d.startsWith("skip")) {
            return true;
        }
        if (id.contains("close-or-skip-button") || id.contains("close_button") || id.contains("btn_close") ||
            id.contains("iv_close") || id.contains("close_ad") || id.contains("ad_close") ||
            id.contains("btn_back") || id.contains("al_close") || id.contains("unity_close") ||
            id.contains("vungle_close") || id.contains("closebutton") || id.contains("close-button")) {
            return true;
        }
        return t.equals("\u2715") || t.equals("\u2716") || t.equals("\u2A2F") ||
               t.equals("\u00D7") || t.equalsIgnoreCase("x") || t.equals("<") ||
               t.equals("‹") || t.equals("back") || t.equals("exit") ||
               d.equals("back") || d.equals("navigate up") || d.equals("exit");
    }

    private void play25GemsMilestoneAlert() {
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null && v.hasVibrator()) {
                        long[] pattern = {0, 400, 200, 400, 200, 700};
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                        } else {
                            v.vibrate(pattern, -1);
                        }
                    }
                } catch (Exception ignored) {}

                try {
                    Uri alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                    if (alertUri == null) alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
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
                    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
                    Thread.sleep(400);
                    tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700);
                } catch (Exception ignored) {}
            }
        });
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        loopHandler.removeCallbacks(solveLoopRunnable);
        instance = null;
        isServiceActive = false;
        Log.i(TAG, "QuizEarnLiteAccessibilityService destroyed.");
    }

    private static class NodeItem {
        final AccessibilityNodeInfo node;
        final String text;
        final String contentDesc;
        final String resId;
        final String className;
        final Rect rect;
        final boolean clickable;
        final boolean isEnabled;

        NodeItem(AccessibilityNodeInfo node, String text, String contentDesc, String resId, String className, Rect rect, boolean clickable, boolean isEnabled) {
            this.node = node;
            this.text = text;
            this.contentDesc = contentDesc;
            this.resId = resId;
            this.className = className;
            this.rect = rect;
            this.clickable = clickable;
            this.isEnabled = isEnabled;
        }
    }
}

package com.quizearn.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
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
    public static volatile boolean isUserRunning = false;
    public static volatile boolean isServiceActive = false;
    public static volatile int solvedCount = 0;
    public static volatile int liveGems = -1;
    public static volatile String currentStatus = "Standby";
    private static boolean hasAlerted25Gems = false;
    public static volatile boolean isCollecting25thGem = false;
    public static volatile boolean finalAdStarted = false;

    // Super Offer State Machine
    public static final int OFFER_STATE_IDLE = 0;              // Normal quiz solving
    public static final int OFFER_STATE_MILESTONE = 1;         // On 25 gems milestone screen, ready to tap "Start Offer"
    public static final int OFFER_STATE_DETAIL_PAGE = 2;       // On Super Offer detail screen, ready to tap "Start Offer" (starts ad)
    public static final int OFFER_STATE_AD_PLAYING = 3;        // Super Offer Ad is playing, waiting to tap Play Store CTA
    public static final int OFFER_STATE_PLAYSTORE = 4;         // In Play Store: check if app already installed or new
    public static final int OFFER_STATE_INSTALLING = 5;        // Waiting for app installation to complete
    public static final int OFFER_STATE_WAIT_AD_RETURN = 6;    // Returning from duplicate app check, closing ad
    public static final int OFFER_STATE_VERIFYING = 7;         // In Quiz app, tapping "Verify Installation"
    public static final int OFFER_STATE_APP_ACTIVE = 8;        // App verified! Actively using the app for 125 seconds
    public static final int OFFER_STATE_REWARD_CLAIM = 9;      // 2min usage done, return to Quiz app, tap "View My Offers", claim coins

    public static volatile int offerState = OFFER_STATE_IDLE;
    private static long offerStateStartTime = 0;
    private static String offerTargetPackage = "";
    private static long appUsageStartTime = 0;
    private static long lastUsageGestureTime = 0;
    private static boolean appIsDuplicate = false;
    private static List<String> baselinePackages = new ArrayList<>();
    private static int offerAppLaunchAttempts = 0;

    // User Toggle for 25-Gem Super Offer Auto-Install
    public static volatile boolean isSuperOfferEnabled = true;

    public static void setSuperOfferEnabled(Context context, boolean enabled) {
        isSuperOfferEnabled = enabled;
        Log.i(TAG, "isSuperOfferEnabled updated to: " + enabled);
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences("QuizEarnPrefs", Context.MODE_PRIVATE);
                prefs.edit().putBoolean("super_offer_enabled", enabled).apply();
            } catch (Exception e) {
                Log.e(TAG, "Failed to persist super_offer_enabled", e);
            }
        }
    }

    public static QuizEarnAccessibilityService instance = null;

    private final Handler loopHandler = new Handler(Looper.getMainLooper());
    private long lastTapTime = 0;
    private long adStartTime = 0;
    private long lastProgressTime = 0;
    private String lastSolvedTarget = "";
    private long lastSolveTime = 0;
    private int externalAppBackCount = 0;

    // Ad loading watchdog after quiz completion & question option loading watchdog:
    private long collectGemScreenStartTime = 0;
    private int collectGemTapCount = 0;
    private long questionWaitingStartTime = 0;
    private String lastWaitingQuestion = "";

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
        isUserRunning = true;
        liveGems = -1; // Reset live gems for clean cycle detection
        hasAlerted25Gems = false;
        isCollecting25thGem = false;
        finalAdStarted = false;
        offerState = OFFER_STATE_IDLE;
        offerTargetPackage = "";
        appUsageStartTime = 0;
        appIsDuplicate = false;
        baselinePackages.clear();
        currentStatus = "Scanning...";
        Log.i(TAG, "startSolving called! instance = " + instance);
        if (instance != null) {
            instance.lastSolvedTarget = "";
            instance.lastSolveTime = 0;
            instance.lastProgressTime = System.currentTimeMillis();
            instance.adStartTime = 0;
            instance.collectGemScreenStartTime = 0;
            instance.collectGemTapCount = 0;
            instance.questionWaitingStartTime = 0;
            instance.lastWaitingQuestion = "";
            instance.loopHandler.removeCallbacks(instance.solveLoopRunnable);
            instance.loopHandler.post(instance.solveLoopRunnable);
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
        if (ctx == null) return "com.quiz.earn.money.game";
        try {
            PackageManager pm = ctx.getPackageManager();
            String[] candidates = new String[]{"com.quiz.earn.money.game", "com.rayole.cashmafia2"};
            for (String p : candidates) {
                try {
                    pm.getPackageInfo(p, 0);
                    return p;
                } catch (PackageManager.NameNotFoundException ignored) {}
            }
        } catch (Exception ignored) {}
        return "com.quiz.earn.money.game";
    }

    public static void restartQuizApp() {
        restartQuizApp(null);
    }

    public static void restartQuizApp(Context callerCtx) {
        Log.i(TAG, "restartQuizApp called.");
        Context ctx = (callerCtx != null) ? callerCtx : ((instance != null) ? instance : null);
        if (ctx != null) {
            try {
                final String targetPkg = getQuizAppPackage(ctx);
                // Safe non-blocking force stop on background thread for rooted devices
                new Thread(() -> {
                    try {
                        Runtime.getRuntime().exec(new String[]{"am", "force-stop", targetPkg});
                    } catch (Throwable ignored) {}
                }).start();

                // Press Home to dismiss any stuck foreground ad/dialog window
                if (instance != null) {
                    try {
                        instance.performGlobalAction(GLOBAL_ACTION_HOME);
                    } catch (Throwable ignored) {}
                }

                // Relaunch with FLAG_ACTIVITY_CLEAR_TASK to ensure the activity is forcefully recreated
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(targetPkg);
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            ctx.startActivity(intent);
                            Log.i(TAG, "Quiz & Earn (" + targetPkg + ") relaunched cleanly with CLEAR_TASK.");
                        }
                    } catch (Throwable ex) {
                        Log.e(TAG, "Failed to start activity in restartQuizApp", ex);
                    }
                }, 300);

                if (instance != null) {
                    instance.lastSolvedTarget = "";
                    instance.lastSolveTime = 0;
                    instance.lastProgressTime = System.currentTimeMillis();
                    instance.adStartTime = 0;
                    instance.collectGemScreenStartTime = 0;
                    instance.collectGemTapCount = 0;
                    instance.questionWaitingStartTime = 0;
                    instance.lastWaitingQuestion = "";
                }
                currentStatus = "App Restarted";
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart Quiz & Earn", e);
            }
        }
    }

    public static void bringQuizAppToFront() {
        Log.i(TAG, "bringQuizAppToFront called (soft resume without clearing task).");
        Context ctx = (instance != null) ? instance : null;
        if (ctx != null) {
            try {
                String targetPkg = getQuizAppPackage(ctx);
                Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(targetPkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    ctx.startActivity(intent);
                    if (instance != null) {
                        instance.lastProgressTime = System.currentTimeMillis();
                        instance.adStartTime = 0;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to bring Quiz & Earn to front", e);
            }
        }
    }

    public static void stopSolving() {
        isRunning = false;
        isUserRunning = false;
        isCollecting25thGem = false;
        finalAdStarted = false;
        offerState = OFFER_STATE_IDLE;
        appIsDuplicate = false;
        Log.i(TAG, "stopSolving called.");
        if (instance != null) {
            instance.lastSolvedTarget = "";
            instance.lastSolveTime = 0;
            instance.collectGemScreenStartTime = 0;
            instance.collectGemTapCount = 0;
            instance.questionWaitingStartTime = 0;
            instance.lastWaitingQuestion = "";
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
        try {
            SharedPreferences prefs = getSharedPreferences("QuizEarnPrefs", Context.MODE_PRIVATE);
            isSuperOfferEnabled = prefs.getBoolean("super_offer_enabled", true);
            Log.i(TAG, "Loaded isSuperOfferEnabled preference: " + isSuperOfferEnabled);
        } catch (Exception e) {
            Log.e(TAG, "Error loading super_offer_enabled pref", e);
        }
        baselinePackages = snapshotInstalledPackages();
        Log.i(TAG, "Quiz Math Assistant connected successfully. isUserRunning=" + isUserRunning);
        if (isUserRunning) {
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

    private long lastOrientationFixTime = 0;

    public void restorePortraitOrientation() {
        long now = SystemClock.uptimeMillis();
        if (now - lastOrientationFixTime < 3500) return; // Prevent spamming
        lastOrientationFixTime = now;
        try {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            Log.w(TAG, "🔄 Landscape orientation detected (" + w + "x" + h + ")! Launching PortraitFixActivity to restore Portrait (900x1600)...");
            Intent fixIntent = new Intent(this, PortraitFixActivity.class);
            fixIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(fixIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error triggering PortraitFixActivity", e);
        }
    }

    public synchronized void solveScreen() {
        if (!isRunning) return;

        long now = System.currentTimeMillis();
        if (now - lastTapTime < 450) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<NodeItem> allNodes = new ArrayList<>();
        List<AccessibilityNodeInfo> rootsToRecycle = new ArrayList<>();
        String pkg = "";

        // 1. Primary source for active package is ALWAYS the active window
        if (root != null) {
            rootsToRecycle.add(root);
            if (root.getPackageName() != null) {
                pkg = root.getPackageName().toString();
            }
            collectAllNodes(root, allNodes);
        }

        // 2. Also inspect windows for multi-window ads and overlay buttons
        boolean foundQuizInWindows = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null && !windows.isEmpty()) {
                    for (AccessibilityWindowInfo win : windows) {
                        if (win == null) continue;
                        AccessibilityNodeInfo winRoot = win.getRoot();
                        if (winRoot != null) {
                            rootsToRecycle.add(winRoot);
                            CharSequence wpSeq = winRoot.getPackageName();
                            if (wpSeq != null) {
                                String wp = wpSeq.toString();
                                if (isQuizAppPackage(wp)) {
                                    foundQuizInWindows = true;
                                }
                                if (pkg.isEmpty() && (win.isActive() || win.isFocused() || win.getType() == AccessibilityWindowInfo.TYPE_APPLICATION)) {
                                    if (!wp.equals("com.quizearn.assistant") && !wp.equals("com.android.systemui")) {
                                        pkg = wp;
                                    }
                                }
                            }
                            if (root == null || !winRoot.equals(root)) {
                                collectAllNodes(winRoot, allNodes);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting windows list", e);
            }
        }

        // 3. Absolute Safeguard: If the Quiz app is present in any active window or root, pkg MUST be the Quiz app!
        if (foundQuizInWindows || isQuizAppPackage(pkg)) {
            pkg = getQuizAppPackage(this);
        }

        if (allNodes.isEmpty()) {
            for (AccessibilityNodeInfo r : rootsToRecycle) {
                try { if (r != null) r.recycle(); } catch (Throwable ignored) {}
            }
            return;
        }

        try {

            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;

            // AUTO-PORTRAIT SAFEGUARD: If screen flipped to landscape (width > height), force portrait recovery immediately
            if (screenW > screenH) {
                restorePortraitOrientation();
            }

            // 0. System ANR / Crash Dialog Dismissal & Uninstall Confirmation
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.equals("close app") || d.equals("close app") || t.equals("wait") || d.equals("wait") ||
                    t.contains("close app") || (t.equals("ok") && (t.contains("stopping") || d.contains("stopping")))) {
                    Log.w(TAG, "Dismissing system ANR/Crash dialog via '" + item.text + "' at " + item.rect);
                    tapNode(item);
                    lastTapTime = now + 1200;
                    return;
                }
                if ((t.equals("ok") || t.equals("uninstall") || d.equals("ok")) &&
                    (pkg.contains("packageinstaller") || pkg.contains("settings"))) {
                    Log.i(TAG, "Confirming app uninstall dialog via '" + item.text + "' at " + item.rect);
                    tapNode(item);
                    lastTapTime = now + 1200;
                    return;
                }
            }

            // 0A. Network Error / Disconnect Recovery ("Try Again", "Retry", "No Internet Connection")
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.equals("try again") || d.equals("try again") || t.equals("retry") || d.equals("retry") || t.contains("reload")) {
                    Log.w(TAG, "⚡ Network error screen detected ('" + item.text + "'). Tapping Try Again at " + item.rect);
                    tapNode(item);
                    lastTapTime = now + 2500;
                    lastProgressTime = now;
                    currentStatus = "Retrying Connection...";
                    return;
                }
            }

            // 0. Universal Auto-Dismiss: Return immediately if an ad launched Chrome or unexpected external app
            boolean isQuizApp = isQuizAppPackage(pkg);
            boolean isAssistantApp = pkg.equals("com.quizearn.assistant");
            boolean isSystemOrSettings = pkg.equals("com.android.systemui") || pkg.equals("com.android.settings");
            boolean isAdActivity = pkg.contains("gms.ads") || pkg.contains("admob") || 
                                   pkg.contains("unity3d") || pkg.contains("applovin") || 
                                   pkg.contains("ironsource") || pkg.contains("vungle") || 
                                   pkg.contains("mintegral") || pkg.contains("inmobi") || 
                                   pkg.contains("fyber");
            boolean isPlayStore = pkg.equals("com.android.vending") || pkg.contains("vending");
            boolean isLauncher = pkg.contains("launcher") || pkg.contains("home") || pkg.equals("android");

            // While in Play Store state, Play Store is EXPECTED and required!
            if (isPlayStore && (offerState == OFFER_STATE_AD_PLAYING || offerState == OFFER_STATE_PLAYSTORE || offerState == OFFER_STATE_INSTALLING)) {
                externalAppBackCount = 0;
                handlePlayStoreScreen(root, allNodes, now);
                return;
            }

            // While in 2-minute App Active state:
            if (offerState == OFFER_STATE_APP_ACTIVE) {
                if (pkg.equals(offerTargetPackage) || (!offerTargetPackage.isEmpty() && pkg.contains(offerTargetPackage))) {
                    externalAppBackCount = 0;
                    offerAppLaunchAttempts = 0;
                    handleActiveAppUsage(now);
                    return;
                } else if (isQuizApp) {
                    boolean isProgressScreen = false;
                    NodeItem stuckRestartBtn = null;
                    for (NodeItem item : allNodes) {
                        String t = item.text.trim().toLowerCase();
                        if (t.contains("app usage progress") || t.contains("progress updates automatically") || t.contains("more needed")) {
                            isProgressScreen = true;
                        }
                        if (t.contains("restart offer") || t.contains("stuck? restart offer")) {
                            stuckRestartBtn = item;
                        }
                    }
                    if (isProgressScreen) {
                        if (offerAppLaunchAttempts < 2) {
                            Log.w(TAG, "Offer app not in foreground (" + pkg + ") during 2-min usage. Attempting relaunch (" + offerAppLaunchAttempts + ")...");
                            offerAppLaunchAttempts++;
                            boolean launched = launchOfferApp();
                            if (!launched) {
                                Log.w(TAG, "Offer app failed to launch. Tapping 'Stuck? Restart Offer'...");
                                if (offerTargetPackage != null && !offerTargetPackage.isEmpty()) {
                                    uninstallApp(offerTargetPackage);
                                    offerTargetPackage = "";
                                }
                                if (stuckRestartBtn != null) tapNode(stuckRestartBtn);
                                offerState = OFFER_STATE_IDLE;
                            }
                            lastTapTime = now + 2000;
                            return;
                        } else {
                            Log.e(TAG, "Offer app failed to stay open / crashed repeatedly! Restarting offer...");
                            if (offerTargetPackage != null && !offerTargetPackage.isEmpty()) {
                                uninstallApp(offerTargetPackage);
                                offerTargetPackage = "";
                            }
                            if (stuckRestartBtn != null) tapNode(stuckRestartBtn);
                            offerState = OFFER_STATE_IDLE;
                            offerAppLaunchAttempts = 0;
                            lastTapTime = now + 1500;
                            return;
                        }
                    }
                }
            }

            if (!isQuizApp && !isAssistantApp && !isSystemOrSettings && !isAdActivity && !isLauncher && !pkg.isEmpty()) {
                externalAppBackCount++;
                Log.i(TAG, "External app opened (" + pkg + ")! Attempt=" + externalAppBackCount + ". Pressing Back immediately to return to Quiz & Earn...");
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastTapTime = now + 2000;
                lastProgressTime = now;
                currentStatus = "Returning to Quiz...";
                adStartTime = 0;

                // If still stuck on external app after 3 back attempts, force bring Quiz & Earn directly to front
                if (externalAppBackCount >= 3) {
                    Log.w(TAG, "Still stuck on " + pkg + " after 3 back attempts. Bringing Quiz & Earn to front via Intent...");
                    bringQuizAppToFront();
                    externalAppBackCount = 0;
                }
                return;
            } else {
                externalAppBackCount = 0;
            }

            // Pre-scan for active quiz solving elements and collect gem buttons
            boolean hasCollectGem = false;
            for (NodeItem item : allNodes) {
                if (isForbiddenElement(item)) continue;
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                String combined = (t + " " + d).trim();
                if (combined.contains("collect gem") || combined.contains("collect reward")) {
                    hasCollectGem = true;
                    break;
                }
            }

            // Check if Super Offer milestone page is visible (Start Offer, 25 gems, instructions, etc.)
            boolean hasSuperOfferMilestone = false;
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.contains("start offer") || d.contains("start offer") ||
                    t.contains("gems collected") || d.contains("gems collected") ||
                    t.contains("guaranteed 1000") || d.contains("guaranteed 1000") ||
                    t.contains("app usage progress") || d.contains("app usage progress") ||
                    t.contains("my super offers") || d.contains("my super offers") ||
                    t.contains("claim 1000 coins") || d.contains("claim 1000 coins") ||
                    t.contains("watch a video ad") || d.contains("watch a video ad") ||
                    t.contains("install the app") || d.contains("install the app") ||
                    t.contains("restart offer") || d.contains("restart offer")) {
                    hasSuperOfferMilestone = true;
                    break;
                }
            }

            // Check if active quiz elements exist on screen (question or answer options)
            // (Cannot be an active quiz if Super Offer milestone page is visible)
            boolean hasActiveQuiz = false;
            if (!hasSuperOfferMilestone) {
                for (NodeItem item : allNodes) {
                    int cy = item.rect.centerY();
                    // Question Card Zone (math equation or captcha number)
                    if (cy >= (int)(screenH * 0.10) && cy <= (int)(screenH * 0.42)) {
                        String raw = item.text.trim();
                        if (mathPattern.matcher(raw).find() || numPattern.matcher(cleanNumText(raw)).matches()) {
                            hasActiveQuiz = true;
                            break;
                        }
                    }
                    // Options Grid Zone: answer button checking
                    if (cy >= (int)(screenH * 0.26) && cy <= (int)(screenH * 0.65)) {
                        String raw = cleanNumText(item.text.trim());
                        if (numPattern.matcher(raw).matches()) {
                            hasActiveQuiz = true;
                            break;
                        }
                    }
                }
            }

            // Check if Home Screen is visible (Start Quiz, Tasks, Community, Wallet, Profile)
            boolean hasHomeScreen = false;
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                if (t.equals("start quiz") || t.equals("start math quiz") || 
                    t.equals("tasks") || t.equals("wallet") || t.equals("profile") ||
                    t.contains("rate us 5 stars") || t.contains("daily win")) {
                    hasHomeScreen = true;
                    break;
                }
            }

            // 1. Detect Ad State & Countdowns (Ad 1 of 2, Ad 2 of 2, etc.)
            boolean hasAdCountdown = false;
            boolean isAd = false;
            if (pkg.contains("gms.ads") || pkg.contains("admob") || pkg.contains("unity3d") || 
                pkg.contains("applovin") || pkg.contains("ironsource") || pkg.contains("vungle") || 
                pkg.contains("mintegral") || pkg.contains("inmobi") || pkg.contains("fyber")) {
                isAd = true;
            }

            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase().trim();
                String d = item.contentDesc.toLowerCase().trim();
                String id = item.resId.toLowerCase();
                boolean isWebView = (item.node != null && item.node.getClassName() != null && 
                    item.node.getClassName().toString().toLowerCase().contains("webview"));

                if (t.contains("reward in") || t.contains("video ends in") || 
                    t.contains("video will end in") || t.contains("seconds after") || 
                    t.contains("skip in ") || d.contains("reward in") || 
                    d.contains("ends in") || d.contains("skip in ") ||
                    id.contains("countdown") || id.contains("count_down") ||
                    id.contains("timer")) {
                    hasAdCountdown = true;
                    isAd = true;
                }

                if (t.contains("ad 1 of") || t.contains("ad 2 of") || t.contains("ad 1 of 3") || 
                    t.contains("ad 2 of 3") || t.contains("ad 3 of 3") ||
                    d.contains("ad 1 of") || d.contains("ad 2 of")) {
                    isAd = true;
                }

                boolean isAdCta = (t.equals("install") || t.equals("install now") || (t.startsWith("install") && t.length() <= 15) ||
                    t.equals("download") || t.equals("download now") || (t.startsWith("download") && t.length() <= 15) ||
                    (t.contains("play store") && !t.contains("rate") && !t.contains("stars")) || t.contains("advertisement") ||
                    t.contains("reward will be") || t.contains("you can skip in") ||
                    t.contains("shop now") || t.contains("claim bonus") || t.contains("play now") ||
                    t.contains("get it on") || t.equals("get") || t.equals("open"));

                if (!isAd && (t.startsWith("ad ") || t.contains("seconds after") || 
                    t.contains("will be taken to the website") || isAdCta ||
                    d.contains("liftoff") || id.contains("adjs") || id.contains("moloco") || 
                    id.contains("endcard") || id.contains("watermark") || id.contains("advert") || 
                    id.contains("unity3d") || id.contains("applovin") || id.contains("mbridge") || 
                    id.contains("tt_") || id.contains("ia_") || id.contains("close-or-skip-button") ||
                    id.contains("app-interstitial") || id.contains("ad-interstitial") ||
                    id.contains("mys-creative") || id.contains("mys-content") ||
                    id.contains("mys-wrapper") || id.contains("mys-progress-bar") ||
                    isWebView || t.contains("moloco") || t.contains("liftoff"))) {
                    isAd = true;
                }
            }

            // Game screen override: If actual quiz questions, Collect Gem, Super Offer milestone page, OR Home Screen are visible, this is NOT an ad!
            if (hasActiveQuiz || hasCollectGem || hasSuperOfferMilestone || hasHomeScreen) {
                isAd = false;
                hasAdCountdown = false;
                adStartTime = 0;
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
                collectGemScreenStartTime = 0;
                collectGemTapCount = 0;
                questionWaitingStartTime = 0;
                lastWaitingQuestion = "";
                if (adStartTime == 0) adStartTime = now;
                long adElapsed = (now - adStartTime) / 1000;
                if (isCollecting25thGem) {
                    finalAdStarted = true;
                    currentStatus = "Final Ad (" + adElapsed + "s) - 25th Gem...";
                } else if (offerState == OFFER_STATE_AD_PLAYING) {
                    currentStatus = "Offer Ad (" + adElapsed + "s)...";
                } else {
                    currentStatus = "Ad Playing (" + adElapsed + "s)...";
                }

                // If this is a Super Offer Ad, we WANT to find and tap the CTA to open Play Store!
                // But only if we haven't already returned from duplicate check (appIsDuplicate)
                if (offerState == OFFER_STATE_AD_PLAYING && !appIsDuplicate) {
                    for (NodeItem item : allNodes) {
                        String t = item.text.trim().toLowerCase();
                        String d = item.contentDesc.trim().toLowerCase();
                        String id = item.resId.trim().toLowerCase();

                        // Check for Play Store CTA elements
                        if (t.equals("install") || t.equals("install now") || d.equals("install") ||
                            t.equals("open") || d.equals("open") || t.equals("get") || d.equals("get") ||
                            t.contains("download") || d.contains("download") ||
                            id.contains("cta") || id.contains("action_button") || id.contains("install_button")) {

                            Log.i(TAG, "⚡ Super Offer Ad CTA detected: '" + t + "' / '" + d + "' at " + item.rect + ". Tapping to open Google Play Store...");
                            currentStatus = "Opening Play Store...";
                            tapNode(item);
                            lastTapTime = now + 1500;
                            return;
                        }
                    }
                }

                // After 18s: Search for verified small corner close icons / back buttons in custom webview/canvas
                if (!hasAdCountdown && adElapsed >= 18 && (now - lastTapTime > 2000)) {
                    for (NodeItem item : allNodes) {
                        if (isForbiddenElement(item)) continue;
                        int cx = item.rect.centerX();
                        int cy = item.rect.centerY();
                        int w = item.rect.width();
                        int h = item.rect.height();

                        String itemT = item.text.trim();
                        String itemD = item.contentDesc.trim();
                        String itemId = item.resId.trim();

                        // Genuine close button MUST have verified close/skip text, desc, or ID!
                        boolean hasCloseIndicator = isCloseOrSkipText(itemT) || 
                                                    isCloseOrSkipDesc(itemD) || 
                                                    isCloseOrSkipId(itemId);
                        if (!hasCloseIndicator) {
                            continue; // Reject any anonymous or non-close node (e.g. "Open", countdown wrappers, etc.)
                        }

                        // Genuine close icons are small (30-130px)
                        if (w > 130 || h > 130) {
                            continue;
                        }

                        // Top-right close icon in webview/playable
                        if (cx > (int)(screenW * 0.75) && cy < 350) {
                            Log.i(TAG, "Ad timeout (" + adElapsed + "s): Tapping verified top-right corner close node at " + item.rect + " [text='" + itemT + "', desc='" + itemD + "', id='" + itemId + "']");
                            tapNode(item);
                            lastTapTime = now;
                            return;
                        }

                        // Top-left back / skip arrow in webview/playable
                        if (cx < (int)(screenW * 0.25) && cy < 350) {
                            Log.i(TAG, "Ad timeout (" + adElapsed + "s): Tapping verified top-left corner close node at " + item.rect + " [text='" + itemT + "', desc='" + itemD + "', id='" + itemId + "']");
                            tapNode(item);
                            lastTapTime = now;
                            return;
                        }
                    }
                }

                // Keep progress active while ad is running (so stall timer never triggers upon ad completion)
                lastProgressTime = now;

                // Progressive Universal Close taps (top-right skip pill, corner close X, top-left corner, and Back key)
                long minAdElapsed = hasAdCountdown ? 20 : 8;
                if (adElapsed >= minAdElapsed && (now - lastTapTime > 1800)) {
                    // Only after 120s+ of total ad unresponsiveness: soft-bring Quiz & Earn to front (NEVER kill app)
                    if (adElapsed >= 120) {
                        Log.w(TAG, "Ad exceeded 120s timeout without dismissing. Bringing Quiz & Earn to front...");
                        bringQuizAppToFront();
                        lastTapTime = now + 3000;
                        lastProgressTime = now;
                        adStartTime = 0;
                        return;
                    }

                    int step = (int)((adElapsed - minAdElapsed) / 2);
                    if (step % 4 == 0) {
                        // Center of top-right Skip pill button (e.g. ">> Skip to Google Play")
                        int targetX = Math.max(50, screenW - 120);
                        int targetY = 65;
                        Log.i(TAG, "Ad running (" + adElapsed + "s): Tapping top-right Skip pill button at (" + targetX + ", " + targetY + ")...");
                        tapCoordinates(targetX, targetY);
                        lastTapTime = now;
                        return;
                    } else if (step % 4 == 1) {
                        // Extreme top-right corner close X / chevron
                        int targetX = screenW - 55;
                        int targetY = 55;
                        Log.i(TAG, "Ad running (" + adElapsed + "s): Tapping top-right corner close coordinate at (" + targetX + ", " + targetY + ")...");
                        tapCoordinates(targetX, targetY);
                        lastTapTime = now;
                        return;
                    } else if (step % 4 == 2) {
                        // Top-left corner fallback close
                        int targetX = 55;
                        int targetY = 55;
                        Log.i(TAG, "Ad running (" + adElapsed + "s): Tapping top-left corner close coordinate at (" + targetX + ", " + targetY + ")...");
                        tapCoordinates(targetX, targetY);
                        lastTapTime = now;
                        return;
                    }
                    Log.i(TAG, "Ad running (" + adElapsed + "s): Pressing Back key to return to Quiz...");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastTapTime = now;
                    return;
                }

                return;
            } else {
                if (adStartTime > 0) {
                    // Ad just completed: reset stall timer so return to quiz is never flagged as a freeze!
                    lastProgressTime = now;
                    adStartTime = 0;
                }
            }

            // 5. Game Stall Auto-Recovery (Only when in game, completely frozen with no questions or ads for > 75s, and no ad active)
            if (isQuizApp && adStartTime == 0 && lastProgressTime > 0 && now - lastProgressTime > 75000) {
                Log.w(TAG, "Quiz & Earn stuck without questions or ads (>75s). Restarting app cleanly...");
                currentStatus = "Stall: Restarting App...";
                restartQuizApp();
                lastProgressTime = now;
                lastTapTime = now + 3500;
                return;
            }

            // 3. Detect Real Gem Count & 25 Gems Milestone
            int detectedGems = -1;

            // Pattern A: Match "X/25" in a single node (e.g. "5/25 gems to unlock", "20/25", "20/25 💎")
            Pattern slash25Pattern = Pattern.compile("(\\d+)\\s*/\\s*25");
            for (NodeItem item : allNodes) {
                Matcher m = slash25Pattern.matcher(item.text);
                if (!m.find() && !item.contentDesc.isEmpty()) {
                    m = slash25Pattern.matcher(item.contentDesc);
                }
                if (m.find()) {
                    try {
                        detectedGems = Integer.parseInt(m.group(1));
                        break;
                    } catch (Exception ignored) {}
                }
            }

            // Pattern B: Match split nodes in Super Offer row: [X] [/] [25]
            if (detectedGems < 0) {
                for (int i = 0; i < allNodes.size() - 2; i++) {
                    NodeItem itemCur = allNodes.get(i);
                    NodeItem itemSlash = allNodes.get(i + 1);
                    NodeItem item25 = allNodes.get(i + 2);

                    String slashT = itemSlash.text.trim();
                    String t25 = item25.text.trim();
                    if (slashT.equals("/") && t25.equals("25")) {
                        try {
                            int g = Integer.parseInt(itemCur.text.trim());
                            if (g >= 0 && g <= 25) {
                                detectedGems = g;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Pattern B2: Single node slash followed by 25: [X] [/25]
            if (detectedGems < 0) {
                for (int i = 0; i < allNodes.size() - 1; i++) {
                    NodeItem itemCur = allNodes.get(i);
                    NodeItem itemSlash25 = allNodes.get(i + 1);
                    String s25 = itemSlash25.text.replaceAll("\\s+", "");
                    if (s25.equals("/25")) {
                        try {
                            int g = Integer.parseInt(itemCur.text.trim());
                            if (g >= 0 && g <= 25) {
                                detectedGems = g;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Pattern C: Top-right header gem badge (rightmost counter in header bar)
            if (detectedGems < 0) {
                int maxCx = -1;
                for (NodeItem item : allNodes) {
                    int cx = item.rect.centerX();
                    int cy = item.rect.centerY();
                    // Header row: top 12% of screen, right 30% of screen
                    if (cx > (int)(screenW * 0.70) && cy < (int)(screenH * 0.12)) {
                        String cleaned = item.text.trim();
                        if (cleaned.matches("^\\d{1,2}$")) {
                            try {
                                int val = Integer.parseInt(cleaned);
                                // The gem badge is always the rightmost element in the header (gifts are on the left)
                                if (cx > maxCx && val >= 0 && val <= 99) {
                                    maxCx = cx;
                                    detectedGems = val;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            // Pattern D: "X Gems Collected!" card (e.g. "26 Gems Collected!")
            if (detectedGems < 0) {
                Pattern gemsCollectedPattern = Pattern.compile("(\\d+)\\s*gems?\\s*collected", Pattern.CASE_INSENSITIVE);
                for (NodeItem item : allNodes) {
                    Matcher m = gemsCollectedPattern.matcher(item.text);
                    if (!m.find() && !item.contentDesc.isEmpty()) {
                        m = gemsCollectedPattern.matcher(item.contentDesc);
                    }
                    if (m.find()) {
                        try {
                            detectedGems = Integer.parseInt(m.group(1));
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (detectedGems >= 0) {
                liveGems = detectedGems;
            }

            boolean is25GemsOnScreen = (detectedGems >= 25);
            if (!hasActiveQuiz) {
                for (NodeItem item : allNodes) {
                    String t = item.text.toLowerCase().trim();
                    String d = item.contentDesc.toLowerCase().trim();
                    if (t.contains("25/25") || d.contains("25/25") || t.contains("25 / 25") || d.contains("25 / 25") ||
                        t.contains("gems collected") || d.contains("gems collected") ||
                        t.contains("start offer") || d.contains("start offer") ||
                        t.contains("claim 1000 coins") || d.contains("claim 1000 coins") ||
                        t.contains("collect 1000 coins") || d.contains("collect 1000 coins") ||
                        t.contains("my super offers") || d.contains("my super offers") ||
                        t.contains("super offer unlocked") || t.contains("super task unlocked")) {
                        is25GemsOnScreen = true;
                        break;
                    }
                }
            } else {
                is25GemsOnScreen = false;
            }

            // Check if final ad for 25th gem has finished and returned to Quiz app
            boolean finalAdCompleted = (isCollecting25thGem && finalAdStarted && !isAd && (now - lastTapTime > 1500));

            boolean isOfferScreen = is25GemsOnScreen || hasSuperOfferMilestone || (offerState != OFFER_STATE_IDLE) || finalAdCompleted;
            if (!isOfferScreen) {
                for (NodeItem item : allNodes) {
                    String t = item.text.toLowerCase().trim();
                    String d = item.contentDesc.toLowerCase().trim();
                    if (t.contains("super offer") || d.contains("super offer") ||
                        t.contains("start offer") || d.contains("start offer") ||
                        t.contains("gems collected") || d.contains("gems collected") ||
                        t.contains("guaranteed 1000") || d.contains("guaranteed 1000") ||
                        t.contains("watch a video ad") || d.contains("watch a video ad") ||
                        t.contains("install the app") || d.contains("install the app") ||
                        t.contains("verify installation") || d.contains("verify installation") ||
                        t.contains("app usage progress") || d.contains("app usage progress") ||
                        t.contains("duplicate") || t.contains("try different") ||
                        t.contains("initial install reward") || t.contains("approved")) {
                        isOfferScreen = true;
                        break;
                    }
                }
            }

            // Check if we are on the Main Home Screen (User greeting, Task Partners, Leaderboard, Super/Mega Offer cards)
            boolean isHomeScreen = false;
            NodeItem homeSuperOfferClaimBtn = null;
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.contains("hi, ") || d.contains("hi, ") ||
                    t.contains("daily win") || t.contains("task partners") ||
                    t.contains("weekly leaderboard") || t.contains("refer & earn") ||
                    t.contains("go to wallet")) {
                    isHomeScreen = true;
                }
                if (t.equals("claim") || t.equals("claim >") || d.equals("claim") || t.startsWith("claim")) {
                    if (item.rect.centerX() < screenW / 2) {
                        homeSuperOfferClaimBtn = item;
                    }
                }
            }

            if (isHomeScreen) {
                if (homeSuperOfferClaimBtn != null) {
                    Log.i(TAG, "On Home Screen: Tapping Super Offer 'Claim >' button at " + homeSuperOfferClaimBtn.rect);
                    tapNode(homeSuperOfferClaimBtn);
                    lastTapTime = now + 2500;
                    lastProgressTime = now;
                    currentStatus = "Entering Super Offer / Quiz...";
                    return;
                } else {
                    for (NodeItem item : allNodes) {
                        String t = item.text.trim().toLowerCase();
                        if (t.contains("claim") || t.contains("quiz")) {
                            tapNode(item);
                            lastTapTime = now + 2000;
                            lastProgressTime = now;
                            return;
                        }
                    }
                }
                return;
            }

            // SUPER OFFER AUTOMATION: Automates the 25-Gems 1000 Coins Super Offer end-to-end
            if (!isAd && isQuizApp) {
                if (isSuperOfferEnabled) {
                    if (isOfferScreen) {
                        if (isCollecting25thGem && finalAdCompleted) {
                            isCollecting25thGem = false;
                            finalAdStarted = false;
                            liveGems = 25;
                            if (!hasAlerted25Gems) {
                                hasAlerted25Gems = true;
                                play25GemsMilestoneAlert();
                            }
                        }
                        if (handleSuperOfferScreen(root, allNodes, now)) {
                            return;
                        }
                    }
                } else {
                    // Super Offer is DISABLED on this player instance (Player 2 / Player 3):
                    // Safely pause on the 25 gems milestone page without downloading or installing any apps
                    if (!hasActiveQuiz && !hasCollectGem && (is25GemsOnScreen || finalAdCompleted)) {
                        isCollecting25thGem = false;
                        finalAdStarted = false;
                        liveGems = 25;
                        Log.i(TAG, "🎉 25 GEMS TARGET REACHED (Auto-Offer OFF for this player). Stopping bot on 25 Gems Page.");
                        currentStatus = "25 GEMS HIT! (Auto-Offer Off)";
                        if (!hasAlerted25Gems) {
                            hasAlerted25Gems = true;
                            play25GemsMilestoneAlert();
                        }
                        stopSolving();
                        return;
                    }
                }
            }

            // 4. Check for "Collect Gem", "Claim", "Verify Usage", or Reward collection buttons (Normal Solving Loop)
            if (!is25GemsOnScreen && offerState == OFFER_STATE_IDLE) {
                // Check if we are on the Quiz Complete / Gem Collection Screen (Waiting for Ad)
                boolean isQuizCompleteCollectGemScreen = false;
                boolean hasAdLoadErrorToast = false;
                boolean hasActiveQuizQuestion = false;

                for (NodeItem n : allNodes) {
                    String nt = n.text.toLowerCase();
                    String nd = n.contentDesc.toLowerCase();

                    // Check for active quiz questions / options (prevents false matches on question screen!)
                    if (nt.contains("question ") || nt.contains("?") || nt.contains(" = ") ||
                        nt.matches(".*\\d+\\s*[+\\-xX/÷*]\\s*\\d+.*")) {
                        hasActiveQuizQuestion = true;
                    }

                    if (nt.contains("congratulations") || nd.contains("congratulations") ||
                        nt.contains("+1 gem") || nd.contains("+1 gem") ||
                        nt.contains("watch an ad to collect your gem") || nd.contains("watch an ad to collect your gem") ||
                        nt.equals("collect gem") || nd.equals("collect gem") ||
                        nt.equals("collect reward") || nd.equals("collect reward")) {
                        isQuizCompleteCollectGemScreen = true;
                    }
                    if (nt.contains("please watch the full ad") || nd.contains("please watch the full ad") ||
                        nt.contains("ad not available") || nd.contains("failed to load ad")) {
                        hasAdLoadErrorToast = true;
                    }
                }

                if (hasActiveQuizQuestion) {
                    isQuizCompleteCollectGemScreen = false;
                }

                if (isQuizCompleteCollectGemScreen) {
                    if (collectGemScreenStartTime == 0) {
                        collectGemScreenStartTime = now;
                        collectGemTapCount = 0;
                    }

                    long elapsedOnCollectScreen = now - collectGemScreenStartTime;

                    // Watchdog: If the ad does not load after completing quiz within 10s of tapping Collect Gem:
                    // Automatically restart the app cleanly as requested by user!
                    // ONLY restart if we already attempted to tap collect gem at least once (collectGemTapCount > 0)!
                    // NEVER restart if collectGemTapCount == 0 (we haven't even tapped Collect Gem yet!)
                    if (collectGemTapCount > 0) {
                        if ((collectGemTapCount >= 2 && elapsedOnCollectScreen > 8000) ||
                            (hasAdLoadErrorToast && elapsedOnCollectScreen > 5000) ||
                            (elapsedOnCollectScreen > 15000)) {
                            Log.w(TAG, "Ad not loading after completing quiz (" + (elapsedOnCollectScreen / 1000) + "s on Collect Gem screen, taps=" + collectGemTapCount + "). Restarting app cleanly as requested...");
                            currentStatus = "Ad not loading -> Restarting App";
                            collectGemScreenStartTime = 0;
                            collectGemTapCount = 0;
                            restartQuizApp();
                            lastTapTime = now + 3500;
                            lastProgressTime = now;
                            return;
                        }
                    }
                } else {
                    collectGemScreenStartTime = 0;
                    collectGemTapCount = 0;
                }

                for (NodeItem item : allNodes) {
                    if (isForbiddenElement(item)) continue;
                    String t = item.text.trim().toLowerCase();
                    String d = item.contentDesc.trim().toLowerCase();
                    String combined = (t + " " + d).trim();
                    if (combined.contains("collect gem") || combined.contains("collect reward")) {
                        // Debounce: Wait 3.5s after tapping Collect Gem for the ad to launch
                        if (collectGemTapCount > 0 && (now - lastTapTime < 3500)) {
                            return;
                        }
                        collectGemTapCount++;

                        // If we are at 24+ gems, this collection action starts the final ad for the 25th gem!
                        boolean is24thGem = (liveGems >= 24);
                        if (!is24thGem) {
                            for (NodeItem n : allNodes) {
                                String nt = n.text.toLowerCase();
                                String nd = n.contentDesc.toLowerCase();
                                if (nt.contains("24/25") || nd.contains("24/25") || nt.contains("24 / 25") || nd.contains("24 / 25")) {
                                    is24thGem = true;
                                    break;
                                }
                            }
                        }
                        if (is24thGem) {
                            isCollecting25thGem = true;
                            finalAdStarted = false;
                            Log.i(TAG, "Collecting 25th Gem! Final ad will play now. Bot remains ACTIVE to close the ad before stopping on the 25 gems page.");
                        }

                        Log.i(TAG, "Found Action/Claim/Verify button: " + item.text + " (Tap #" + collectGemTapCount + ") at " + item.rect);
                        tapNode(item);
                        lastTapTime = now + 3000;
                        lastProgressTime = now;
                        lastSolvedTarget = "";
                        lastSolveTime = 0;
                        currentStatus = "Collecting Gem (Waiting for ad)...";
                        adStartTime = 0;
                        return;
                    } else if (t.equals("collect") || t.startsWith("claim") || d.startsWith("claim") ||
                               t.contains("verify usage") || t.equals("verify") || d.contains("verify usage")) {
                        Log.i(TAG, "Found Action/Claim/Verify button: " + item.text + " at " + item.rect);
                        tapNode(item);
                        lastTapTime = now + 1500;
                        lastProgressTime = now;
                        lastSolvedTarget = "";
                        lastSolveTime = 0;
                        currentStatus = (t.contains("verify") || d.contains("verify")) ? "Verifying Usage..." : "Collecting Gem...";
                        adStartTime = 0;
                        return;
                    }
                }
            }

            // 5. ZONE-BASED 100% ACCURATE QUIZ SOLVER
            // Strict spatial zones to prevent ANY header/footer badge misidentification:
            // - Header Zone: Y < screenH * 0.12 (Gems counter, Gift badges, Quiz title) -> EXCLUDED
            // - Question Card Zone: screenH * 0.12 <= Y <= screenH * 0.36 (Math equation / Captcha number)
            // - Options Grid Zone: screenH * 0.28 <= Y <= screenH * 0.60 (2x2 grid of 4 answer buttons)
            // - Footer Zone: Y > screenH * 0.60 (Super offer milestones, 1000 coins text, My Offers) -> EXCLUDED
            // Adaptable spatial zones for all aspect ratios & screen resolutions (720p, 1080p, 2K, 16:9 to 21:9):
            int qMinY = (int) (screenH * 0.10);
            int qMaxY = (int) (screenH * 0.38);
            int optMinY = (int) (screenH * 0.26);
            int optMaxY = (int) (screenH * 0.65);

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
            boolean hasCaptchaContext = false;
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase();
                String d = item.contentDesc.toLowerCase();
                if (t.contains("select the correct") || d.contains("select the correct") ||
                    t.contains("attempt") || d.contains("attempt") ||
                    t.contains("/2") || d.contains("/2") ||
                    t.contains("/ 2") || d.contains("/ 2")) {
                    hasCaptchaContext = true;
                    break;
                }
            }

            if (detectedTarget == null && hasCaptchaContext) {
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
                // If this is the exact same target we already solved:
                if (detectedTarget.equals(lastSolvedTarget)) {
                    // If the app has remained stuck on this already-solved question for > 15s, auto-refresh via Back key!
                    if (now - lastSolveTime > 15000) {
                        Log.w(TAG, "Quiz WebView stuck on answered question (" + detectedTarget + ") > 15s! Auto-backing to Home to refresh...");
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        lastTapTime = now + 1500;
                        lastSolveTime = now;
                        lastSolvedTarget = "";
                        lastProgressTime = now;
                        currentStatus = "Refreshing Quiz...";
                        return;
                    }
                    if (now - lastSolveTime < 2500) {
                        // Anti-Ghosting: Waiting for screen to update to next question
                        return;
                    }
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
                    // Check if option button is already disabled (clicked)
                    if (answerButton.node != null && !answerButton.node.isEnabled()) {
                        if (detectedTarget.equals(lastSolvedTarget) && (now - lastSolveTime > 12000)) {
                            Log.w(TAG, "Option button '" + targetAnsStr + "' is disabled and app hasn't advanced for 12s. Auto-backing to Home to refresh...");
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
                    if (questionWaitingStartTime == 0 || !detectedTarget.equals(lastWaitingQuestion)) {
                        questionWaitingStartTime = now;
                        lastWaitingQuestion = detectedTarget;
                    } else if (now - questionWaitingStartTime > 35000) {
                        Log.w(TAG, "Quiz question options stuck on '...' for >35s! Bringing app cleanly to front...");
                        questionWaitingStartTime = 0;
                        lastWaitingQuestion = "";
                        bringQuizAppToFront();
                        lastTapTime = now + 2500;
                        lastProgressTime = now;
                        return;
                    }
                }
            } else {
                questionWaitingStartTime = 0;
                lastWaitingQuestion = "";
            }

            currentStatus = "Scanning...";

        } finally {
            for (AccessibilityNodeInfo r : rootsToRecycle) {
                try {
                    if (r != null) r.recycle();
                } catch (Throwable ignored) {}
            }
        }
    }

    private void collectAllNodes(AccessibilityNodeInfo node, List<NodeItem> list) {
        if (node == null) return;

        CharSequence pkgSeq = node.getPackageName();
        String nodePkg = (pkgSeq != null) ? pkgSeq.toString() : "";

        // STRICT NOTIFICATION & LAUNCHER FILTER (Allow all ad SDKs, webviews, and app activities)
        if (nodePkg.equals("com.android.systemui") || 
            nodePkg.equals("com.android.launcher") || 
            nodePkg.contains("launcher") || 
            nodePkg.contains("systemui")) {
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

        boolean isSpecialClass = false;
        CharSequence clsSeq = node.getClassName();
        if (clsSeq != null) {
            String cls = clsSeq.toString().toLowerCase();
            if (cls.contains("webview")) {
                isSpecialClass = true;
            }
        }

        // Add node if it has text, content description, resource ID, is clickable, or is a WebView
        if ((!effectiveText.isEmpty() || !desc.isEmpty() || !resId.isEmpty() || node.isClickable() || isSpecialClass) && !rect.isEmpty()) {
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

    private boolean isCloseOrSkipText(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase();
        if (t.isEmpty()) return false;
        if ((t.contains(" in ") || t.contains(" in:") || t.contains("seconds") || t.contains("sec")) &&
            !t.contains("in 0")) return false;
        return t.equals("close") || t.equals("skip") || t.contains("skip") || t.equals("dismiss") ||
               t.equals("x") || t.equals("✕") || t.equals("✖") || t.equals("×") ||
               t.equals("<") || t.equals("‹") || t.equals("«") || t.equals("back") ||
               t.equals("done") || t.equals("exit") || t.equals("no thanks") ||
               t.equals("not now") || t.contains(">>") || t.contains("\u203A\u203A") || t.contains("\u00BB") ||
               t.contains("\u276F\u276F") || t.contains("▶▶") || t.contains("⏭") || 
               t.contains("⏩") || t.contains(">|");
    }

    private boolean isCloseOrSkipDesc(String desc) {
        if (desc == null) return false;
        String d = desc.trim().toLowerCase();
        if (d.isEmpty()) return false;
        return d.contains("close") || d.contains("skip") || d.contains("dismiss") ||
               d.equals("x") || d.equals("✕") || d.equals("✖") || d.equals("back") ||
               d.equals("exit") || d.equals("navigate up");
    }

    private boolean isCloseOrSkipId(String resId) {
        if (resId == null) return false;
        String id = resId.trim().toLowerCase();
        if (id.isEmpty()) return false;
        return id.contains("close") || id.contains("skip") || id.contains("dismiss") || 
               id.contains("btn_back") || id.contains("iv_back") || id.contains("al_close");
    }

    private boolean isPointInForbiddenElement(int x, int y, List<NodeItem> allNodes) {
        if (allNodes == null) return false;
        for (NodeItem item : allNodes) {
            if (item.rect != null && item.rect.contains(x, y)) {
                // Ignore large background containers when checking point collisions
                if (item.rect.width() >= 450 && item.rect.height() >= 450) {
                    continue;
                }
                if (isForbiddenElement(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Global filter to NEVER touch CTA buttons (Open, Install, Download, Play, etc.), audio, privacy, or deceptive layers
    private boolean isForbiddenElement(NodeItem item) {
        if (item == null || item.rect == null) return true;

        String t = item.text.toLowerCase().trim();
        String d = item.contentDesc.toLowerCase().trim();
        String id = item.resId.toLowerCase().trim();

        // CRITICAL EXCEPTION: Explicit Skip buttons (e.g. ">> Skip to Google Play", "Skip Ad", "Skip >>") are NEVER forbidden!
        if (t.contains("skip") || d.contains("skip") || id.contains("skip") || 
            t.contains(">>") || d.contains(">>") ||
            t.contains("\u203A\u203A") || t.contains("\u00BB") || t.contains("\u276F\u276F") ||
            d.contains("\u203A\u203A") || d.contains("\u00BB") || d.contains("\u276F\u276F") ||
            t.contains("\u23ED") || t.contains("\u23E9") || d.contains("\u23ED") || d.contains("\u23E9")) {
            if (!t.contains(" in ") && !d.contains(" in ") && !t.contains("seconds") && !d.contains("seconds") &&
                !t.contains(" in:") && !d.contains(" in:")) {
                return false;
            }
        }

        // CRITICAL GUARD: Fullscreen and oversized touch overlays are ad-traps!
        int w = item.rect.width();
        int h = item.rect.height();
        if (w >= 450 && h >= 450) {
            return true;
        }

        String combined = (t + " " + d + " " + id);

        // Deceptive full-screen / CTA overlay layers
        if (combined.contains("fullscreen") || combined.contains("overlay") || 
            combined.contains("interceptor") || combined.contains("touch_layer") || 
            combined.contains("call to action") || id.contains("overlay") || 
            id.contains("fullscreen") || id.contains("cta")) {
            return true;
        }

        // Sound / Audio / Mute / Volume / Music
        if (combined.contains("sound") || combined.contains("mute") || 
            combined.contains("unmute") || combined.contains("volume") || 
            combined.contains("audio") || combined.contains("speaker") || 
            combined.contains("music") || id.contains("sound") || 
            id.contains("mute") || id.contains("volume") || id.contains("audio")) {
            return true;
        }

        // Action CTA: "Open" buttons & variants (Skyscanner, apps, stores)
        if (t.equals("open") || t.startsWith("open ") || t.endsWith(" open") || 
            t.contains("open in") || t.contains("open app") || t.contains("open game") ||
            d.equals("open") || d.startsWith("open ") || d.endsWith(" open") || 
            d.contains("open in") || d.contains("open app") ||
            id.contains("open_button") || id.contains("btn_open") || 
            id.contains("open_app") || id.contains("openbutton")) {
            return true;
        }

        // Action CTA: "Play" buttons & variants
        if (t.equals("play") || t.equals("play now") || t.startsWith("play ") || 
            t.contains("play game") || t.contains("play free") ||
            d.equals("play") || d.equals("play now") || d.startsWith("play ") || 
            id.contains("btn_play") || id.contains("play_button")) {
            return true;
        }

        // Action CTA: Install / Download / Get
        if (t.equals("get") || t.startsWith("get ") || t.contains("get app") ||
            d.equals("get") || d.startsWith("get ") || d.contains("get app") ||
            combined.contains("install") || combined.contains("download") || 
            combined.contains("get app") || combined.contains("play store") || 
            id.contains("install") || id.contains("download")) {
            return true;
        }

        // Action CTA: Shop / Try / Visit / Details
        if (t.equals("shop") || t.startsWith("shop ") || t.contains("shop now") ||
            d.equals("shop") || d.startsWith("shop ") || d.contains("shop now") ||
            t.equals("try") || t.startsWith("try ") || t.contains("try now") ||
            d.equals("try") || d.startsWith("try ") || d.contains("try now") ||
            t.equals("visit") || t.startsWith("visit ") || d.equals("visit") ||
            combined.contains("learn more") || combined.contains("view details") ||
            combined.contains("see more") || combined.contains("sign up") ||
            combined.contains("order now") || combined.contains("buy now") ||
            id.contains("action_button") || id.contains("button_action") || id.contains("btn_action")) {
            return true;
        }

        // Info / AdChoices / Privacy / Report
        if (combined.contains("adchoice") || combined.contains("privacy") || 
            combined.contains("about this ad") || combined.contains("why this ad") ||
            id.contains("adchoice") || id.contains("privacy") || id.contains("info")) {
            return true;
        }

        // Ad counters/labels ("Ad 1 of 2", "Ad 2 of 2", "Ad", "Sponsored", "Advertisement")
        if (t.contains("ad 1 of") || t.contains("ad 2 of") || t.contains("ad 3 of") || 
            t.equals("ad") || t.equals("sponsored") || t.equals("advertisement") ||
            d.contains("ad 1 of") || d.contains("ad 2 of")) {
            return true;
        }

        return false;
    }

    private boolean isSkipElement(NodeItem item) {
        if (isForbiddenElement(item)) {
            return false;
        }

        int w = item.rect.width();
        int h = item.rect.height();
        if (w < 8 || h < 8 || w > 550 || h > 250) {
            return false;
        }

        String t = item.text.toLowerCase().trim();
        String d = item.contentDesc.toLowerCase().trim();
        String id = item.resId.toLowerCase().trim();

        if (numPattern.matcher(item.text).matches()) {
            return false;
        }

        // Do not tap active countdown timers (e.g. "Skip in 5s", "You can skip in 3 seconds")
        if ((t.contains(" in ") || d.contains(" in ") || t.contains(" in:") || d.contains(" in:") ||
             t.contains("seconds") || d.contains("seconds") || t.matches(".*skip.*\\d+\\s*s.*")) &&
            !t.contains("in 0") && !d.contains("in 0")) {
            return false;
        }

        // Explicit skip text or description (e.g. "Skip", "Skip Ad", ">> Skip to Google Play", "Skip to Play Store")
        if (t.contains("skip") || d.contains("skip")) {
            return true;
        }

        // Visible skip arrows: >>, ››, », ❯❯, >|, ▶▶, ⏭, ⏩
        if (t.contains(">>") || t.contains("\u203A\u203A") || t.contains("\u00BB") || 
            t.contains("\u276F\u276F") || t.contains(">|") || t.contains("\u25B6\u25B6") || 
            t.contains("\u23ED") || t.contains("\u23E9") ||
            d.contains(">>") || d.contains("\u203A\u203A") || d.contains("\u00BB") || 
            d.contains("\u276F\u276F") || d.contains("fast forward")) {
            return true;
        }

        // Exact skip resource IDs (InMobi, Google, Unity, AppLovin, Vungle, Liftoff)
        if (id.contains("skip_button") || id.contains("btn_skip") || 
            id.contains("close-or-skip-button") || id.contains("skip_ad") || 
            id.contains("skipbutton") || id.contains("skip-button") ||
            id.contains("skip-btn") || id.contains("skip_btn") ||
            id.contains("skip-close") || id.contains("skip_close") ||
            id.contains("mbridge_skip") || id.contains("skip_cta") ||
            id.contains("skip")) {
            return true;
        }

        return false;
    }

    private boolean isCloseElement(NodeItem item, boolean hasAdCountdown) {
        if (isForbiddenElement(item)) {
            return false;
        }

        int w = item.rect.width();
        int h = item.rect.height();
        if (w < 8 || h < 8 || w > 450 || h > 250) {
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
            t.contains("seconds after") || d.contains("seconds after") ||
            id.contains("countdown")) {
            return false;
        }

        // Do not close prematurely while countdown is active
        if (hasAdCountdown) {
            return false;
        }

        // Exact text match for confirmation dialogs ("Close Video", "Skip Video", "Leave", "Yes", etc.)
        if (t.equals("close video") || t.equals("skip video") || t.equals("dismiss") || 
            t.equals("no thanks") || t.equals("not now") || t.equals("leave") || 
            t.equals("yes") || t.equals("resume video") || t.contains("close video") ||
            t.equals("close") || t.equals("close ad")) {
            return true;
        }

        // A close icon (X, Close button, etc.) MUST be either:
        // 1. In the top area (item.rect.top <= 450)
        // 2. In a side/corner (item.rect.right >= screenW * 0.70 || item.rect.left <= screenW * 0.30)
        int screenW = getResources().getDisplayMetrics().widthPixels;
        boolean isTopOrCorner = (item.rect.top <= 450) || 
                                (item.rect.centerX() >= (int)(screenW * 0.70)) || 
                                (item.rect.centerX() <= (int)(screenW * 0.30));

        if (!isTopOrCorner) {
            return false; // Reject any button in the center of the screen
        }

        // Explicit Close / Close Ad content descriptions (e.g. Google Ads, Liftoff, AppLovin, Unity)
        if (d.equals("close ad") || d.equals("close") || d.equals("close button") || 
            d.equals("close video") || d.equals("dismiss") || d.contains("close ad") ||
            d.contains("close advertisement") || d.startsWith("close") ||
            d.contains("skip ad") || d.equals("skip") || d.startsWith("skip")) {
            return true;
        }

        // Explicit Close resource IDs
        if (id.contains("close-or-skip-button") || id.contains("close_button") || 
            id.contains("btn_close") || id.contains("iv_close") || 
            id.contains("ia_clickable_close_button") || id.contains("tt_video_ad_close") || 
            id.contains("close_ad") || id.contains("ad_close") || id.contains("btn_back") || 
            id.contains("iv_back") || id.contains("button_back") || id.contains("al_close") ||
            id.contains("unity_close") || id.contains("vungle_close") || 
            id.contains("skip-close") || id.contains("skip_close") ||
            id.contains("skip-btn") || id.contains("skip_btn") ||
            id.contains("closebutton") || id.contains("close-button")) {
            return true;
        }

        // Explicit Close text symbols (✕, ✖, ⨯, ×, X, <, ‹, back, exit)
        if (t.equals("\u2715") || t.equals("\u2716") || t.equals("\u2A2F") || 
            t.equals("\u00D7") || t.equalsIgnoreCase("x") || t.equals("✕") || t.equals("✖") ||
            t.equals("<") || t.equals("\u2039") || t.equals("‹") ||
            t.equals("back") || t.equals("exit") ||
            d.equals("back") || d.equals("navigate up") || d.equals("exit") ||
            d.equals("x") || d.equals("\u2715") || d.equals("\u2716")) {
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

    private void swipeCoordinates(int startX, int startY, int endX, int endY, long durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription stroke = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build();
            dispatchGesture(stroke, null, null);
        }
    }

    private List<String> snapshotInstalledPackages() {
        List<String> list = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            List<PackageInfo> installed = pm.getInstalledPackages(0);
            for (PackageInfo pi : installed) {
                list.add(pi.packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error snapshotting packages", e);
        }
        return list;
    }

    private String detectNewlyInstalledPackage(List<String> baseline) {
        if (baseline == null || baseline.isEmpty()) {
            return null;
        }
        try {
            PackageManager pm = getPackageManager();
            List<PackageInfo> installed = pm.getInstalledPackages(0);
            for (PackageInfo pi : installed) {
                if (!baseline.contains(pi.packageName)) {
                    // Check if it's not a google or system service
                    if (!pi.packageName.startsWith("com.google.android.") &&
                        !pi.packageName.startsWith("com.android.") &&
                        !pi.packageName.startsWith("com.bluestacks.") &&
                        !pi.packageName.contains("location.provider") &&
                        !isQuizAppPackage(pi.packageName) &&
                        !pi.packageName.equals("com.quizearn.assistant")) {
                        return pi.packageName;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error detecting newly installed package", e);
        }
        return null;
    }

    private void finishInstallationAndReturnToQuiz(long now) {
        currentStatus = "App Installed! Returning to Quiz...";
        offerState = OFFER_STATE_VERIFYING;
        offerStateStartTime = now;

        // Bring Quiz & Earn directly to front
        bringQuizAppToFront();
        lastTapTime = now + 2000;
    }

    private void handlePlayStoreScreen(AccessibilityNodeInfo root, List<NodeItem> allNodes, long now) {
        if (now - lastTapTime < 800) return;

        // Transition to PLAYSTORE if coming from AD_PLAYING
        if (offerState == OFFER_STATE_AD_PLAYING) {
            offerState = OFFER_STATE_PLAYSTORE;
            offerStateStartTime = now;
            Log.i(TAG, "Opened Google Play Store for Super Offer app!");
        }

        currentStatus = "Play Store: Checking app...";

        // Check if app has already finished installing (e.g. state was INSTALLING)
        String newPkg = detectNewlyInstalledPackage(baselinePackages);
        if (newPkg != null) {
            offerTargetPackage = newPkg;
            Log.i(TAG, "Detected newly installed package from Play Store: " + newPkg);
            finishInstallationAndReturnToQuiz(now);
            return;
        }

        // Search for action buttons in Play Store
        int screenH = getResources().getDisplayMetrics().heightPixels;
        NodeItem installButton = null;
        NodeItem openButton = null;
        NodeItem closeSheetButton = null;

        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();

            if (d.contains("close sheet") || d.equals("close") || t.equals("close")) {
                closeSheetButton = item;
            }

            // In top 65% of screen (main app hero section, ignoring suggested/sponsored apps)
            if (item.rect.centerY() < (int)(screenH * 0.65)) {
                // Check for Open or Uninstall (app already installed on this phone)
                if (t.equals("open") || d.equals("open") || t.equals("uninstall") || d.equals("uninstall")) {
                    openButton = item;
                }

                // Check for Install button
                if (t.equals("install") || d.equals("install")) {
                    installButton = item;
                }
            }
        }

        // Case 1: App is ALREADY installed on phone
        if (openButton != null && installButton == null) {
            Log.i(TAG, "⚠️ App in Play Store is ALREADY INSTALLED on this device! (User Rule: Skip & Restart Offer)");
            currentStatus = "Duplicate App: Restarting Offer...";
            appIsDuplicate = true;
            offerState = OFFER_STATE_WAIT_AD_RETURN;
            offerStateStartTime = now;

            // Close Play Store to return to ad
            if (closeSheetButton != null) {
                tapNode(closeSheetButton);
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            lastTapTime = now + 1500;
            return;
        }

        // Case 2: App is NOT yet installed -> Tap "Install"
        if (installButton != null && offerState != OFFER_STATE_INSTALLING) {
            Log.i(TAG, "⚡ Tapping 'Install' in Play Store for Super Offer app...");
            currentStatus = "Installing App...";
            tapNode(installButton);
            offerState = OFFER_STATE_INSTALLING;
            offerStateStartTime = now;
            lastTapTime = now + 2000;
            return;
        }

        // Case 3: We are in INSTALLING state, waiting for download
        if (offerState == OFFER_STATE_INSTALLING) {
            long installElapsed = (now - offerStateStartTime) / 1000;
            currentStatus = "Installing (" + installElapsed + "s)...";

            // If "Open" button appears, installation finished!
            if (openButton != null) {
                Log.i(TAG, "Play Store shows 'Open' button! App installed successfully.");
                finishInstallationAndReturnToQuiz(now);
                return;
            }

            // Timeout after 180s (3 minutes) if download fails or hangs
            if (installElapsed > 180) {
                Log.w(TAG, "App installation timed out (>180s). Returning to Quiz app to restart offer.");
                performGlobalAction(GLOBAL_ACTION_BACK);
                offerState = OFFER_STATE_VERIFYING;
                lastTapTime = now + 1500;
                return;
            }
        }
    }

    private void handleActiveAppUsage(long now) {
        if (appUsageStartTime == 0) {
            appUsageStartTime = now;
        }

        long elapsedSec = (now - appUsageStartTime) / 1000;
        currentStatus = "Active App Usage (" + elapsedSec + "s / 125s)";

        // Every 15-20 seconds: dispatch gentle swipe to keep app alive
        if (now - lastUsageGestureTime > 15000) {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            swipeCoordinates(screenW / 2, (int)(screenH * 0.65), screenW / 2, (int)(screenH * 0.35), 250);
            lastUsageGestureTime = now;
        }

        // When 125s (2 minutes + buffer) is reached:
        if (elapsedSec >= 125) {
            Log.i(TAG, "🎉 2 Minutes (125s) App Usage Completed! Returning to Quiz & Earn to collect reward...");
            currentStatus = "2min Done! Claiming Offer...";
            offerState = OFFER_STATE_REWARD_CLAIM;
            offerStateStartTime = now;

            bringQuizAppToFront();
            lastTapTime = now + 2500;
        }
    }

    private String findMostRecentInstalledPackage() {
        try {
            PackageManager pm = getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);
            long newestTime = 0;
            String newestPkg = null;
            long now = System.currentTimeMillis();

            for (PackageInfo p : pkgs) {
                if (isQuizAppPackage(p.packageName) || 
                    p.packageName.equals("com.quizearn.assistant") ||
                    p.packageName.startsWith("com.android.") ||
                    p.packageName.startsWith("com.google.android.") ||
                    p.packageName.startsWith("com.bluestacks.")) {
                    continue;
                }
                if (now - p.firstInstallTime < 45 * 60 * 1000L) {
                    if (p.firstInstallTime > newestTime) {
                        newestTime = p.firstInstallTime;
                        newestPkg = p.packageName;
                    }
                }
            }
            return newestPkg != null ? newestPkg : "";
        } catch (Exception e) {
            Log.e(TAG, "Error finding newest package", e);
            return "";
        }
    }

    private void uninstallApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        Log.i(TAG, "🗑️ Uninstalling broken/incompatible app: " + packageName);
        try {
            Runtime.getRuntime().exec("pm uninstall " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "Error executing pm uninstall", e);
        }
        try {
            Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
            uninstallIntent.setData(Uri.parse("package:" + packageName));
            uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(uninstallIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching ACTION_DELETE intent", e);
        }
    }

    private boolean launchOfferApp() {
        if (offerTargetPackage == null || offerTargetPackage.isEmpty()) {
            offerTargetPackage = findMostRecentInstalledPackage();
        }
        if (offerTargetPackage != null && !offerTargetPackage.isEmpty()) {
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(offerTargetPackage);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    Log.i(TAG, "Launched offer app: " + offerTargetPackage);
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch " + offerTargetPackage, e);
            }
        }

        // Fallback: Detect newly installed package from baselinePackages
        String newlyInstalled = detectNewlyInstalledPackage(baselinePackages);
        if (newlyInstalled != null) {
            offerTargetPackage = newlyInstalled;
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(newlyInstalled);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    Log.i(TAG, "Launched detected newly installed offer app: " + newlyInstalled);
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch fallback app " + newlyInstalled, e);
            }
        }

        Log.w(TAG, "Could not find launch intent for offer app! Will remain on screen.");
        return false;
    }

    private boolean handleSuperOfferScreen(AccessibilityNodeInfo root, List<NodeItem> allNodes, long now) {
        if (now - lastTapTime < 500) return false;

        // 1. REWARD CLAIM PHASE (OFFER_STATE_REWARD_CLAIM)
        if (offerState == OFFER_STATE_REWARD_CLAIM) {
            // Check A: Green "View My Offers" button (+ 100 Coins Earned screen)
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.contains("view my offers") || d.contains("view my offers")) {
                    Log.i(TAG, "⚡ Super Offer Verified! Tapping green 'View My Offers' button at " + item.rect);
                    tapNode(item);
                    lastTapTime = now + 1500;
                    currentStatus = "Opening My Offers...";
                    return true;
                }
            }

            // Check A2: Offer details page with "Approved" status -> Press Back to reach Home
            boolean isOfferApprovedPage = false;
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase();
                if ((t.contains("approved") && t.contains("✓")) || 
                    (t.contains("initial install reward") && t.contains("approved")) ||
                    t.contains("upload proof screenshot")) {
                    isOfferApprovedPage = true;
                    break;
                }
            }
            if (isOfferApprovedPage) {
                Log.i(TAG, "⚡ Offer Initial Install Reward is Approved! Pressing Back to reach Home...");
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastTapTime = now + 1500;
                currentStatus = "Reward Approved! Returning...";
                return true;
            }

            // Check B: "My Super Offers" page -> Go back to Home to claim
            boolean isMyOffersPage = false;
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase();
                if (t.contains("my super offers") || t.contains("active pending offers") || t.contains("pending offers")) {
                    isMyOffersPage = true;
                    break;
                }
            }
            if (isMyOffersPage) {
                // Find Home button at bottom or press Back
                NodeItem homeBtn = null;
                for (NodeItem item : allNodes) {
                    if (item.text.equalsIgnoreCase("home")) {
                        homeBtn = item;
                        break;
                    }
                }
                if (homeBtn != null) {
                    Log.i(TAG, "On My Super Offers page: Tapping 'Home' button...");
                    tapNode(homeBtn);
                } else {
                    Log.i(TAG, "On My Super Offers page: Pressing Back to reach Home...");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
                lastTapTime = now + 1500;
                currentStatus = "Navigating to Home...";
                return true;
            }

            // Check C: Home screen with "SUPER OFFER Guaranteed 1,000 Coins Claim" card
            boolean hasSuperOfferCard = false;
            NodeItem claimBtn = null;
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase();
                if (t.contains("super offer") || t.contains("guaranteed")) {
                    hasSuperOfferCard = true;
                }
                if (t.equalsIgnoreCase("claim") || t.equals("claim now") || t.startsWith("claim") || t.contains("claim")) {
                    // Check if it's the 1,000 coins card (left half of screen)
                    if (item.rect.centerX() < getResources().getDisplayMetrics().widthPixels / 2) {
                        claimBtn = item;
                    }
                }
            }
            if (hasSuperOfferCard && claimBtn != null) {
                Log.i(TAG, "🎉 FOUND SUPER OFFER 1,000 COINS CLAIM BUTTON! Tapping at " + claimBtn.rect);
                tapNode(claimBtn);
                lastTapTime = now + 2500;
                currentStatus = "1,000 Coins Claimed!";
                return true;
            }

            // Check D: If we've returned to the Quiz screen and gems reset or active quiz is present:
            boolean hasActiveQuiz = false;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            for (NodeItem item : allNodes) {
                int cy = item.rect.centerY();
                if (cy >= (int)(screenH * 0.10) && cy <= (int)(screenH * 0.42)) {
                    String raw = item.text.trim();
                    if (mathPattern.matcher(raw).find() || numPattern.matcher(cleanNumText(raw)).matches()) {
                        hasActiveQuiz = true;
                        break;
                    }
                }
            }

            if (hasActiveQuiz || (liveGems == 0 && (now - offerStateStartTime > 5000))) {
                Log.i(TAG, "🎉 Super Offer Cycle Complete! Coins claimed, gems reset to 0/25. Resuming normal quiz solving!");
                offerState = OFFER_STATE_IDLE;
                liveGems = 0;
                hasAlerted25Gems = false;
                isCollecting25thGem = false;
                finalAdStarted = false;
                offerTargetPackage = "";
                appIsDuplicate = false;
                currentStatus = "Scanning...";
                return false; // Hand over to normal quiz solver
            }
        }

        // 2. ACTIVE USAGE PROGRESS PHASE
        // Triggered ONLY by the live progress screen (e.g. "more needed", "progress updates automatically", "app usage progress", or "open app" button)
        boolean isUsageProgressActive = false;
        NodeItem stuckRestartBtn = null;
        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();
            if (t.contains("app usage progress") || t.contains("progress updates automatically") ||
                t.contains("more needed") || (t.equals("open app") && item.rect.centerY() > 1000)) {
                isUsageProgressActive = true;
            }
            if (t.contains("restart offer") || t.contains("stuck? restart offer") || d.contains("restart offer")) {
                stuckRestartBtn = item;
            }
        }
        if (isUsageProgressActive) {
            if (offerState == OFFER_STATE_REWARD_CLAIM) {
                long waitElapsed = (now - offerStateStartTime) / 1000;
                if (waitElapsed > 15) {
                    Log.w(TAG, "App usage was not credited after 15s wait. Tapping 'Stuck? Restart Offer' to retry...");
                    if (offerTargetPackage != null && !offerTargetPackage.isEmpty()) {
                        uninstallApp(offerTargetPackage);
                        offerTargetPackage = "";
                    }
                    if (stuckRestartBtn != null) {
                        tapNode(stuckRestartBtn);
                    } else {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                    offerState = OFFER_STATE_IDLE;
                    lastTapTime = now + 1500;
                    return true;
                } else {
                    currentStatus = "Waiting for reward confirmation (" + waitElapsed + "s)...";
                    return true;
                }
            } else {
                Log.i(TAG, "🎉 APP VERIFIED BY QUIZ APP! 'App Usage Progress' active. (User Rule: Never tap Restart/Go Back). Launching offer app for 125s...");
                offerState = OFFER_STATE_APP_ACTIVE;
                appUsageStartTime = now;
                lastUsageGestureTime = now;
                offerAppLaunchAttempts = 0;
                currentStatus = "Launching App...";
                boolean launched = launchOfferApp();
                if (!launched) {
                    Log.w(TAG, "Target offer app failed to launch or crashed! Restarting offer...");
                    if (offerTargetPackage != null && !offerTargetPackage.isEmpty()) {
                        uninstallApp(offerTargetPackage);
                        offerTargetPackage = "";
                    }
                    if (stuckRestartBtn != null) {
                        tapNode(stuckRestartBtn);
                    }
                    offerState = OFFER_STATE_IDLE;
                    lastTapTime = now + 1500;
                    return true;
                }
                lastTapTime = now + 2000;
                return true;
            }
        }

        // 3. VERIFICATION & RETRY BUTTONS PHASE
        NodeItem verifyBtn = null;
        NodeItem restartOfferBtn = null;
        NodeItem goBackBtn = null;
        boolean isAppNotDetected = false;

        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();

            if (t.contains("verify installation") || t.equals("verify") || d.contains("verify installation")) {
                verifyBtn = item;
            }
            if (t.contains("app not detected") || d.contains("app not detected") || 
                t.contains("verification failed") || t.contains("duplicate") ||
                t.contains("already in your pending offers") ||
                t.contains("already on our phone") || t.contains("not recognized")) {
                isAppNotDetected = true;
            }
            if (t.equals("restart offer") || d.equals("restart offer") ||
                t.equals("try different app") || d.equals("try different app") ||
                (t.contains("try different app") && !t.contains("⚠️") && !t.contains("pending offer"))) {
                restartOfferBtn = item;
            }
            if (t.equals("go back") || d.equals("go back") || t.contains("go back")) {
                goBackBtn = item;
            }
        }

        // Case A: App Not Detected or Duplicate app confirmed failed (User Rule: Tap Restart / Try Different App / Go Back)
        if (isAppNotDetected || appIsDuplicate) {
            NodeItem retryBtn = (restartOfferBtn != null) ? restartOfferBtn : goBackBtn;
            if (retryBtn != null) {
                Log.w(TAG, "⚠️ Duplicate App / App Not Detected on Super Offer screen! Tapping " + retryBtn.text + " to retry with new app...");
                currentStatus = "Restarting Offer...";
                if (offerTargetPackage != null && !offerTargetPackage.isEmpty()) {
                    uninstallApp(offerTargetPackage);
                    offerTargetPackage = "";
                }
                tapNode(retryBtn);
                offerState = OFFER_STATE_IDLE;
                appIsDuplicate = false;
                lastTapTime = now + 1500;
                return true;
            }
        }

        // Case B: Verify button is present
        if (verifyBtn != null) {
            String newPkg = (offerTargetPackage != null && !offerTargetPackage.isEmpty()) 
                ? offerTargetPackage 
                : detectNewlyInstalledPackage(baselinePackages);

            if (newPkg == null || newPkg.isEmpty()) {
                newPkg = findMostRecentInstalledPackage();
            }

            if (newPkg != null && !newPkg.isEmpty()) {
                offerTargetPackage = newPkg;
                Log.i(TAG, "⚡ App ready/checked (" + offerTargetPackage + ")! Tapping 'Verify Installation' on Super Offer page at " + verifyBtn.rect);
                currentStatus = "Verifying Offer...";
                tapNode(verifyBtn);
                offerState = OFFER_STATE_VERIFYING;
                offerStateStartTime = now;
                lastTapTime = now + 2000;
                return true;
            } else {
                // Check if there is an Install or Download button to open Play Store
                NodeItem installBtn = null;
                for (NodeItem item : allNodes) {
                    if (isForbiddenElement(item)) continue;
                    String t = item.text.trim().toLowerCase();
                    String d = item.contentDesc.trim().toLowerCase();
                    if (t.equals("install") || t.equals("install now") || t.equals("download") || 
                        d.equals("install") || d.equals("download") || t.contains("install the app")) {
                        installBtn = item;
                        break;
                    }
                }
                if (installBtn != null && (now - lastTapTime > 3000)) {
                    Log.i(TAG, "Tapping Install button to open Play Store for Super Offer at " + installBtn.rect);
                    tapNode(installBtn);
                    offerState = OFFER_STATE_PLAYSTORE;
                    offerStateStartTime = now;
                    lastTapTime = now + 2500;
                    return true;
                }

                // If no app installed yet:
                // Wait at least 35s for the app to be installed from Play Store before giving up and retrying
                if (offerStateStartTime > 0 && (now - offerStateStartTime > 35000)) {
                    NodeItem backBtn = (goBackBtn != null) ? goBackBtn : restartOfferBtn;
                    if (backBtn != null) {
                        Log.w(TAG, "⚠️ No app installed after 35s! Tapping " + backBtn.text + " to retry and play offer ad...");
                        currentStatus = "Retrying Offer Ad...";
                        tapNode(backBtn);
                    } else {
                        Log.w(TAG, "⚠️ No app installed after 35s! Pressing Back to retry ad...");
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                    offerState = OFFER_STATE_IDLE;
                    lastTapTime = now + 2000;
                    return true;
                }

                currentStatus = "Waiting for app install...";
                return true;
            }
        }

        // 4. START OFFER PHASE (Works for both Milestone card & Detail page)
        NodeItem startOfferBtn = null;
        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();
            if (t.equals("start offer") || d.equals("start offer") || t.contains("start offer")) {
                startOfferBtn = item;
                break;
            }
        }

        if (startOfferBtn != null) {
            Log.i(TAG, "⚡ Super Offer 'Start Offer' button detected! Tapping at " + startOfferBtn.rect + " to play offer ad...");
            currentStatus = "Starting Offer Ad...";
            baselinePackages = snapshotInstalledPackages();
            tapNode(startOfferBtn);
            offerState = OFFER_STATE_AD_PLAYING;
            adStartTime = now;
            offerStateStartTime = now;
            lastTapTime = now + 2000;
            return true;
        }

        // 5. If on offer detail page and Start button is scrolled off screen, scroll down!
        boolean isOfferDetailPage = false;
        boolean hasLoadingButton = false;
        boolean hasBottomAction = false;
        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            if (t.contains("watch a video ad") || t.contains("download and install the advertised app")) {
                isOfferDetailPage = true;
            }
            if (t.contains("loading")) {
                hasLoadingButton = true;
            }
            if (item.rect.centerY() > 1000 && ((item.node != null && item.node.getClassName() != null && item.node.getClassName().toString().contains("Button")) || (item.text != null && item.text.trim().length() > 0))) {
                hasBottomAction = true;
            }
        }
        if (hasLoadingButton) {
            currentStatus = "Loading Ad...";
            return true;
        }
        if (isOfferDetailPage && startOfferBtn == null && verifyBtn == null && restartOfferBtn == null && !hasBottomAction) {
            Log.i(TAG, "On Offer Detail Page but action button not in view: Scrolling down...");
            swipeCoordinates(450, 1200, 450, 500, 400);
            lastTapTime = now + 1000;
            return true;
        }

        return false;
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
        isRunning = false;
        if (instance != null) {
            instance.loopHandler.removeCallbacks(instance.solveLoopRunnable);
        }
        instance = null;
        isServiceActive = false;
        Log.i(TAG, "onDestroy called, isUserRunning=" + isUserRunning);
    }
}

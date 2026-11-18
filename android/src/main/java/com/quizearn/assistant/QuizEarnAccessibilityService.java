package com.quizearn.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.ActivityManager;
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
import android.util.DisplayMetrics;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QuizEarnAccessibilityService extends AccessibilityService {

    public static final String TAG = "QuizEarnAssistant";

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

    // Super Offer State Machine
    public static final int OFFER_STATE_IDLE = 0;
    public static final int OFFER_STATE_MILESTONE = 1;
    public static final int OFFER_STATE_DETAIL_PAGE = 2;
    public static final int OFFER_STATE_AD_PLAYING = 3;
    public static final int OFFER_STATE_PLAYSTORE = 4;
    public static final int OFFER_STATE_INSTALLING = 5;
    public static final int OFFER_STATE_WAIT_AD_RETURN = 6;
    public static final int OFFER_STATE_VERIFYING = 7;
    public static final int OFFER_STATE_APP_ACTIVE = 8;
    public static final int OFFER_STATE_REWARD_CLAIM = 9;

    public static volatile int offerState = OFFER_STATE_IDLE;
    private static long offerStateStartTime = 0;
    private static String offerTargetPackage = "";
    private static long appUsageStartTime = 0;
    private static long lastUsageGestureTime = 0;
    private static boolean appIsDuplicate = false;
    private static List<String> baselinePackages = new ArrayList<>();
    private static int offerAppLaunchAttempts = 0;
    private static volatile boolean isAppNotDetectedToast = false;
    private static volatile long adReloadCooldownUntil = 0;
    private static int downloadedAppCrashCount = 0;
    private static volatile boolean isAppCrashStuck = false;
    private static long currentOfferStartTime = 0;
    private static int consecutiveVerifyFailures = 0;
    private static int verifyScrollAttempts = 0;

    public static volatile boolean isSuperOfferEnabled = false;
    public static volatile String preferredQuizPackage = "com.quiz.earn.money.game";
    public static volatile String lastActiveQuizPackage = "";

    private static final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    public static void setPreferredQuizPackage(Context context, String pkg) {
        if (pkg != null && isQuizAppPackage(pkg)) {
            preferredQuizPackage = pkg;
            if (context != null) {
                try {
                    context.getSharedPreferences("QuizEarnPrefs", Context.MODE_PRIVATE)
                        .edit().putString("preferred_quiz_pkg", pkg).apply();
                } catch (Exception ignored) {}
            }
        }
    }

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
    private long playStoreOpenSeenTime = 0;
    private int verifyAttempts = 0;
    private long waitingForInstallStartTime = 0;
    private String lastSolvedTarget = "";
    private long lastSolveTime = 0;
    private int externalAppBackCount = 0;

    private long collectGemScreenStartTime = 0;
    private int collectGemTapCount = 0;
    private int collectGemRestartCount = 0;
    private long lastSeenCollectionScreenTime = 0;
    private long questionWaitingStartTime = 0;
    private String lastWaitingQuestion = "";
    private long emptyNodesStartTime = 0;

    private final Pattern mathPattern = Pattern.compile("(\\d+)\\s*([\\+\\-\\–\\—\\*×xX]|÷|\\/)\\s*(\\d+)");
    private final Pattern mathSlashPattern = Pattern.compile("(\\d+)\\s*\\/\\s*(\\d+)\\s*(?:=|\\?)");
    private final Pattern numPattern = Pattern.compile("^\\d{3,6}$");
    private final Pattern percentProgressPattern = Pattern.compile("^\\d{1,3}%$|^\\d{1,3}%\\s*(of|downloaded|complete).*");

    private String cleanNumText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[\\s\\u00A0\\u200B\\r\\n]+", "").trim();
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
        offerState = OFFER_STATE_IDLE;
        offerTargetPackage = "";
        downloadedAppCrashCount = 0;
        isAppCrashStuck = false;
        offerAppLaunchAttempts = 0;
        appUsageStartTime = 0;
        appIsDuplicate = false;
        isAppNotDetectedToast = false;
        currentOfferStartTime = 0;
        consecutiveVerifyFailures = 0;
        verifyScrollAttempts = 0;
        currentStatus = "Scanning...";

        final QuizEarnAccessibilityService svc = instance;
        if (svc != null) {
            bgExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    baselinePackages = svc.snapshotInstalledPackages();
                }
            });
            svc.playStoreOpenSeenTime = 0;
            svc.verifyAttempts = 0;
            svc.waitingForInstallStartTime = 0;
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

    public static boolean isQuizAppPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return pkg.equals("com.quiz.earn.money.game") ||
               pkg.equals("com.rayole.cashmafia2") ||
               pkg.contains("cashmafia") ||
               pkg.contains("quiz.earn");
    }

    public static String getQuizAppPackage(Context ctx) {
        if (isQuizAppPackage(lastActiveQuizPackage)) {
            return lastActiveQuizPackage;
        }
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                if (isQuizAppPackage(preferredQuizPackage) && pm.getLaunchIntentForPackage(preferredQuizPackage) != null) {
                    return preferredQuizPackage;
                }
                List<PackageInfo> installed = pm.getInstalledPackages(0);
                for (PackageInfo pi : installed) {
                    if (pi.packageName != null && isQuizAppPackage(pi.packageName)) {
                        return pi.packageName;
                    }
                }
            } catch (Exception ignored) {}
        }
        return preferredQuizPackage;
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
        offerState = OFFER_STATE_IDLE;
        isAppNotDetectedToast = false;
        offerTargetPackage = "";
        downloadedAppCrashCount = 0;
        isAppCrashStuck = false;
        offerAppLaunchAttempts = 0;
        isCollecting25thGem = false;
        finalAdStarted = false;
        hasAlerted25Gems = false;
        is25GemsOnScreen = false;

        final Context ctx = (callerCtx != null) ? callerCtx : instance;
        if (ctx != null) {
            try {
                final Context appCtx = ctx.getApplicationContext();
                final String targetPkg = getQuizAppPackage(appCtx);

                // Move target app out of foreground BEFORE killing so killBackgroundProcesses can terminate it
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
                            Thread.sleep(300);
                        } catch (InterruptedException ignored) {}

                        boolean forceStopped = false;
                        // Try su -c force-stop first (works on BlueStacks 5 / rooted devices)
                        try {
                            Process suProc = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + targetPkg});
                            forceStopped = (suProc.waitFor() == 0);
                        } catch (Throwable ignored) {}

                        if (!forceStopped) {
                            try {
                                Process amProc = Runtime.getRuntime().exec(new String[]{"am", "force-stop", targetPkg});
                                forceStopped = (amProc.waitFor() == 0);
                            } catch (Throwable ignored) {}
                        }

                        // Non-root fallback: requires KILL_BACKGROUND_PROCESSES + app already in background
                        try {
                            ActivityManager am = (ActivityManager) appCtx.getSystemService(Context.ACTIVITY_SERVICE);
                            if (am != null) {
                                am.killBackgroundProcesses(targetPkg);
                            }
                        } catch (Throwable ignored) {}

                        try {
                            Thread.sleep(350);
                        } catch (InterruptedException ignored) {}

                        final boolean wasKilled = forceStopped;
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Intent intent = appCtx.getPackageManager().getLaunchIntentForPackage(targetPkg);
                                    if (intent != null) {
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                        appCtx.startActivity(intent);
                                        currentStatus = "App Restarted";
                                        Log.i(TAG, "Target app (" + targetPkg + ") relaunched (forceStopped=" + wasKilled + ").");
                                    }
                                } catch (Throwable ex) {
                                    Log.e(TAG, "Failed to relaunch target app", ex);
                                }
                            }
                        });
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart target app", e);
            }
        }
    }

    public static void bringQuizAppToFront() {
        Context ctx = instance;
        if (ctx != null) {
            try {
                String targetPkg = getQuizAppPackage(ctx);
                Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(targetPkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    ctx.startActivity(intent);
                    instance.lastProgressTime = System.currentTimeMillis();
                    instance.adStartTime = 0;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to bring target app to front", e);
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
        if (instance != null) {
            instance.playStoreOpenSeenTime = 0;
            instance.verifyAttempts = 0;
            instance.waitingForInstallStartTime = 0;
            instance.lastSolvedTarget = "";
            instance.lastSolveTime = 0;
            instance.collectGemScreenStartTime = 0;
            instance.collectGemTapCount = 0;
            instance.collectGemRestartCount = 0;
            instance.lastSeenCollectionScreenTime = 0;
            instance.questionWaitingStartTime = 0;
            instance.lastWaitingQuestion = "";
            instance.emptyNodesStartTime = 0;
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
            isSuperOfferEnabled = prefs.getBoolean("super_offer_enabled", false);
            preferredQuizPackage = prefs.getString("preferred_quiz_pkg", "com.quiz.earn.money.game");
        } catch (Exception e) {
            Log.e(TAG, "Error loading preferences", e);
        }
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                baselinePackages = snapshotInstalledPackages();
            }
        });
        if (isUserRunning) {
            startSolving();
        }
    }

    private static volatile String currentActivityName = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                CharSequence cls = event.getClassName();
                if (cls != null) {
                    currentActivityName = cls.toString();
                }
                CharSequence pkgEv = event.getPackageName();
                if (pkgEv != null && isQuizAppPackage(pkgEv.toString())) {
                    lastActiveQuizPackage = pkgEv.toString();
                }
            }
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                List<CharSequence> texts = event.getText();
                if (texts != null) {
                    for (CharSequence cs : texts) {
                        if (cs != null) {
                            String s = cs.toString().toLowerCase();
                            if (s.contains("not detected") || s.contains("verification failed") ||
                                s.contains("not recognized") || s.contains("please install") ||
                                s.contains("app not found")) {
                                isAppNotDetectedToast = true;
                            }
                        }
                    }
                }
            }
            if (!isRunning) return;
            long now = System.currentTimeMillis();
            if (now - lastTapTime > 260) {
                solveScreen();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Recovered in onAccessibilityEvent", t);
        }
    }

    private long lastOrientationFixTime = 0;

    public void restorePortraitOrientation() {
        long now = SystemClock.uptimeMillis();
        if (now - lastOrientationFixTime < 4000) return;
        lastOrientationFixTime = now;
        try {
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
                                if (wp.equals("com.quizearn.assistant") || wp.equals("com.android.systemui")) {
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
                    } else if (emptyElapsed % 6 == 3) {
                        tapCoordinates(screenW - dp(24), dp(32));
                        lastTapTime = now;
                    }
                }
                return;
            } else {
                emptyNodesStartTime = 0;
            }

            // Only enforce portrait when inside the Quiz/CashMafia app (never interrupt landscape video ads or Play Store)
            if (isQuizAppPackage(pkg) && screenW > screenH && Math.min(screenW, screenH) < dp(600)) {
                restorePortraitOrientation();
            }

            // 0. System ANR / Crash Dialog Dismissal
            boolean hasCrashDialog = false;
            NodeItem crashDismissBtn = null;
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.contains("keeps stopping") || d.contains("keeps stopping") ||
                    t.contains("has stopped") || d.contains("has stopped") ||
                    t.contains("isn't responding") || d.contains("isn't responding") ||
                    t.contains("repeatedly stopped") || d.contains("repeatedly stopped")) {
                    hasCrashDialog = true;
                }
                if (t.equals("close app") || d.equals("close app") || t.contains("close app") ||
                    t.equals("wait") || d.equals("wait") ||
                    (t.equals("ok") && hasCrashDialog)) {
                    crashDismissBtn = item;
                }
            }

            // Auto-grant runtime permissions for newly installed offer apps
            if (pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")) {
                for (NodeItem item : allNodes) {
                    String t = item.text.trim().toLowerCase();
                    String d = item.contentDesc.trim().toLowerCase();
                    if (t.contains("while using the app") || t.equals("allow") || t.equals("continue") ||
                        d.contains("while using the app") || d.equals("allow") || t.contains("only this time")) {
                        tapNode(item);
                        lastTapTime = now + 900;
                        return;
                    }
                }
            }

            if (crashDismissBtn != null) {
                tapNode(crashDismissBtn);
                lastTapTime = now + 800;
                boolean isOfferAppRelated = (offerState == OFFER_STATE_APP_ACTIVE ||
                    offerState == OFFER_STATE_INSTALLING ||
                    offerState == OFFER_STATE_VERIFYING ||
                    (offerTargetPackage != null && !offerTargetPackage.isEmpty()));
                if (isOfferAppRelated || hasCrashDialog) {
                    downloadedAppCrashCount++;
                    if (downloadedAppCrashCount >= 2 || offerAppLaunchAttempts >= 2) {
                        isAppCrashStuck = true;
                        offerAppLaunchAttempts = 2;
                        currentStatus = "App Crash -> Restarting Offer";
                        bringQuizAppToFront();
                        lastTapTime = now + 1500;
                    }
                }
                return;
            }

            // 0A. Network Error Recovery
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
            boolean isAssistantApp = pkg.equals("com.quizearn.assistant");
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

            // Play Store Handling (Flexible state check: works from Detail Page, Ad Playing, or PlayStore states)
            if (isPlayStore) {
                if (isSuperOfferEnabled && (
                    offerState == OFFER_STATE_DETAIL_PAGE ||
                    offerState == OFFER_STATE_AD_PLAYING ||
                    offerState == OFFER_STATE_PLAYSTORE ||
                    offerState == OFFER_STATE_INSTALLING ||
                    liveGems >= 25 || is25GemsOnScreen)) {
                    externalAppBackCount = 0;
                    handlePlayStoreScreen(allNodes, now, screenW, screenH);
                    return;
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastTapTime = now + 1500;
                    lastProgressTime = now;
                    return;
                }
            }

            // 2-Minute App Active Usage or Crash Recovery
            if (offerState == OFFER_STATE_APP_ACTIVE || isAppCrashStuck) {
                if (!isAppCrashStuck && !offerTargetPackage.isEmpty() && pkg.contains(offerTargetPackage)) {
                    externalAppBackCount = 0;
                    offerAppLaunchAttempts = 0;
                    handleActiveAppUsage(now, screenW, screenH);
                    return;
                } else if (isQuizApp) {
                    boolean isProgressScreen = false;
                    boolean hasStartOfferOnScreen = false;
                    NodeItem stuckRestartBtn = null;
                    NodeItem restartConfirmBtn = null;
                    for (NodeItem item : allNodes) {
                        String t = item.text.trim().toLowerCase();
                        String d = item.contentDesc.trim().toLowerCase();
                        if ((t.contains("start offer") || d.contains("start offer")) && !t.contains("restart") && !d.contains("restart")) {
                            hasStartOfferOnScreen = true;
                        }
                        if (t.contains("app usage progress") || t.contains("progress updates automatically") ||
                            t.contains("more needed") || (t.equals("open app") && item.rect.centerY() > (int)(screenH * 0.60f)) ||
                            t.contains("stuck? restart offer")) {
                            isProgressScreen = true;
                        }
                        if (t.contains("restart offer") || t.contains("stuck? restart offer") || t.contains("stuck?")) {
                            stuckRestartBtn = item;
                        }
                        if ((t.equals("restart") || t.equals("yes") || t.equals("confirm") || t.equals("ok")) &&
                            item.rect.centerY() > (int)(screenH * 0.35f) && item.rect.centerY() < (int)(screenH * 0.72f)) {
                            restartConfirmBtn = item;
                        }
                    }
                    if (hasStartOfferOnScreen) isProgressScreen = false;

                    if (restartConfirmBtn != null) {
                        tapNode(restartConfirmBtn);
                        offerTargetPackage = "";
                        offerState = OFFER_STATE_IDLE;
                        offerAppLaunchAttempts = 0;
                        downloadedAppCrashCount = 0;
                        isAppCrashStuck = false;
                        lastTapTime = now + 2000;
                        currentStatus = "Offer Restarted";
                        return;
                    }

                    if (isProgressScreen) {
                        if (!isAppCrashStuck && downloadedAppCrashCount < 3 && offerAppLaunchAttempts < 5) {
                            offerAppLaunchAttempts++;
                            if (launchOfferApp()) {
                                lastTapTime = now + 2500;
                                return;
                            }
                        }
                        if (downloadedAppCrashCount >= 2 || isAppCrashStuck || offerAppLaunchAttempts >= 5) {
                            currentStatus = "Tapping Stuck? Restart Offer...";
                            if (stuckRestartBtn != null && stuckRestartBtn.rect.centerY() < (int)(screenH * 0.875f)) {
                                tapNode(stuckRestartBtn);
                            } else {
                                swipeCoordinates(screenW / 2, (int)(screenH * 0.75f), screenW / 2, (int)(screenH * 0.35f), 350);
                                lastTapTime = now + 1200;
                                return;
                            }
                            offerTargetPackage = "";
                            offerState = OFFER_STATE_IDLE;
                            offerAppLaunchAttempts = 0;
                            downloadedAppCrashCount = 0;
                            isAppCrashStuck = false;
                            lastTapTime = now + 2500;
                            return;
                        }
                        lastTapTime = now + 2000;
                        return;
                    }
                } else if (isLauncher) {
                    bringQuizAppToFront();
                    lastTapTime = now + 1500;
                    return;
                }
            }

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

            // 1. Check active quiz elements
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

            // 2. Pre-scan Gem Collection Screen (takes precedence over accidental numeric/ratio matches)
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

            // 3. Super Offer Milestone Screen Detection
            boolean hasSuperOfferMilestone = false;
            if (!hasActiveQuiz && !hasCollectGem) {
                for (NodeItem item : allNodes) {
                    String t = item.text.trim().toLowerCase();
                    String d = item.contentDesc.trim().toLowerCase();
                    if (t.contains("unlock super offer") || t.contains("gems to unlock") || t.contains("watch an ad to collect")) {
                        continue;
                    }
                    if (t.contains("start offer") || d.contains("start offer") ||
                        t.contains("gems collected") || d.contains("gems collected") ||
                        t.contains("how to complete") || d.contains("how to complete") ||
                        (t.contains("super offer") && !t.contains("gems to unlock")) ||
                        (d.contains("super offer") && !d.contains("gems to unlock")) ||
                        t.contains("guaranteed 1,000 coins") || t.contains("guaranteed 1000 coins") ||
                        t.contains("app usage progress") || d.contains("app usage progress") ||
                        t.contains("watch a video ad") || d.contains("watch a video ad") ||
                        t.contains("install the app") || d.contains("install the app") ||
                        t.contains("verify installation") || d.contains("verify installation") ||
                        t.equals("verify") || d.equals("verify") ||
                        t.contains("app not detected") || d.contains("app not detected") ||
                        t.contains("verification failed") || t.contains("try different app") ||
                        t.contains("try a different app") ||
                        t.contains("view my offers") || d.contains("view my offers") ||
                        t.contains("verification successful") || d.contains("verification successful") ||
                        t.contains("+100 coins") || d.contains("+100 coins") ||
                        t.contains("restart offer") || d.contains("restart offer")) {
                        hasSuperOfferMilestone = true;
                        break;
                    }
                }
            }

            boolean hasHomeScreen = false;
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.contains("hi, ") || d.contains("hi, ") ||
                    t.contains("refer & earn") || t.contains("task partners") ||
                    t.contains("weekly leaderboard") || t.contains("daily win")) {
                    hasHomeScreen = true;
                    break;
                }
            }

            // Detect Ad State & Countdowns
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
                    id.contains("mys-creative") || id.contains("mys-content") ||
                    isWebView || t.contains("moloco") || t.contains("liftoff"))) {
                    isAd = true;
                }
            }

            if (hasActiveQuiz || hasCollectGem || hasHomeScreen || hasSuperOfferMilestone) {
                isAd = false;
                hasAdCountdown = false;
                adStartTime = 0;
            }

            // Dismiss In-App Store Bottom Sheet Overlay ONLY when not actively installing/opening a Super Offer app
            boolean isSuperOfferInstallPhase = isSuperOfferEnabled && (
                offerState == OFFER_STATE_AD_PLAYING ||
                offerState == OFFER_STATE_PLAYSTORE ||
                offerState == OFFER_STATE_INSTALLING ||
                offerState == OFFER_STATE_VERIFYING
            );
            if (!isSuperOfferInstallPhase) {
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

            // During Super Offer Ad, tap Install CTA BEFORE clicking Skip/Close so we don't close the ad before opening Play Store
            if (isAd && isSuperOfferEnabled && offerState == OFFER_STATE_AD_PLAYING && !appIsDuplicate) {
                for (NodeItem item : allNodes) {
                    String t = item.text.trim().toLowerCase();
                    String d = item.contentDesc.trim().toLowerCase();
                    String id = item.resId.trim().toLowerCase();
                    if (t.contains("advertised app") || t.contains("pending offer") ||
                        t.contains("installing apps other") || t.length() > 30) {
                        continue;
                    }
                    if (t.equals("install") || t.equals("install now") || d.equals("install") ||
                        t.equals("open") || d.equals("open") || t.equals("get") || d.equals("get") ||
                        t.equals("download") || t.equals("download now") ||
                        (t.startsWith("download") && t.length() <= 15) ||
                        (t.startsWith("install") && t.length() <= 15) ||
                        t.contains("skip to google play") || d.contains("skip to google play") ||
                        t.contains("learn more") || d.contains("learn more") ||
                        t.contains("skip to play store") || d.contains("skip to play store") ||
                        id.contains("cta") || id.contains("action_button") || id.contains("install_button")) {
                        currentStatus = "Opening Play Store...";
                        tapNode(item);
                        lastTapTime = now + 1500;
                        return;
                    }
                }
            }

            // If we're still installing the offer app in the background, hold off closing the ad until install finishes or 45s elapses
            if (isAd && isSuperOfferEnabled && offerState == OFFER_STATE_INSTALLING) {
                String installedNow = detectNewlyInstalledPackage(baselinePackages);
                if (installedNow == null || installedNow.isEmpty()) {
                    long installHoldSec = (now - offerStateStartTime) / 1000;
                    if (installHoldSec < 45) {
                        currentStatus = "Waiting for install (" + installHoldSec + "s)...";
                        return;
                    }
                } else {
                    offerTargetPackage = installedNow;
                }
            }

            // Official Skip / Reward Granted / Close Button Checks
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

            // Ad Watching & Progressive Ad Dismissal (Density-aware across all screen sizes)
            if (isAd) {
                lastProgressTime = now;
                collectGemScreenStartTime = 0;
                collectGemTapCount = 0;
                collectGemRestartCount = 0;
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
                        if (!hasCloseIndicator) continue;
                        if ((cx > (int)(screenW * 0.75f) || cx < (int)(screenW * 0.25f)) && cy < (int)(screenH * 0.22f)) {
                            tapNode(item);
                            lastTapTime = now;
                            return;
                        }
                    }
                }

                if (hasAdCountdown && adElapsed < 35) return;

                long minAdElapsed = hasAdCountdown ? 35 : 26;
                if (adElapsed >= minAdElapsed && (now - lastTapTime > 1200)) {
                    if (adElapsed >= 38) {
                        bringQuizAppToFront();
                        lastTapTime = now + 3000;
                        lastProgressTime = now;
                        adStartTime = 0;
                        return;
                    }

                    int step = (int)((adElapsed - minAdElapsed) / 2);
                    if (offerState == OFFER_STATE_AD_PLAYING && step % 4 == 0) {
                        int targetX = Math.max(dp(32), screenW - dp(72));
                        int targetY = Math.max(dp(40), (int)(screenH * 0.055f));
                        tapCoordinates(targetX, targetY);
                        lastTapTime = now;
                        return;
                    } else {
                        int dismissStep = step % 4;
                        if (dismissStep == 0) {
                            tapCoordinates(screenW - dp(24), dp(32));
                        } else if (dismissStep == 1) {
                            tapCoordinates(screenW - dp(32), dp(30));
                        } else if (dismissStep == 2) {
                            tapCoordinates(screenW - dp(16), dp(28));
                        } else {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                        }
                        lastTapTime = now;
                        return;
                    }
                }
                return;
            } else {
                if (adStartTime > 0 && (hasActiveQuiz || hasHomeScreen || hasCollectGem || (hasSuperOfferMilestone && offerState != OFFER_STATE_AD_PLAYING))) {
                    lastProgressTime = now;
                    adStartTime = 0;
                }
            }

            if (isQuizApp && adStartTime == 0 && !hasHomeScreen && liveGems < 25 && offerState == OFFER_STATE_IDLE && lastProgressTime > 0 && now - lastProgressTime > 75000) {
                currentStatus = "Stall: Restarting App...";
                restartQuizApp();
                lastProgressTime = now;
                lastTapTime = now + 3500;
                return;
            }

            // Detect Live Gems
            int detectedGems = detectLiveGemsCount(allNodes, screenW, screenH);
            if (!hasActiveQuiz && !hasCollectGem && hasSuperOfferMilestone && detectedGems < 25) {
                detectedGems = 25;
            }
            if (detectedGems >= 0) liveGems = detectedGems;

            is25GemsOnScreen = false;
            if (!hasActiveQuiz && !hasCollectGem) {
                if (detectedGems >= 25) {
                    is25GemsOnScreen = true;
                } else {
                    for (NodeItem item : allNodes) {
                        String t = item.text.toLowerCase().trim();
                        String d = item.contentDesc.toLowerCase().trim();
                        if (t.contains("25/25") || d.contains("25/25") || t.contains("25 / 25") ||
                            t.contains("gems collected") || t.contains("start offer") ||
                            t.contains("claim 1000 coins") || t.contains("collect 1000 coins") ||
                            t.contains("super offer unlocked") || t.contains("super task unlocked")) {
                            is25GemsOnScreen = true;
                            break;
                        }
                    }
                }
            }

            boolean finalAdCompleted = (isCollecting25thGem && finalAdStarted && !isAd && (now - lastTapTime > 1500));
            boolean isOfferScreen = (!hasActiveQuiz && !hasCollectGem) && (is25GemsOnScreen || hasSuperOfferMilestone || (offerState != OFFER_STATE_IDLE) || finalAdCompleted);

            // Daily Rewards / Non-Home Tab Escape
            boolean isDailyRewardsScreen = false;
            NodeItem homeTabBtn = null;
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.contains("daily rewards") || d.contains("daily rewards") ||
                    t.contains("daily missions") || t.contains("daily tasks") ||
                    t.contains("events") || t.contains("giveaways") ||
                    t.contains("videos") || t.contains("community") ||
                    t.contains("wallet") || t.contains("profile")) {
                    isDailyRewardsScreen = true;
                }
                if (t.equals("home") || d.equals("home")) homeTabBtn = item;
            }
            if (isDailyRewardsScreen && !hasHomeScreen && !isAd && !isOfferScreen && !hasActiveQuiz) {
                if (homeTabBtn != null) tapNode(homeTabBtn);
                else performGlobalAction(GLOBAL_ACTION_BACK);
                lastTapTime = now + 1500;
                lastProgressTime = now;
                return;
            }

            // Home Screen Handling
            boolean isHomeScreen = hasHomeScreen;
            NodeItem homeSuperOfferClaimBtn = null;
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
                if (t.equals("claim") || t.equals("claim >") || d.equals("claim") || t.startsWith("claim")) {
                    if (item.rect.centerX() < screenW / 2) homeSuperOfferClaimBtn = item;
                }
                if (t.equals("start quiz") || t.equals("start math quiz") || t.contains("start quiz") ||
                    t.equals("play quiz") || d.contains("start quiz") || d.contains("start math")) {
                    startQuizBtn = item;
                }
            }
            if (homeSuperOfferClaimBtn == null) {
                for (NodeItem item : allNodes) {
                    String t = item.text.trim().toLowerCase();
                    if ((t.equals("1,000") || t.equals("1000") || t.equals("super offer")) &&
                        item.rect.centerX() < screenW / 2 && item.rect.centerY() < screenH * 0.70f) {
                        homeSuperOfferClaimBtn = item;
                        break;
                    }
                }
            }

            if (isOfferScreen || is25GemsOnScreen || hasSuperOfferMilestone) isHomeScreen = false;

            if (rewardPopupBtn != null) {
                tapNode(rewardPopupBtn);
                lastTapTime = now + 1000;
                lastProgressTime = now;
                currentStatus = "Reward Collected!";
                liveGems = 0;
                is25GemsOnScreen = false;
                hasAlerted25Gems = false;
                offerState = OFFER_STATE_IDLE;
                return;
            }

            if (isHomeScreen) {
                if (SystemClock.uptimeMillis() < adReloadCooldownUntil) {
                    long remainingSec = Math.max(1, (adReloadCooldownUntil - SystemClock.uptimeMillis()) / 1000);
                    currentStatus = "Waiting " + remainingSec + "s for ad preload...";
                    return;
                }
                if (homeSuperOfferClaimBtn != null && (liveGems >= 25 || is25GemsOnScreen)) {
                    if (isSuperOfferEnabled) {
                        tapNode(homeSuperOfferClaimBtn);
                        lastTapTime = now + 1500;
                        lastProgressTime = now;
                        currentStatus = "Entering Super Offer...";
                        return;
                    } else {
                        if (!hasAlerted25Gems) {
                            hasAlerted25Gems = true;
                            play25GemsMilestoneAlert();
                        }
                        currentStatus = "25💎 Ready (Waiting)";
                        return;
                    }
                }
                if (startQuizBtn != null) {
                    tapNode(startQuizBtn);
                    lastTapTime = now + 1200;
                    lastProgressTime = now;
                    currentStatus = "Starting Quiz Cycle...";
                    return;
                } else if (homeSuperOfferClaimBtn != null && liveGems < 25) {
                    tapNode(homeSuperOfferClaimBtn);
                    lastTapTime = now + 1500;
                    lastProgressTime = now;
                    currentStatus = "Starting Quiz Cycle...";
                    return;
                }
                return;
            }

            // Super Offer Automation
            if (!isAd && isQuizApp && isOfferScreen) {
                if (!isSuperOfferEnabled) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastTapTime = now + 1500;
                    lastProgressTime = now;
                    return;
                }
                boolean isCompletedOrRewardScreen = false;
                for (NodeItem item : allNodes) {
                    String t = item.text.toLowerCase().trim();
                    if (t.contains("view my offers") || t.contains("+100 coins") ||
                        t.contains("verification successful") || t.contains("approved") ||
                        t.contains("initial install reward")) {
                        isCompletedOrRewardScreen = true;
                        break;
                    }
                }
                if (!isCompletedOrRewardScreen && offerState == OFFER_STATE_IDLE && liveGems < 25 && !is25GemsOnScreen && !finalAdCompleted) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastTapTime = now + 1500;
                    lastProgressTime = now;
                    return;
                }
                if (isCollecting25thGem && finalAdCompleted) {
                    isCollecting25thGem = false;
                    finalAdStarted = false;
                    liveGems = 25;
                    if (!hasAlerted25Gems) {
                        hasAlerted25Gems = true;
                        play25GemsMilestoneAlert();
                    }
                }
                if (handleSuperOfferScreen(allNodes, now, screenW, screenH)) {
                    return;
                }
            }

            // Collect Gem Screen
            if (hasCollectGem || (!is25GemsOnScreen && offerState == OFFER_STATE_IDLE)) {
                if (hasCollectGem && liveGems < 25 && offerState != OFFER_STATE_IDLE &&
                    offerState != OFFER_STATE_VERIFYING && offerState != OFFER_STATE_REWARD_CLAIM) {
                    offerState = OFFER_STATE_IDLE;
                }
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
                            // Prefer the outer clickable button container or wider hit target over small inner text/icon
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
                        adReloadCooldownUntil = SystemClock.uptimeMillis() + 8000;
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
                } else if (now - lastSeenCollectionScreenTime > 2500) {
                    collectGemScreenStartTime = 0;
                    collectGemTapCount = 0;
                    collectGemRestartCount = 0;
                }

                for (NodeItem item : allNodes) {
                    if (isForbiddenElement(item, screenW, screenH)) continue;
                    String t = item.text.trim().toLowerCase();
                    String d = item.contentDesc.trim().toLowerCase();
                    if (t.equals("collect") ||
                        ((t.startsWith("claim") || d.startsWith("claim")) && !t.contains("daily") && !t.contains("bonus") && !t.contains("claimed"))) {
                        tapNode(item);
                        lastTapTime = now + 800;
                        lastProgressTime = now;
                        lastSolvedTarget = "";
                        lastSolveTime = 0;
                        currentStatus = "Collecting Gem...";
                        adStartTime = 0;
                        return;
                    }
                }
            }

            // 5. RESPONSIVE MATH & NUMBER CAPTCHA SOLVER (Fixed single-pass Matcher.find())
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
            // Safely recycle collected AccessibilityNodeInfo Binder handles to avoid leaks
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
            if (item.text.trim().toLowerCase().contains("gems collected") && i > 0) {
                String prevDigits = allNodes.get(i - 1).text.replaceAll("[^0-9]", "").trim();
                if (prevDigits.length() >= 1 && prevDigits.length() <= 2) {
                    try { return Integer.parseInt(prevDigits); } catch (Exception ignored) {}
                }
            }
        }
        return -1;
    }

    private void collectAllNodes(AccessibilityNodeInfo node, List<NodeItem> list, List<AccessibilityNodeInfo> recycleBag, int depth) {
        if (node == null || depth > 25 || list.size() > 450) return;

        CharSequence pkgSeq = node.getPackageName();
        String nodePkg = (pkgSeq != null) ? pkgSeq.toString() : "";
        if (nodePkg.equals("com.quizearn.assistant") ||
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
        tapNodeOffset(item, 0.5f, 0.5f);
    }

    private void tapNodeOffset(final NodeItem item, float xFraction, float yFraction) {
        if (item == null || item.rect == null) return;
        int cx = item.rect.left + Math.round(item.rect.width() * xFraction);
        int cy = item.rect.top + Math.round(item.rect.height() * yFraction);
        if (item.rect.width() > 6) cx = Math.max(item.rect.left + 3, Math.min(item.rect.right - 3, cx));
        if (item.rect.height() > 6) cy = Math.max(item.rect.top + 3, Math.min(item.rect.bottom - 3, cy));

        boolean gestureDispatched = tapCoordinates(cx, cy);

        // Native Accessibility click fallback ONLY if gesture dispatch was unavailable/rejected
        if (!gestureDispatched) {
            try {
                if (item.node != null) {
                    if (item.clickable) {
                        item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    } else {
                        AccessibilityNodeInfo p = item.node.getParent();
                        int hops = 0;
                        while (p != null && hops < 3) {
                            boolean pClick = p.isClickable();
                            if (pClick) {
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

        // Density/Screen-relative ad trap check (works from 480x854 to 1440x3200)
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
        if (t.equals("community") || t.equals("tasks") || t.equals("wallet") || t.equals("profile") ||
            d.equals("community") || d.equals("tasks") || d.equals("wallet") || d.equals("profile")) {
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
        if (baseline == null || baseline.isEmpty()) return null;
        try {
            PackageManager pm = getPackageManager();
            List<PackageInfo> installed = pm.getInstalledPackages(0);
            for (PackageInfo pi : installed) {
                if (!baseline.contains(pi.packageName)) {
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
        } catch (Exception ignored) {}
        return null;
    }

    private void finishInstallationAndReturnToQuiz(long now) {
        currentStatus = "App Installed! Returning...";
        offerState = OFFER_STATE_VERIFYING;
        offerStateStartTime = now;
        bringQuizAppToFront();
        lastTapTime = now + 2000;
    }

    private void handlePlayStoreScreen(List<NodeItem> allNodes, long now, int screenW, int screenH) {
        if (now - lastTapTime < 800) return;

        if (baselinePackages == null || baselinePackages.isEmpty()) {
            baselinePackages = snapshotInstalledPackages();
        }

        if (offerState != OFFER_STATE_PLAYSTORE && offerState != OFFER_STATE_INSTALLING) {
            offerState = OFFER_STATE_PLAYSTORE;
            offerStateStartTime = now;
            playStoreOpenSeenTime = 0;
            Log.i(TAG, "Entered Google Play Store for Super Offer app.");
        }

        lastProgressTime = now;

        // 0. Handle Play Store blockers: Wi-Fi Only download dialog, Play Protect "Install anyway", Account setup "Skip"
        boolean hasWifiPrompt = false;
        boolean hasPlayProtectPrompt = false;
        boolean hasAccountPrompt = false;
        NodeItem dialogConfirmBtn = null;

        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();
            if (t.contains("wi-fi only") || t.contains("download now") || t.contains("over any network") || t.contains("wait for wi-fi")) {
                hasWifiPrompt = true;
            }
            if (t.contains("play protect") || t.contains("harmful") || t.contains("unrecognized")) {
                hasPlayProtectPrompt = true;
            }
            if (t.contains("complete account setup") || t.contains("add payment")) {
                hasAccountPrompt = true;
            }
            if (t.equals("proceed") || t.equals("download now") || t.equals("ok") ||
                t.equals("install anyway") || t.equals("skip") || t.equals("not now") ||
                d.equals("proceed") || d.equals("install anyway") || d.equals("skip")) {
                if (item.rect.centerY() > (int)(screenH * 0.25f)) {
                    dialogConfirmBtn = item;
                }
            }
        }
        if ((hasWifiPrompt || hasPlayProtectPrompt || hasAccountPrompt) && dialogConfirmBtn != null) {
            Log.i(TAG, "Confirming Play Store prompt via '" + dialogConfirmBtn.text + "'");
            tapNode(dialogConfirmBtn);
            lastTapTime = now + 1200;
            return;
        }

        NodeItem installButton = null;
        NodeItem openButton = null;
        NodeItem closeSheetButton = null;
        boolean isPlayStoreActiveInstall = false;
        String activeStatusText = "";

        int suggestionDividerY = Integer.MAX_VALUE;
        for (NodeItem item : allNodes) {
            int cy = item.rect.centerY();
            if (cy < (int)(screenH * 0.35f)) continue;
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();
            if (t.contains("available on more devices") || d.contains("available on more devices") ||
                t.contains("suggested for you") || t.contains("similar apps") ||
                t.contains("related to this app") || t.contains("recommended for you") ||
                t.contains("more apps like this") || t.contains("rate this app") ||
                t.contains("about this app")) {
                if (cy < suggestionDividerY) suggestionDividerY = cy;
            }
        }

        int heroCutoffY = (suggestionDividerY != Integer.MAX_VALUE) ? suggestionDividerY : (int)(screenH * 0.86f);
        boolean isTargetAppAlreadyInstalled = false;

        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();

            if (d.contains("close sheet") || d.equals("close") || t.equals("close")) {
                closeSheetButton = item;
            }

            if (item.rect.centerY() < heroCutoffY) {
                if (t.equals("installed") || d.equals("installed")) {
                    isTargetAppAlreadyInstalled = true;
                }
                if (t.equals("open") || d.equals("open") ||
                    ((t.equals("play") || d.equals("play")) && !d.contains("video") && !d.contains("trailer"))) {
                    openButton = item;
                }
                if (t.equals("install") || d.equals("install") ||
                    t.startsWith("install on") || t.equals("update") ||
                    (t.startsWith("install") && t.length() <= 18 && !t.contains("installed"))) {
                    if (installButton == null || t.equals("install") || d.equals("install")) {
                        installButton = item;
                    }
                }
                // Strict download progress check (never match marketing "100% Free"!)
                boolean isPercentProgress = percentProgressPattern.matcher(t).matches() || percentProgressPattern.matcher(d).matches();
                if (t.contains("installing") || d.contains("installing") ||
                    t.contains("verifying") || d.contains("verifying") ||
                    t.contains("downloading") || d.contains("downloading") ||
                    t.contains("waiting for download") || t.equals("pending") ||
                    t.equals("cancel") || d.equals("cancel") || isPercentProgress) {
                    isPlayStoreActiveInstall = true;
                    activeStatusText = !t.isEmpty() ? item.text.trim() : item.contentDesc.trim();
                }
            }
        }

        if (openButton != null) {
            installButton = null;
            isPlayStoreActiveInstall = false;
        }

        String newPkg = detectNewlyInstalledPackage(baselinePackages);
        if (newPkg != null && !newPkg.isEmpty()) offerTargetPackage = newPkg;

        if (offerState == OFFER_STATE_PLAYSTORE && (now - offerStateStartTime < 2500)) {
            currentStatus = "Play Store: Loading...";
            return;
        }

        if (isPlayStoreActiveInstall) {
            offerState = OFFER_STATE_INSTALLING;
            playStoreOpenSeenTime = 0;
            long installElapsed = (now - offerStateStartTime) / 1000;
            currentStatus = "Play Store: " + (activeStatusText.isEmpty() ? "Installing..." : activeStatusText) + " (" + installElapsed + "s)";
            return;
        }

        boolean isDuplicate = (isTargetAppAlreadyInstalled || (openButton != null && installButton == null));
        if (isDuplicate && offerState == OFFER_STATE_PLAYSTORE && (now - offerStateStartTime >= 2500)) {
            currentStatus = "Duplicate App -> Restarting...";
            appIsDuplicate = true;
            offerState = OFFER_STATE_WAIT_AD_RETURN;
            offerStateStartTime = now;
            if (closeSheetButton != null) tapNode(closeSheetButton);
            else performGlobalAction(GLOBAL_ACTION_BACK);
            lastTapTime = now + 1500;
            return;
        }

        if (installButton != null && offerState != OFFER_STATE_INSTALLING) {
            currentStatus = "Installing App...";
            // Tap left 35% of wide split Install buttons to avoid hitting "Install on more devices" dropdown arrow
            if (installButton.rect.width() > dp(160)) {
                tapNodeOffset(installButton, 0.35f, 0.5f);
            } else {
                tapNode(installButton);
            }
            offerState = OFFER_STATE_INSTALLING;
            offerStateStartTime = now;
            playStoreOpenSeenTime = 0;
            lastTapTime = now + 2000;
            return;
        }

        boolean hasReviewSheet = false;
        NodeItem reviewCloseBtn = null;
        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();
            if (t.contains("ratings and reviews") || t.contains("rating and reviews") || t.contains("rate this app")) {
                hasReviewSheet = true;
            }
            if (d.equals("close sheet") || (d.equals("close") && item.rect.top < screenH * 0.45f) ||
                (t.equalsIgnoreCase("x") && item.rect.top < screenH * 0.45f)) {
                reviewCloseBtn = item;
            }
        }
        if (hasReviewSheet && installButton == null && openButton == null && !isPlayStoreActiveInstall) {
            if (reviewCloseBtn != null) tapNode(reviewCloseBtn);
            else performGlobalAction(GLOBAL_ACTION_BACK);
            lastTapTime = now + 1200;
            return;
        }

        if (installButton == null && !isPlayStoreActiveInstall && openButton == null && offerState == OFFER_STATE_PLAYSTORE) {
            long waitInPlayStore = (now - offerStateStartTime) / 1000;
            if (waitInPlayStore >= 12) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                offerState = OFFER_STATE_IDLE;
                lastTapTime = now + 1500;
                return;
            } else if (waitInPlayStore >= 5 && closeSheetButton != null) {
                tapNode(closeSheetButton);
                lastTapTime = now + 1200;
                return;
            }
        }

        if (offerState == OFFER_STATE_INSTALLING) {
            long installElapsed = (now - offerStateStartTime) / 1000;
            if (openButton != null) {
                if (newPkg == null || newPkg.isEmpty()) {
                    newPkg = detectNewlyInstalledPackage(baselinePackages);
                    if (newPkg != null && !newPkg.isEmpty()) offerTargetPackage = newPkg;
                }
                if (newPkg == null || newPkg.isEmpty()) {
                    newPkg = findMostRecentInstalledPackage();
                    if (newPkg != null && !newPkg.isEmpty()) offerTargetPackage = newPkg;
                }

                if (playStoreOpenSeenTime == 0) {
                    playStoreOpenSeenTime = now;
                    currentStatus = "Finalizing Install...";
                    return;
                }
                boolean pkgConfirmedInOs = (newPkg != null && !newPkg.isEmpty());
                long sinceOpenMs = now - playStoreOpenSeenTime;
                // Wait up to 6s after Open appears if PackageManager has not yet registered the new package
                if (!pkgConfirmedInOs && sinceOpenMs < 6000) {
                    currentStatus = "Finalizing Package Registration (" + (sinceOpenMs / 1000) + "s)...";
                    return;
                }
                if (sinceOpenMs < 2500) {
                    currentStatus = "Finalizing Install...";
                    return;
                }
                playStoreOpenSeenTime = 0;
                finishInstallationAndReturnToQuiz(now);
                return;
            }

            currentStatus = "Installing (" + installElapsed + "s)...";
            if (installElapsed > 180) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                offerState = OFFER_STATE_VERIFYING;
                lastTapTime = now + 1500;
            }
        }
    }

    private void handleActiveAppUsage(long now, int screenW, int screenH) {
        if (appUsageStartTime == 0) appUsageStartTime = now;
        long elapsedSec = (now - appUsageStartTime) / 1000;
        currentStatus = "Active Usage (" + elapsedSec + "s / 125s)";

        if (now - lastUsageGestureTime > 15000) {
            swipeCoordinates(screenW / 2, (int)(screenH * 0.65f), screenW / 2, (int)(screenH * 0.35f), 250);
            lastUsageGestureTime = now;
        }
        if (elapsedSec >= 125) {
            currentStatus = "2min Done! Claiming Offer...";
            offerState = OFFER_STATE_REWARD_CLAIM;
            offerStateStartTime = now;
            bringQuizAppToFront();
            lastTapTime = now + 2500;
        }
    }

    private boolean isPackageInstalledRecently(String packageName, long sinceTime) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(packageName, 0);
            if (pi == null) return false;
            long cutoff = (sinceTime > 0) ? (sinceTime - 10000L) : (System.currentTimeMillis() - 180000L);
            return Math.max(pi.firstInstallTime, pi.lastUpdateTime) >= cutoff;
        } catch (Exception e) {
            return false;
        }
    }

    private String findMostRecentInstalledPackage(long minInstallTime) {
        try {
            PackageManager pm = getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);
            long newestTime = 0;
            String newestPkg = null;
            for (PackageInfo p : pkgs) {
                if (isQuizAppPackage(p.packageName) ||
                    p.packageName.equals("com.quizearn.assistant") ||
                    p.packageName.startsWith("com.android.") ||
                    p.packageName.startsWith("com.google.android.") ||
                    p.packageName.startsWith("com.bluestacks.")) {
                    continue;
                }
                if (pm.getLaunchIntentForPackage(p.packageName) == null) continue;
                long installTime = Math.max(p.firstInstallTime, p.lastUpdateTime);
                if (installTime >= minInstallTime && installTime > newestTime) {
                    newestTime = installTime;
                    newestPkg = p.packageName;
                }
            }
            return newestPkg != null ? newestPkg : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String findMostRecentInstalledPackage() {
        String pkg = findMostRecentInstalledPackage(System.currentTimeMillis() - (12 * 3600 * 1000L));
        return (pkg != null && !pkg.isEmpty()) ? pkg : findMostRecentInstalledPackage(0);
    }

    private boolean launchOfferApp() {
        if (offerTargetPackage == null || offerTargetPackage.isEmpty()) {
            offerTargetPackage = findMostRecentInstalledPackage();
        }
        if (offerTargetPackage == null || offerTargetPackage.isEmpty()) {
            offerTargetPackage = detectNewlyInstalledPackage(baselinePackages);
        }
        if (offerTargetPackage == null || offerTargetPackage.isEmpty()) {
            offerTargetPackage = findMostRecentInstalledPackage(0);
        }

        if (offerTargetPackage != null && !offerTargetPackage.isEmpty()) {
            boolean launched = false;
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(offerTargetPackage);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    launched = true;
                }
            } catch (Exception ignored) {}

            final String pkgToLaunch = offerTargetPackage;
            bgExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Runtime.getRuntime().exec(new String[]{"monkey", "-p", pkgToLaunch, "-c", "android.intent.category.LAUNCHER", "1"});
                    } catch (Throwable ignored) {}
                }
            });
            return launched || !pkgToLaunch.isEmpty();
        }
        return false;
    }

    private boolean handleSuperOfferScreen(List<NodeItem> allNodes, long now, int screenW, int screenH) {
        if (!isSuperOfferEnabled) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            lastTapTime = now + 1500;
            return true;
        }
        if (now - lastTapTime < 250) return false;

        int safeBottomCutoffY = (int)(screenH * 0.875f);

        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();
            if (t.contains("view my offers") || d.contains("view my offers")) {
                offerState = OFFER_STATE_REWARD_CLAIM;
                tapNode(item);
                lastTapTime = now + 1500;
                currentStatus = "Opening My Offers...";
                return true;
            }
        }

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
            offerState = OFFER_STATE_REWARD_CLAIM;
            performGlobalAction(GLOBAL_ACTION_BACK);
            lastTapTime = now + 1500;
            currentStatus = "Reward Approved! Returning...";
            return true;
        }

        boolean isMyOffersPage = false;
        for (NodeItem item : allNodes) {
            String t = item.text.toLowerCase();
            if (t.contains("my super offers") || t.contains("active pending offers") || t.contains("pending offers")) {
                isMyOffersPage = true;
                break;
            }
        }
        if (isMyOffersPage) {
            NodeItem homeBtn = null;
            for (NodeItem item : allNodes) {
                if (item.text.equalsIgnoreCase("home")) {
                    homeBtn = item;
                    break;
                }
            }
            offerState = OFFER_STATE_REWARD_CLAIM;
            if (homeBtn != null) tapNode(homeBtn);
            else performGlobalAction(GLOBAL_ACTION_BACK);
            lastTapTime = now + 1500;
            currentStatus = "Navigating to Home...";
            return true;
        }

        if (offerState == OFFER_STATE_REWARD_CLAIM) {
            boolean hasSuperOfferCard = false;
            NodeItem claimBtn = null;
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase();
                if (t.contains("super offer") || t.contains("guaranteed")) hasSuperOfferCard = true;
                if (t.equals("claim") || t.equals("claim now") || t.startsWith("claim")) {
                    if (item.rect.centerX() < screenW / 2) claimBtn = item;
                }
            }
            if (hasSuperOfferCard && claimBtn != null) {
                tapNode(claimBtn);
                lastTapTime = now + 2500;
                currentStatus = "1,000 Coins Claimed!";
                return true;
            }

            boolean hasActiveQuiz = false;
            for (NodeItem item : allNodes) {
                int cy = item.rect.centerY();
                if (cy >= (int)(screenH * 0.10f) && cy <= (int)(screenH * 0.42f)) {
                    String raw = item.text.trim();
                    if (mathPattern.matcher(raw).find() || numPattern.matcher(cleanNumText(raw)).matches()) {
                        hasActiveQuiz = true;
                        break;
                    }
                }
            }
            if (hasActiveQuiz || ((liveGems >= 0 && liveGems < 25) && (now - offerStateStartTime > 5000))) {
                offerState = OFFER_STATE_IDLE;
                if (liveGems == 25) liveGems = 0;
                hasAlerted25Gems = false;
                isCollecting25thGem = false;
                finalAdStarted = false;
                offerTargetPackage = "";
                appIsDuplicate = false;
                currentStatus = "Scanning...";
                return false;
            }
        }

        boolean isUsageProgressActive = false;
        boolean hasStartOfferOnScreen = false;
        boolean hasRestartConfirmPrompt = false;
        NodeItem stuckRestartBtn = null;
        NodeItem restartConfirmBtn = null;
        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();
            if ((t.contains("start offer") || d.contains("start offer")) && !t.contains("restart") && !d.contains("restart")) {
                hasStartOfferOnScreen = true;
            }
            if (t.contains("restart this offer") || t.contains("sure you want to restart") || t.contains("reset offer")) {
                hasRestartConfirmPrompt = true;
            }
            if (t.contains("app usage progress") || t.contains("progress updates automatically") ||
                t.contains("more needed") || (t.equals("open app") && item.rect.centerY() > (int)(screenH * 0.60f)) ||
                t.contains("stuck? restart offer")) {
                isUsageProgressActive = true;
            }
            if (t.contains("restart offer") || t.contains("stuck? restart offer") || t.contains("stuck?")) {
                stuckRestartBtn = item;
            }
            if ((t.equals("restart") || t.equals("yes") || t.equals("yes, restart") || t.equals("confirm") ||
                (t.equals("ok") && hasRestartConfirmPrompt)) &&
                item.rect.centerY() > (int)(screenH * 0.35f) && item.rect.centerY() < (int)(screenH * 0.72f)) {
                restartConfirmBtn = item;
            }
        }
        if (hasStartOfferOnScreen) isUsageProgressActive = false;

        if (restartConfirmBtn != null) {
            tapNode(restartConfirmBtn);
            offerTargetPackage = "";
            offerState = OFFER_STATE_IDLE;
            offerAppLaunchAttempts = 0;
            downloadedAppCrashCount = 0;
            isAppCrashStuck = false;
            verifyAttempts = 0;
            verifyScrollAttempts = 0;
            consecutiveVerifyFailures = 0;
            lastTapTime = now + 2000;
            currentStatus = "Offer Restarted";
            return true;
        }

        if (isUsageProgressActive) {
            if (isAppCrashStuck || downloadedAppCrashCount >= 2 || offerAppLaunchAttempts >= 5) {
                currentStatus = "Tapping Stuck? Restart Offer...";
                if (stuckRestartBtn != null && stuckRestartBtn.rect.centerY() < safeBottomCutoffY) {
                    tapNode(stuckRestartBtn);
                } else {
                    swipeCoordinates(screenW / 2, (int)(screenH * 0.75f), screenW / 2, (int)(screenH * 0.35f), 350);
                    lastTapTime = now + 1200;
                    return true;
                }
                offerTargetPackage = "";
                downloadedAppCrashCount = 0;
                offerAppLaunchAttempts = 0;
                isAppCrashStuck = false;
                offerState = OFFER_STATE_IDLE;
                lastTapTime = now + 2500;
                return true;
            }

            if (offerState == OFFER_STATE_REWARD_CLAIM) {
                long waitElapsed = (now - offerStateStartTime) / 1000;
                if (waitElapsed < 30) {
                    currentStatus = "Waiting for reward sync (" + waitElapsed + "s)...";
                    lastTapTime = now + 1500;
                    return true;
                }
                long totalUsageSec = (now - appUsageStartTime) / 1000;
                if (totalUsageSec < 240) {
                    offerState = OFFER_STATE_APP_ACTIVE;
                    currentStatus = "Resuming App Usage...";
                    launchOfferApp();
                    lastTapTime = now + 2000;
                    return true;
                }
                currentStatus = "Waiting for offer credit...";
                lastTapTime = now + 3000;
                return true;
            } else {
                offerState = OFFER_STATE_APP_ACTIVE;
                consecutiveVerifyFailures = 0;
                appUsageStartTime = now;
                lastUsageGestureTime = now;
                offerAppLaunchAttempts = 0;
                currentStatus = "Launching App...";
                if (!launchOfferApp()) {
                    offerAppLaunchAttempts++;
                    if (offerAppLaunchAttempts >= 5) isAppCrashStuck = true;
                }
                lastTapTime = now + 2000;
                return true;
            }
        }

        boolean hasWatchTutorial = false;
        NodeItem startOfferBtn = null;
        NodeItem verifyBtn = null;
        NodeItem restartOfferBtn = null;
        NodeItem goBackBtn = null;
        boolean isAppNotDetected = false;

        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            String d = item.contentDesc.trim().toLowerCase();

            if (t.contains("watch tutorial") || t.contains("how to complete") || t.contains("warning: installing")) {
                hasWatchTutorial = true;
            }
            if (t.contains("verify installation") || t.equals("verify") || d.contains("verify installation")) {
                verifyBtn = item;
            }
            if (t.contains("app not detected") || d.contains("app not detected") ||
                t.contains("verification failed") || t.contains("duplicate") ||
                t.contains("already in your pending offers") ||
                t.contains("already on our phone") || t.contains("not recognized") ||
                t.contains("please install") || t.contains("app not found")) {
                isAppNotDetected = true;
            }
            if ((t.equals("restart offer") || d.equals("restart offer") ||
                t.equals("try different app") || d.equals("try different app") ||
                t.contains("try a different app") || d.contains("try a different app")) &&
                !t.contains("stuck") && !d.contains("stuck") &&
                !t.contains("pending offer") && !t.contains("skip and try") &&
                !t.contains("already have a pending") && !t.contains("shown in the ad") &&
                item.clickable) {
                restartOfferBtn = item;
            }
            if (t.equals("go back") || d.equals("go back") || t.contains("go back")) {
                goBackBtn = item;
            }
            if ((t.equals("start offer") || d.equals("start offer") || ((t.contains("start offer") || d.contains("start offer")) && !t.contains("restart") && !d.contains("restart")))) {
                startOfferBtn = item;
            }
        }

        String newlyInstalledPkg = null;
        if (!offerTargetPackage.isEmpty() && isPackageInstalledRecently(offerTargetPackage, currentOfferStartTime > 0 ? currentOfferStartTime : offerStateStartTime)) {
            newlyInstalledPkg = offerTargetPackage;
        } else {
            newlyInstalledPkg = detectNewlyInstalledPackage(baselinePackages);
            if ((newlyInstalledPkg == null || newlyInstalledPkg.isEmpty()) && currentOfferStartTime > 0) {
                newlyInstalledPkg = findMostRecentInstalledPackage(currentOfferStartTime - 10000L);
            }
        }

        if (startOfferBtn != null) {
            if (liveGems < 25 && !is25GemsOnScreen) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastTapTime = now + 1500;
                lastProgressTime = now;
                return true;
            }
            if (!hasWatchTutorial && startOfferBtn.rect.centerY() < (int)(screenH * 0.68f)) {
                currentStatus = "Opening Offer Details...";
                tapNode(startOfferBtn);
                offerState = OFFER_STATE_DETAIL_PAGE;
                lastTapTime = now + 2000;
                lastProgressTime = now;
                return true;
            } else {
                currentStatus = "Starting Offer Ad...";
                bgExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        baselinePackages = snapshotInstalledPackages();
                    }
                });
                offerTargetPackage = "";
                currentOfferStartTime = System.currentTimeMillis();
                waitingForInstallStartTime = 0;
                verifyAttempts = 0;
                verifyScrollAttempts = 0;
                isAppNotDetectedToast = false;
                tapNode(startOfferBtn);
                offerState = OFFER_STATE_AD_PLAYING;
                adStartTime = now;
                offerStateStartTime = now;
                lastTapTime = now + 2500;
                lastProgressTime = now;
                return true;
            }
        }

        if (verifyBtn != null) {
            boolean isInstalledInOs = (newlyInstalledPkg != null && !newlyInstalledPkg.isEmpty());
            boolean verificationFailed = isAppNotDetected || isAppNotDetectedToast || appIsDuplicate;
            if (offerState == OFFER_STATE_VERIFYING && (now - offerStateStartTime > 6500)) {
                verificationFailed = true;
            }

            // Hold Verify briefly if we returned early from ad/Play Store while the app is still finishing installation in OS
            if (!verificationFailed && !isInstalledInOs && offerState != OFFER_STATE_VERIFYING) {
                if (waitingForInstallStartTime == 0) waitingForInstallStartTime = now;
                long waitInstallMs = now - waitingForInstallStartTime;
                if (waitInstallMs < 25000L) {
                    currentStatus = "Waiting for install to finish (" + (waitInstallMs / 1000) + "s)...";
                    lastProgressTime = now;
                    return true;
                }
                // 25s elapsed with no new package installed in OS -> trigger App Not Detected recovery cleanly instead of blind Verify loop
                verificationFailed = true;
            }

            if (verificationFailed) {
                waitingForInstallStartTime = 0;
                if (isInstalledInOs && verifyAttempts < 2 && !appIsDuplicate) {
                    verifyAttempts++;
                    currentStatus = "Retrying Verification (" + verifyAttempts + "/2)...";
                    lastTapTime = now + 3000;
                    lastProgressTime = now;
                    isAppNotDetectedToast = false;
                    tapNode(verifyBtn);
                    offerState = OFFER_STATE_VERIFYING;
                    offerStateStartTime = now;
                    return true;
                }

                // Prefer Restart Offer / Try Different App FIRST so we break out of "App Not Detected" instead of restarting back to the same Verify button!
                if (restartOfferBtn != null && restartOfferBtn.rect.centerY() < safeBottomCutoffY) {
                    currentStatus = "Not Detected -> Restarting Offer...";
                    tapNode(restartOfferBtn);
                    offerTargetPackage = "";
                    offerState = OFFER_STATE_DETAIL_PAGE;
                    isAppNotDetectedToast = false;
                    appIsDuplicate = false;
                    verifyAttempts = 0;
                    verifyScrollAttempts = 0;
                    consecutiveVerifyFailures = 0;
                    lastTapTime = now + 2500;
                    return true;
                }

                if (verifyScrollAttempts < 2 && !isAppNotDetected) {
                    verifyScrollAttempts++;
                    currentStatus = "Finding Restart Offer...";
                    swipeCoordinates(screenW / 2, (int)(screenH * 0.72f), screenW / 2, (int)(screenH * 0.30f), 350);
                    lastTapTime = now + 1200;
                    return true;
                }

                consecutiveVerifyFailures++;
                currentStatus = "App Not Detected (" + consecutiveVerifyFailures + ") -> Backing out...";
                if (goBackBtn != null) tapNode(goBackBtn);
                else performGlobalAction(GLOBAL_ACTION_BACK);

                offerTargetPackage = "";
                offerState = OFFER_STATE_IDLE;
                isAppNotDetectedToast = false;
                appIsDuplicate = false;
                verifyAttempts = 0;
                verifyScrollAttempts = 0;
                adReloadCooldownUntil = SystemClock.uptimeMillis() + 8000;

                // Only force-restart app once; if repeated 3+ times without Restart Offer button, cool down at Home to break infinite loop
                if (consecutiveVerifyFailures <= 1) {
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            restartQuizApp();
                        }
                    }, 1200);
                } else {
                    if (consecutiveVerifyFailures >= 3) {
                        adReloadCooldownUntil = SystemClock.uptimeMillis() + 20000;
                    }
                    performGlobalAction(GLOBAL_ACTION_HOME);
                }
                lastTapTime = now + 3500;
                return true;
            }

            if (offerState != OFFER_STATE_VERIFYING && (now - lastTapTime > 2000)) {
                waitingForInstallStartTime = 0;
                verifyScrollAttempts = 0;
                if (newlyInstalledPkg != null && !newlyInstalledPkg.isEmpty()) {
                    offerTargetPackage = newlyInstalledPkg;
                }
                currentStatus = "Verifying Installation...";
                tapNode(verifyBtn);
                offerState = OFFER_STATE_VERIFYING;
                offerStateStartTime = now;
                lastTapTime = now + 2500;
                return true;
            }

            if (offerState == OFFER_STATE_VERIFYING) {
                long elapsed = (now - offerStateStartTime) / 1000;
                currentStatus = "Verifying (" + elapsed + "s)...";
                return true;
            }
        }

        if (restartOfferBtn != null && !isUsageProgressActive && restartOfferBtn.rect.centerY() < safeBottomCutoffY) {
            tapNode(restartOfferBtn);
            offerTargetPackage = "";
            downloadedAppCrashCount = 0;
            isAppCrashStuck = false;
            offerAppLaunchAttempts = 0;
            verifyAttempts = 0;
            verifyScrollAttempts = 0;
            consecutiveVerifyFailures = 0;
            offerState = OFFER_STATE_DETAIL_PAGE;
            lastTapTime = now + 2000;
            return true;
        }

        if (isAppNotDetected || isAppNotDetectedToast || appIsDuplicate) {
            boolean isInstalledInOs = (newlyInstalledPkg != null && !newlyInstalledPkg.isEmpty());
            if (isInstalledInOs && verifyBtn != null && verifyAttempts < 2 && !appIsDuplicate) {
                verifyAttempts++;
                currentStatus = "Retrying Verification (" + verifyAttempts + "/2)...";
                lastTapTime = now + 3500;
                lastProgressTime = now;
                tapNode(verifyBtn);
                return true;
            }
            if (restartOfferBtn != null && restartOfferBtn.rect.centerY() < safeBottomCutoffY) {
                currentStatus = "Not Detected -> Restarting Offer...";
                tapNode(restartOfferBtn);
                offerTargetPackage = "";
                offerState = OFFER_STATE_DETAIL_PAGE;
                isAppNotDetectedToast = false;
                appIsDuplicate = false;
                verifyAttempts = 0;
                verifyScrollAttempts = 0;
                consecutiveVerifyFailures = 0;
                lastTapTime = now + 2500;
                return true;
            }
            consecutiveVerifyFailures++;
            currentStatus = "App Not Detected -> Go Back...";
            if (goBackBtn != null) tapNode(goBackBtn);
            else tapCoordinates((int)(screenW * 0.50f), (int)(screenH * 0.82f));

            if (consecutiveVerifyFailures <= 1) {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        restartQuizApp();
                    }
                }, 1200);
            }

            offerTargetPackage = "";
            offerState = OFFER_STATE_IDLE;
            isAppNotDetectedToast = false;
            appIsDuplicate = false;
            verifyAttempts = 0;
            verifyScrollAttempts = 0;
            adReloadCooldownUntil = SystemClock.uptimeMillis() + 8000;
            lastTapTime = now + 3500;
            return true;
        }

        boolean isOfferDetailPage = false;
        boolean hasLoadingButton = false;
        boolean hasBottomAction = false;
        for (NodeItem item : allNodes) {
            String t = item.text.trim().toLowerCase();
            if (t.contains("watch a video ad") || t.contains("download and install the advertised app") || t.contains("gems collected!")) {
                isOfferDetailPage = true;
            }
            if (t.contains("loading")) hasLoadingButton = true;
            if (item.rect.centerY() > (int)(screenH * 0.62f) && (item.className.contains("Button") || !item.text.trim().isEmpty())) {
                hasBottomAction = true;
            }
        }

        if (offerState == OFFER_STATE_AD_PLAYING && (now - offerStateStartTime > 8000)) {
            currentStatus = "Ad stalled -> Restarting App";
            restartQuizApp();
            offerState = OFFER_STATE_IDLE;
            offerTargetPackage = "";
            lastTapTime = now + 4000;
            return true;
        }

        if (hasLoadingButton) {
            if (offerStateStartTime == 0) offerStateStartTime = now;
            long elapsedLoading = now - offerStateStartTime;
            if (elapsedLoading > 12000) {
                currentStatus = "Ad stalled -> Restarting App";
                restartQuizApp();
                offerState = OFFER_STATE_IDLE;
                offerTargetPackage = "";
                lastTapTime = now + 3500;
                return true;
            }
            currentStatus = "Loading Ad (" + (elapsedLoading / 1000) + "s)...";
            return true;
        }

        if (isOfferDetailPage && startOfferBtn == null && verifyBtn == null && restartOfferBtn == null && !hasBottomAction) {
            swipeCoordinates(screenW / 2, (int)(screenH * 0.75f), screenW / 2, (int)(screenH * 0.30f), 400);
            lastTapTime = now + 1000;
            return true;
        }

        return false;
    }

    private void play25GemsMilestoneAlert() {
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
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
                    Uri alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                    if (alertUri == null) alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
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
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 300);
                    Thread.sleep(350);
                    tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
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
    }
}
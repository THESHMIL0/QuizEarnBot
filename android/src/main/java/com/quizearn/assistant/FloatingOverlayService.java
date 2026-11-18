package com.quizearn.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingOverlayService extends Service {

    public static final String TAG = "QuizEarnAssistant";

    private WindowManager windowManager;
    private FrameLayout floatView;
    private WindowManager.LayoutParams params;

    private boolean isExpanded = false;
    private LinearLayout ballView;
    private LinearLayout menuView;

    private TextView ballIcon;
    private TextView ballGemBadge;

    private TextView menuGemText;
    private TextView menuStatusText;
    private Button btnToggle;
    private Button btnOfferToggle;
    private Button btnRestart;
    private Button btnMinimize;
    private Button btnClose;
    private Button btnMinBottom;

    private int initialX = 0;
    private int initialY = 0;
    private float initialTouchX = 0f;
    private float initialTouchY = 0f;
    private boolean isDragMoved = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateUiState();
            uiHandler.postDelayed(this, isExpanded ? 300 : 600);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            Log.i(TAG, "FloatingOverlayService received action: " + act);
            if ("com.quizearn.assistant.START".equals(act)) {
                if (QuizEarnAccessibilityService.currentStatus.contains("25 GEMS")) {
                    QuizEarnAccessibilityService.solvedCount = 0;
                }
                QuizEarnAccessibilityService.startSolving();
                updateUiState();
            } else if ("com.quizearn.assistant.STOP".equals(act)) {
                QuizEarnAccessibilityService.stopSolving();
                updateUiState();
            } else if ("com.quizearn.assistant.AUTO_OFFER_ON".equals(act)) {
                QuizEarnAccessibilityService.setSuperOfferEnabled(this, true);
                updateUiState();
            } else if ("com.quizearn.assistant.AUTO_OFFER_OFF".equals(act)) {
                QuizEarnAccessibilityService.setSuperOfferEnabled(this, false);
                updateUiState();
            } else if ("com.quizearn.assistant.RESTART_APP".equals(act)) {
                QuizEarnAccessibilityService.restartQuizApp(this);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed: keeping Accessibility state safe while overlay persists.");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            SharedPreferences prefs = getSharedPreferences("QuizEarnPrefs", Context.MODE_PRIVATE);
            QuizEarnAccessibilityService.isSuperOfferEnabled = prefs.getBoolean("super_offer_enabled", false);
        } catch (Exception ignored) {}

        startForegroundServiceNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission missing! Stopping FloatingOverlayService cleanly.");
            stopSelf();
            return;
        }

        createFloatingWidget();
        uiHandler.post(refreshRunnable);
    }

    private void startForegroundServiceNotification() {
        String channelId = "quiz_math_overlay_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "Quiz Math Overlay",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, channelId)
            : new Notification.Builder(this);

        builder.setContentTitle("Quiz Math Assistant")
            .setContentText("Floating HUD is active.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        Notification notification = builder.build();

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1001, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error starting foreground notification", t);
            try {
                startForeground(1001, notification);
            } catch (Throwable ignored) {}
        }
    }

    private int getMenuWidthPx() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.min(dp(268), Math.max(dp(210), (int) (dm.widthPixels * 0.78f)));
    }

    private void clampPositionToScreen() {
        if (params == null || floatView == null || windowManager == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        int viewW = isExpanded ? getMenuWidthPx() : dp(56);
        int viewH = isExpanded ? Math.max(dp(260), floatView.getHeight()) : dp(56);

        int minX = dp(4);
        int maxX = Math.max(minX, screenW - viewW - dp(4));
        int minY = dp(24);
        int maxY = Math.max(minY, screenH - viewH - dp(24));

        params.x = Math.max(minX, Math.min(maxX, params.x));
        params.y = Math.max(minY, Math.min(maxY, params.y));
    }

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        if (floatView != null) {
            try {
                windowManager.removeViewImmediate(floatView);
            } catch (Exception ignored) {}
            floatView = null;
        }

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        // NOTE: Do NOT force screenOrientation on overlay LayoutParams—allows smooth landscape ads & tablets!
        DisplayMetrics dm = getResources().getDisplayMetrics();
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = Math.max(dp(48), (int) (dm.heightPixels * 0.18f));

        floatView = new FrameLayout(this);

        buildBallView();
        buildMenuView();

        floatView.addView(ballView);
        floatView.addView(menuView);

        isExpanded = false;
        ballView.setVisibility(View.VISIBLE);
        menuView.setVisibility(View.GONE);

        try {
            windowManager.addView(floatView, params);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to attach floating window", t);
            stopSelf();
            return;
        }
        updateUiState();
    }

    private void buildBallView() {
        ballView = new LinearLayout(this);
        ballView.setOrientation(LinearLayout.VERTICAL);
        ballView.setGravity(Gravity.CENTER);
        int ballSize = dp(56);
        ballView.setLayoutParams(new FrameLayout.LayoutParams(ballSize, ballSize));

        GradientDrawable ballBg = new GradientDrawable();
        ballBg.setShape(GradientDrawable.OVAL);
        ballBg.setColor(Color.parseColor("#090D16"));
        ballBg.setStroke(dp(2), Color.parseColor("#10B981"));
        ballView.setBackground(ballBg);

        ballIcon = new TextView(this);
        ballIcon.setText("⚡");
        ballIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        ballIcon.setTextColor(Color.parseColor("#10B981"));
        ballIcon.setGravity(Gravity.CENTER);

        ballGemBadge = new TextView(this);
        ballGemBadge.setText("💎 0");
        ballGemBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        ballGemBadge.setTypeface(Typeface.DEFAULT_BOLD);
        ballGemBadge.setTextColor(Color.WHITE);
        ballGemBadge.setGravity(Gravity.CENTER);

        ballView.addView(ballIcon);
        ballView.addView(ballGemBadge);

        ballView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (params == null || windowManager == null || floatView == null) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragMoved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                            isDragMoved = true;
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            clampPositionToScreen();
                            try {
                                windowManager.updateViewLayout(floatView, params);
                            } catch (Exception ignored) {}
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragMoved) {
                            expandMenu();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void buildMenuView() {
        menuView = new LinearLayout(this);
        menuView.setOrientation(LinearLayout.VERTICAL);
        menuView.setPadding(dp(12), dp(12), dp(12), dp(12));
        menuView.setLayoutParams(new FrameLayout.LayoutParams(getMenuWidthPx(), FrameLayout.LayoutParams.WRAP_CONTENT));

        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setShape(GradientDrawable.RECTANGLE);
        menuBg.setColor(Color.parseColor("#0A0E17"));
        menuBg.setStroke(dp(2), Color.parseColor("#10B981"));
        menuBg.setCornerRadius(dp(16));
        menuView.setBackground(menuBg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("⚡ QUIZ EARN BOT");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setSingleLine(true);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        btnMinimize = new Button(this);
        btnMinimize.setText("─");
        btnMinimize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnMinimize.setTypeface(Typeface.DEFAULT_BOLD);
        btnMinimize.setTextColor(Color.parseColor("#94A3B8"));
        btnMinimize.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable minBg = new GradientDrawable();
        minBg.setColor(Color.parseColor("#1E293B"));
        minBg.setCornerRadius(dp(8));
        btnMinimize.setBackground(minBg);
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(dp(34), dp(32));
        minLp.setMargins(0, 0, dp(6), 0);
        btnMinimize.setLayoutParams(minLp);
        btnMinimize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapseMenu();
            }
        });

        btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btnClose.setTextColor(Color.parseColor("#EF4444"));
        btnClose.setPadding(0, 0, 0, 0);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.parseColor("#1E293B"));
        closeBg.setCornerRadius(dp(8));
        btnClose.setBackground(closeBg);
        btnClose.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuizEarnAccessibilityService.stopSolving();
                stopSelf();
            }
        });

        header.addView(tvTitle);
        header.addView(btnMinimize);
        header.addView(btnClose);

        header.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (params == null || windowManager == null || floatView == null) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        clampPositionToScreen();
                        try {
                            windowManager.updateViewLayout(floatView, params);
                        } catch (Exception ignored) {}
                        return true;
                }
                return false;
            }
        });

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1E293B"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(8), 0, dp(8));
        divider.setLayoutParams(divLp);

        LinearLayout statsCard = new LinearLayout(this);
        statsCard.setOrientation(LinearLayout.VERTICAL);
        statsCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable statsBg = new GradientDrawable();
        statsBg.setColor(Color.parseColor("#030712"));
        statsBg.setCornerRadius(dp(10));
        statsCard.setBackground(statsBg);

        menuGemText = new TextView(this);
        menuGemText.setText("💎 GEMS: 0 / 25");
        menuGemText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        menuGemText.setTypeface(Typeface.DEFAULT_BOLD);
        menuGemText.setTextColor(Color.parseColor("#38BDF8"));

        menuStatusText = new TextView(this);
        menuStatusText.setText("● STANDBY");
        menuStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        menuStatusText.setTextColor(Color.parseColor("#9CA3AF"));
        menuStatusText.setPadding(0, dp(3), 0, 0);

        statsCard.addView(menuGemText);
        statsCard.addView(menuStatusText);

        btnToggle = new Button(this);
        btnToggle.setText("▶ START BOT");
        btnToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btnToggle.setTypeface(Typeface.DEFAULT_BOLD);
        btnToggle.setTextColor(Color.BLACK);
        btnToggle.setMinimumHeight(dp(40));
        btnToggle.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        toggleLp.setMargins(0, dp(10), 0, dp(8));
        btnToggle.setLayoutParams(toggleLp);

        GradientDrawable toggleBg = new GradientDrawable();
        toggleBg.setColor(Color.parseColor("#10B981"));
        toggleBg.setCornerRadius(dp(10));
        btnToggle.setBackground(toggleBg);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (QuizEarnAccessibilityService.isRunning) {
                    QuizEarnAccessibilityService.stopSolving();
                    updateUiState();
                } else {
                    if (QuizEarnAccessibilityService.currentStatus.contains("25 GEMS")) {
                        QuizEarnAccessibilityService.currentStatus = "Scanning...";
                    }
                    QuizEarnAccessibilityService.startSolving();
                    updateUiState();
                    collapseMenu();
                }
            }
        });

        btnOfferToggle = new Button(this);
        btnOfferToggle.setMinimumHeight(dp(38));
        btnOfferToggle.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams offerLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        offerLp.setMargins(0, 0, 0, dp(8));
        btnOfferToggle.setLayoutParams(offerLp);
        updateOfferToggleBtn();

        btnOfferToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newState = !QuizEarnAccessibilityService.isSuperOfferEnabled;
                QuizEarnAccessibilityService.setSuperOfferEnabled(FloatingOverlayService.this, newState);
                updateOfferToggleBtn();
            }
        });

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);

        btnRestart = new Button(this);
        btnRestart.setText("⟳ Restart");
        btnRestart.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnRestart.setTypeface(Typeface.DEFAULT_BOLD);
        btnRestart.setTextColor(Color.parseColor("#38BDF8"));
        btnRestart.setMinimumHeight(dp(36));
        btnRestart.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable rstBg = new GradientDrawable();
        rstBg.setColor(Color.parseColor("#1E293B"));
        rstBg.setCornerRadius(dp(10));
        btnRestart.setBackground(rstBg);
        LinearLayout.LayoutParams rstLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        rstLp.setMargins(0, 0, dp(6), 0);
        btnRestart.setLayoutParams(rstLp);

        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnRestart.setText("✓ Restarting");
                btnRestart.setTextColor(Color.parseColor("#10B981"));
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            btnRestart.setText("⟳ Restart");
                            btnRestart.setTextColor(Color.parseColor("#38BDF8"));
                        } catch (Exception ignored) {}
                    }
                }, 1500);
                QuizEarnAccessibilityService.restartQuizApp(FloatingOverlayService.this);
            }
        });

        btnMinBottom = new Button(this);
        btnMinBottom.setText("▼ Minimize");
        btnMinBottom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnMinBottom.setTypeface(Typeface.DEFAULT_BOLD);
        btnMinBottom.setTextColor(Color.parseColor("#94A3B8"));
        btnMinBottom.setMinimumHeight(dp(36));
        btnMinBottom.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable minBtmBg = new GradientDrawable();
        minBtmBg.setColor(Color.parseColor("#1E293B"));
        minBtmBg.setCornerRadius(dp(10));
        btnMinBottom.setBackground(minBtmBg);
        btnMinBottom.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        btnMinBottom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapseMenu();
            }
        });

        bottomRow.addView(btnRestart);
        bottomRow.addView(btnMinBottom);

        menuView.addView(header);
        menuView.addView(divider);
        menuView.addView(statsCard);
        menuView.addView(btnToggle);
        menuView.addView(btnOfferToggle);
        menuView.addView(bottomRow);
    }

    public void expandMenu() {
        isExpanded = true;
        if (menuView != null) {
            menuView.setLayoutParams(new FrameLayout.LayoutParams(getMenuWidthPx(), FrameLayout.LayoutParams.WRAP_CONTENT));
            menuView.setVisibility(View.VISIBLE);
        }
        if (ballView != null) ballView.setVisibility(View.GONE);
        clampPositionToScreen();
        if (windowManager != null && floatView != null) {
            try {
                windowManager.updateViewLayout(floatView, params);
            } catch (Exception ignored) {}
        }
    }

    public void collapseMenu() {
        isExpanded = false;
        if (menuView != null) menuView.setVisibility(View.GONE);
        if (ballView != null) ballView.setVisibility(View.VISIBLE);
        clampPositionToScreen();
        if (windowManager != null && floatView != null) {
            try {
                windowManager.updateViewLayout(floatView, params);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (menuView != null && isExpanded) {
            menuView.setLayoutParams(new FrameLayout.LayoutParams(getMenuWidthPx(), FrameLayout.LayoutParams.WRAP_CONTENT));
        }
        clampPositionToScreen();
        if (windowManager != null && floatView != null && params != null) {
            try {
                windowManager.updateViewLayout(floatView, params);
            } catch (Exception ignored) {}
        }
    }

    private void updateUiState() {
        boolean running = QuizEarnAccessibilityService.isRunning;
        boolean serviceOk = QuizEarnAccessibilityService.isServiceActive;
        int count = QuizEarnAccessibilityService.solvedCount;
        int gems = QuizEarnAccessibilityService.liveGems;
        String status = QuizEarnAccessibilityService.currentStatus;

        String gemDisplay = (gems >= 0) ? (gems + "/25") : ("#" + count);

        if (ballGemBadge != null && ballIcon != null) {
            ballGemBadge.setText("💎 " + gemDisplay);
            if (running) {
                ballGemBadge.setTextColor(Color.parseColor("#10B981"));
                ballIcon.setTextColor(Color.parseColor("#10B981"));
            } else {
                ballGemBadge.setTextColor(Color.parseColor("#94A3B8"));
                ballIcon.setTextColor(Color.parseColor("#F59E0B"));
            }
        }

        if (menuGemText != null) {
            menuGemText.setText("💎 GEMS: " + gemDisplay);
        }

        if (menuStatusText != null && btnToggle != null) {
            if (!serviceOk) {
                menuStatusText.setText("● ACCESSIBILITY SERVICE OFF");
                menuStatusText.setTextColor(Color.parseColor("#EF4444"));
                btnToggle.setEnabled(false);
                btnToggle.setText("ENABLE ACCESSIBILITY");
            } else if (status.contains("25 GEMS") && gems >= 25) {
                menuStatusText.setText("🎉 25/25 GEMS! CLAIM REWARD");
                menuStatusText.setTextColor(Color.parseColor("#F59E0B"));
                btnToggle.setText("▶ START BOT");
                btnToggle.setEnabled(true);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(Color.parseColor("#10B981"));
                btnBg.setCornerRadius(dp(10));
                btnToggle.setBackground(btnBg);
                btnToggle.setTextColor(Color.BLACK);
            } else if (running) {
                menuStatusText.setText("● " + status.toUpperCase());
                menuStatusText.setTextColor(Color.parseColor("#10B981"));
                btnToggle.setText("⏸ PAUSE BOT");
                btnToggle.setEnabled(true);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(Color.parseColor("#DC2626"));
                btnBg.setCornerRadius(dp(10));
                btnToggle.setBackground(btnBg);
                btnToggle.setTextColor(Color.WHITE);
            } else {
                menuStatusText.setText("● PAUSED / STANDBY");
                menuStatusText.setTextColor(Color.parseColor("#94A3B8"));
                btnToggle.setText("▶ START BOT");
                btnToggle.setEnabled(true);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(Color.parseColor("#10B981"));
                btnBg.setCornerRadius(dp(10));
                btnToggle.setBackground(btnBg);
                btnToggle.setTextColor(Color.BLACK);
            }
        }

        updateOfferToggleBtn();
    }

    private void updateOfferToggleBtn() {
        if (btnOfferToggle == null) return;
        boolean enabled = QuizEarnAccessibilityService.isSuperOfferEnabled;
        btnOfferToggle.setText(enabled ? "⚡ AUTO-OFFER 25💎: [ON]" : "⚡ AUTO-OFFER 25💎: [OFF]");
        btnOfferToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnOfferToggle.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (enabled) {
            bg.setColor(Color.parseColor("#059669"));
            btnOfferToggle.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.parseColor("#374151"));
            btnOfferToggle.setTextColor(Color.parseColor("#D1D5DB"));
        }
        btnOfferToggle.setBackground(bg);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        super.dump(fd, writer, args);
        writer.println("QUIZEARN_BOT_STATE=" + (QuizEarnAccessibilityService.isRunning ? "RUNNING" : "STOPPED"));
        writer.println("QUIZEARN_GEMS=" + QuizEarnAccessibilityService.liveGems);
        writer.println("QUIZEARN_STATUS=" + QuizEarnAccessibilityService.currentStatus);
        writer.println("QUIZEARN_SOLVED=" + QuizEarnAccessibilityService.solvedCount);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(refreshRunnable);
        if (floatView != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(floatView);
            } catch (Exception ignored) {}
            floatView = null;
        }
    }
}
package com.quizearn.assistant.lite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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

    public static final String TAG = "QuizEarnLiteAssistant";

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
    private Button btnRestart;
    private Button btnMinimize;
    private Button btnClose;

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
            Log.i(TAG, "FloatingOverlayService action: " + act);
            if ("com.quizearn.assistant.lite.START".equals(act)) {
                QuizEarnAccessibilityService.startSolving();
                updateUiState();
            } else if ("com.quizearn.assistant.lite.STOP".equals(act)) {
                QuizEarnAccessibilityService.stopSolving();
                updateUiState();
            } else if ("com.quizearn.assistant.lite.RESTART_APP".equals(act)) {
                QuizEarnAccessibilityService.restartQuizApp(this);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
        String channelId = "quiz_math_lite_overlay_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "Quiz & Earn Lite Overlay",
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

        builder.setContentTitle("Quiz & Earn Lite")
            .setContentText("Lite HUD active (Quiz + Ads + 25 Gem Stop).")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        Notification notification = builder.build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1002, notification);
            }
        } catch (Throwable t) {
            try {
                startForeground(1002, notification);
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
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatView = new FrameLayout(this);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(120);

        buildBallView();
        buildMenuView();

        floatView.addView(ballView);
        floatView.addView(menuView);
        menuView.setVisibility(View.GONE);

        floatView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
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
                            toggleMenu();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(floatView, params);
        } catch (Exception e) {
            Log.e(TAG, "Error adding floatView to WindowManager", e);
        }
    }

    private void buildBallView() {
        ballView = new LinearLayout(this);
        ballView.setOrientation(LinearLayout.VERTICAL);
        ballView.setGravity(Gravity.CENTER);
        ballView.setLayoutParams(new FrameLayout.LayoutParams(dp(56), dp(56)));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#08080A"));
        bg.setStroke(dp(2), Color.parseColor("#10B981"));
        ballView.setBackground(bg);

        ballIcon = new TextView(this);
        ballIcon.setText("⚡");
        ballIcon.setTextColor(Color.WHITE);
        ballIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        ballIcon.setGravity(Gravity.CENTER);
        ballView.addView(ballIcon);

        ballGemBadge = new TextView(this);
        ballGemBadge.setText("💎--");
        ballGemBadge.setTextColor(Color.parseColor("#10B981"));
        ballGemBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        ballGemBadge.setTypeface(null, Typeface.BOLD);
        ballGemBadge.setGravity(Gravity.CENTER);
        ballView.addView(ballGemBadge);
    }

    private void buildMenuView() {
        int menuWidth = getMenuWidthPx();
        menuView = new LinearLayout(this);
        menuView.setOrientation(LinearLayout.VERTICAL);
        menuView.setLayoutParams(new FrameLayout.LayoutParams(menuWidth, FrameLayout.LayoutParams.WRAP_CONTENT));
        menuView.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor("#050507"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#1F1F26"));
        menuView.setBackground(bg);

        // Header Row
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(6));

        TextView title = new TextView(this);
        title.setText("Quiz & Earn Lite");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        btnMinimize = createSmallButton("—", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMenu();
            }
        });
        btnClose = createSmallButton("✕", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuizEarnAccessibilityService.stopSolving();
                stopSelf();
            }
        });
        header.addView(btnMinimize);
        header.addView(btnClose);
        menuView.addView(header);

        // Gem & Solved Counters
        menuGemText = new TextView(this);
        menuGemText.setText("💎 Gem: --/25  •  Solved: 0");
        menuGemText.setTextColor(Color.parseColor("#10B981"));
        menuGemText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        menuGemText.setTypeface(null, Typeface.BOLD);
        menuGemText.setPadding(0, 0, 0, dp(4));
        menuView.addView(menuGemText);

        // Live Status
        menuStatusText = new TextView(this);
        menuStatusText.setText("Status: Standby");
        menuStatusText.setTextColor(Color.parseColor("#9CA3AF"));
        menuStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        menuStatusText.setPadding(0, 0, 0, dp(10));
        menuView.addView(menuStatusText);

        // Big Toggle Start/Pause Button
        btnToggle = new Button(this);
        btnToggle.setText("[ ▶ START BOT ]");
        btnToggle.setTextColor(Color.WHITE);
        btnToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btnToggle.setTypeface(null, Typeface.BOLD);
        btnToggle.setMinimumHeight(dp(38));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#059669"));
        btnBg.setCornerRadius(dp(8));
        btnToggle.setBackground(btnBg);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (QuizEarnAccessibilityService.isRunning) {
                    QuizEarnAccessibilityService.stopSolving();
                } else {
                    QuizEarnAccessibilityService.startSolving();
                }
                updateUiState();
            }
        });
        menuView.addView(btnToggle);

        // Restart Quiz App Button
        btnRestart = new Button(this);
        btnRestart.setText("Restart Quiz App");
        btnRestart.setTextColor(Color.parseColor("#D1D5DB"));
        btnRestart.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        btnRestart.setMinimumHeight(dp(32));

        GradientDrawable rstBg = new GradientDrawable();
        rstBg.setColor(Color.parseColor("#1F1F26"));
        rstBg.setCornerRadius(dp(6));
        btnRestart.setBackground(rstBg);

        LinearLayout.LayoutParams rstLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rstLp.setMargins(0, dp(6), 0, 0);
        btnRestart.setLayoutParams(rstLp);

        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuizEarnAccessibilityService.restartQuizApp(FloatingOverlayService.this);
            }
        });
        menuView.addView(btnRestart);
    }

    private Button createSmallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.parseColor("#9CA3AF"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setMinWidth(dp(26));
        b.setMinHeight(dp(26));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        b.setBackground(bg);
        b.setOnClickListener(l);
        return b;
    }

    private void toggleMenu() {
        isExpanded = !isExpanded;
        if (isExpanded) {
            ballView.setVisibility(View.GONE);
            menuView.setVisibility(View.VISIBLE);
        } else {
            menuView.setVisibility(View.GONE);
            ballView.setVisibility(View.VISIBLE);
        }
        clampPositionToScreen();
        try {
            windowManager.updateViewLayout(floatView, params);
        } catch (Exception ignored) {}
    }

    private void updateUiState() {
        boolean running = QuizEarnAccessibilityService.isRunning;
        int gems = QuizEarnAccessibilityService.liveGems;
        int solved = QuizEarnAccessibilityService.solvedCount;
        String status = QuizEarnAccessibilityService.currentStatus;

        // Update Ball
        if (ballIcon != null) {
            ballIcon.setText(running ? "⚡" : "⏸");
        }
        if (ballGemBadge != null) {
            ballGemBadge.setText(gems >= 0 ? "💎" + gems : "💎--");
            ballGemBadge.setTextColor(gems >= 25 ? Color.parseColor("#F59E0B") : Color.parseColor("#10B981"));
        }
        if (ballView != null) {
            GradientDrawable bg = (GradientDrawable) ballView.getBackground();
            if (bg != null) {
                bg.setStroke(dp(2), running ? Color.parseColor("#10B981") : Color.parseColor("#EF4444"));
            }
        }

        // Update Menu
        if (menuGemText != null) {
            String gemStr = (gems >= 0 ? gems + "/25" : "--/25");
            menuGemText.setText("💎 Gem: " + gemStr + "  •  Solved: " + solved);
            menuGemText.setTextColor(gems >= 25 ? Color.parseColor("#F59E0B") : Color.parseColor("#10B981"));
        }
        if (menuStatusText != null) {
            menuStatusText.setText("Status: " + status);
        }
        if (btnToggle != null) {
            btnToggle.setText(running ? "[ ⏸ PAUSE BOT ]" : "[ ▶ START BOT ]");
            GradientDrawable btnBg = (GradientDrawable) btnToggle.getBackground();
            if (btnBg != null) {
                btnBg.setColor(running ? Color.parseColor("#991B1B") : Color.parseColor("#059669"));
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(refreshRunnable);
        if (windowManager != null && floatView != null) {
            try {
                windowManager.removeView(floatView);
            } catch (Exception ignored) {}
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * Math.max(0.75f, density));
    }
}

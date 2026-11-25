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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
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
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingOverlayService extends Service {

    public static final String TAG = "QuizEarnAssistant";
    public static volatile FloatingOverlayService instance;

    private WindowManager windowManager;
    private FrameLayout floatView;
    private WindowManager.LayoutParams params;

    private boolean isExpanded = false;
    private LinearLayout ballView;
    private CyberBallView cyberBallView;
    private CyberWindowLayout menuView;

    private Button btnToggle;
    private Button btnDirLeft;
    private Button btnDirRight;
    private CyberSwitchView switchAutoDownload;
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
            } else if ("com.quizearn.assistant.CLOSE".equals(act)) {
                closeOverlayAndStop();
                return START_NOT_STICKY;
            } else if ("com.quizearn.assistant.SET_OFFER_MODE".equals(act)) {
                int mode = intent.getIntExtra("mode", -1);
                if (mode == -1 && intent.hasExtra("mode_str")) {
                    String str = intent.getStringExtra("mode_str");
                    if ("mega".equalsIgnoreCase(str) || "right".equalsIgnoreCase(str)) {
                        mode = QuizEarnAccessibilityService.OFFER_MODE_MEGA;
                    } else if ("super".equalsIgnoreCase(str) || "left".equalsIgnoreCase(str)) {
                        mode = QuizEarnAccessibilityService.OFFER_MODE_SUPER;
                    }
                }
                if (mode == QuizEarnAccessibilityService.OFFER_MODE_MEGA || mode == QuizEarnAccessibilityService.OFFER_MODE_SUPER) {
                    QuizEarnAccessibilityService.setOfferTargetMode(this, mode);
                    updateDirectionButtons();
                }
            } else if ("com.quizearn.assistant.SET_AUTO_DOWNLOAD".equals(act)) {
                boolean enabled = intent.getBooleanExtra("enabled", QuizEarnAccessibilityService.isSuperOfferEnabled);
                if (intent.hasExtra("enabled_int")) {
                    enabled = (intent.getIntExtra("enabled_int", 1) == 1);
                }
                QuizEarnAccessibilityService.setSuperOfferEnabled(this, enabled);
                updateAutoDownloadSwitch();
            } else if ("com.quizearn.assistant.AUTO_OFFER_ON".equals(act)) {
                QuizEarnAccessibilityService.setSuperOfferEnabled(this, true);
                updateAutoDownloadSwitch();
            } else if ("com.quizearn.assistant.AUTO_OFFER_OFF".equals(act)) {
                QuizEarnAccessibilityService.setSuperOfferEnabled(this, false);
                updateAutoDownloadSwitch();
            } else if ("com.quizearn.assistant.RESTART_APP".equals(act)) {
                QuizEarnAccessibilityService.restartQuizApp(this);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed from Recents: closing Floating HUD and stopping bot.");
        closeOverlayAndStop();
    }

    private void closeOverlayAndStop() {
        QuizEarnAccessibilityService.stopSolving();
        uiHandler.removeCallbacks(refreshRunnable);
        if (floatView != null && windowManager != null) {
            try {
                windowManager.removeView(floatView);
            } catch (Exception ignored) {}
            floatView = null;
        }
        instance = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundWithNotification();
        ensureOrientationFixed();
        createFloatingView();
        uiHandler.post(refreshRunnable);
    }

    private void ensureOrientationFixed() {
        try {
            boolean hasWriteSettings = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) || Settings.System.canWrite(this);
            if (hasWriteSettings) {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, 0);
                Log.i(TAG, "FloatingOverlayService: system rotation locked to portrait (0).");
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureOrientationFixed warning: " + t.getMessage());
        }
    }

    private void startForegroundWithNotification() {
        String channelId = "quiz_earn_overlay_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId, "Quiz Earn Overlay", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Floating Mod Menu HUD");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent closeIntent = new Intent(this, FloatingOverlayService.class);
        closeIntent.setAction("com.quizearn.assistant.CLOSE");
        PendingIntent closePendingIntent = PendingIntent.getService(
            this, 1, closeIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(android.R.drawable.ic_menu_compass)
               .setContentTitle("Quiz Math Assistant")
               .setContentText("Floating HUD active. Tap ✕ on menu or Close below to exit.")
               .setContentIntent(pendingIntent)
               .addAction(new Notification.Action.Builder(
                   android.R.drawable.ic_menu_close_clear_cancel,
                   "Close HUD",
                   closePendingIntent
               ).build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1001, builder.build());
        }
    }

    private void createFloatingView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

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
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.RGBA_8888
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(140);

        buildBallView();
        buildMenuView();

        floatView.addView(ballView);
        floatView.addView(menuView);

        menuView.setVisibility(View.GONE);
        ballView.setVisibility(View.VISIBLE);
        isExpanded = false;

        try {
            windowManager.addView(floatView, params);
        } catch (Exception e) {
            Log.e(TAG, "Error adding overlay view: " + e.getMessage());
        }
    }

    private void buildBallView() {
        int ballSize = dp(82);
        ballView = new LinearLayout(this);
        ballView.setOrientation(LinearLayout.VERTICAL);
        ballView.setGravity(Gravity.CENTER);
        ballView.setLayoutParams(new FrameLayout.LayoutParams(ballSize, ballSize));

        cyberBallView = new CyberBallView(this);
        cyberBallView.setLayoutParams(new LinearLayout.LayoutParams(ballSize, ballSize));
        ballView.addView(cyberBallView);

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
        menuView = new CyberWindowLayout(this);
        menuView.setOrientation(LinearLayout.VERTICAL);
        menuView.setPadding(dp(18), dp(16), dp(18), dp(16));
        menuView.setLayoutParams(new FrameLayout.LayoutParams(getMenuWidthPx(), FrameLayout.LayoutParams.WRAP_CONTENT));

        // ---------------- TITLE BAR (Robot Icon + BOT MOD MENU + Minus + Close) ----------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        RobotIconView robotIcon = new RobotIconView(this);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(20));
        iconLp.setMargins(0, 0, dp(8), 0);
        robotIcon.setLayoutParams(iconLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("BOT MOD MENU");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Color.parseColor("#00E5FF")); // Bright Cyber Cyan
        tvTitle.setSingleLine(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tvTitle.setLetterSpacing(0.04f);
        }
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        btnMinimize = new Button(this);
        btnMinimize.setText("—");
        btnMinimize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        btnMinimize.setTypeface(Typeface.DEFAULT_BOLD);
        btnMinimize.setTextColor(Color.parseColor("#00E5FF"));
        btnMinimize.setBackgroundColor(Color.TRANSPARENT);
        btnMinimize.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        minLp.setMargins(0, 0, dp(2), 0);
        btnMinimize.setLayoutParams(minLp);
        btnMinimize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapseMenu();
            }
        });

        btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        btnClose.setTypeface(Typeface.DEFAULT_BOLD);
        btnClose.setTextColor(Color.parseColor("#00E5FF"));
        btnClose.setBackgroundColor(Color.TRANSPARENT);
        btnClose.setPadding(0, 0, 0, 0);
        btnClose.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeOverlayAndStop();
            }
        });

        header.addView(robotIcon);
        header.addView(tvTitle);
        header.addView(btnMinimize);
        header.addView(btnClose);

        // Attach drag listener to tvTitle so dragging window is smooth without intercepting button clicks
        tvTitle.setOnTouchListener(new View.OnTouchListener() {
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
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) {
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            clampPositionToScreen();
                            try {
                                windowManager.updateViewLayout(floatView, params);
                            } catch (Exception ignored) {}
                        }
                        return true;
                }
                return false;
            }
        });

        // ---------------- Section 1: BOT START ----------------
        TextView tvBotStartHdr = new TextView(this);
        tvBotStartHdr.setText("BOT START");
        tvBotStartHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvBotStartHdr.setTypeface(Typeface.DEFAULT_BOLD);
        tvBotStartHdr.setTextColor(Color.parseColor("#5A8296")); // Subtle Cyan-Gray
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tvBotStartHdr.setLetterSpacing(0.06f);
        }
        LinearLayout.LayoutParams bsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bsLp.setMargins(0, dp(12), 0, dp(6));
        tvBotStartHdr.setLayoutParams(bsLp);

        btnToggle = new Button(this);
        btnToggle.setText("▶  START BOT");
        btnToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        btnToggle.setTypeface(Typeface.DEFAULT_BOLD);
        btnToggle.setTextColor(Color.BLACK);
        btnToggle.setMinimumHeight(dp(46));
        btnToggle.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
        );
        toggleLp.setMargins(0, 0, 0, dp(10));
        btnToggle.setLayoutParams(toggleLp);

        GradientDrawable toggleBg = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.parseColor("#A2FF00"), Color.parseColor("#6FE400")}
        );
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

        // ---------------- Section 2: DIRECTION ----------------
        TextView tvDirHdr = new TextView(this);
        tvDirHdr.setText("DIRECTION");
        tvDirHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvDirHdr.setTypeface(Typeface.DEFAULT_BOLD);
        tvDirHdr.setTextColor(Color.parseColor("#5A8296"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tvDirHdr.setLetterSpacing(0.06f);
        }
        LinearLayout.LayoutParams dirHdrLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dirHdrLp.setMargins(0, 0, 0, dp(6));
        tvDirHdr.setLayoutParams(dirHdrLp);

        // Segmented Track Container (Matches picture dark housing)
        LinearLayout dirTrack = new LinearLayout(this);
        dirTrack.setOrientation(LinearLayout.HORIZONTAL);
        dirTrack.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams dirTrackLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        dirTrackLp.setMargins(0, 0, 0, dp(10));
        dirTrack.setLayoutParams(dirTrackLp);

        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(Color.parseColor("#041018"));
        trackBg.setStroke(dp(1), Color.parseColor("#0D222F"));
        trackBg.setCornerRadius(dp(10));
        dirTrack.setBackground(trackBg);

        btnDirLeft = new Button(this);
        btnDirLeft.setText("◀ LEFT");
        btnDirLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnDirLeft.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
        leftLp.setMargins(0, 0, dp(2), 0);
        btnDirLeft.setLayoutParams(leftLp);
        btnDirLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuizEarnAccessibilityService.setOfferTargetMode(FloatingOverlayService.this, QuizEarnAccessibilityService.OFFER_MODE_SUPER);
                updateDirectionButtons();
            }
        });

        btnDirRight = new Button(this);
        btnDirRight.setText("RIGHT ▶");
        btnDirRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnDirRight.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
        rightLp.setMargins(dp(2), 0, 0, 0);
        btnDirRight.setLayoutParams(rightLp);
        btnDirRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuizEarnAccessibilityService.setOfferTargetMode(FloatingOverlayService.this, QuizEarnAccessibilityService.OFFER_MODE_MEGA);
                updateDirectionButtons();
            }
        });

        dirTrack.addView(btnDirLeft);
        dirTrack.addView(btnDirRight);

        // ---------------- Section 3: AUTO DOWNLOAD ----------------
        LinearLayout autoBox = new LinearLayout(this);
        autoBox.setOrientation(LinearLayout.HORIZONTAL);
        autoBox.setGravity(Gravity.CENTER_VERTICAL);
        autoBox.setPadding(dp(14), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams autoBoxLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        autoBoxLp.setMargins(0, 0, 0, dp(4));
        autoBox.setLayoutParams(autoBoxLp);

        GradientDrawable autoBoxBg = new GradientDrawable();
        autoBoxBg.setColor(Color.parseColor("#041018"));
        autoBoxBg.setStroke(dp(1), Color.parseColor("#0D222F"));
        autoBoxBg.setCornerRadius(dp(10));
        autoBox.setBackground(autoBoxBg);

        TextView tvAutoLabel = new TextView(this);
        tvAutoLabel.setText("AUTO DOWNLOAD");
        tvAutoLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvAutoLabel.setTypeface(Typeface.DEFAULT_BOLD);
        tvAutoLabel.setTextColor(Color.parseColor("#C5D8E4"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tvAutoLabel.setLetterSpacing(0.04f);
        }
        tvAutoLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        switchAutoDownload = new CyberSwitchView(this);
        switchAutoDownload.setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(36)));
        switchAutoDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newEnabled = !QuizEarnAccessibilityService.isSuperOfferEnabled;
                QuizEarnAccessibilityService.setSuperOfferEnabled(FloatingOverlayService.this, newEnabled);
                updateAutoDownloadSwitch();
            }
        });

        autoBox.addView(tvAutoLabel);
        autoBox.addView(switchAutoDownload);

        // Assemble all sections into menuView
        menuView.addView(header);
        menuView.addView(tvBotStartHdr);
        menuView.addView(btnToggle);
        menuView.addView(tvDirHdr);
        menuView.addView(dirTrack);
        menuView.addView(autoBox);

        updateDirectionButtons();
        updateAutoDownloadSwitch();
    }

    public void updateDirectionButtons() {
        if (btnDirLeft == null || btnDirRight == null) return;
        boolean isRight = (QuizEarnAccessibilityService.offerTargetMode == QuizEarnAccessibilityService.OFFER_MODE_MEGA);

        // Active State: Glowing Neon Cyan Border + Deep Dark Cyan Background (Matches Picture)
        GradientDrawable activeBg = new GradientDrawable();
        activeBg.setColor(Color.parseColor("#062737")); // Dark Cyan Fill
        activeBg.setStroke(dp(2.2f), Color.parseColor("#00E5FF")); // Bright Glowing Cyan
        activeBg.setCornerRadius(dp(8));

        // Inactive State: Pure transparent with muted text
        GradientDrawable inactiveBg = new GradientDrawable();
        inactiveBg.setColor(Color.TRANSPARENT);
        inactiveBg.setCornerRadius(dp(8));

        if (isRight) {
            btnDirRight.setBackground(activeBg);
            btnDirRight.setTextColor(Color.parseColor("#00E5FF"));

            btnDirLeft.setBackground(inactiveBg);
            btnDirLeft.setTextColor(Color.parseColor("#2B485A"));
        } else {
            btnDirLeft.setBackground(activeBg);
            btnDirLeft.setTextColor(Color.parseColor("#00E5FF"));

            btnDirRight.setBackground(inactiveBg);
            btnDirRight.setTextColor(Color.parseColor("#2B485A"));
        }
    }

    public void updateAutoDownloadSwitch() {
        if (switchAutoDownload == null) return;
        switchAutoDownload.setChecked(QuizEarnAccessibilityService.isSuperOfferEnabled);
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

    @SuppressWarnings("deprecation")
    private int[] getRealScreenSize() {
        try {
            if (windowManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowMetrics metrics = windowManager.getMaximumWindowMetrics();
                    if (metrics != null) {
                        android.graphics.Rect b = metrics.getBounds();
                        if (b.width() > 0 && b.height() > 0) {
                            return new int[]{b.width(), b.height()};
                        }
                    }
                } else {
                    DisplayMetrics realDm = new DisplayMetrics();
                    windowManager.getDefaultDisplay().getRealMetrics(realDm);
                    if (realDm.widthPixels > 0 && realDm.heightPixels > 0) {
                        return new int[]{realDm.widthPixels, realDm.heightPixels};
                    }
                }
            }
        } catch (Throwable ignored) {}
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private int getMenuWidthPx() {
        int screenW = getRealScreenSize()[0];
        return Math.min(dp(350), Math.max(dp(290), (int) (screenW * 0.90f)));
    }

    private void clampPositionToScreen() {
        if (params == null || floatView == null || windowManager == null) return;
        int[] realSize = getRealScreenSize();
        int screenW = realSize[0];
        int screenH = realSize[1];
        int viewW = isExpanded ? getMenuWidthPx() : dp(82);
        int viewH = isExpanded ? Math.max(dp(260), floatView.getHeight()) : dp(82);

        int minX = dp(4);
        int maxX = Math.max(minX, screenW - viewW - dp(4));
        int minY = dp(24);
        int maxY = Math.max(minY, screenH - viewH - dp(24));

        params.x = Math.max(minX, Math.min(maxX, params.x));
        params.y = Math.max(minY, Math.min(maxY, params.y));
    }

    private boolean lastUiStateValid = false;
    private boolean lastUiRunning = false;
    private int lastUiGems = -2;
    private int lastUiDir = -1;
    private boolean lastUiAuto = false;

    private void updateUiState() {
        boolean running = QuizEarnAccessibilityService.isRunning;
        int gems = QuizEarnAccessibilityService.liveGems;
        int dir = QuizEarnAccessibilityService.offerTargetMode;
        boolean auto = QuizEarnAccessibilityService.isSuperOfferEnabled;

        if (lastUiStateValid && running == lastUiRunning && gems == lastUiGems && dir == lastUiDir && auto == lastUiAuto) {
            return;
        }
        lastUiStateValid = true;
        lastUiRunning = running;
        lastUiGems = gems;
        lastUiDir = dir;
        lastUiAuto = auto;

        if (cyberBallView != null) {
            cyberBallView.setState(running, gems);
        }

        if (btnToggle != null) {
            if (running) {
                btnToggle.setText("⏹  STOP BOT");
                GradientDrawable btnBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.parseColor("#FF2B45"), Color.parseColor("#CC0018")}
                );
                btnBg.setCornerRadius(dp(10));
                btnToggle.setBackground(btnBg);
                btnToggle.setTextColor(Color.WHITE);
            } else {
                btnToggle.setText("▶  START BOT");
                GradientDrawable btnBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.parseColor("#A2FF00"), Color.parseColor("#6FE400")}
                );
                btnBg.setCornerRadius(dp(10));
                btnToggle.setBackground(btnBg);
                btnToggle.setTextColor(Color.BLACK);
            }
        }

        updateDirectionButtons();
        updateAutoDownloadSwitch();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        super.dump(fd, writer, args);
        writer.println("QUIZEARN_BOT_STATE=" + (QuizEarnAccessibilityService.isRunning ? "RUNNING" : "STOPPED"));
        writer.println("QUIZEARN_DIRECTION=" + (QuizEarnAccessibilityService.offerTargetMode == QuizEarnAccessibilityService.OFFER_MODE_MEGA ? "RIGHT" : "LEFT"));
        writer.println("QUIZEARN_AUTO_DOWNLOAD=" + (QuizEarnAccessibilityService.isSuperOfferEnabled ? "ON" : "OFF"));
        writer.println("QUIZEARN_GEMS=" + QuizEarnAccessibilityService.liveGems);
        writer.println("QUIZEARN_STATUS=" + QuizEarnAccessibilityService.currentStatus);
        writer.println("QUIZEARN_SOLVED=" + QuizEarnAccessibilityService.solvedCount);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        QuizEarnAccessibilityService.stopSolving();
        instance = null;
        uiHandler.removeCallbacks(refreshRunnable);
        if (floatView != null && windowManager != null) {
            try {
                windowManager.removeView(floatView);
            } catch (Exception ignored) {}
            floatView = null;
        }
    }

    // =========================================================================
    // CUSTOM SCI-FI CYBERNETIC VIEWS (100% MATCHING USER REFERENCE IMAGE)
    // =========================================================================

    /**
     * Outer Window Frame:
     * - Dark Cyberpunk Gradient Background (#06141D -> #091F2B)
     * - Horizontal Scanlines
     * - Chamfered 45-degree Beveled Corners
     * - Stepped Top Center Notch
     * - Glowing Neon Cyan Border & Corner Accent Brackets
     */
    public static class CyberWindowLayout extends LinearLayout {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scanPaint = new Paint();
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        public CyberWindowLayout(Context context) {
            super(context);
            setWillNotDraw(false);
            density = context.getResources().getDisplayMetrics().density;

            scanPaint.setColor(Color.parseColor("#092434"));
            scanPaint.setStrokeWidth(dp(1));
            scanPaint.setAlpha(40);

            borderPaint.setColor(Color.parseColor("#00E5FF"));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1.8f));

            bracketPaint.setColor(Color.parseColor("#00E5FF"));
            bracketPaint.setStyle(Paint.Style.STROKE);
            bracketPaint.setStrokeWidth(dp(2.4f));
            bracketPaint.setStrokeCap(Paint.Cap.SQUARE);
        }

        private float dp(float v) {
            return v * density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float p = dp(6.5f); // Generous padding so floating outer brackets never get clipped
            float right = w - p;
            float bottom = h - p;
            float cham = dp(14f); // 45-degree corner bevel
            float notchW = dp(36f);
            float notchD = dp(3.5f);
            float midX = w / 2f;
            float midY = h / 2f;

            // 1. Chamfered Outer Path (Main Shell)
            Path clipPath = new Path();
            clipPath.moveTo(p + cham, p);
            // Top notch
            clipPath.lineTo(midX - notchW, p);
            clipPath.lineTo(midX - notchW + dp(4), p + notchD);
            clipPath.lineTo(midX + notchW - dp(4), p + notchD);
            clipPath.lineTo(midX + notchW, p);
            clipPath.lineTo(right - cham, p);
            // Top-Right Chamfer
            clipPath.lineTo(right, p + cham);
            // Right edge notch
            clipPath.lineTo(right, midY - dp(10));
            clipPath.lineTo(right - dp(2.5f), midY - dp(6));
            clipPath.lineTo(right - dp(2.5f), midY + dp(6));
            clipPath.lineTo(right, midY + dp(10));
            clipPath.lineTo(right, bottom - cham);
            // Bottom-Right Chamfer
            clipPath.lineTo(right - cham, bottom);
            // Bottom notch
            clipPath.lineTo(midX + notchW, bottom);
            clipPath.lineTo(midX + notchW - dp(4), bottom - notchD);
            clipPath.lineTo(midX - notchW + dp(4), bottom - notchD);
            clipPath.lineTo(midX - notchW, bottom);
            clipPath.lineTo(p + cham, bottom);
            // Bottom-Left Chamfer
            clipPath.lineTo(p, bottom - cham);
            // Left edge notch
            clipPath.lineTo(p, midY + dp(10));
            clipPath.lineTo(p + dp(2.5f), midY + dp(6));
            clipPath.lineTo(p + dp(2.5f), midY - dp(6));
            clipPath.lineTo(p, midY - dp(10));
            clipPath.lineTo(p, p + cham);
            clipPath.close();

            // Background Deep Cyber Gradient (#06141D -> #091F2B)
            bgPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                Color.parseColor("#06141D"),
                Color.parseColor("#091F2B"),
                Shader.TileMode.CLAMP
            ));
            canvas.drawPath(clipPath, bgPaint);

            // Subtle Scanlines
            canvas.save();
            canvas.clipPath(clipPath);
            float step = dp(4.0f);
            for (float y = 0; y < h; y += step) {
                canvas.drawLine(0, y, w, y, scanPaint);
            }
            canvas.restore();

            // Outer Glowing Cyan Border
            canvas.drawPath(clipPath, borderPaint);

            // 2. Corner Outer Brackets (Exact Match to Reference HUD)
            bracketPaint.setStrokeWidth(dp(2.4f));

            // Floating gap distance from the main border
            float gap = dp(3.5f);

            // Top-Left Bracket: wraps around 45° chamfer
            Path brTL = new Path();
            brTL.moveTo(p + cham + dp(8), p - gap);
            brTL.lineTo(p + cham, p - gap);
            brTL.lineTo(p - gap, p + cham);
            brTL.lineTo(p - gap, p + cham + dp(8));
            canvas.drawPath(brTL, bracketPaint);

            // Top-Right Bracket
            Path brTR = new Path();
            brTR.moveTo(right - cham - dp(8), p - gap);
            brTR.lineTo(right - cham, p - gap);
            brTR.lineTo(right + gap, p + cham);
            brTR.lineTo(right + gap, p + cham + dp(8));
            canvas.drawPath(brTR, bracketPaint);

            // Bottom-Left Bracket
            Path brBL = new Path();
            brBL.moveTo(p - gap, bottom - cham - dp(8));
            brBL.lineTo(p - gap, bottom - cham);
            brBL.lineTo(p + cham, bottom + gap);
            brBL.lineTo(p + cham + dp(8), bottom + gap);
            canvas.drawPath(brBL, bracketPaint);

            // Bottom-Right Bracket
            Path brBR = new Path();
            brBR.moveTo(right + gap, bottom - cham - dp(8));
            brBR.lineTo(right + gap, bottom - cham);
            brBR.lineTo(right - cham, bottom + gap);
            brBR.lineTo(right - cham - dp(8), bottom + gap);
            canvas.drawPath(brBR, bracketPaint);

            // Edge Center Accent Lines
            canvas.drawLine(p - gap, midY - dp(8), p - gap, midY + dp(8), bracketPaint);
            canvas.drawLine(right + gap, midY - dp(8), right + gap, midY + dp(8), bracketPaint);
            canvas.drawLine(midX - dp(14), p - gap, midX + dp(14), p - gap, bracketPaint);
            canvas.drawLine(midX - dp(14), bottom + gap, midX + dp(14), bottom + gap, bracketPaint);

            super.onDraw(canvas);
        }
    }

    /**
     * Retro 8-Bit Pixel Robot Head Icon (Matches top-left header in reference photo)
     */
    public static class RobotIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint darkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public RobotIconView(Context context) {
            super(context);
            paint.setColor(Color.parseColor("#00E5FF"));
            darkPaint.setColor(Color.parseColor("#06141D"));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float u = w / 16f;

            // Antenna ears
            canvas.drawRect(3.5f * u, 1 * u, 5.5f * u, 4 * u, paint);
            canvas.drawRect(10.5f * u, 1 * u, 12.5f * u, 4 * u, paint);

            // Side cheek bolts
            canvas.drawRect(1 * u, 6 * u, 3 * u, 10 * u, paint);
            canvas.drawRect(13 * u, 6 * u, 15 * u, 10 * u, paint);

            // Head core
            canvas.drawRect(3 * u, 4 * u, 13 * u, 13 * u, paint);

            // Eye cutouts
            canvas.drawRect(5 * u, 6.5f * u, 7.5f * u, 9f * u, darkPaint);
            canvas.drawRect(8.5f * u, 6.5f * u, 11f * u, 9f * u, darkPaint);

            // Mouth slot
            canvas.drawRect(5.5f * u, 10.5f * u, 10.5f * u, 11.5f * u, darkPaint);
        }
    }

    /**
     * Cybernetic Toggle Switch (Matches the AUTO DOWNLOAD switch in reference photo)
     * - Top Label "ON / OFF"
     * - Outer Cyan/Green Cyber Bracket
     * - Inner Dark Groove Track
     * - Glowing LED Thumb Knob & Bold Text
     * - Bottom Cyan Tick Notch
     */
    public static class CyberSwitchView extends View {
        private boolean isChecked = true;
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        public CyberSwitchView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setClickable(true);
        }

        private float dp(float v) {
            return v * density;
        }

        public void setChecked(boolean checked) {
            this.isChecked = checked;
            invalidate();
        }

        public boolean isChecked() {
            return isChecked;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 1. Top label: "ON / OFF"
            textPaint.setColor(Color.parseColor("#5E8296"));
            textPaint.setTextSize(dp(7.5f));
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("ON / OFF", w / 2f, dp(7f), textPaint);

            // 2. Switch Outer Frame & Track Area
            float trackTop = dp(9.5f);
            float trackH = dp(20f);
            float trackW = w - dp(8f);
            float trackLeft = dp(4f);
            RectF trackRect = new RectF(trackLeft, trackTop, trackLeft + trackW, trackTop + trackH);
            float cr = trackH / 2f;

            // Inner dark track
            trackPaint.setColor(Color.parseColor("#020B10"));
            canvas.drawRoundRect(trackRect, cr, cr, trackPaint);

            // Outer Cyber Bracket Rim
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1.8f));
            if (isChecked) {
                borderPaint.setColor(Color.parseColor("#00E5FF")); // Cyan outer rim
            } else {
                borderPaint.setColor(Color.parseColor("#1E3A4D")); // Muted rim
            }
            canvas.drawRoundRect(trackRect, cr, cr, borderPaint);

            // Outer decorative cyber bracket steps around the capsule
            Paint outerStepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            outerStepPaint.setStyle(Paint.Style.STROKE);
            outerStepPaint.setStrokeWidth(dp(1.5f));
            outerStepPaint.setColor(isChecked ? Color.parseColor("#00E5FF") : Color.parseColor("#1E3A4D"));

            // Left cyber bracket wing
            Path leftWing = new Path();
            leftWing.moveTo(trackLeft + dp(10), trackTop - dp(2));
            leftWing.lineTo(trackLeft - dp(1.5f), trackTop - dp(2));
            leftWing.lineTo(trackLeft - dp(3.5f), trackTop + dp(4));
            leftWing.lineTo(trackLeft - dp(3.5f), trackTop + trackH - dp(4));
            leftWing.lineTo(trackLeft - dp(1.5f), trackTop + trackH + dp(2));
            leftWing.lineTo(trackLeft + dp(10), trackTop + trackH + dp(2));
            canvas.drawPath(leftWing, outerStepPaint);

            // Right cyber bracket wing
            Path rightWing = new Path();
            rightWing.moveTo(trackLeft + trackW - dp(10), trackTop - dp(2));
            rightWing.lineTo(trackLeft + trackW + dp(1.5f), trackTop - dp(2));
            rightWing.lineTo(trackLeft + trackW + dp(3.5f), trackTop + dp(4));
            rightWing.lineTo(trackLeft + trackW + dp(3.5f), trackTop + trackH - dp(4));
            rightWing.lineTo(trackLeft + trackW + dp(1.5f), trackTop + trackH + dp(2));
            rightWing.lineTo(trackLeft + trackW - dp(10), trackTop + trackH + dp(2));
            canvas.drawPath(rightWing, outerStepPaint);

            // 3. Knob & State Text
            float centerY = trackTop + trackH / 2f;
            float knobRadius = dp(7.0f);

            if (isChecked) {
                // Text "ON" on the left
                textPaint.setColor(Color.parseColor("#76FF03")); // Neon Lime Green
                textPaint.setTextSize(dp(11.5f));
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("ON", trackLeft + dp(18f), centerY + dp(4f), textPaint);

                // Glowing Knob on the right
                float knobX = trackLeft + trackW - dp(12f);

                // Halo glow
                glowPaint.setColor(Color.parseColor("#76FF03"));
                glowPaint.setAlpha(70);
                canvas.drawCircle(knobX, centerY, knobRadius + dp(3.5f), glowPaint);

                // Solid core
                thumbPaint.setColor(Color.parseColor("#76FF03"));
                canvas.drawCircle(knobX, centerY, knobRadius, thumbPaint);

                // Center bright spot
                thumbPaint.setColor(Color.parseColor("#DCFF66"));
                canvas.drawCircle(knobX, centerY, dp(2.4f), thumbPaint);
            } else {
                // Text "OFF" on the right
                textPaint.setColor(Color.parseColor("#64748B")); // Muted Gray
                textPaint.setTextSize(dp(10.5f));
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("OFF", trackLeft + trackW - dp(18f), centerY + dp(3.8f), textPaint);

                // Dark/dimmed Knob on the left
                float knobX = trackLeft + dp(12f);
                thumbPaint.setColor(Color.parseColor("#475569"));
                canvas.drawCircle(knobX, centerY, knobRadius, thumbPaint);
            }

            // 4. Bottom Accent Cyan Line with notch
            float lineY = trackTop + trackH + dp(4.5f);
            Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setColor(Color.parseColor("#00E5FF"));
            linePaint.setStrokeWidth(dp(1.2f));
            canvas.drawLine(w * 0.35f, lineY, w * 0.65f, lineY, linePaint);
            canvas.drawCircle(w * 0.5f, lineY, dp(1.6f), linePaint);
        }
    }

    /**
     * Futuristic Sci-Fi Hologram Floating Minimize Ball
     * (Exact 100% Match to User Reference Photo media_1795538365276_cc80de9b.png)
     * - Outer Holographic Radar Rings with cyan tick arcs and dots
     * - Glowing Neon Gradient Bezel Ring (Electric Cyan -> Lime Green)
     * - Inward Notch at 3 o'clock with Right-Pointing Chevron (>)
     * - Bottom Inset Tab with bold cyan "BOT" text
     * - High-Tech Friendly Robot Head:
     *   * Metallic Helmet Dome & Dual Antennas
     *   * Rounded Side Ear Pods
     *   * Glowing Forehead Cyan Sensor Gem
     *   * Dark Obsidian Visor Faceplate
     *   * Glowing Neon Lime Circular Eyes & Cute Smile Arc
     */
    public static class CyberBallView extends View {
        private boolean isRunning = false;
        private int gems = 0;
        private final float density;

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outerAuraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint radarRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint radarDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bezelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bezelGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint notchFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tabPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tabStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tabTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Robot Head Paints
        private final Paint antennaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint helmetFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint helmetStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint earFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint earStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint visorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint visorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gemGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gemCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint eyeGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pupilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mouthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public CyberBallView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            density = context.getResources().getDisplayMetrics().density;
            initPaints();
        }

        private float dp(float v) {
            return v * density;
        }

        public void setState(boolean running, int gems) {
            this.isRunning = running;
            this.gems = gems;
            invalidate();
        }

        private void initPaints() {
            outerAuraPaint.setColor(Color.parseColor("#00E5FF"));
            outerAuraPaint.setStyle(Paint.Style.STROKE);
            outerAuraPaint.setStrokeWidth(dp(1.0f));
            outerAuraPaint.setAlpha(40);

            radarRingPaint.setColor(Color.parseColor("#00E5FF"));
            radarRingPaint.setStyle(Paint.Style.STROKE);
            radarRingPaint.setStrokeWidth(dp(1.3f));
            radarRingPaint.setAlpha(75);

            radarDotPaint.setColor(Color.parseColor("#00E5FF"));
            radarDotPaint.setStyle(Paint.Style.FILL);
            radarDotPaint.setAlpha(180);

            bezelGlowPaint.setStyle(Paint.Style.STROKE);
            bezelGlowPaint.setStrokeWidth(dp(4.2f));
            bezelGlowPaint.setAlpha(65);

            bezelPaint.setStyle(Paint.Style.STROKE);
            bezelPaint.setStrokeWidth(dp(2.4f));

            notchFillPaint.setColor(Color.parseColor("#030A10"));
            notchFillPaint.setStyle(Paint.Style.FILL);

            chevronPaint.setColor(Color.parseColor("#00E5FF"));
            chevronPaint.setStyle(Paint.Style.STROKE);
            chevronPaint.setStrokeWidth(dp(1.5f));
            chevronPaint.setStrokeCap(Paint.Cap.ROUND);
            chevronPaint.setStrokeJoin(Paint.Join.ROUND);

            tabPaint.setColor(Color.parseColor("#040E16"));
            tabPaint.setStyle(Paint.Style.FILL);

            tabStrokePaint.setColor(Color.parseColor("#00E5FF"));
            tabStrokePaint.setStyle(Paint.Style.STROKE);
            tabStrokePaint.setStrokeWidth(dp(1.0f));
            tabStrokePaint.setAlpha(120);

            tabTextPaint.setColor(Color.parseColor("#00E5FF"));
            tabTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
            tabTextPaint.setTextAlign(Paint.Align.CENTER);
            tabTextPaint.setTextSize(dp(7.0f));

            antennaPaint.setColor(Color.parseColor("#5A84A1"));
            antennaPaint.setStyle(Paint.Style.STROKE);
            antennaPaint.setStrokeWidth(dp(1.8f));
            antennaPaint.setStrokeCap(Paint.Cap.ROUND);

            helmetFillPaint.setColor(Color.parseColor("#2F495D"));
            helmetFillPaint.setStyle(Paint.Style.FILL);

            helmetStrokePaint.setColor(Color.parseColor("#5A84A1"));
            helmetStrokePaint.setStyle(Paint.Style.STROKE);
            helmetStrokePaint.setStrokeWidth(dp(1.2f));

            earFillPaint.setColor(Color.parseColor("#273D4E"));
            earFillPaint.setStyle(Paint.Style.FILL);

            earStrokePaint.setColor(Color.parseColor("#5A84A1"));
            earStrokePaint.setStyle(Paint.Style.STROKE);
            earStrokePaint.setStrokeWidth(dp(1.0f));

            visorPaint.setColor(Color.parseColor("#040C14"));
            visorPaint.setStyle(Paint.Style.FILL);

            visorStrokePaint.setColor(Color.parseColor("#1C384D"));
            visorStrokePaint.setStyle(Paint.Style.STROKE);
            visorStrokePaint.setStrokeWidth(dp(1.0f));

            gemGlowPaint.setColor(Color.parseColor("#00E5FF"));
            gemGlowPaint.setStyle(Paint.Style.FILL);
            gemGlowPaint.setAlpha(90);

            gemPaint.setColor(Color.parseColor("#00E5FF"));
            gemPaint.setStyle(Paint.Style.FILL);

            gemCorePaint.setColor(Color.parseColor("#BFFFFF"));
            gemCorePaint.setStyle(Paint.Style.FILL);

            eyeGlowPaint.setColor(Color.parseColor("#76FF03"));
            eyeGlowPaint.setStyle(Paint.Style.FILL);
            eyeGlowPaint.setAlpha(80);

            eyePaint.setColor(Color.parseColor("#76FF03"));
            eyePaint.setStyle(Paint.Style.FILL);

            pupilPaint.setColor(Color.parseColor("#E4FF9E"));
            pupilPaint.setStyle(Paint.Style.FILL);

            mouthPaint.setColor(Color.parseColor("#76FF03"));
            mouthPaint.setStyle(Paint.Style.STROKE);
            mouthPaint.setStrokeWidth(dp(1.3f));
            mouthPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            try {
                drawCyberBall(canvas, w, h);
            } catch (Throwable t) {
                Log.e(TAG, "Error drawing CyberBall: " + t.getMessage(), t);
            }
        }

        private void drawCyberBall(Canvas canvas, int w, int h) {
            float cx = w / 2f;
            float cy = h / 2f;
            float outerR = Math.min(w, h) / 2f - dp(1.5f);

            // 1. OUTER HOLOGRAPHIC RADAR AURA & RINGS
            // Soft cyan radial background glow
            Paint glowAura = new Paint(Paint.ANTI_ALIAS_FLAG);
            glowAura.setShader(new RadialGradient(
                cx, cy, outerR,
                new int[]{Color.parseColor("#3300E5FF"), Color.parseColor("#1500E5FF"), Color.TRANSPARENT},
                new float[]{0.60f, 0.85f, 1.0f},
                Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(cx, cy, outerR, glowAura);

            // Outermost faint radar circle
            float rAura = outerR * 0.95f;
            canvas.drawCircle(cx, cy, rAura, outerAuraPaint);

            // Segmented secondary radar circle
            float rRadar = outerR * 0.86f;
            RectF radarRect = new RectF(cx - rRadar, cy - rRadar, cx + rRadar, cy + rRadar);
            canvas.drawArc(radarRect, 195, 68, false, radarRingPaint);
            canvas.drawArc(radarRect, 105, 65, false, radarRingPaint);
            canvas.drawArc(radarRect, 328, 65, false, radarRingPaint);

            // Radar nodes (cyan glowing dots)
            float dot1X = cx + rRadar * (float) Math.cos(Math.toRadians(210));
            float dot1Y = cy + rRadar * (float) Math.sin(Math.toRadians(210));
            canvas.drawCircle(dot1X, dot1Y, dp(1.5f), radarDotPaint);

            float dot2X = cx + rRadar * (float) Math.cos(Math.toRadians(350));
            float dot2Y = cy + rRadar * (float) Math.sin(Math.toRadians(350));
            canvas.drawCircle(dot2X, dot2Y, dp(1.5f), radarDotPaint);

            // Radar radial tick marks
            canvas.drawLine(
                cx + (rRadar - dp(2.2f)) * (float) Math.cos(Math.toRadians(210)),
                cy + (rRadar - dp(2.2f)) * (float) Math.sin(Math.toRadians(210)),
                cx + (rRadar + dp(2.2f)) * (float) Math.cos(Math.toRadians(210)),
                cy + (rRadar + dp(2.2f)) * (float) Math.sin(Math.toRadians(210)),
                radarRingPaint
            );

            // 2. MAIN DISC BASE (Deep dark navy/cyan radial gradient)
            float discR = outerR * 0.74f;
            bgPaint.setShader(new RadialGradient(
                cx, cy, discR,
                new int[]{Color.parseColor("#0C2332"), Color.parseColor("#06131D"), Color.parseColor("#02090F")},
                new float[]{0.0f, 0.65f, 1.0f},
                Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(cx, cy, discR, bgPaint);

            // 3. GLOWING NEON GRADIENT BEZEL (Electric Cyan -> Neon Lime Green)
            SweepGradient sweep = new SweepGradient(
                cx, cy,
                new int[]{
                    Color.parseColor("#76FF03"), // 0 deg (right)
                    Color.parseColor("#38F281"), // bottom transition
                    Color.parseColor("#00E5FF"), // 180 deg (left)
                    Color.parseColor("#00E5FF"), // left upper
                    Color.parseColor("#38F281"), // top transition
                    Color.parseColor("#76FF03")  // 360 deg
                },
                new float[]{0.0f, 0.25f, 0.45f, 0.55f, 0.75f, 1.0f}
            );
            bezelPaint.setShader(sweep);
            bezelPaint.setStrokeWidth(dp(2.8f));
            bezelGlowPaint.setShader(sweep);
            bezelGlowPaint.setStrokeWidth(dp(4.6f));

            RectF bezelRect = new RectF(cx - discR, cy - discR, cx + discR, cy + discR);

            // Single continuous Bezel path that gracefully flows into the 3 o'clock notch
            Path bezelPath = new Path();
            float topNotchDeg = 338f;
            float botNotchDeg = 22f;
            bezelPath.arcTo(bezelRect, botNotchDeg, topNotchDeg - botNotchDeg);

            float topNotchX = cx + discR * (float) Math.cos(Math.toRadians(topNotchDeg));
            float topNotchY = cy + discR * (float) Math.sin(Math.toRadians(topNotchDeg));
            float botNotchX = cx + discR * (float) Math.cos(Math.toRadians(botNotchDeg));
            float botNotchY = cy + discR * (float) Math.sin(Math.toRadians(botNotchDeg));
            float notchInnerX = cx + discR - dp(4.8f);

            bezelPath.cubicTo(
                cx + discR - dp(1.8f), topNotchY + dp(1.5f),
                notchInnerX, cy - dp(2.2f),
                notchInnerX, cy
            );
            bezelPath.cubicTo(
                notchInnerX, cy + dp(2.2f),
                cx + discR - dp(1.8f), botNotchY - dp(1.5f),
                botNotchX, botNotchY
            );

            // Draw glowing halo and sharp gradient border
            canvas.drawPath(bezelPath, bezelGlowPaint);
            canvas.drawPath(bezelPath, bezelPaint);

            // 4. RIGHT-POINTING CHEVRON (>) INSIDE NOTCH
            Path chPath = new Path();
            chPath.moveTo(notchInnerX + dp(0.8f), cy - dp(3.2f));
            chPath.lineTo(notchInnerX + dp(3.4f), cy);
            chPath.lineTo(notchInnerX + dp(0.8f), cy + dp(3.2f));
            canvas.drawPath(chPath, chevronPaint);

            // 5. BOTTOM INSET TAB WITH "BOT" TEXT
            float tabW = dp(28f);
            float tabH = dp(11.0f);
            float tabTop = cy + discR - dp(11.0f);
            RectF tabRect = new RectF(cx - tabW / 2f, tabTop, cx + tabW / 2f, tabTop + tabH);
            float tabR = dp(4.0f);
            canvas.drawRoundRect(tabRect, tabR, tabR, tabPaint);
            canvas.drawRoundRect(tabRect, tabR, tabR, tabStrokePaint);

            // Crisp "BOT" text
            tabTextPaint.setTextSize(dp(8.0f));
            canvas.drawText("BOT", cx, tabTop + dp(8.0f), tabTextPaint);

            // 6. VECTOR SCI-FI ROBOT HEAD (Centered Perfectly Above Bottom Tab)
            float hx = cx;
            float hy = cy - dp(2.5f);
            float u = dp(3.1f); // scaled to fill nicely like reference photo

            // Antennas
            antennaPaint.setStrokeWidth(dp(1.8f));
            canvas.drawLine(hx - 2.8f * u, hy - 2.0f * u, hx - 3.0f * u, hy - 4.8f * u, antennaPaint);
            canvas.drawLine(hx + 2.8f * u, hy - 2.0f * u, hx + 3.0f * u, hy - 4.8f * u, antennaPaint);
            // Glowing antenna tips
            canvas.drawCircle(hx - 3.0f * u, hy - 4.8f * u, dp(1.1f), gemPaint);
            canvas.drawCircle(hx + 3.0f * u, hy - 4.8f * u, dp(1.1f), gemPaint);

            // Side Ear Cups
            RectF leftEar = new RectF(hx - 4.6f * u, hy - 1.4f * u, hx - 3.4f * u, hy + 1.8f * u);
            canvas.drawRoundRect(leftEar, dp(1.5f), dp(1.5f), earFillPaint);
            canvas.drawRoundRect(leftEar, dp(1.5f), dp(1.5f), earStrokePaint);

            RectF rightEar = new RectF(hx + 3.4f * u, hy - 1.4f * u, hx + 4.6f * u, hy + 1.8f * u);
            canvas.drawRoundRect(rightEar, dp(1.5f), dp(1.5f), earFillPaint);
            canvas.drawRoundRect(rightEar, dp(1.5f), dp(1.5f), earStrokePaint);

            // Metallic Helmet Dome
            Path domePath = new Path();
            domePath.moveTo(hx - 3.4f * u, hy + 1.6f * u);
            domePath.lineTo(hx - 3.4f * u, hy - 1.2f * u);
            domePath.cubicTo(hx - 3.2f * u, hy - 4.3f * u, hx + 3.2f * u, hy - 4.3f * u, hx + 3.4f * u, hy - 1.2f * u);
            domePath.lineTo(hx + 3.4f * u, hy + 1.6f * u);
            domePath.lineTo(hx + 2.5f * u, hy + 2.8f * u);
            domePath.lineTo(hx - 2.5f * u, hy + 2.8f * u);
            domePath.close();

            helmetFillPaint.setShader(new LinearGradient(
                hx, hy - 4.3f * u, hx, hy + 2.8f * u,
                Color.parseColor("#3C5A72"),
                Color.parseColor("#223645"),
                Shader.TileMode.CLAMP
            ));
            canvas.drawPath(domePath, helmetFillPaint);
            canvas.drawPath(domePath, helmetStrokePaint);

            // Forehead Oval Sensor Gem (Cyan Capsule Glowing)
            float gemY = hy - 2.5f * u;
            RectF gemGlowRect = new RectF(hx - dp(4.2f), gemY - dp(2.6f), hx + dp(4.2f), gemY + dp(2.6f));
            canvas.drawOval(gemGlowRect, gemGlowPaint);
            RectF gemCoreRect = new RectF(hx - dp(2.8f), gemY - dp(1.8f), hx + dp(2.8f), gemY + dp(1.8f));
            canvas.drawOval(gemCoreRect, gemPaint);
            canvas.drawCircle(hx, gemY, dp(0.9f), gemCorePaint);

            // Dark Obsidian Visor Faceplate
            Path visorPath = new Path();
            float browY = hy - 0.9f * u;
            visorPath.moveTo(hx - 2.7f * u, browY);
            visorPath.quadTo(hx, browY - dp(1.0f), hx + 2.7f * u, browY);
            visorPath.lineTo(hx + 2.6f * u, hy + 1.6f * u);
            visorPath.lineTo(hx, hy + 2.6f * u);
            visorPath.lineTo(hx - 2.7f * u, hy + 1.6f * u);
            visorPath.close();

            canvas.drawPath(visorPath, visorPaint);
            canvas.drawPath(visorPath, visorStrokePaint);

            // Glowing Neon Lime Circular Eyes (Always Vivid Lime Green like reference!)
            float eyeY = hy + 0.6f * u;
            float eyeLX = hx - 1.55f * u;
            float eyeRX = hx + 1.55f * u;

            eyeGlowPaint.setColor(Color.parseColor("#76FF03"));
            eyePaint.setColor(Color.parseColor("#76FF03"));
            mouthPaint.setColor(Color.parseColor("#76FF03"));

            // Soft glowing halo
            canvas.drawCircle(eyeLX, eyeY, dp(3.8f), eyeGlowPaint);
            canvas.drawCircle(eyeRX, eyeY, dp(3.8f), eyeGlowPaint);

            // Solid bright eye
            canvas.drawCircle(eyeLX, eyeY, dp(2.2f), eyePaint);
            canvas.drawCircle(eyeRX, eyeY, dp(2.2f), eyePaint);

            // Crisp pupil center
            canvas.drawCircle(eyeLX, eyeY, dp(0.8f), pupilPaint);
            canvas.drawCircle(eyeRX, eyeY, dp(0.8f), pupilPaint);

            // Cute Smiling Arc Mouth
            Path mouthPath = new Path();
            mouthPath.moveTo(hx - 1.15f * u, hy + 1.6f * u);
            mouthPath.quadTo(hx, hy + 2.1f * u, hx + 1.15f * u, hy + 1.6f * u);
            canvas.drawPath(mouthPath, mouthPaint);
        }
    }
}

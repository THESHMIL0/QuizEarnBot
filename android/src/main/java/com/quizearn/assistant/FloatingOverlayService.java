package com.quizearn.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingOverlayService extends Service {

    public static final String TAG = "QuizEarnAssistant";

    private WindowManager windowManager;
    private LinearLayout floatView;
    private WindowManager.LayoutParams params;

    private TextView statusText;
    private TextView dragHandle;
    private Button btnToggle;
    private Button btnClose;

    private int initialX = 0;
    private int initialY = 0;
    private float initialTouchX = 0f;
    private float initialTouchY = 0f;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateUiState();
            uiHandler.postDelayed(this, 300);
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
            } else if ("com.quizearn.assistant.RESTART_APP".equals(act)) {
                QuizEarnAccessibilityService.restartQuizApp();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundServiceNotification();
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

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, channelId)
            : new Notification.Builder(this);

        Notification notification = builder
            .setContentTitle("Quiz Math Assistant")
            .setContentText("Floating HUD is active.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build();

        startForeground(1001, notification);
    }

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 200;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(dp(12), dp(8), dp(12), dp(8));
        container.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#08080A"));
        bg.setStroke(dp(1), Color.parseColor("#1F1F26"));
        bg.setCornerRadius(dp(24));
        container.setBackground(bg);

        // Dedicated drag grip
        dragHandle = new TextView(this);
        dragHandle.setText("⠿");
        dragHandle.setTextColor(Color.parseColor("#9CA3AF"));
        dragHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        dragHandle.setPadding(dp(2), 0, dp(6), 0);

        statusText = new TextView(this);
        statusText.setText("STANDBY");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        statusText.setPadding(0, 0, dp(8), 0);

        btnToggle = new Button(this);
        btnToggle.setText("START");
        btnToggle.setTextColor(Color.BLACK);
        btnToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnToggle.setPadding(dp(14), dp(4), dp(14), dp(4));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#10B981"));
        btnBg.setCornerRadius(dp(16));
        btnToggle.setBackground(btnBg);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "START/PAUSE BUTTON CLICKED! Current state: " + QuizEarnAccessibilityService.isRunning);
                if (QuizEarnAccessibilityService.isRunning) {
                    QuizEarnAccessibilityService.stopSolving();
                } else {
                    if (QuizEarnAccessibilityService.currentStatus.contains("25 GEMS")) {
                        QuizEarnAccessibilityService.currentStatus = "Scanning...";
                    }
                    QuizEarnAccessibilityService.startSolving();
                }
                updateUiState();
            }
        });

        btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.parseColor("#9CA3AF"));
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnClose.setBackground(null);
        btnClose.setPadding(dp(8), 0, dp(2), 0);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "CLOSE BUTTON CLICKED");
                QuizEarnAccessibilityService.stopSolving();
                stopSelf();
            }
        });

        Button btnRestart = new Button(this);
        btnRestart.setText("⟳");
        btnRestart.setTextColor(Color.parseColor("#38BDF8"));
        btnRestart.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btnRestart.setBackground(null);
        btnRestart.setPadding(dp(4), 0, dp(4), 0);
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "HUD RESTART APP CLICKED");
                QuizEarnAccessibilityService.restartQuizApp();
            }
        });

        container.addView(dragHandle);
        container.addView(statusText);
        container.addView(btnRestart);
        container.addView(btnToggle);
        container.addView(btnClose);

        // Drag listener attached ONLY to dragHandle and statusText (NOT container!)
        View.OnTouchListener dragListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (params == null) return false;
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
                        windowManager.updateViewLayout(floatView, params);
                        return true;
                }
                return false;
            }
        };

        dragHandle.setOnTouchListener(dragListener);
        statusText.setOnTouchListener(dragListener);

        floatView = container;
        windowManager.addView(floatView, params);
        updateUiState();
    }

    private void updateUiState() {
        boolean running = QuizEarnAccessibilityService.isRunning;
        boolean serviceOk = QuizEarnAccessibilityService.isServiceActive;
        int count = QuizEarnAccessibilityService.solvedCount;
        int gems = QuizEarnAccessibilityService.liveGems;
        String status = QuizEarnAccessibilityService.currentStatus;

        String gemTag = (gems >= 0) ? ("💎 " + gems + "/25") : ("#" + count);

        if (!serviceOk) {
            statusText.setText("SERVICE OFF");
            statusText.setTextColor(Color.parseColor("#EF4444"));
            btnToggle.setEnabled(false);
            btnToggle.setText("OFF");
        } else if (status.contains("25 GEMS") && gems >= 25) {
            statusText.setText("🎉 25/25 GEMS! CLAIM OFFER");
            statusText.setTextColor(Color.parseColor("#F59E0B"));
            btnToggle.setText("START");
            btnToggle.setEnabled(true);

            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(Color.parseColor("#10B981"));
            btnBg.setCornerRadius(dp(16));
            btnToggle.setBackground(btnBg);
            btnToggle.setTextColor(Color.BLACK);
        } else if (running) {
            statusText.setText(gemTag + " • " + status.toUpperCase());
            statusText.setTextColor(Color.parseColor("#10B981"));
            btnToggle.setText("PAUSE");
            btnToggle.setEnabled(true);

            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(Color.parseColor("#EF4444"));
            btnBg.setCornerRadius(dp(16));
            btnToggle.setBackground(btnBg);
            btnToggle.setTextColor(Color.WHITE);
        } else {
            statusText.setText(gemTag + " • STANDBY");
            statusText.setTextColor(Color.parseColor("#9CA3AF"));
            btnToggle.setText("START");
            btnToggle.setEnabled(true);

            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(Color.parseColor("#10B981"));
            btnBg.setCornerRadius(dp(16));
            btnToggle.setBackground(btnBg);
            btnToggle.setTextColor(Color.BLACK);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(refreshRunnable);
        if (floatView != null && windowManager != null) {
            windowManager.removeView(floatView);
            floatView = null;
        }
    }
}

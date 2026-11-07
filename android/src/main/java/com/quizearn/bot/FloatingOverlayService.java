package com.quizearn.bot;

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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingOverlayService extends Service {

    private WindowManager windowManager;
    private LinearLayout floatView;
    private WindowManager.LayoutParams params;

    private TextView statusText;
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
            uiHandler.postDelayed(this, 500);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundServiceNotification();
        createFloatingWidget();
        uiHandler.post(refreshRunnable);
    }

    private void startForegroundServiceNotification() {
        String channelId = "quiz_earn_overlay_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "Quiz & Earn Overlay",
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
            .setContentTitle("Quiz & Earn Mobile Bot")
            .setContentText("Floating overlay is active.")
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

        statusText = new TextView(this);
        statusText.setText("STANDBY");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        statusText.setPadding(dp(4), 0, dp(10), 0);

        btnToggle = new Button(this);
        btnToggle.setText("START");
        btnToggle.setTextColor(Color.BLACK);
        btnToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnToggle.setPadding(dp(12), dp(4), dp(12), dp(4));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#10B981"));
        btnBg.setCornerRadius(dp(16));
        btnToggle.setBackground(btnBg);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuizEarnAccessibilityService.isRunning = !QuizEarnAccessibilityService.isRunning;
                updateUiState();
            }
        });

        btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.parseColor("#9CA3AF"));
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnClose.setBackground(null);
        btnClose.setPadding(dp(6), 0, dp(2), 0);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuizEarnAccessibilityService.isRunning = false;
                stopSelf();
            }
        });

        container.addView(statusText);
        container.addView(btnToggle);
        container.addView(btnClose);

        container.setOnTouchListener(new View.OnTouchListener() {
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
        });

        floatView = container;
        windowManager.addView(floatView, params);
        updateUiState();
    }

    private void updateUiState() {
        boolean running = QuizEarnAccessibilityService.isRunning;
        boolean serviceOk = QuizEarnAccessibilityService.isServiceActive;
        int count = QuizEarnAccessibilityService.solvedCount;

        if (!serviceOk) {
            statusText.setText("SERVICE OFF");
            statusText.setTextColor(Color.parseColor("#EF4444"));
            btnToggle.setEnabled(false);
        } else if (running) {
            statusText.setText("RUNNING (" + count + ")");
            statusText.setTextColor(Color.parseColor("#10B981"));
            btnToggle.setText("PAUSE");
            btnToggle.setEnabled(true);
        } else {
            statusText.setText("STANDBY (" + count + ")");
            statusText.setTextColor(Color.parseColor("#9CA3AF"));
            btnToggle.setText("START");
            btnToggle.setEnabled(true);
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


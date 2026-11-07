package com.quizearn.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingOverlayService extends Service {

    public static final String TAG = "QuizEarnAssistant";

    private WindowManager windowManager;
    private FrameLayout floatView;
    private WindowManager.LayoutParams params;

    // Dual-Mode UI components
    private boolean isExpanded = false;
    private LinearLayout ballView;
    private LinearLayout menuView;

    // Mini Ball components
    private TextView ballIcon;
    private TextView ballGemBadge;

    // Mod Menu components
    private TextView menuGemText;
    private TextView menuStatusText;
    private Button btnToggle;
    private Button btnRestart;
    private Button btnMinimize;
    private Button btnClose;
    private Button btnMinBottom;

    // Drag tracking
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
            .setContentText("Mod Menu Floating HUD is active.")
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
        params.x = 30;
        params.y = dp(280);

        // Root container holds both Ball view and Menu Card
        floatView = new FrameLayout(this);

        buildBallView();
        buildMenuView();

        floatView.addView(ballView);
        floatView.addView(menuView);

        // Start in collapsed ball mode by default
        isExpanded = false;
        ballView.setVisibility(View.VISIBLE);
        menuView.setVisibility(View.GONE);

        windowManager.addView(floatView, params);
        updateUiState();
    }

    // ==========================================
    // 1. MINI FLOATING BALL (COLLAPSED MOD ICON)
    // ==========================================
    private void buildBallView() {
        ballView = new LinearLayout(this);
        ballView.setOrientation(LinearLayout.VERTICAL);
        ballView.setGravity(Gravity.CENTER);
        int ballSize = dp(54);
        ballView.setLayoutParams(new FrameLayout.LayoutParams(ballSize, ballSize));

        // Circular styling with glowing emerald border
        GradientDrawable ballBg = new GradientDrawable();
        ballBg.setShape(GradientDrawable.OVAL);
        ballBg.setColor(Color.parseColor("#090D16"));
        ballBg.setStroke(dp(2), Color.parseColor("#10B981"));
        ballView.setBackground(ballBg);

        // Mod icon
        ballIcon = new TextView(this);
        ballIcon.setText("⚡");
        ballIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        ballIcon.setTextColor(Color.parseColor("#10B981"));
        ballIcon.setGravity(Gravity.CENTER);

        // Mini gem / status badge
        ballGemBadge = new TextView(this);
        ballGemBadge.setText("💎 0");
        ballGemBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        ballGemBadge.setTypeface(Typeface.DEFAULT_BOLD);
        ballGemBadge.setTextColor(Color.WHITE);
        ballGemBadge.setGravity(Gravity.CENTER);

        ballView.addView(ballIcon);
        ballView.addView(ballGemBadge);

        // Drag & Click detector on the ball
        ballView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (params == null) return false;
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
                            windowManager.updateViewLayout(floatView, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragMoved) {
                            // Tap detected: expand into full mod menu!
                            expandMenu();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // ==========================================
    // 2. EXPANDED MOD MENU CARD
    // ==========================================
    private void buildMenuView() {
        menuView = new LinearLayout(this);
        menuView.setOrientation(LinearLayout.VERTICAL);
        menuView.setPadding(dp(14), dp(12), dp(14), dp(12));
        int menuWidth = dp(260);
        menuView.setLayoutParams(new FrameLayout.LayoutParams(menuWidth, FrameLayout.LayoutParams.WRAP_CONTENT));

        // Modern game mod menu card aesthetic
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setShape(GradientDrawable.RECTANGLE);
        menuBg.setColor(Color.parseColor("#0A0E17"));
        menuBg.setStroke(dp(2), Color.parseColor("#10B981"));
        menuBg.setCornerRadius(dp(18));
        menuView.setBackground(menuBg);

        // --- HEADER BAR ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("⚡ QUIZ EARN MOD");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvTitle.setLayoutParams(titleLp);

        // Minimize [─] button
        btnMinimize = new Button(this);
        btnMinimize.setText("─");
        btnMinimize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnMinimize.setTypeface(Typeface.DEFAULT_BOLD);
        btnMinimize.setTextColor(Color.parseColor("#94A3B8"));
        btnMinimize.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable minBg = new GradientDrawable();
        minBg.setColor(Color.parseColor("#1E293B"));
        minBg.setCornerRadius(dp(8));
        btnMinimize.setBackground(minBg);
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(dp(30), dp(28));
        minLp.setMargins(0, 0, dp(6), 0);
        btnMinimize.setLayoutParams(minLp);

        btnMinimize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapseMenu();
            }
        });

        // Close [✕] button
        btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnClose.setTextColor(Color.parseColor("#EF4444"));
        btnClose.setPadding(0, 0, 0, 0);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.parseColor("#1E293B"));
        closeBg.setCornerRadius(dp(8));
        btnClose.setBackground(closeBg);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        btnClose.setLayoutParams(closeLp);

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "CLOSE BUTTON CLICKED - Stopping overlay service");
                QuizEarnAccessibilityService.stopSolving();
                stopSelf();
            }
        });

        header.addView(tvTitle);
        header.addView(btnMinimize);
        header.addView(btnClose);

        // Allow dragging the whole menu by its header
        header.setOnTouchListener(new View.OnTouchListener() {
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

        // --- DIVIDER ---
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1E293B"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(8), 0, dp(10));
        divider.setLayoutParams(divLp);

        // --- STATS CARD ---
        LinearLayout statsCard = new LinearLayout(this);
        statsCard.setOrientation(LinearLayout.VERTICAL);
        statsCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable statsBg = new GradientDrawable();
        statsBg.setColor(Color.parseColor("#030712"));
        statsBg.setCornerRadius(dp(12));
        statsCard.setBackground(statsBg);

        menuGemText = new TextView(this);
        menuGemText.setText("💎 GEMS: 0 / 25");
        menuGemText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        menuGemText.setTypeface(Typeface.DEFAULT_BOLD);
        menuGemText.setTextColor(Color.parseColor("#38BDF8"));

        menuStatusText = new TextView(this);
        menuStatusText.setText("● STANDBY");
        menuStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        menuStatusText.setTextColor(Color.parseColor("#9CA3AF"));
        menuStatusText.setPadding(0, dp(3), 0, 0);

        statsCard.addView(menuGemText);
        statsCard.addView(menuStatusText);

        // --- PRIMARY TOGGLE (START / PAUSE) ---
        btnToggle = new Button(this);
        btnToggle.setText("▶ START BOT");
        btnToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btnToggle.setTypeface(Typeface.DEFAULT_BOLD);
        btnToggle.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        toggleLp.setMargins(0, dp(10), 0, dp(8));
        btnToggle.setLayoutParams(toggleLp);

        GradientDrawable toggleBg = new GradientDrawable();
        toggleBg.setColor(Color.parseColor("#10B981"));
        toggleBg.setCornerRadius(dp(12));
        btnToggle.setBackground(toggleBg);

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

        // --- SECONDARY ACTION ROW (RESTART APP & MINIMIZE) ---
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);

        btnRestart = new Button(this);
        btnRestart.setText("⟳ Restart App");
        btnRestart.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        btnRestart.setTypeface(Typeface.DEFAULT_BOLD);
        btnRestart.setTextColor(Color.parseColor("#38BDF8"));
        GradientDrawable rstBg = new GradientDrawable();
        rstBg.setColor(Color.parseColor("#1E293B"));
        rstBg.setCornerRadius(dp(10));
        btnRestart.setBackground(rstBg);
        LinearLayout.LayoutParams rstLp = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
        rstLp.setMargins(0, 0, dp(6), 0);
        btnRestart.setLayoutParams(rstLp);

        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "RESTART APP CLICKED");
                QuizEarnAccessibilityService.restartQuizApp();
            }
        });

        btnMinBottom = new Button(this);
        btnMinBottom.setText("▼ Minimize");
        btnMinBottom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        btnMinBottom.setTypeface(Typeface.DEFAULT_BOLD);
        btnMinBottom.setTextColor(Color.parseColor("#94A3B8"));
        GradientDrawable minBtmBg = new GradientDrawable();
        minBtmBg.setColor(Color.parseColor("#1E293B"));
        minBtmBg.setCornerRadius(dp(10));
        btnMinBottom.setBackground(minBtmBg);
        LinearLayout.LayoutParams minBtmLp = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
        btnMinBottom.setLayoutParams(minBtmLp);

        btnMinBottom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapseMenu();
            }
        });

        bottomRow.addView(btnRestart);
        bottomRow.addView(btnMinBottom);

        // Assemble the Mod Menu Card
        menuView.addView(header);
        menuView.addView(divider);
        menuView.addView(statsCard);
        menuView.addView(btnToggle);
        menuView.addView(bottomRow);
    }

    public void expandMenu() {
        isExpanded = true;
        ballView.setVisibility(View.GONE);
        menuView.setVisibility(View.VISIBLE);

        // Clamp screen boundaries so the expanded card is never clipped off-screen
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int menuWidth = dp(260);
        if (params.x + menuWidth > screenWidth - dp(10)) {
            params.x = Math.max(dp(10), screenWidth - menuWidth - dp(10));
        }
        windowManager.updateViewLayout(floatView, params);
    }

    public void collapseMenu() {
        isExpanded = false;
        menuView.setVisibility(View.GONE);
        ballView.setVisibility(View.VISIBLE);
        windowManager.updateViewLayout(floatView, params);
    }

    private void updateUiState() {
        boolean running = QuizEarnAccessibilityService.isRunning;
        boolean serviceOk = QuizEarnAccessibilityService.isServiceActive;
        int count = QuizEarnAccessibilityService.solvedCount;
        int gems = QuizEarnAccessibilityService.liveGems;
        String status = QuizEarnAccessibilityService.currentStatus;

        String gemDisplay = (gems >= 0) ? (gems + "/25") : ("#" + count);

        // Update Mini Ball badge
        if (ballGemBadge != null) {
            ballGemBadge.setText("💎 " + gemDisplay);
            if (running) {
                ballGemBadge.setTextColor(Color.parseColor("#10B981"));
                ballIcon.setTextColor(Color.parseColor("#10B981"));
            } else {
                ballGemBadge.setTextColor(Color.parseColor("#94A3B8"));
                ballIcon.setTextColor(Color.parseColor("#F59E0B"));
            }
        }

        // Update Expanded Menu Card
        if (menuGemText != null) {
            menuGemText.setText("💎 GEMS: " + gemDisplay);
        }

        if (menuStatusText != null && btnToggle != null) {
            if (!serviceOk) {
                menuStatusText.setText("● ACCESSIBILITY SERVICE OFF");
                menuStatusText.setTextColor(Color.parseColor("#EF4444"));
                btnToggle.setEnabled(false);
                btnToggle.setText("OFF");
            } else if (status.contains("25 GEMS") && gems >= 25) {
                menuStatusText.setText("🎉 25/25 GEMS! CLAIM REWARD");
                menuStatusText.setTextColor(Color.parseColor("#F59E0B"));
                btnToggle.setText("▶ START BOT");
                btnToggle.setEnabled(true);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(Color.parseColor("#10B981"));
                btnBg.setCornerRadius(dp(12));
                btnToggle.setBackground(btnBg);
                btnToggle.setTextColor(Color.BLACK);
            } else if (running) {
                menuStatusText.setText("● " + status.toUpperCase());
                menuStatusText.setTextColor(Color.parseColor("#10B981"));
                btnToggle.setText("⏸ PAUSE BOT");
                btnToggle.setEnabled(true);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(Color.parseColor("#DC2626"));
                btnBg.setCornerRadius(dp(12));
                btnToggle.setBackground(btnBg);
                btnToggle.setTextColor(Color.WHITE);
            } else {
                menuStatusText.setText("● PAUSED / STANDBY");
                menuStatusText.setTextColor(Color.parseColor("#94A3B8"));
                btnToggle.setText("▶ START BOT");
                btnToggle.setEnabled(true);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(Color.parseColor("#10B981"));
                btnBg.setCornerRadius(dp(12));
                btnToggle.setBackground(btnBg);
                btnToggle.setTextColor(Color.BLACK);
            }
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

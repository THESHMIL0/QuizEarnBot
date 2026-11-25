package com.quizearn.assistant;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 1002;

    private TextView txtOverlayStatus;
    private TextView txtAccessStatus;
    private TextView txtBatteryStatus;
    private Button btnOverlay;
    private Button btnAccess;
    private Button btnBattery;
    private Button btnLaunch;
    private Button btnStopOverlay;
    private Button btnTargetApp;

    private static final String[] TARGET_PACKAGES = new String[]{
        "com.quiz.earn.money.game",
        "com.rayole.cashmafia2"
    };
    private static final String[] TARGET_LABELS = new String[]{
        "Target: Quiz & Earn",
        "Target: CashMafia"
    };
    private int selectedTargetIdx = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("QuizEarnPrefs", Context.MODE_PRIVATE);
        selectedTargetIdx = prefs.getInt("selected_target_idx", 0);
        if (selectedTargetIdx < 0 || selectedTargetIdx >= TARGET_PACKAGES.length) {
            selectedTargetIdx = 0;
        }
        QuizEarnAccessibilityService.setPreferredQuizPackage(this, TARGET_PACKAGES[selectedTargetIdx]);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int padH = Math.max(dp(16), Math.min(dp(28), (int) (dm.widthPixels * 0.055f / dm.density * dm.density)));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#000000"));
        scrollView.setFillViewport(true);
        scrollView.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#000000"));
        root.setPadding(padH, dp(28), padH, dp(28));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        // Constrain max readable width on tablets / wide BlueStacks windows
        LinearLayout contentCol = new LinearLayout(this);
        contentCol.setOrientation(LinearLayout.VERTICAL);
        int maxColWidth = Math.min(dm.widthPixels - (padH * 2), dp(540));
        contentCol.setLayoutParams(new LinearLayout.LayoutParams(
            maxColWidth > 0 ? maxColWidth : LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("QuizEarnBot v3.0");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("Universal Android 7–15+ (All Brands) & Emulators");
        subtitle.setTextColor(Color.parseColor("#10B981"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        subtitle.setGravity(Gravity.CENTER);

        contentCol.addView(title);
        contentCol.addView(subtitle);

        // 1. Overlay Permission Card
        LinearLayout cardOverlay = createCard(
            "1. FLOATING OVERLAY PERMISSION",
            "Required to display the floating HUD on top of Quiz & Earn, CashMafia, and Play Store."
        );
        txtOverlayStatus = createStatusBadge("Checking...");
        btnOverlay = createActionButton("GRANT OVERLAY PERMISSION", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettingsSafely();
            }
        });
        cardOverlay.addView(txtOverlayStatus);
        cardOverlay.addView(btnOverlay);
        contentCol.addView(cardOverlay);

        // 2. Accessibility Service Card
        LinearLayout cardAccess = createCard(
            "2. ACCESSIBILITY SERVICE",
            "Find 'Quiz Math Assistant' in Accessibility and turn it ON. On Android 13–15, if 'Restricted setting' blocks it: tap Open App Info below -> ⋮ (top right) -> Allow restricted settings."
        );
        txtAccessStatus = createStatusBadge("Checking...");
        btnAccess = createActionButton("ENABLE ACCESSIBILITY", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettingsSafely();
            }
        });
        Button btnAppInfoFix = createActionButton("OPEN APP INFO (ANDROID 13–15 FIX)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppInfoSafely();
            }
        });
        LinearLayout.LayoutParams appInfoLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        appInfoLp.setMargins(0, dp(8), 0, 0);
        btnAppInfoFix.setLayoutParams(appInfoLp);

        cardAccess.addView(txtAccessStatus);
        cardAccess.addView(btnAccess);
        cardAccess.addView(btnAppInfoFix);
        contentCol.addView(cardAccess);

        // 3. Super Offer Automation & Target Selector Card
        LinearLayout cardOffer = createCard(
            "3. SUPER OFFER & TARGET APP",
            "Auto-install offer app, 2-min usage & 1000 coins claim. Set ON for Player 1, OFF for Gem Farmers."
        );
        final Button btnMainOffer = createActionButton("", null);
        final Runnable updateMainOfferBtn = new Runnable() {
            @Override
            public void run() {
                boolean on = QuizEarnAccessibilityService.isSuperOfferEnabled;
                btnMainOffer.setText(on ? "AUTO-OFFER: [ON] (Master / Player 1)" : "AUTO-OFFER: [OFF] (Gem Farmer)");
                GradientDrawable b = new GradientDrawable();
                b.setCornerRadius(dp(10));
                if (on) {
                    b.setColor(Color.parseColor("#059669"));
                    btnMainOffer.setTextColor(Color.WHITE);
                } else {
                    b.setColor(Color.parseColor("#374151"));
                    btnMainOffer.setTextColor(Color.parseColor("#D1D5DB"));
                }
                btnMainOffer.setBackground(b);
            }
        };
        updateMainOfferBtn.run();
        btnMainOffer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newState = !QuizEarnAccessibilityService.isSuperOfferEnabled;
                QuizEarnAccessibilityService.setSuperOfferEnabled(MainActivity.this, newState);
                updateMainOfferBtn.run();
            }
        });

        btnTargetApp = createActionButton(TARGET_LABELS[selectedTargetIdx], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedTargetIdx = (selectedTargetIdx + 1) % TARGET_PACKAGES.length;
                getSharedPreferences("QuizEarnPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("selected_target_idx", selectedTargetIdx)
                    .apply();
                QuizEarnAccessibilityService.setPreferredQuizPackage(MainActivity.this, TARGET_PACKAGES[selectedTargetIdx]);
                btnTargetApp.setText(TARGET_LABELS[selectedTargetIdx]);
            }
        });
        LinearLayout.LayoutParams targetLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        targetLp.setMargins(0, dp(8), 0, 0);
        btnTargetApp.setLayoutParams(targetLp);

        cardOffer.addView(btnMainOffer);
        cardOffer.addView(btnTargetApp);
        contentCol.addView(cardOffer);

        // 4. Battery Optimization Exemption (Crucial for OEM phones & Android 8+)
        LinearLayout cardBattery = createCard(
            "4. BACKGROUND STABILITY & BRAND TIPS",
            "All phones: Disable battery optimization.\n• Xiaomi/Redmi/POCO: App Info -> Other permissions -> allow 'Display pop-up windows while running in background' + Autostart.\n• Oppo/Realme/Vivo/Samsung: Allow background usage & turn off Deep Sleep."
        );
        txtBatteryStatus = createStatusBadge("Checking...");
        btnBattery = createActionButton("DISABLE BATTERY OPTIMIZATION", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBatteryOptimizationsSafely();
            }
        });
        cardBattery.addView(txtBatteryStatus);
        cardBattery.addView(btnBattery);
        contentCol.addView(cardBattery);

        // Launch Button
        btnLaunch = new Button(this);
        btnLaunch.setText("LAUNCH FLOATING HUD");
        btnLaunch.setTextColor(Color.BLACK);
        btnLaunch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnLaunch.setTypeface(null, Typeface.BOLD);
        btnLaunch.setMinimumHeight(dp(48));
        btnLaunch.setPadding(dp(20), dp(14), dp(20), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#10B981"));
        bg.setCornerRadius(dp(14));
        btnLaunch.setBackground(bg);

        btnLaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFloatingService();
            }
        });

        LinearLayout launchContainer = new LinearLayout(this);
        launchContainer.setOrientation(LinearLayout.VERTICAL);
        launchContainer.setPadding(0, dp(8), 0, dp(16));
        launchContainer.addView(btnLaunch);

        btnStopOverlay = createActionButton("STOP BOT & CLOSE FLOATING HUD", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopFloatingService();
            }
        });
        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setColor(Color.parseColor("#3F0D12"));
        stopBg.setStroke(dp(1), Color.parseColor("#EF4444"));
        stopBg.setCornerRadius(dp(12));
        btnStopOverlay.setBackground(stopBg);
        btnStopOverlay.setTextColor(Color.parseColor("#FCA5A5"));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        stopLp.setMargins(0, dp(10), 0, 0);
        btnStopOverlay.setLayoutParams(stopLp);
        launchContainer.addView(btnStopOverlay);

        contentCol.addView(launchContainer);

        TextView infoText = new TextView(this);
        infoText.setText("Quick Guide:\n1. Grant Overlay permission.\n2. Enable 'Quiz Math Assistant' in Accessibility (use 'Open App Info -> ⋮ -> Allow restricted settings' first on Android 13–15).\n3. Exempt Battery Optimization (and Autostart/Background pop-ups on Xiaomi/Vivo/Oppo).\n4. Keep Play Store in English & screen unlocked, then tap 'Launch Floating HUD'.");
        infoText.setTextColor(Color.parseColor("#9CA3AF"));
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        infoText.setLineSpacing(dp(4), 1f);
        infoText.setPadding(dp(4), dp(4), dp(4), dp(16));
        contentCol.addView(infoText);

        root.addView(contentCol);
        scrollView.addView(root);
        setContentView(scrollView);

        requestNotificationPermissionIfNeeded();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIFICATIONS);
                } catch (Exception ignored) {}
            }
        }
    }

    private void openAppInfoSafely() {
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(details);
        } catch (Exception e) {
            Toast.makeText(this, "Open Android Settings > Apps > Quiz Math Assistant.", Toast.LENGTH_LONG).show();
        }
    }

    private void openOverlaySettingsSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                openAppInfoSafely();
            }
        }
    }

    private void openAccessibilitySettingsSafely() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Open Android Settings > Accessibility > Quiz Math Assistant.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestIgnoreBatteryOptimizationsSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Set Battery usage to Unrestricted in App Info.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isAccessibilityEnabledInSettings() {
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(services)) return false;
            return services.contains(getPackageName() + "/" + QuizEarnAccessibilityService.class.getName()) ||
                   services.contains(getPackageName() + "/.QuizEarnAccessibilityService");
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
    }

    private void refreshPermissions() {
        boolean hasOverlay = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasOverlay = Settings.canDrawOverlays(this);
        }

        boolean serviceBound = QuizEarnAccessibilityService.isServiceActive;
        boolean settingEnabled = isAccessibilityEnabledInSettings();
        boolean hasAccess = serviceBound || settingEnabled;

        if (hasOverlay) {
            txtOverlayStatus.setText("✓ Overlay Permission Granted");
            txtOverlayStatus.setTextColor(Color.parseColor("#10B981"));
            btnOverlay.setEnabled(false);
            btnOverlay.setText("GRANTED");
        } else {
            txtOverlayStatus.setText("✕ Overlay Permission Needed");
            txtOverlayStatus.setTextColor(Color.parseColor("#EF4444"));
            btnOverlay.setEnabled(true);
            btnOverlay.setText("GRANT OVERLAY PERMISSION");
        }

        if (serviceBound) {
            txtAccessStatus.setText("✓ Accessibility Service Active");
            txtAccessStatus.setTextColor(Color.parseColor("#10B981"));
            btnAccess.setEnabled(false);
            btnAccess.setText("ENABLED");
        } else if (settingEnabled) {
            txtAccessStatus.setText("● Enabled (Waiting for OS bind — tap to toggle if stuck)");
            txtAccessStatus.setTextColor(Color.parseColor("#F59E0B"));
            btnAccess.setEnabled(true);
            btnAccess.setText("OPEN ACCESSIBILITY SETTINGS");
        } else {
            txtAccessStatus.setText("✕ Accessibility Service Disabled");
            txtAccessStatus.setTextColor(Color.parseColor("#EF4444"));
            btnAccess.setEnabled(true);
            btnAccess.setText("ENABLE ACCESSIBILITY");
        }

        boolean ignoringBattery = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            ignoringBattery = (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
        }
        if (ignoringBattery) {
            txtBatteryStatus.setText("✓ Unrestricted Background Execution");
            txtBatteryStatus.setTextColor(Color.parseColor("#10B981"));
            btnBattery.setEnabled(false);
            btnBattery.setText("UNRESTRICTED");
        } else {
            txtBatteryStatus.setText("● Battery Optimized (May pause on screen lock / idle)");
            txtBatteryStatus.setTextColor(Color.parseColor("#F59E0B"));
            btnBattery.setEnabled(true);
            btnBattery.setText("DISABLE BATTERY OPTIMIZATION");
        }

        btnLaunch.setEnabled(hasOverlay && hasAccess);
        btnLaunch.setAlpha((hasOverlay && hasAccess) ? 1.0f : 0.5f);

        boolean hudActive = (FloatingOverlayService.instance != null) || QuizEarnAccessibilityService.isRunning;
        if (btnStopOverlay != null) {
            btnStopOverlay.setEnabled(hudActive);
            btnStopOverlay.setAlpha(hudActive ? 1.0f : 0.45f);
            btnStopOverlay.setText(hudActive ? "STOP BOT & CLOSE FLOATING HUD" : "FLOATING HUD CLOSED");
        }
    }

    private void stopFloatingService() {
        QuizEarnAccessibilityService.stopSolving();
        try {
            stopService(new Intent(this, FloatingOverlayService.class));
        } catch (Exception ignored) {}
        refreshPermissions();
        Toast.makeText(this, "Bot stopped & Floating HUD closed.", Toast.LENGTH_SHORT).show();
    }

    private void startFloatingService() {
        Intent intent = new Intent(this, FloatingOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        String preferred = TARGET_PACKAGES[selectedTargetIdx];
        String secondary = TARGET_PACKAGES[(selectedTargetIdx + 1) % TARGET_PACKAGES.length];
        String[] order = new String[]{preferred, secondary};
        for (String pkg : order) {
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    break;
                }
            } catch (Exception ignored) {}
        }
    }

    private LinearLayout createCard(String title, String desc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#08080A"));
        bg.setStroke(dp(1), Color.parseColor("#1F1F26"));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(params);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        t.setTypeface(null, Typeface.BOLD);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(Color.parseColor("#9CA3AF"));
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        d.setPadding(0, dp(4), 0, dp(10));

        card.addView(t);
        card.addView(d);
        return card;
    }

    private TextView createStatusBadge(String initialText) {
        TextView badge = new TextView(this);
        badge.setText(initialText);
        badge.setTextColor(Color.parseColor("#9CA3AF"));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        badge.setPadding(0, 0, 0, dp(10));
        return badge;
    }

    private Button createActionButton(String text, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setMinimumHeight(dp(44));
        btn.setPadding(dp(14), dp(10), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#16161A"));
        bg.setStroke(dp(1), Color.parseColor("#27272A"));
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);

        if (onClick != null) {
            btn.setOnClickListener(onClick);
        }
        return btn;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}